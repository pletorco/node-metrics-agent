package kr.pletor.nodemetrics.metrics;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Streaming resource limits for collector input, independent of procfs size metadata. */
public final class CollectorInput {
  static final Limits NUMBER = new Limits(4096, 4096, 16);
  static final Limits SMALL = new Limits(65_536, 16_384, 1024);
  static final Limits TABLE = new Limits(1_048_576, 16_384, 8192);
  static final Limits MAPS = new Limits(67_108_864, 16_384, 1_048_576);
  static final int MAX_DIRECTORY_ENTRIES = 8192;
  static final int MAX_INTERFACE_CACHE_ENTRIES = 4096;
  static final int MAX_CACHE_KEY_CHARS = 256;
  static final int MAX_CGROUP_ANCESTORS = 64;
  private static final LongAdder REJECTED = new LongAdder();

  private CollectorInput() {}

  /** Number of collector reads/enumerations rejected at a resource limit. */
  public static long limitExceededCount() {
    return REJECTED.sum();
  }

  static final class Limits {
    final int bytes;
    final int lineBytes;
    final int lines;

    Limits(int bytes, int lineBytes, int lines) {
      this.bytes = bytes;
      this.lineBytes = lineBytes;
      this.lines = lines;
    }
  }

  static final class LimitExceededException extends IOException {
    LimitExceededException(String dimension, int limit) {
      super("collector input exceeds " + dimension + " limit (" + limit + ")");
      REJECTED.increment();
    }
  }

  static List<String> readLines(Path path, Limits limits) throws IOException {
    try (BufferedReader reader = reader(Files.newInputStream(path), limits, false)) {
      List<String> lines = new ArrayList<>();
      for (String line; (line = reader.readLine()) != null; ) {
        lines.add(line);
      }
      return lines;
    }
  }

  static String readString(Path path, Limits limits) throws IOException {
    try (BufferedReader reader = reader(Files.newInputStream(path), limits, false)) {
      StringBuilder result = new StringBuilder();
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        result.append(buffer, 0, count);
      }
      return result.toString();
    }
  }

  static String readFirstLine(Path path) throws IOException {
    try (BufferedReader reader = reader(Files.newInputStream(path), NUMBER, true)) {
      return reader.readLine();
    }
  }

  // The decoder and BufferedReader only see validated bytes. A huge line therefore cannot grow
  // readLine's internal builder without limit. UTF-8 decoding rejects malformed input as before.
  private static BufferedReader reader(InputStream input, Limits limits, boolean firstLine) {
    return new BufferedReader(
        new InputStreamReader(
            limited(input, limits, firstLine), StandardCharsets.UTF_8.newDecoder()));
  }

  static InputStream limited(InputStream input, Limits limits, boolean firstLine) {
    return new LimitedInput(input, limits, firstLine);
  }

  private static final class LimitedInput extends FilterInputStream {
    private final Limits limits;
    private final boolean firstLine;

    LimitedInput(InputStream input, Limits limits, boolean firstLine) {
      super(input);
      this.limits = limits;
      this.firstLine = firstLine;
    }

    private int bytes;
    private int lineBytes;
    private int lines;
    private boolean afterCr;
    private boolean finished;

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (finished) {
        return -1;
      }
      // Read at most the byte/line budget plus one byte needed to detect an overflow.
      int allowed = Math.min(limits.bytes - bytes + 1, limits.lineBytes - lineBytes + 1);
      int count = in.read(buffer, offset, Math.min(length, allowed));
      for (int i = 0; i < count; i++) {
        int value = buffer[offset + i] & 0xff;
        if (++bytes > limits.bytes) {
          throw new LimitExceededException("bytes", limits.bytes);
        }
        if (afterCr && value == '\n') {
          afterCr = false;
          continue;
        }
        afterCr = false;
        if (lines >= limits.lines) {
          throw new LimitExceededException("lines", limits.lines);
        }
        if (value == '\n' || value == '\r') {
          lines++;
          lineBytes = 0;
          afterCr = value == '\r';
          if (firstLine) {
            finished = true;
            return i + 1;
          }
        } else if (++lineBytes > limits.lineBytes) {
          throw new LimitExceededException("line bytes", limits.lineBytes);
        }
      }
      return count;
    }
  }

  static boolean excessiveAncestors(Path directory, Path root) {
    if (root != null
        && directory.startsWith(root)
        && directory.getNameCount() - root.getNameCount() > MAX_CGROUP_ANCESTORS) {
      REJECTED.increment();
      return true;
    }
    return false;
  }
}
