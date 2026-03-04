package com.nvicache;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NviCacheMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("nvicache");

    @Override
    public void onInitializeClient() {
        LOGGER.info("NviCache initialized");
    }
}
