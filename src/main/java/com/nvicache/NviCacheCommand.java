package com.nvicache;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.nvicache.cache.NviCacheConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client command:
 * <pre>
 * /nvicache maxCacheSizeMb            show the current limit
 * /nvicache maxCacheSizeMb &lt;mb&gt;       set it (0 = no limit)
 * /nvicache maxCacheSizeMb default    reset it
 * </pre>
 * The new limit applies from the next save (leaving the world or changing render settings).
 */
public final class NviCacheCommand {
    private NviCacheCommand() {}

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nvicache")
                .then(Commands.literal("maxCacheSizeMb")
                        .executes(ctx -> {
                            int mb = NviCacheConfig.get().maxCacheSizeMb;
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "NviCache maxCacheSizeMb = " + describe(mb)), false);
                            return mb;
                        })
                        .then(Commands.literal("default")
                                .executes(ctx -> set(ctx.getSource(), NviCacheConfig.DEFAULT_MAX_CACHE_SIZE_MB)))
                        .then(Commands.argument("mb", IntegerArgumentType.integer(0))
                                .executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "mb"))))));
    }

    private static int set(CommandSourceStack source, int mb) {
        var config = NviCacheConfig.get();
        config.maxCacheSizeMb = mb;
        config.save();
        source.sendSuccess(() -> Component.literal(
                "NviCache maxCacheSizeMb set to " + describe(mb) + ", applies on the next save"), false);
        return mb;
    }

    private static String describe(int mb) {
        return mb == 0 ? "0 (no limit)" : mb + " MB";
    }
}
