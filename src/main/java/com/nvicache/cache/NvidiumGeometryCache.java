package com.nvicache.cache;

import com.nvicache.NviCacheMod;
import com.nvicache.interfaces.ISectionManagerCacheable;
import me.cortex.nvidium.managers.SectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11.glFinish;

public class NvidiumGeometryCache {
    private static final int MAGIC = 0x4E564743; // "NVGC"
    // v5: terrain split into pages/<n>.bin, only changed pages are rewritten
    private static final short VERSION = 5;

    private static Path metaPath(Path dir)    { return dir.resolve("geometry.meta"); }
    private static Path pagesDir(Path dir)    { return dir.resolve("pages"); }
    private static Path tmp(Path p)           { return p.resolveSibling(p.getFileName() + ".tmp"); }

    /**
     * Uncompressed geometry bytes per byte of page files, from the last load or save. Used to
     * turn the on-disk size limit into a geometry budget; 1.0 (no compression assumed) until known.
     */
    private static volatile double compressionRatio = 1.0;

    /**
     * The tail of the last save (page writes, then geometry.meta), finishing in the background.
     * Loads and the next save wait for it; so does the JVM on exit.
     */
    private static CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);
    private static final ExecutorService FINISHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nvicache-save");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(NvidiumGeometryCache::awaitPendingSave, "nvicache-shutdown"));
    }

    /** Blocks until the background part of the last save is on disk. */
    public static void awaitPendingSave() {
        CompletableFuture<Void> f;
        synchronized (NvidiumGeometryCache.class) { f = pendingSave; }
        if (f.isDone()) return;
        long start = System.nanoTime();
        try {
            f.get(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            NviCacheMod.LOGGER.error("NviCache: waiting for the previous save failed", e);
        }
        NviCacheMod.LOGGER.info("NviCache: waited {} ms for the previous save to finish", (System.nanoTime() - start) / 1_000_000);
    }

    private static void updateRatio(PageStore pages, long diskBytes) {
        if (pages.rawBytes > 0 && diskBytes > 0) compressionRatio = (double) pages.rawBytes / diskBytes;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static int[] getAtlasSize() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var acc = (com.nvicache.mixin.TextureAtlasAccessor) tex;
        return new int[]{ acc.nvicache$getWidth(), acc.nvicache$getHeight() };
    }

    /**
     * Drops the farthest sections from (x, z) until the page files should fit the configured size
     * limit, estimating the compressed size from the last known compression ratio.
     */
    public static void trimForSave(SectionManager sm, double x, double z) {
        var config = NviCacheConfig.get();
        if (config.maxCacheSizeMb <= 0) return;
        double ratio = config.compress ? compressionRatio : 1.0;
        long maxRawBytes = (long) (config.maxCacheSizeMb * 1024L * 1024L * ratio);
        int removed = ((ISectionManagerCacheable) sm).nvicache$trimForSave(x, z, maxRawBytes);
        if (removed > 0) {
            NviCacheMod.LOGGER.info("NviCache: over the {} MB limit, dropped {} farthest sections",
                    config.maxCacheSizeMb, removed);
        }
    }

    /**
     * Copies the geometry out of the GPU on this (render) thread; compressing and writing it
     * to disk continues in the background. Until that is done the cache has no geometry.meta,
     * so a crash meanwhile only loses the cache.
     */
    public static void save(SectionManager sm, Path dir) {
        awaitPendingSave();
        var pages = new PageStore(pagesDir(dir), NviCacheConfig.get().compress);
        long start = System.nanoTime();
        byte[] meta;
        try {
            Files.createDirectories(dir);
            cleanupLegacy(dir);
            int[] size = getAtlasSize();
            String key = CacheKey.compute(size[0], size[1]);
            NviCacheMod.LOGGER.info("Saving nvicache for {}, key={}", dir, key);

            glFinish();

            // The meta file is what makes the cache valid. Drop it while pages are being
            // replaced, so a crash mid-save leaves no cache rather than a mismatched one.
            Files.deleteIfExists(metaPath(dir));
            var metaBytes = new ByteArrayOutputStream(1 << 20);
            try (var metaOut = new DataOutputStream(metaBytes)) {
                metaOut.writeInt(MAGIC);
                metaOut.writeShort(VERSION);
                byte[] keyBytes = key.getBytes();
                metaOut.writeShort(keyBytes.length);
                metaOut.write(keyBytes);
                ((ISectionManagerCacheable) sm).nvicache$serialize(metaOut, pages);
            }
            meta = metaBytes.toByteArray();
        } catch (Exception e) {
            NviCacheMod.LOGGER.error("Failed to save nvicache", e);
            invalidate(dir);
            return;
        }
        long blockingMs = (System.nanoTime() - start) / 1_000_000;

        var finish = CompletableFuture.runAsync(() -> {
            try {
                for (var f : pages.pendingWrites) f.get();
                Path metaTmp = tmp(metaPath(dir));
                Files.write(metaTmp, meta);
                Files.move(metaTmp, metaPath(dir), StandardCopyOption.REPLACE_EXISTING);

                long disk = pages.diskBytes();
                updateRatio(pages, disk);
                NviCacheMod.LOGGER.info("NviCache saved successfully: {} MB geometry, {} MB on disk{}, rewrote {} of {} pages; "
                                + "blocked the game for {} ms, done after {} ms",
                        mb(pages.rawBytes), mb(disk), pages.compress ? " (LZ4)" : "",
                        pages.pagesWritten, pages.pagesTotal, blockingMs, (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                NviCacheMod.LOGGER.error("Failed to save nvicache", e);
                invalidate(dir);
            }
        }, FINISHER);
        synchronized (NvidiumGeometryCache.class) { pendingSave = finish; }
    }

    public static boolean tryLoad(SectionManager sm, Path dir) {
        awaitPendingSave();
        if (!Files.exists(metaPath(dir))) return false;
        long start = System.nanoTime();

        try {
            int[] size = getAtlasSize();
            String currentKey = CacheKey.compute(size[0], size[1]);

            try (var metaIn = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(metaPath(dir)), 1 << 20))) {

                int magic = metaIn.readInt();
                if (magic != MAGIC) {
                    NviCacheMod.LOGGER.warn("NviCache: bad magic, invalidating");
                    invalidate(dir);
                    return false;
                }
                short version = metaIn.readShort();
                if (version != VERSION) {
                    NviCacheMod.LOGGER.info("NviCache: version mismatch ({} vs {}), invalidating", version, VERSION);
                    invalidate(dir);
                    return false;
                }
                int keyLen = metaIn.readShort() & 0xFFFF;
                byte[] keyBytes = new byte[keyLen];
                metaIn.readFully(keyBytes);

                if (!new String(keyBytes).equals(currentKey)) {
                    NviCacheMod.LOGGER.info("NviCache: key mismatch, invalidating");
                    invalidate(dir);
                    return false;
                }

                NviCacheMod.LOGGER.info("Loading nvicache from {}", dir);
                var pages = new PageStore(pagesDir(dir), NviCacheConfig.get().compress);
                ((ISectionManagerCacheable) sm).nvicache$deserialize(metaIn, pages);
                long disk = pages.diskBytes();
                updateRatio(pages, disk);
                NviCacheMod.LOGGER.info("NviCache loaded successfully: {} MB geometry from {} pages in {} ms",
                        mb(pages.rawBytes), pages.pagesTotal, (System.nanoTime() - start) / 1_000_000);
                return true;
            }
        } catch (Exception e) {
            NviCacheMod.LOGGER.error("Failed to load nvicache, invalidating", e);
            ((ISectionManagerCacheable) sm).nvicache$resetState();
            invalidate(dir);
            return false;
        }
    }

    /**
     * Cache v2/v3 wrote straight into <world>/nvicache/ (no dimension folder), and exits
     * after the singleplayer server stopped went to <gameDir>/nvicache/unknown/.
     */
    private static void cleanupLegacy(Path dir) {
        invalidate(dir.getParent());
        Path unknown = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("nvicache").resolve("unknown");
        if (Files.isDirectory(unknown)) {
            invalidate(unknown);
            try {
                Files.deleteIfExists(unknown);
            } catch (IOException ignored) {
                // not empty: leave it
            }
        }
    }

    public static void invalidate(Path dir) {
        try {
            Files.deleteIfExists(metaPath(dir));
            Files.deleteIfExists(tmp(metaPath(dir)));
            PageStore.deleteAll(pagesDir(dir));
            // Single-file terrain of cache v4 and older
            Files.deleteIfExists(dir.resolve("terrain.bin"));
            Files.deleteIfExists(dir.resolve("terrain.bin.tmp"));
            Files.deleteIfExists(dir.resolve("index.bin"));
        } catch (IOException e) {
            NviCacheMod.LOGGER.error("Failed to invalidate nvicache", e);
        }
    }
}
