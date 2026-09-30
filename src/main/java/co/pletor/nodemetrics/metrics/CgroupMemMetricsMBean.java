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
   * Returns the cumulative number of processes killed by the OOM killer in this cgroup ({@code
   * oom_kill} in {@code memory.events} on v2, {@code memory.oom_control} on v1, kernel 4.13+).
   *
   * <p>The counter lives and dies with the cgroup: when a container is restarted or its pod is
   * replaced, a new cgroup starts at 0, and a kill of the JVM itself takes the agent down with it.
   * So this reliably reports kills of other processes in the container, and of the JVM only where
   * the cgroup outlives it (for example a systemd service). On Kubernetes use the termination
   * reason for the JVM.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("kills")
  long getMemoryOomKillTotal();

  /**
   * Returns the cumulative number of times the cgroup's memory use reached its limit and the kernel
   * had to reclaim or kill ({@code max} in {@code memory.events} on v2, {@code memory.failcnt} on
   * v1). It rises before the first OOM kill, so it is the earlier warning.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("events")
  long getMemoryMaxEventsTotal();

  /**
   * Returns the cumulative number of times the cgroup went over {@code memory.high} and was
   * throttled and reclaimed ({@code high} in {@code memory.events}). It stays {@code 0} unless
   * {@code memory.high} is set, which Kubernetes does only with the memory QoS feature.
   *
   * @return the count, or {@code -1} when unavailable (cgroup v1 has no such counter)
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("events")
  long getMemoryHighEventsTotal();

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
