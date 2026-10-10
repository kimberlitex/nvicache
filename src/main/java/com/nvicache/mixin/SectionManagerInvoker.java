package com.nvicache.mixin;

import me.cortex.nvidium.managers.SectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SectionManager.class, remap = false)
public interface SectionManagerInvoker {
    // Private SectionManager.deleteSection(long): frees the section's geometry and region slot.
    @Invoker("deleteSection")
    void nvicache$deleteSection(long sectionKey);
}
