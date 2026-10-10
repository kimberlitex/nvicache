package com.nvicache.interfaces;

import com.nvicache.cache.PageStore;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface ISectionManagerCacheable {
    void nvicache$serialize(DataOutputStream meta, PageStore pages) throws IOException;
    void nvicache$deserialize(DataInputStream meta, PageStore pages) throws IOException;
    void nvicache$resetState();

    /**
     * Deletes the sections farthest (horizontally) from (x, z) until the geometry fits in
     * {@code maxBytes}.
     * Only meant to be called right before saving, while the renderer is being torn down.
     *
     * @return number of sections removed
     */
    int nvicache$trimForSave(double x, double z, long maxBytes);
}
