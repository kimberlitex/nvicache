package com.nvicache.mixin;

import com.nvicache.cache.NvidiumGeometryCache;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NvidiumWorldRenderer.class, remap = false)
public class MixinNvidiumWorldRenderer {

    @Shadow private final SectionManager sectionManager = null;
    @Shadow private final UploadingBufferStream uploadStream = null;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvicache$onInit(AsyncOcclusionTracker asyncChunkTracker, CallbackInfo ci) {
        NvidiumGeometryCache.tryLoad(sectionManager);
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void nvicache$onDelete(CallbackInfo ci) {
        sectionManager.commitChanges();
        uploadStream.commit();
        NvidiumGeometryCache.save(sectionManager);
    }
}
