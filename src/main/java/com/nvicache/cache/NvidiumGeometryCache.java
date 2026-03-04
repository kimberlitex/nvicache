package com.nvicache.cache;

import com.nvicache.NviCacheMod;
import com.nvicache.interfaces.ISectionManagerCacheable;
import me.cortex.nvidium.managers.SectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.lwjgl.opengl.GL11.glFinish;

public class NvidiumGeometryCache {
    private static final int MAGIC = 0x4E564743; // "NVGC"
    private static final short VERSION = 2;

    private static Path metaPath(Path dir)    { return dir.resolve("geometry.meta"); }
    private static Path terrainPath(Path dir) { return dir.resolve("terrain.bin"); }
    private static Path indexPath(Path dir)   { return dir.resolve("index.bin"); }

    private static int[] getAtlasSize() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var acc = (com.nvicache.mixin.TextureAtlasAccessor) tex;
        return new int[]{ acc.nvicache$getWidth(), acc.nvicache$getHeight() };
    }

    public static void save(SectionManager sm) {
        Path dir = CacheKey.worldCacheDir();
        try {
            Files.createDirectories(dir);
            int[] size = getAtlasSize();
            String key = CacheKey.compute(size[0], size[1]);
            NviCacheMod.LOGGER.info("Saving nvicache for {}, key={}", dir, key);

            glFinish();

            try (var metaOut = new DataOutputStream(new BufferedOutputStream(
                         Files.newOutputStream(metaPath(dir)), 1 << 20));
                 var terrainCh = FileChannel.open(terrainPath(dir),
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 var indexCh = FileChannel.open(indexPath(dir),
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                metaOut.writeInt(MAGIC);
                metaOut.writeShort(VERSION);
                byte[] keyBytes = key.getBytes();
                metaOut.writeShort(keyBytes.length);
                metaOut.write(keyBytes);

                ((ISectionManagerCacheable) sm).nvicache$serialize(metaOut, terrainCh, indexCh);
            }

            NviCacheMod.LOGGER.info("NviCache saved successfully");
        } catch (Exception e) {
            NviCacheMod.LOGGER.error("Failed to save nvicache", e);
            invalidate(dir);
        }
    }

    public static boolean tryLoad(SectionManager sm) {
        Path dir = CacheKey.worldCacheDir();
        if (!Files.exists(metaPath(dir))) return false;

        try {
            int[] size = getAtlasSize();
            String currentKey = CacheKey.compute(size[0], size[1]);

            try (var metaIn = new DataInputStream(new BufferedInputStream(
                         Files.newInputStream(metaPath(dir)), 1 << 20));
                 var terrainCh = FileChannel.open(terrainPath(dir), StandardOpenOption.READ);
                 var indexCh   = FileChannel.open(indexPath(dir),   StandardOpenOption.READ)) {

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
                ((ISectionManagerCacheable) sm).nvicache$deserialize(metaIn, terrainCh, indexCh);
                NviCacheMod.LOGGER.info("NviCache loaded successfully");
                return true;
            }
        } catch (Exception e) {
            NviCacheMod.LOGGER.error("Failed to load nvicache, invalidating", e);
            ((ISectionManagerCacheable) sm).nvicache$resetState();
            invalidate(dir);
            return false;
        }
    }

    public static void invalidate(Path dir) {
        try {
            Files.deleteIfExists(metaPath(dir));
            Files.deleteIfExists(terrainPath(dir));
            Files.deleteIfExists(indexPath(dir));
        } catch (IOException e) {
            NviCacheMod.LOGGER.error("Failed to invalidate nvicache", e);
        }
    }
}
