package com.nvicache.interfaces;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface IRegionManagerCacheable {
    void nvicache$serialize(DataOutputStream out) throws IOException;
    void nvicache$deserialize(DataInputStream in) throws IOException;
}
