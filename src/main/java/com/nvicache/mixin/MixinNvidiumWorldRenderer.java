package com.nvicache.mixin;

import com.nvicache.cache.CacheKey;
import com.nvicache.cache.NvidiumGeometryCache;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(value = NvidiumWorldRenderer.class, remap = false)
public class MixinNvidiumWorldRenderer {

    @Shadow private SectionManager sectionManager;
    @Shadow private UploadingBufferStream uploadStream;

    // Resolved once at creation: when delete() runs on leaving a world, the singleplayer
    // server (and so the world folder) is no longer reachable.
    @Unique private Path nvicache$cacheDir;
    // Last camera position rendered, used to decide which sections are near enough to save.
    @Unique private boolean nvicache$hasCamera;
    @Unique private double nvicache$cameraX;
    @Unique private double nvicache$cameraZ;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvicache$onInit(AsyncOcclusionTracker asyncChunkTracker, CallbackInfo ci) {
        nvicache$cacheDir = CacheKey.worldCacheDir();
        if (nvicache$cacheDir != null) {
            NvidiumGeometryCache.tryLoad(sectionManager, nvicache$cacheDir);
        }
    }

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void nvicache$trackCamera(Viewport viewport, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
        nvicache$hasCamera = true;
        nvicache$cameraX = x;
        nvicache$cameraZ = z;
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void nvicache$onDelete(CallbackInfo ci) {
        // Nothing rendered means nothing new to save; keep whatever cache is on disk.
        if (nvicache$cacheDir == null || !nvicache$hasCamera) return;
        NvidiumGeometryCache.trimForSave(sectionManager, nvicache$cameraX, nvicache$cameraZ);
        sectionManager.commitChanges();
        uploadStream.commit();
        NvidiumGeometryCache.save(sectionManager, nvicache$cacheDir);
    }
}
