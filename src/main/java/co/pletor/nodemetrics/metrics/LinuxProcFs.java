package co.pletor.nodemetrics.metrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utility helpers for reading Linux {@code /proc}, {@code /sys}, and cgroup files.
 * <p>
 * This class is package-private and not intended to be part of the public API.
 * It provides:
 * <ul>
 *   <li>Disk I/O totals from {@code /proc/diskstats}</li>
 *   <li>Network I/O totals from {@code /proc/net/dev}</li>
 *   <li>Cgroup (v1/v2) detection and paths</li>
 *   <li>Convenience helpers for parsing small numeric files</li>
 * </ul>
 */
final class LinuxProcFs {
  private static final String BLOCK_DIR = "block";
  private static final long LEAF_DEVICE_CACHE_TTL_MS = 30_000L;
  private static Path procRoot = Paths.get("/proc");
  private static Path sysRoot = Paths.get("/sys");
  @SuppressWarnings("java:S3077")
  private static volatile Set<String> cachedLeafDevices = Set.of();
  private static volatile long leafDeviceCacheTimeMs = 0L;
  private static final Object LEAF_DEVICE_CACHE_LOCK = new Object();
  private static final Logger LOGGER = Logger.getLogger(LinuxProcFs.class.getName());

  private LinuxProcFs() {
    // Utility class; no instances.
  }

  // Visible for testing
  static void setProcRoot(Path p) {
    procRoot = p;
  }

  // Visible for testing
  static void setSysRoot(Path p) {
    sysRoot = p;
    invalidateLeafDeviceCache();
  }

  private static void invalidateLeafDeviceCache() {
    synchronized (LEAF_DEVICE_CACHE_LOCK) {
      cachedLeafDevices = Set.of();
      leafDeviceCacheTimeMs = 0L;
    }
  }

  /**
   * Check whether the current OS is a Linux variant.
   *
   * @return {@code true} if {@code os.name} contains {@code "linux"} (case-insensitive)
   */
  static boolean isLinux() {
    try {
      String os = System.getProperty("os.name", "");
      return os != null && os.toLowerCase(Locale.ROOT).contains("linux");
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Read all lines from a file using UTF-8.
   *
   * @param p path to the file
   * @return list of lines (without line terminators)
   * @throws IOException if the file cannot be read
   */
  static List<String> readLines(Path p) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      List<String> out = new ArrayList<>();
      for (String line; (line = br.readLine()) != null; ) {
        out.add(line);
      }
      return out;
    }
  }

  // ---------------------------------------------------------------------------
  // Diskstats parsing (/proc/diskstats)
  // ---------------------------------------------------------------------------

  /**
   * Aggregated disk totals across block devices.
   */
  static class DiskTotals {
    /**
     * Sum of sectors read across all base/leaf block devices.
     */
    long readSectors = 0;

    /**
     * Sum of sectors written across all base/leaf block devices.
     */
    long writtenSectors = 0;
  }

  /**
   * Collect leaf block devices under /sys/block.
   * <p>
   * A device is considered "leaf" when:
   * <ul>
   *   <li>It is a top-level entry under /sys/block</li>
   *   <li>Its {@code slaves/} directory is empty (or missing)</li>
   *   <li>It is not a well-known pseudo device such as loop, ram, fd, sr</li>
   * </ul>
   * If discovery fails, an empty set is returned and the caller should fall back to
   * the legacy behavior.
   */
  private static Set<String> collectLeafBlockDevices() {
    Set<String> leaf = new HashSet<>();

    Path sysBlock = sysRoot.resolve(BLOCK_DIR);
    if (!Files.isDirectory(sysBlock)) {
      return leaf;
    }

    try (Stream<Path> stream = Files.list(sysBlock)) {
      stream.forEach(devPath -> {
        String name = devPath.getFileName().toString();

        // Skip well-known pseudo / virtual devices.
        if (isPseudoBlockDevice(name)) {
          return;
        }

        Path slavesDir = devPath.resolve("slaves");
        boolean hasSlaves = false;

        if (Files.isDirectory(slavesDir)) {
          try (Stream<Path> slaves = Files.list(slavesDir)) {
            hasSlaves = slaves.findAny().isPresent();
          } catch (IOException e) {
            // In doubt, treat as non-leaf to avoid double-counting stacked devices.
            hasSlaves = true;
          }
        }

        // Devices without slaves are treated as leaf devices (physical / backing).
        if (!hasSlaves) {
          leaf.add(name);
        }
      });
    } catch (IOException ignore) {
      // On any failure, return an empty set to let the caller fall back to legacy behavior.
    }

    return leaf;
  }

  private static boolean isPseudoBlockDevice(String name) {
    return name.startsWith("ram")
        || name.startsWith("loop")
        || name.startsWith("fd")
        || name.startsWith("sr");
  }

  /**
   * Return cached leaf block devices with a short TTL to avoid repeated
   * expensive scans of /sys/block on every scrape.
   */
  private static Set<String> getLeafBlockDevicesCached() {
    long now = System.currentTimeMillis();
    long age = now - leafDeviceCacheTimeMs;
    if (leafDeviceCacheTimeMs > 0L && age >= 0L && age < LEAF_DEVICE_CACHE_TTL_MS) {
      return cachedLeafDevices;
    }

    synchronized (LEAF_DEVICE_CACHE_LOCK) {
      now = System.currentTimeMillis();
      age = now - leafDeviceCacheTimeMs;
      if (leafDeviceCacheTimeMs > 0L && age >= 0L && age < LEAF_DEVICE_CACHE_TTL_MS) {
        return cachedLeafDevices;
      }

      Set<String> discovered = collectLeafBlockDevices();
      cachedLeafDevices = discovered.isEmpty() ? Set.of() : Set.copyOf(discovered);
      leafDeviceCacheTimeMs = now;
      return cachedLeafDevices;
    }
  }

  /**
   * Read disk I/O totals from {@code /proc/diskstats}.
   * <p>
   * Only leaf devices under {@code /sys/block/<name>} are included when possible.
   * If leaf detection fails, we fall back to including all base devices that
   * exist under {@code /sys/block/<name>} (the previous behavior).
   *
   * @return aggregated {@link DiskTotals} (zeroed if file is missing/unreadable)
   * @throws IOException if {@code /proc/diskstats} cannot be read
   */
  static DiskTotals readDiskTotals() throws IOException {
    DiskTotals t = new DiskTotals();
    Path diskstats = procRoot.resolve("diskstats");
    if (!Files.isRegularFile(diskstats)) {
      return t;
    }

    // Discover leaf devices once so that we avoid double-counting stacked devices
    // such as dm-0 or md0 on top of physical disks.
    Set<String> leafDevices = getLeafBlockDevicesCached();
    boolean sysBlockAvailable = Files.isDirectory(sysRoot.resolve(BLOCK_DIR));

    List<String> lines = readLines(diskstats);
    for (String ln : lines) {
      ln = ln.trim();
      if (ln.isEmpty()) {
        continue;
      }

      // Format: major minor name ... many fields ...
      String[] parts = ln.split("\\s+");
      if (parts.length < 14) {
        continue;
      }

      String name = parts[2];
      if (!shouldIncludeDiskDevice(name, leafDevices, sysBlockAvailable)) {
        continue;
      }

      // /proc/diskstats fields:
      //  6th = sectors read (parts[5])
      // 10th = sectors written (parts[9])
      long sectorsRead = parseLongSafe(parts[5]);
      long sectorsWritten = parseLongSafe(parts[9]);

      t.readSectors += sectorsRead;
      t.writtenSectors += sectorsWritten;
    }
    return t;
  }

  private static boolean shouldIncludeDiskDevice(
      String name,
      Set<String> leafDevices,
      boolean sysBlockAvailable
  ) {
    if (!leafDevices.isEmpty()) {
      // When we have a leaf-device set, restrict to those devices only.
      return leafDevices.contains(name);
    }
    if (sysBlockAvailable) {
      // Fallback when leaf discovery fails: include only base devices present under /sys/block/<name>.
      return Files.isDirectory(sysRoot.resolve(BLOCK_DIR).resolve(name));
    }
    // In restricted environments where /sys/block is not available, avoid obvious pseudo devices.
    return !isPseudoBlockDevice(name);
  }

  // ---------------------------------------------------------------------------
  // Network totals parsing (/proc/net/dev)
  // ---------------------------------------------------------------------------

  /**
   * Aggregated network I/O totals across interfaces.
   */
  static class NetTotals {
    /**
     * Sum of received bytes from all non-loopback interfaces.
     */
    long rxBytes = 0;

    /**
     * Sum of transmitted bytes from all non-loopback interfaces.
     */
    long txBytes = 0;
  }

  /**
   * Read network I/O totals from {@code /proc/net/dev}.
   * <p>
   * Loopback interface ({@code lo}) is excluded.
   *
   * @return aggregated {@link NetTotals} (zeroed if file is missing/unreadable)
   * @throws IOException if {@code /proc/net/dev} cannot be read
   */
  static NetTotals readNetTotals() throws IOException {
    NetTotals t = new NetTotals();
    Path p = procRoot.resolve("net/dev");
    if (!Files.isRegularFile(p)) {
      return t;
    }

    List<String> lines = readLines(p);
    for (String ln : lines) {
      ln = ln.trim();
      if (!ln.contains(":")) {
        continue;
      }

      String[] kv = ln.split(":", 2);
      String iface = kv[0].trim();
      if ("lo".equals(iface)) {
        // Skip loopback
        continue;
      }

      String[] nums = kv[1].trim().split("\\s+");
      if (nums.length < 16) {
        continue;
      }

      // Layout: rx bytes (0), rx packets (1), ..., tx bytes (8), ...
      long rx = parseLongSafe(nums[0]);
      long tx = parseLongSafe(nums[8]);

      t.rxBytes += rx;
      t.txBytes += tx;
    }
    return t;
  }

  /**
   * Safely parse a long from a string.
   *
   * @param s input string
   * @return parsed value, or {@code 0} on any error
   */
  static long parseLongSafe(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (Exception e) {
      return 0L;
    }
  }

  // ---------------------------------------------------------------------------
  // Cgroup helpers
  // ---------------------------------------------------------------------------

  /**
   * Captured cgroup information for the current process.
   */
  static class CgroupInfo {
    /**
     * Detected cgroup version: {@code "v1"}, {@code "v2"}, or {@code "none"}.
     */
    String version = "none";

    /**
     * Raw cgroup path as reported in {@code /proc/self/cgroup}.
     */
    String path = "";

    /**
     * Base directory for the cgroup controller (e.g., {@code /sys/fs/cgroup}).
     */
    Path baseDir = null;

    /**
     * Resolved absolute path to the cgroup directory combining {@code baseDir} and {@code path}.
     */
    Path resolved = null;
  }

  /**
   * Detect cgroup version and paths for the current process.
   * <p>
   * Logic:
   * <ol>
   *   <li>If {@code /sys/fs/cgroup/cgroup.controllers} exists, treat as cgroup v2.</li>
   *   <li>Otherwise, inspect {@code /proc/self/cgroup} for a {@code memory} controller (v1).</li>
   *   <li>On failure or non-Linux, version is {@code "none"}.</li>
   * </ol>
   *
   * @return populated {@link CgroupInfo} (version may be {@code "none"})
   */
  static CgroupInfo detectCgroup() {
    CgroupInfo info = new CgroupInfo();
    if (!isLinux()) {
      return info;
    }

    try {
      CgroupInfo v2 = tryDetectCgroupV2();
      if (v2 != null) {
        LOGGER.log(Level.FINE, "Detected cgroup v2: {0}", v2.resolved);
        return v2;
      }

      CgroupInfo v1 = tryDetectCgroupV1();
      if (v1 != null) {
        LOGGER.log(Level.FINE, "Detected cgroup v1: {0}", v1.resolved);
        return v1;
      }
    } catch (Exception e) {
       LOGGER.log(Level.FINE, "Cgroup detection failed", e);
      // Fall through and return default info (version "none").
    }

    LOGGER.log(Level.FINE, "No cgroup controller detected (version='none')");
    return info;
  }

  /**
   * Try to detect cgroup v2 configuration.
   *
   * @return populated {@link CgroupInfo} for v2, or {@code null} if not v2.
   */
  private static CgroupInfo tryDetectCgroupV2() {
    Path v2Controllers = sysRoot.resolve("fs/cgroup/cgroup.controllers");
    if (!Files.isRegularFile(v2Controllers)) {
      return null;
    }

    CgroupInfo info = new CgroupInfo();
    info.version = "v2";
    info.baseDir = sysRoot.resolve("fs/cgroup");

    String path = readV2SelfCgroupPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }

    info.path = path;
    info.resolved = resolveCgroupPath(info.baseDir, path);
    return info;
  }

  /**
   * Parse {@code /proc/self/cgroup} to get the cgroup path for v2
   * (line starting with "0::").
   */
  private static String readV2SelfCgroupPath() {
    Path selfCgroup = procRoot.resolve("self/cgroup");
    try {
      if (!Files.isRegularFile(selfCgroup)) {
        return "";
      }
      for (String ln : readLines(selfCgroup)) {
        String[] parts = ln.split(":", 3);
        if (parts.length == 3 && "0".equals(parts[0])) {
          return parts[2];
        }
      }
    } catch (Exception ignore) {
      // ignore and fallback
    }
    return "";
  }

  /**
   * Try to detect cgroup v1 configuration (memory controller).
   *
   * @return populated {@link CgroupInfo} for v1, or {@code null} if not v1.
   */
  private static CgroupInfo tryDetectCgroupV1() {
    Path cgroupFile = procRoot.resolve("self/cgroup");
    if (!Files.isRegularFile(cgroupFile)) {
      return null;
    }

    String memoryPath = findMemoryControllerPath(cgroupFile);
    if (memoryPath == null) {
      return null;
    }

    CgroupInfo info = new CgroupInfo();
    info.version = "v1";
    info.path = memoryPath;
    info.baseDir = sysRoot.resolve("fs/cgroup/memory");
    info.resolved = resolveCgroupPath(info.baseDir, memoryPath);
    return info;
  }

  /**
   * Look for a line containing "memory" controller in {@code /proc/self/cgroup}
   * and return its cgroup path.
   */
  private static String findMemoryControllerPath(Path cgroupFile) {
    try {
      for (String ln : readLines(cgroupFile)) {
        String[] parts = ln.split(":", 3);
        if (parts.length == 3) {
          String controllers = parts[1];
          if (controllers.contains("memory")) {
            return parts[2];
          }
        }
      }
    } catch (Exception ignore) {
      // ignore and fallback
    }
    return null;
  }

  /**
   * Resolve a cgroup path against its base directory.
   */
  private static Path resolveCgroupPath(Path baseDir, String cgroupPath) {
    if (baseDir == null) {
      return null;
    }
    if (cgroupPath == null || cgroupPath.isEmpty() || "/".equals(cgroupPath)) {
      return baseDir;
    }
    // strip leading "/" to avoid double separators
    String relative = cgroupPath.replaceFirst("^/", "");
    return baseDir.resolve(relative);
  }

  /**
   * Read a small file that contains a single numeric value.
   * <p>
   * Special handling:
   * <ul>
   *   <li>When content is {@code "max"}, returns {@code -1} (used by cgroup v2).</li>
   *   <li>On any error, returns {@code -1}.</li>
   * </ul>
   *
   * @param p path to the file
   * @return parsed long value, or {@code -1} on failure or {@code "max"}
   */
  static long readFirstNumber(Path p) {
    try {
      String s = Files.readString(p, StandardCharsets.UTF_8).trim();
      // cgroup v2 memory.max could be "max"
      if ("max".equals(s)) {
        return -1L;
      }
      return Long.parseLong(s);
    } catch (Exception e) {
      return -1L;
    }
  }
}
