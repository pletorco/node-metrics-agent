package co.pletor.nodemetrics.metrics;

/**
 * MBean interface for {@link OsInfoMetrics}.
 * <p>
 * Exposes basic operating system information and a best-effort
 * classification of the environment type (host/VM, container, Kubernetes).
 */
public interface OsInfoMetricsMBean {

  /**
   * Returns a constant info flag for use as a Prometheus {@code *_info} metric.
   *
   * @return always {@code 1}
   */
  int getInfoFlag();

  /**
   * Returns a human-readable OS release string.
   * <p>
   * On Linux this is typically derived from {@code /etc/os-release}.
   *
   * @return OS release string, or an empty string when unknown
   */
  String getOsRelease();

  /**
   * Returns the kernel version of the underlying OS.
   * <p>
   * On Linux this is typically equivalent to {@code uname -r}.
   *
   * @return kernel version, or an empty string when unknown
   */
  String getKernelVersion();

  /**
   * Returns the JVM-reported OS name.
   * <p>
   * This usually corresponds to {@code System.getProperty("os.name")}.
   *
   * @return OS name, or an empty string when unknown
   */
  String getOsName();

  /**
   * Returns the JVM-reported OS architecture.
   * <p>
   * This usually corresponds to {@code System.getProperty("os.arch")}.
   *
   * @return OS architecture string, or an empty string when unknown
   */
  String getOsArch();

  /**
   * Returns the best-effort environment type.
   * <p>
   * Typical values:
   * <ul>
   *   <li>{@code "host_or_vm"} – bare metal host or virtual machine</li>
   *   <li>{@code "container"} – generic Linux container (Docker, containerd, Podman, ...)</li>
   *   <li>{@code "kubernetes"} – container running as a Kubernetes pod</li>
   *   <li>{@code "unknown"} – non-Linux or insufficient information</li>
   * </ul>
   *
   * @return environment type label
   */
  String getEnvironmentType();

  /**
   * Returns the version of the metrics agent.
   *
   * @return the agent version
   */
  @JmxMetricHint("gauge")
  String getAgentVersion();

  /**
   * Returns the Git commit ID the agent was built from.
   *
   * @return the agent commit ID
   */
  @JmxMetricHint("gauge")
  String getAgentCommitId();
}
