package com.nvicache.mixin;

import com.nvicache.cache.PageStore;
import com.nvicache.interfaces.IBufferArenaCacheable;
import com.nvicache.interfaces.ISegmentedManagerCacheable;
import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.SegmentedManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;

@Mixin(value = BufferArena.class, remap = false)
public class MixinBufferArena implements IBufferArenaCacheable {

    @Shadow SegmentedManager segments;
    @Shadow public IDeviceMappedBuffer buffer;
    @Shadow private long totalQuads;
    @Shadow private int vertexFormatSize;

    // Pages touched by an alloc, free or upload since the arena was last loaded from or saved to
    // disk. Until then (nvicache$inSync false) the disk says nothing about this arena, so every
    // page counts as changed. Created lazily: the constructor allocates before field initializers.
    @Unique private LongOpenHashSet nvicache$dirtyPages;
    @Unique private boolean nvicache$inSync;

    /** Read-back geometry allowed to wait in memory for the background writers. */
    @Unique private static final long MAX_PENDING_BYTES = 1L << 30;

    @Unique
    private void nvicache$markDirty(int addr) {
        if (nvicache$dirtyPages == null) nvicache$dirtyPages = new LongOpenHashSet();
        nvicache$dirtyPages.add(nvicache$pageOf(Integer.toUnsignedLong(addr)));
    }

    @Unique
    private long nvicache$pageOf(long addr) {
        return addr * 4L * vertexFormatSize / PageStore.PAGE_BYTES;
    }

    @Inject(method = "allocQuads", at = @At("RETURN"))
    private void nvicache$onAlloc(int quads, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() != -1) nvicache$markDirty(cir.getReturnValueI());
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void nvicache$onFree(int addr, CallbackInfo ci) {
        nvicache$markDirty(addr);
    }

    @Inject(method = "upload", at = @At("HEAD"))
    private void nvicache$onUpload(UploadingBufferStream stream, int addr, CallbackInfoReturnable<Long> cir) {
        nvicache$markDirty(addr);
    }

    @Unique
    private void nvicache$syncedWithDisk() {
        if (nvicache$dirtyPages != null) nvicache$dirtyPages.clear();
        nvicache$inSync = true;
    }

    /** Calls {@code run(dataOffset, addr, quads)} for each run of allocations that are adjacent in the arena. */
    @Unique
    private static void nvicache$forEachRun(long[] addrs, long[] sizes, RunConsumer run) {
        long dataOffset = 0;
        int i = 0;
        while (i < addrs.length) {
            long start = addrs[i];
            long quads = sizes[i];
            int j = i + 1;
            while (j < addrs.length && addrs[j] == start + quads) quads += sizes[j++];
            run.accept(dataOffset, start, quads);
            dataOffset += quads;
            i = j;
        }
    }

    @Unique
    private interface RunConsumer {
        void accept(long dataOffsetQuads, long addr, long quads);
    }

    @Override
    public void nvicache$saveToCache(DataOutputStream meta, PageStore pages) throws IOException {
        meta.writeInt(vertexFormatSize);
        meta.writeLong(totalQuads);
        ((ISegmentedManagerCacheable)(Object) segments).nvicache$writeTo(meta);

        // (addr, size) pairs per page, in address order
        var byPage = new Long2ObjectRBTreeMap<LongArrayList>();
        ((ISegmentedManagerCacheable)(Object) segments).nvicache$forEachTaken((addr, size) -> {
            var list = byPage.computeIfAbsent(nvicache$pageOf(addr), k -> new LongArrayList());
            list.add(addr);
            list.add(size);
        });
        meta.writeInt(byPage.size());
        for (long page : byPage.keySet()) meta.writeLong(page);

        LongSet dirty = nvicache$dirtyPages != null ? nvicache$dirtyPages : new LongOpenHashSet();
        LongSet stale = nvicache$inSync ? new LongOpenHashSet(dirty) : pages.existingPages();
        stale.removeAll(byPage.keySet());
        for (long page : stale) pages.delete(page);

        long quadBytes = 4L * vertexFormatSize;
        var inFlight = new ArrayDeque<Future<?>>();
        var inFlightBytes = new java.util.concurrent.atomic.AtomicLong();
        boolean ok = false;
        try {
            for (var e : byPage.long2ObjectEntrySet()) {
                long page = e.getLongKey();
                LongArrayList list = e.getValue();
                int n = list.size() / 2;
                long[] addrs = new long[n];
                long[] sizes = new long[n];
                long quads = 0;
                for (int i = 0; i < n; i++) {
                    addrs[i] = list.getLong(2 * i);
                    sizes[i] = list.getLong(2 * i + 1);
                    quads += sizes[i];
                }
                pages.pagesTotal++;
                pages.rawBytes += quads * quadBytes;
                if (nvicache$inSync && !dirty.contains(page)) continue;

                // Read back on this (GL) thread, compress and write on the IO threads.
                var data = MemoryUtil.memAlloc(Math.toIntExact(quads * quadBytes));
                long base = MemoryUtil.memAddress(data);
                nvicache$forEachRun(addrs, sizes, (dataOffset, addr, runQuads) ->
                        nglGetNamedBufferSubData(buffer.getId(), addr * quadBytes, runQuads * quadBytes,
                                base + dataOffset * quadBytes));
                var p = new PageStore.Page(addrs, sizes, data);

                // The writes finish in the background; this only bounds how much read-back
                // geometry may wait in memory for them.
                long bytes = p.data().remaining();
                while (inFlightBytes.get() + bytes > MAX_PENDING_BYTES && !inFlight.isEmpty()) {
                    nvicache$await(inFlight.poll());
                }
                inFlightBytes.addAndGet(bytes);
                inFlight.add(PageStore.executor().submit(() -> {
                    try {
                        pages.write(page, p);
                    } finally {
                        p.free();
                        inFlightBytes.addAndGet(-bytes);
                    }
                    return null;
                }));
                pages.pagesWritten++;
            }
            ok = true;
        } finally {
            if (ok) {
                pages.pendingWrites.addAll(inFlight);
            } else {
                // Let the started writers finish so they release their buffers.
                for (var f : inFlight) {
                    try { f.get(); } catch (Exception ignored) {}
                }
            }
        }
        nvicache$syncedWithDisk();
    }

    @Unique
    private static <T> T nvicache$await(Future<T> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    @Override
    public long nvicache$allocationBytes(int addr) {
        return segments.getSize(Integer.toUnsignedLong(addr)) * 4L * vertexFormatSize;
    }

    @Override
    public void nvicache$loadFromCache(DataInputStream meta, PageStore pages) throws IOException {
        int savedVertexSize = meta.readInt();
        if (savedVertexSize != vertexFormatSize) {
            throw new IOException("vertexFormatSize mismatch: expected " + vertexFormatSize + " got " + savedVertexSize);
        }

        // Undo index-0 reservation from constructor
        ((BufferArena)(Object) this).free(0);

        totalQuads = meta.readLong();
        ((ISegmentedManagerCacheable)(Object) segments).nvicache$readFrom(meta);

        int numPages = meta.readInt();
        long[] pageIds = new long[numPages];
        for (int i = 0; i < numPages; i++) pageIds[i] = meta.readLong();

        int[] takenCount = {0};
        ((ISegmentedManagerCacheable)(Object) segments).nvicache$forEachTaken((addr, size) -> takenCount[0]++);

        long quadBytes = 4L * vertexFormatSize;
        int staging = 0;
        long stagingSize = 0;
        int loadedAllocs = 0;
        // Pages are read and decompressed on the IO threads, uploaded here in order.
        var inFlight = new ArrayDeque<Future<PageStore.Page>>();
        int next = 0;
        try {
            for (int i = 0; i < numPages; i++) {
                while (next < numPages && inFlight.size() < PageStore.THREADS * 2) {
                    long page = pageIds[next++];
                    inFlight.add(PageStore.executor().submit(() -> pages.read(page)));
                }
                PageStore.Page p = nvicache$await(inFlight.poll());
                try {
                    long bytes = p.data().remaining();
                    long expected = 0;
                    for (long s : p.sizes()) expected += s * quadBytes;
                    if (bytes != expected) throw new IOException("page " + pageIds[i] + " size mismatch");

                    if (bytes > stagingSize) {
                        if (staging != 0) glDeleteBuffers(staging);
                        stagingSize = Math.max(bytes, PageStore.PAGE_BYTES * 2);
                        staging = glCreateBuffers();
                        glNamedBufferStorage(staging, stagingSize, GL_DYNAMIC_STORAGE_BIT | GL_CLIENT_STORAGE_BIT);
                    }
                    if (bytes > 0) nglNamedBufferSubData(staging, 0, bytes, MemoryUtil.memAddress(p.data()));

                    if (buffer instanceof PersistentSparseAddressableBuffer psab) {
                        for (int j = 0; j < p.addrs().length; j++) {
                            psab.ensureAllocated(p.addrs()[j] * quadBytes, p.sizes()[j] * quadBytes);
                        }
                    }
                    int stagingId = staging;
                    nvicache$forEachRun(p.addrs(), p.sizes(), (dataOffset, addr, quads) ->
                            glCopyNamedBufferSubData(stagingId, buffer.getId(),
                                    dataOffset * quadBytes, addr * quadBytes, quads * quadBytes));
                    loadedAllocs += p.addrs().length;
                    pages.rawBytes += bytes;
                    pages.pagesTotal++;
                } finally {
                    p.free();
                }
            }
        } finally {
            for (var f : inFlight) {
                try { f.get().free(); } catch (Exception ignored) {}
            }
            if (staging != 0) glDeleteBuffers(staging);
        }
        if (loadedAllocs != takenCount[0]) {
            throw new IOException("pages hold " + loadedAllocs + " allocations, arena expects " + takenCount[0]);
        }
        nvicache$syncedWithDisk();
    }
}
