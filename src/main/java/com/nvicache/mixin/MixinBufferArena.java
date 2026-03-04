package com.nvicache.mixin;

import com.nvicache.interfaces.IBufferArenaCacheable;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.SegmentedManager;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;

@Mixin(value = BufferArena.class, remap = false)
public class MixinBufferArena implements IBufferArenaCacheable {

    @Shadow SegmentedManager segments;
    @Shadow public IDeviceMappedBuffer buffer;
    @Shadow private long totalQuads;
    @Shadow private final int vertexFormatSize = 0;

    @Unique
    private static final int CHUNK_SIZE = 64 * 1024 * 1024;

    @Override
    public void nvicache$saveToCache(DataOutputStream meta, FileChannel data) throws IOException {
        meta.writeInt(vertexFormatSize);
        meta.writeLong(totalQuads);
        ((MixinSegmentedManager)(Object) segments).nvicache$writeTo(meta);

        var allocations = new java.util.ArrayList<long[]>();
        ((MixinSegmentedManager)(Object) segments).nvicache$forEachTaken(
                (addr, size) -> allocations.add(new long[]{addr, size}));
        meta.writeInt(allocations.size());
        if (allocations.isEmpty()) return;

        ByteBuffer chunk = MemoryUtil.memAlloc(CHUNK_SIZE);
        try {
            for (long[] alloc : allocations) {
                long addr = alloc[0];
                long size = alloc[1];
                meta.writeLong(addr);
                meta.writeLong(size);
                long byteOffset = addr * 4L * vertexFormatSize;
                long byteCount  = size * 4L * vertexFormatSize;
                long remaining = byteCount;
                long offset = byteOffset;
                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, CHUNK_SIZE);
                    chunk.limit(toRead).position(0);
                    glGetNamedBufferSubData(buffer.getId(), offset, chunk);
                    chunk.position(0);
                    data.write(chunk);
                    offset += toRead;
                    remaining -= toRead;
                }
            }
        } finally {
            MemoryUtil.memFree(chunk);
        }
    }

    @Override
    public void nvicache$loadFromCache(DataInputStream meta, FileChannel data) throws IOException {
        int savedVertexSize = meta.readInt();
        if (savedVertexSize != vertexFormatSize) {
            throw new IOException("vertexFormatSize mismatch: expected " + vertexFormatSize + " got " + savedVertexSize);
        }

        // Undo index-0 reservation from constructor
        ((BufferArena)(Object) this).free(0);

        totalQuads = meta.readLong();
        ((MixinSegmentedManager)(Object) segments).nvicache$readFrom(meta);

        int numAllocs = meta.readInt();
        if (numAllocs == 0) return;

        int stagingId = glCreateBuffers();
        glNamedBufferStorage(stagingId, CHUNK_SIZE, GL_DYNAMIC_STORAGE_BIT | GL_CLIENT_STORAGE_BIT);
        ByteBuffer chunk = MemoryUtil.memAlloc(CHUNK_SIZE);
        try {
            for (int i = 0; i < numAllocs; i++) {
                long addr = meta.readLong();
                long size = meta.readLong();
                long byteOffset = addr * 4L * vertexFormatSize;
                long byteCount  = size * 4L * vertexFormatSize;

                if (buffer instanceof PersistentSparseAddressableBuffer psab) {
                    psab.ensureAllocated(byteOffset, byteCount);
                }

                long remaining = byteCount;
                long offset = byteOffset;
                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, CHUNK_SIZE);
                    chunk.limit(toRead).position(0);
                    int bytesRead = 0;
                    while (bytesRead < toRead) {
                        int r = data.read(chunk);
                        if (r < 0) throw new IOException("Unexpected EOF reading nvicache");
                        bytesRead += r;
                    }
                    chunk.position(0);
                    glNamedBufferSubData(stagingId, 0, chunk);
                    glCopyNamedBufferSubData(stagingId, buffer.getId(), 0, offset, toRead);
                    offset += toRead;
                    remaining -= toRead;
                }
            }
        } finally {
            MemoryUtil.memFree(chunk);
            glDeleteBuffers(stagingId);
        }
    }
}
