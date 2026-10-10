package com.nvicache.cache;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

public class CacheKey {

    public static String compute(int atlasWidth, int atlasHeight) {
        StringBuilder sb = new StringBuilder();
        ModList.get().getMods().stream()
                .sorted(Comparator.comparing(m -> m.getModId()))
                .forEach(m -> sb.append(m.getModId())
                        .append(":").append(m.getVersion().toString())
                        .append(";"));
        Minecraft.getInstance().getResourcePackRepository()
                .getSelectedIds()
                .forEach(id -> sb.append(id).append(";"));
        sb.append(atlasWidth).append("x").append(atlasHeight);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(sb.toString().hashCode());
        }
    }

    /**
     * Returns the cache directory for the current world and dimension, or null if the world
     * cannot be identified (then nothing is cached, so geometry of one world is never loaded
     * into another).
     * For local worlds: <saves>/<worldName>/nvicache/<dimension>/
     * For multiplayer:  <gameDir>/nvicache/mp_<ip>/<dimension>/
     *
     * Must be resolved while the world is still loaded (renderer creation): by the time the
     * renderer is deleted on leaving a world the singleplayer server is already gone.
     */
    public static Path worldCacheDir() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String dimension = sanitize(mc.level.dimension().location().toString());

        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            // Place cache directly in the world save folder
            Path worldRoot = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            return worldRoot.resolve("nvicache").resolve(dimension);
        }
        var server = mc.getCurrentServer();
        if (server == null) return null;
        return FMLPaths.GAMEDIR.get().resolve("nvicache").resolve("mp_" + sanitize(server.ip)).resolve(dimension);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
