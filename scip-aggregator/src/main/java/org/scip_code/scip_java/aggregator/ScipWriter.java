package org.scip_code.scip_java.aggregator;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.scip_code.scip.Index;

/** High-level utility methods to write SCIP index into the SCIP output stream. */
public class ScipWriter implements AutoCloseable {

  private final Path tmp;
  private final ScipOutputStream output;
  private final ScipAggregatorOptions options;
  private boolean built;

  public ScipWriter(ScipAggregatorOptions options) throws IOException {
    this.tmp = createTempFile();
    this.output = new ScipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)));
    this.options = options;
  }

  private static Path createTempFile() throws IOException {
    Path file = Files.createTempFile("scip-aggregator", "index.scip");
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystems (e.g. Windows) do not support POSIX file permissions.
    }
    return file;
  }

  public void emitTyped(Index index) {
    this.output.write(index.toByteArray());
  }

  public void build() throws IOException {
    output.close();
    Files.move(tmp, options.output(), StandardCopyOption.REPLACE_EXISTING);
    built = true;
  }

  @Override
  public void close() throws IOException {
    try {
      output.close();
    } finally {
      if (!built) {
        Files.deleteIfExists(tmp);
      }
    }
  }

  public void flush() {
    try {
      output.flush();
    } catch (IOException e) {
      options.reporter().error(e);
    }
  }
}
