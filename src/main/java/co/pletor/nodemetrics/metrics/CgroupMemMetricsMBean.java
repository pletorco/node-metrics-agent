package co.pletor.nodemetrics.metrics;

/** JMX MBean interface for exposing cgroup memory metrics. */
public interface CgroupMemMetricsMBean {

  /**
   * Returns the effective memory limit in bytes: the tightest finite limit of this cgroup and its
   * ancestors (for example the Kubernetes pod), or {@code -1} if no level is limited.
   *
   * @return the memory limit in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getMemoryLimitBytes();

  /**
   * Returns the current memory usage in bytes for the cgroup.
   *
   * @return the memory usage in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getMemoryUsageBytes();

  /**
   * Returns the cgroup memory working set in bytes: current usage minus inactive file cache.
   *
   * <p>Usage includes reclaimable page cache, so a page-cache-heavy process (Kafka, databases)
   * looks close to its limit even when it is not. The working set is the better basis for memory
   * pressure alerts. {@code -1} when {@code memory.stat} is unavailable.
   *
   * @return the working set in bytes
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getMemoryWorkingSetBytes();

  /**
   * Returns the swap currently used by the cgroup, in bytes.
   *
   * <p>cgroup v2 reports it directly ({@code memory.swap.current}); on cgroup v1 it is {@code
   * memory.memsw.usage_in_bytes - memory.usage_in_bytes}. {@code -1} when the kernel does not
   * account swap for cgroups (for example {@code swapaccount=0} on v1).
   *
   * @return the swap usage in bytes, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getSwapUsageBytes();

  /**
   * Returns the swap limit of the cgroup itself, in bytes.
   *
   * <p>Unlike {@code MemoryLimitBytes}, ancestor cgroups are not considered. {@code 0} means the
   * cgroup may not swap at all (the usual Kubernetes setting); {@code -1} means unlimited or
   * unavailable.
   *
   * @return the swap limit in bytes, {@code 0} for none allowed, or {@code -1}
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("bytes")
  long getSwapLimitBytes();

  /**
   * Returns the cgroup version (e.g. v1 or v2).
   *
   * @return the cgroup version string
   */
  @JmxMetricHint("gauge")
  String getCgroupVersion();

  /**
   * Returns the cgroup path.
   *
   * @return the cgroup path string
   */
  @JmxMetricHint("gauge")
  String getCgroupPath();
}
