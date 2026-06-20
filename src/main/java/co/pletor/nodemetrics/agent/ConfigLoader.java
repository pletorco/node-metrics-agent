// co.pletor.nodemetrics.agent.ConfigLoader
package co.pletor.nodemetrics.agent;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Utility class for loading {@link Config} from a YAML file.
 * <p>
 * This class:
 * <ul>
 *   <li>Parses a YAML file into a {@link Config} instance</li>
 *   <li>Applies sensible defaults when fields are missing</li>
 *   <li>Computes a SHA-256 checksum for change detection</li>
 * </ul>
 */
final class ConfigLoader {

  /**
   * Prevent instantiation of this utility class.
   */
  private static final Logger LOGGER = Logger.getLogger(ConfigLoader.class.getName());

  /**
   * Prevent instantiation of this utility class.
   */
  private ConfigLoader() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Load configuration from the given path, or return {@link Config#defaults()}
   * when the file does not exist or the path is {@code null}.
   *
   * @param path path to the YAML configuration file (may be {@code null})
   * @return loaded configuration, or default configuration if file is missing
   * @throws IOException if loading the file fails
   */
  static Config loadOrDefault(Path path) throws IOException {
    if (path == null || !Files.isRegularFile(path)) {
      LOGGER.log(Level.INFO, "Config file not found or not specified (path={0}). Using defaults.", path);
      return Config.defaults();
    }
    try {
      return load(path);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, e, () -> "Failed to load config file, using defaults: " + path);
      return Config.defaults();
    }
  }

  /**
   * Load configuration from a YAML file.
   * <p>
   * Expected YAML keys:
   * <ul>
   *   <li>{@code fsmetrics_paths} (list of strings)</li>
   * </ul>
   * Missing keys will be replaced with defaults.
   *
   * @param path path to an existing YAML configuration file
   * @return populated {@link Config} instance
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the YAML content is invalid
   */
  static Config load(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      Object loaded = yaml.load(in);

      // Top-level YAML must be a mapping
      if (!(loaded instanceof Map)) {
        throw new IllegalArgumentException("Invalid YAML: " + path);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) loaded;

      Config c = new Config();

      // ---- fsmetrics_paths ----
      Object ps = m.get("fsmetrics_paths");
      if (ps instanceof List<?>) {
        @SuppressWarnings("unchecked")
        List<Object> lst = (List<Object>) ps;
        // Convert all elements to String (defensive)
        c.fsmetricsPaths = lst.stream()
            .map(String::valueOf)
            .collect(Collectors.toList());
      } else {
        // If paths is missing or not a list, use the default root path
        c.fsmetricsPaths = List.of("/");
        LOGGER.log(Level.INFO, "Config key ''fsmetrics_paths'' missing or invalid; defaulting to ''/''");
      }

      // ---- fsmetrics_max_partitions ----
      c.fsmetricsMaxPartitions = parseMaxPartitionsOrDefault(m.get("fsmetrics_max_partitions"));

      // ---- checksum ----
      // Use file bytes as the source for a SHA-256 checksum
      c.checksum = sha256String(Files.readAllBytes(path));

      return c;
    }
  }

  /**
   * Compute a lowercase hexadecimal SHA-256 digest string for the given bytes.
   *
   * @param bytes input payload
   * @return SHA-256 digest encoded as a hex string
   * @throws IllegalStateException if SHA-256 algorithm is not available
   */
  private static String sha256String(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(bytes);

      StringBuilder sb = new StringBuilder(d.length * 2);
      for (byte b : d) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // On a standard JVM this should never happen; wrap as unchecked.
      throw new IllegalStateException("SHA-256 MessageDigest not available", e);
    }
  }

  private static int parseMaxPartitionsOrDefault(Object raw) {
    if (raw == null) {
      return Config.DEFAULT_FSMETRICS_MAX_PARTITIONS;
    }

    long parsed;
    if (raw instanceof Number) {
      parsed = ((Number) raw).longValue();
    } else {
      try {
        parsed = Long.parseLong(String.valueOf(raw).trim());
      } catch (NumberFormatException e) {
        LOGGER.log(
            Level.WARNING,
            "Config key ''fsmetrics_max_partitions'' is invalid: {0}. Using default: {1}",
            new Object[]{raw, Config.DEFAULT_FSMETRICS_MAX_PARTITIONS}
        );
        return Config.DEFAULT_FSMETRICS_MAX_PARTITIONS;
      }
    }

    if (parsed < 1L || parsed > Integer.MAX_VALUE) {
      LOGGER.log(
          Level.WARNING,
          "Config key ''fsmetrics_max_partitions'' out of range: {0}. Using default: {1}",
          new Object[]{raw, Config.DEFAULT_FSMETRICS_MAX_PARTITIONS}
      );
      return Config.DEFAULT_FSMETRICS_MAX_PARTITIONS;
    }

    return (int) parsed;
  }
}
