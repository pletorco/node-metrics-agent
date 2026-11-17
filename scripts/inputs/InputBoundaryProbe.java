package kr.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Separate, small-heap host exercising the packaged production collectors. */
public final class InputBoundaryProbe {
  private InputBoundaryProbe() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    Path root = Path.of(args[1]);
    Path source = Path.of(args[2]);
    LinuxProcFs.setProcRoot(root);
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    switch (mode) {
      case "long-line":
      case "total-bytes":
      case "many-lines":
        reject(() -> LinuxProcFs.readLines(source));
        break;
      case "small-string":
        reject(() -> SystemAccess.defaultInstance().readString(source));
        break;
      case "scalar":
        require(LinuxProcFs.readFirstNumber(source) == -1L, "scalar was not unavailable");
        break;
      case "mounts":
        mounts(root, source);
        break;
      case "maps":
        maps(root, source);
        break;
      case "interface-cache":
        interfaces(root);
        break;
      default:
        throw new AssertionError("unknown probe mode");
    }
    if (!"interface-cache".equals(mode)) {
      require(CollectorInput.limitExceededCount() > 0L, "limit failure was not counted");
    }
    // Application work and normal process exit must remain possible after the rejected input.
    long checksum = 0;
    for (int i = 0; i < 100000; i++) {
      checksum += i;
    }
    require(checksum == 4999950000L, "host work did not complete");
    System.out.println("INPUT BOUNDARY OK " + mode);
  }

  private static void mounts(Path root, Path source) throws Exception {
    Path target = Files.createDirectories(root.resolve("self")).resolve("mounts");
    Files.writeString(root.resolve("uptime"), "123 0\n");
    Files.writeString(target, "tmpfs / tmpfs rw 0 0\n");
    OsRuntimeMetrics metric = new OsRuntimeMetrics();
    metric.setReadRefreshEnabled(false);
    metric.poll();
    require(metric.getMountCount() == 1, "normal mount snapshot failed");
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    metric.poll();
    require(metric.getMountCount() == -1, "partial mount count published");
    require(metric.getMounts().length == 0, "oversized mounts retained");
    require(metric.lastRefreshError() != null, "mount rejection hidden");
    Files.writeString(target, "tmpfs / tmpfs rw 0 0\n");
    metric.poll();
    require(
        metric.getMountCount() == 1 && metric.lastRefreshError() == null, "mount recovery failed");
  }

  private static void maps(Path root, Path source) throws Exception {
    Path target = Files.createDirectories(root.resolve("self")).resolve("maps");
    Files.writeString(target, "mapping\nmapping\n");
    MemoryMapMetrics metric = new MemoryMapMetrics();
    metric.setReadRefreshEnabled(false);
    metric.poll();
    require(metric.getMemoryMapCount() == 2L, "normal maps count failed");
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    metric.poll();
    require(metric.getMemoryMapCount() == 2L, "last valid maps count was lost");
    require(metric.lastRefreshError() != null, "maps rejection hidden");
    Files.writeString(target, "mapping\n");
    metric.poll();
    require(
        metric.getMemoryMapCount() == 1L && metric.lastRefreshError() == null,
        "maps recovery failed");
  }

  private static void interfaces(Path root) throws Exception {
    Path source = Files.createDirectories(root.resolve("net")).resolve("dev");
    Field field = LinuxProcFs.class.getDeclaredField("PHYSICAL_IFACE_CACHE");
    field.setAccessible(true);
    for (String prefix : new String[] {"first", "second"}) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 5000; i++) {
        text.append(prefix).append(i).append(": 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0\n");
      }
      Files.writeString(source, text);
      require(
          LinuxProcFs.readNetTotals().rxBytes == 5000L, "network totals truncated at cache cap");
      require(((Map<?, ?>) field.get(null)).size() <= 4096, "interface cache exceeded its cap");
    }
  }

  private static void reject(IoAction action) throws Exception {
    try {
      action.run();
      throw new AssertionError("oversized input accepted");
    } catch (IOException expected) {
      require(
          expected.getMessage().startsWith("collector input exceeds"), "unexpected read failure");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private interface IoAction {
    void run() throws IOException;
  }
}
