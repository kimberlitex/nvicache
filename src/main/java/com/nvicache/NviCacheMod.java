package com.nvicache;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = "nvicache", dist = Dist.CLIENT)
public class NviCacheMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("nvicache");

    public NviCacheMod() {
        NeoForge.EVENT_BUS.addListener(NviCacheCommand::register);
        LOGGER.info("NviCache initialized");
    }
}
