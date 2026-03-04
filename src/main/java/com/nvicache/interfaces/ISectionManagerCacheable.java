package com.nvicache.interfaces;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public interface ISectionManagerCacheable {
    void nvicache$serialize(DataOutputStream meta, FileChannel terrain, FileChannel index) throws IOException;
    void nvicache$deserialize(DataInputStream meta, FileChannel terrain, FileChannel index) throws IOException;
    void nvicache$resetState();
}
