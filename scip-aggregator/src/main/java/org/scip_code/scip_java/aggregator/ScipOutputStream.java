package org.scip_code.scip_java.aggregator;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Low-level methods to write raw SCIP bytes into the output stream. */
public class ScipOutputStream implements AutoCloseable {
  private final OutputStream out;
  private final ConcurrentLinkedDeque<byte[]> buffer;
  private final AtomicBoolean isFlushing;
  private final AtomicBoolean closed;

  public ScipOutputStream(OutputStream out) {
    this.out = out;
    this.buffer = new ConcurrentLinkedDeque<>();
    this.isFlushing = new AtomicBoolean(false);
    this.closed = new AtomicBoolean(false);
  }

  public void write(byte[] bytes) {
    if (bytes.length == 0) return;
    buffer.add(bytes);
  }

  public void flush() throws IOException {
    if (isFlushing.compareAndSet(false, true)) {
      try {
        byte[] bytes = buffer.poll();
        while (bytes != null) {
          out.write(bytes);
          bytes = buffer.poll();
        }
        out.flush();
      } finally {
        isFlushing.set(false);
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      try {
        flush();
      } finally {
        out.close();
      }
    }
  }
}
