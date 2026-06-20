// co.pletor.nodemetrics.agent.Config
package co.pletor.nodemetrics.agent;

import java.util.List;
import java.util.Objects;

/**
 * Runtime configuration for the metrics agent.
 * <p>
 * This class is a simple data holder used by the loader and reloader.
 * Package-private fields keep access simple while avoiding public mutable state.
 */
public class Config {

  /**
   * Default hard limit for unique filesystem partitions to monitor.
   */
  public static final int DEFAULT_FSMETRICS_MAX_PARTITIONS = 32;

  /**
   * Creates an empty {@code Config} instance.
   * <p>
   * Fields are initialized to their default Java values and are expected
   * to be populated either by the configuration loader or via
   * {@link #defaults()}.
   */
  public Config() {
    // Default constructor for frameworks and deserializers.
  }


  /**
   * List of filesystem paths to monitor.
   * <p>
   * May be {@code null} right after construction; the loader is responsible
   * for populating it. {@link #defaults()} always sets a non-null value.
   */
  List<String> fsmetricsPaths;

  /**
   * Hard limit for unique filesystem partitions to monitor.
   * <p>
   * Values less than 1 are treated as invalid and replaced by defaults.
   */
  Integer fsmetricsMaxPartitions;

  /**
   * Internal checksum used to detect configuration changes.
   * <p>
   * This is not meant to be set by users directly; it is managed by the loader.
   */
  String checksum;

  /**
   * Create a configuration instance with safe defaults so the agent
   * can start even when no configuration file is present.
   *
   * @return a {@link Config} populated with default values
   */
  public static Config defaults() {
    Config c = new Config();

    // Monitor the root filesystem by default
    c.fsmetricsPaths = List.of("/");
    c.fsmetricsMaxPartitions = DEFAULT_FSMETRICS_MAX_PARTITIONS;
    c.checksum = "DEFAULT";
    return c;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Config)) return false;
    Config c = (Config) o;
    return Objects.equals(fsmetricsPaths, c.fsmetricsPaths)
        && Objects.equals(fsmetricsMaxPartitions, c.fsmetricsMaxPartitions)
        && Objects.equals(checksum, c.checksum);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        fsmetricsPaths,
        fsmetricsMaxPartitions,
        checksum
    );
  }

  @Override
  public String toString() {
    return "Config{"
        + "fsmetrics_paths=" + fsmetricsPaths
        + ", fsmetrics_max_partitions=" + fsmetricsMaxPartitions
        + '}';
  }
}
