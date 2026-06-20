package co.pletor.nodemetrics.metrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Common memory statistics utilities.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Parse {@code /proc/meminfo} lines that contain kB values</li>
 *   <li>Parse {@code memory.stat} key-value files for cgroup memory stats</li>
 *   <li>Locate the appropriate {@code memory.stat} file for cgroup v1/v2</li>
 * </ul>
 */
final class MemStatsUtil {

  private MemStatsUtil() {
    // Utility class; do not instantiate.
  }

  /**
   * Extract the numeric kB value from a {@code /proc/meminfo} line.
   * <p>
   * Example input:
   * <pre>
   *   "Dirty:           1234 kB"
   * </pre>
   * The method scans tokens after the first one and returns the first
   * token that can be parsed as a {@code long}.
   *
   * @param line a single line from {@code /proc/meminfo}
   * @return parsed kB value, or {@code -1} on failure
   */
  static long parseKbLine(String line) {
    String[] parts = line.split("\\s+");
    for (int i = 1; i < parts.length; i++) {
      String token = parts[i].toLowerCase(Locale.ROOT);
      try {
        return Long.parseLong(token);
      } catch (NumberFormatException ignore) {
        // Try next token.
      }
    }
    return -1L;
  }

  /**
   * Read cgroup v1/v2 {@code memory.stat} file and parse it as a key-&gt;value map.
   * <p>
   * The exact file location depends on the cgroup version and the resolved path
   * in {@link LinuxProcFs.CgroupInfo}. If the file cannot be located, an empty
   * map is returned.
   *
   * @param cg cgroup metadata for the current process
   * @return map of {@code memory.stat} keys to {@code Long} values,
   *         or an empty map if the file is not found
   * @throws IOException if reading the file fails
   */
  static Map<String, Long> readCgroupMemoryStat(LinuxProcFs.CgroupInfo cg) throws IOException {
    Path stat = resolveCgroupMemoryStatPath(cg);
    if (stat == null) {
      // Sonar recommendation: return an empty map instead of null.
      return Collections.emptyMap();
    }
    return readKeyValues(stat);
  }

  /**
   * Resolve the {@code memory.stat} file path for cgroup v1/v2.
   * <p>
   * Resolution rules:
   * <ul>
   *   <li>v2: use {@code cg.resolved} if it is a directory, otherwise {@code /sys/fs/cgroup}</li>
   *   <li>v1: use {@code cg.resolved} if it is a directory, otherwise {@code /sys/fs/cgroup/memory}</li>
   *   <li>Non v1/v2 versions: unsupported, return {@code null}</li>
   * </ul>
   * If the computed {@code memory.stat} file does not exist, {@code null} is returned.
   *
   * @param cg cgroup metadata for the current process
   * @return path to {@code memory.stat}, or {@code null} if not found/unsupported
   */
  private static Path resolveCgroupMemoryStatPath(LinuxProcFs.CgroupInfo cg) {
    if (cg == null || cg.version == null) {
      return null;
    }

    Path base;
    if ("v2".equals(cg.version)) {
      base = (cg.resolved != null && Files.isDirectory(cg.resolved))
          ? cg.resolved
          : Paths.get("/sys/fs/cgroup");
    } else if ("v1".equals(cg.version)) {
      base = (cg.resolved != null && Files.isDirectory(cg.resolved))
          ? cg.resolved
          : Paths.get("/sys/fs/cgroup/memory");
    } else {
      // Not cgroup v1 or v2 → unsupported.
      return null;
    }

    Path stat = base.resolve("memory.stat");
    return Files.isRegularFile(stat) ? stat : null;
  }

  /**
   * Parse a file consisting of lines in {@code "key value"} format into a map.
   * <p>
   * Lines that cannot be parsed (e.g. invalid numbers) are skipped.
   *
   * @param file path to the key-value file
   * @return map of keys to parsed long values (never {@code null})
   * @throws IOException if the file cannot be read
   */
  static Map<String, Long> readKeyValues(Path file) throws IOException {
    Map<String, Long> out = new HashMap<>();
    try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
          try {
            out.put(parts[0], Long.parseLong(parts[1]));
          } catch (NumberFormatException ignore) {
            // Skip malformed line.
          }
        }
      }
    }
    return out;
  }
}
