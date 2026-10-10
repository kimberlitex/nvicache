package com.nvicache.interfaces;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface IIdProviderCacheable {
    void nvicache$writeTo(DataOutputStream out) throws IOException;
    void nvicache$readFrom(DataInputStream in) throws IOException;
}
