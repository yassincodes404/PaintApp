package com.paintstudio.common.io;

import java.io.*;

/** Enforces a byte limit even if a file changes after its size was checked. */
public final class LimitedInputStream extends FilterInputStream {
  private long remaining;

  public LimitedInputStream(InputStream input, long maxBytes) {
    super(input);
    remaining = maxBytes;
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value != -1 && --remaining < 0) throw new IOException("Input byte limit exceeded");
    return value;
  }

  @Override
  public int read(byte[] b, int offset, int length) throws IOException {
    int read = in.read(b, offset, (int) Math.min(length, remaining + 1));
    if (read > 0 && (remaining -= read) < 0) throw new IOException("Input byte limit exceeded");
    return read;
  }
}
