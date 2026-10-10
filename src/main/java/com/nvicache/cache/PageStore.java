package com.nvicache.cache;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.lwjgl.system.MemoryUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Terrain geometry split by arena address into fixed-size pages, one file each, so a save only
 * rewrites the pages that changed and pages can be (de)compressed in parallel.
 * <p>
 * A page holds every allocation whose first byte lies in it: page = byteOffset / PAGE_BYTES.
 */
public final class PageStore {
    public static final long PAGE_BYTES = 16L << 20;

    private static final int MAGIC = 0x4E565047; // "NVPG"
    private static final byte FLAG_LZ4 = 1;

    private static ExecutorService executor;
    public static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    public final Path dir;
    public final boolean compress;

    // Filled in by the arena while saving / loading, for logging and the compression ratio.
    public long rawBytes;
    public int pagesTotal;
    public int pagesWritten;
    /** Page writes still running after the arena handed them off. */
    public final java.util.List<java.util.concurrent.Future<?>> pendingWrites = new java.util.ArrayList<>();

    public PageStore(Path dir, boolean compress) {
        this.dir = dir;
        this.compress = compress;
    }

    public static synchronized ExecutorService executor() {
        if (executor == null) {
            AtomicInteger n = new AtomicInteger();
            executor = Executors.newFixedThreadPool(THREADS, r -> {
                Thread t = new Thread(r, "nvicache-io-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    /** One page's allocations (quad address and size), with their geometry packed back to back in that order. */
    public record Page(long[] addrs, long[] sizes, ByteBuffer data) {
        public void free() { MemoryUtil.memFree(data); }
    }

    private Path pageFile(long page) { return dir.resolve(page + ".bin"); }

    public void write(long page, Page p) throws IOException {
        Files.createDirectories(dir);
        Path file = pageFile(page);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var fileOut = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            var header = new DataOutputStream(fileOut);
            header.writeInt(MAGIC);
            header.writeByte(compress ? FLAG_LZ4 : 0);
            header.flush();
            OutputStream body = compress
                    ? new LZ4FrameOutputStream(fileOut, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB)
                    : fileOut;
            var out = new DataOutputStream(body);
            out.writeInt(p.addrs.length);
            for (int i = 0; i < p.addrs.length; i++) {
                out.writeLong(p.addrs[i]);
                out.writeLong(p.sizes[i]);
            }
            out.writeLong(p.data.remaining());
            out.flush();
            WritableByteChannel ch = Channels.newChannel(body);
            ByteBuffer data = p.data.duplicate();
            while (data.hasRemaining()) ch.write(data);
            if (compress) body.close(); // finishes the LZ4 frame
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public Page read(long page) throws IOException {
        try (var fileIn = new BufferedInputStream(Files.newInputStream(pageFile(page)), 1 << 20)) {
            var header = new DataInputStream(fileIn);
            if (header.readInt() != MAGIC) throw new IOException("bad page magic in " + pageFile(page));
            boolean compressed = (header.readByte() & FLAG_LZ4) != 0;
            InputStream body = compressed ? new LZ4FrameInputStream(fileIn) : fileIn;
            var in = new DataInputStream(body);
            int count = in.readInt();
            long[] addrs = new long[count];
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++) {
                addrs[i] = in.readLong();
                sizes[i] = in.readLong();
            }
            long len = in.readLong();
            if (len < 0 || len > Integer.MAX_VALUE) throw new IOException("bad page length " + len);
            ByteBuffer data = MemoryUtil.memAlloc((int) len);
            try {
                ReadableByteChannel ch = Channels.newChannel(in);
                while (data.hasRemaining()) {
                    if (ch.read(data) < 0) throw new IOException("unexpected EOF in " + pageFile(page));
                }
            } catch (IOException | RuntimeException e) {
                MemoryUtil.memFree(data);
                throw e;
            }
            data.flip();
            return new Page(addrs, sizes, data);
        }
    }

    public void delete(long page) throws IOException {
        Files.deleteIfExists(pageFile(page));
    }

    /** Pages that currently have a file on disk. */
    public LongSet existingPages() throws IOException {
        LongSet pages = new LongOpenHashSet();
        if (!Files.isDirectory(dir)) return pages;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                try {
                    pages.add(Long.parseLong(name.substring(0, name.length() - 4)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return pages;
    }

    /** Total size of the page files on disk. */
    public long diskBytes() throws IOException {
        long total = 0;
        if (!Files.isDirectory(dir)) return 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path f : files) total += Files.size(f);
        }
        return total;
    }

    /** Removes the whole page directory, including leftover temp files. */
    public static void deleteAll(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
    }
}
