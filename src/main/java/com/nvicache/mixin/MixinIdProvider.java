package com.nvicache.mixin;

import it.unimi.dsi.fastutil.ints.IntSortedSet;
import me.cortex.nvidium.util.IdProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@Mixin(value = IdProvider.class, remap = false)
public class MixinIdProvider {

    @Shadow private int cid;
    @Shadow private IntSortedSet free;

    @Unique
    public void nvicache$writeTo(DataOutputStream out) throws IOException {
        out.writeInt(cid);
        out.writeInt(free.size());
        for (int v : free) out.writeInt(v);
    }

    @Unique
    public void nvicache$readFrom(DataInputStream in) throws IOException {
        cid = in.readInt();
        free.clear();
        int size = in.readInt();
        for (int i = 0; i < size; i++) free.add(in.readInt());
    }
}
