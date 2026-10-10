package com.nvicache.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nvicache.NviCacheMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/nvicache.json. Every field has a primitive default, so keys missing from an
 * older file keep their defaults instead of becoming null.
 */
public class NviCacheConfig {
    /** Upper bound for terrain.bin on disk, in MB; farthest sections are dropped first. 0 = no limit. */
    public int maxCacheSizeMb = DEFAULT_MAX_CACHE_SIZE_MB;
    /** LZ4-compress terrain.bin. */
    public boolean compress = true;

    public static final int DEFAULT_MAX_CACHE_SIZE_MB = 1024;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static NviCacheConfig instance;

    public static NviCacheConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("nvicache.json");
    }

    private static NviCacheConfig load() {
        NviCacheConfig config = null;
        Path path = path();
        if (Files.exists(path)) {
            try {
                config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), NviCacheConfig.class);
            } catch (Exception e) {
                NviCacheMod.LOGGER.error("Could not read {}, using defaults", path, e);
            }
        }
        if (config == null) config = new NviCacheConfig();
        // Rewrite so new options show up (and stale ones disappear) in the file.
        config.save();
        return config;
    }

    public void save() {
        Path path = path();
        try {
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NviCacheMod.LOGGER.error("Could not write {}", path, e);
        }
    }
}
