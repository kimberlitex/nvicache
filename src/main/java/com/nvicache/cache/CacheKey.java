package com.nvicache.cache;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

public class CacheKey {

    public static String compute(int atlasWidth, int atlasHeight) {
        StringBuilder sb = new StringBuilder();
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(Comparator.comparing(m -> m.getMetadata().getId()))
                .forEach(m -> sb.append(m.getMetadata().getId())
                        .append(":").append(m.getMetadata().getVersion().getFriendlyString())
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
     * Returns the cache directory for the current world.
     * For local worlds: <saves>/<worldName>/nvicache/
     * For multiplayer:  <gameDir>/nvicache/mp_<ip>/
     */
    public static Path worldCacheDir() {
        var mc = Minecraft.getInstance();
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            // Place cache directly in the world save folder
            Path worldRoot = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            return worldRoot.resolve("nvicache");
        }
        var server = mc.getCurrentServer();
        String id = server != null
                ? "mp_" + server.ip.replaceAll("[^a-zA-Z0-9_\\-]", "_")
                : "unknown";
        return FabricLoader.getInstance().getGameDir().resolve("nvicache").resolve(id);
    }
}
