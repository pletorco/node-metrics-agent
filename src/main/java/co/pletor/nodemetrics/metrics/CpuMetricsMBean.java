package co.pletor.nodemetrics.metrics;

/** MBean interface for CPU-related metrics. */
public interface CpuMetricsMBean {

  /**
   * Returns the "recent cpu usage" for the whole system.
   *
   * @return the system cpu load between 0.0 and 1.0
   */
  @JmxMetricHint("gauge")
  double getSystemCpuLoad();

  /**
   * Returns the "recent cpu usage" for the Java Virtual Machine process.
   *
   * @return the process cpu load between 0.0 and 1.0
   */
  @JmxMetricHint("gauge")
  double getProcessCpuLoad();

  /**
   * Returns the system load average for the last minute.
   *
   * @return the system load average
   */
  @JmxMetricHint("gauge")
  double getSystemLoadAverage();

  /**
   * Returns the number of processors available to the Java virtual machine.
   *
   * @return the number of available processors
   */
  @JmxMetricHint("gauge")
  int getAvailableProcessors();

  /**
   * Returns the CPU time used by the process on which the Java virtual machine is running.
   *
   * @return the process cpu time in nanoseconds
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("nanoseconds")
  long getProcessCpuTimeNanos();

  /**
   * Returns the system-wide CPU IO wait ratio.
   *
   * @return the system cpu io wait ratio
   */
  @JmxMetricHint("gauge")
  double getSystemCpuIoWaitRatio();

  /**
   * Returns the system-wide CPU steal ratio.
   *
   * @return the system cpu steal ratio
   */
  @JmxMetricHint("gauge")
  double getSystemCpuStealRatio();

  /**
   * Returns the system load average for the last minute.
   *
   * @return the 1-minute system load average
   */
  @JmxMetricHint("gauge")
  double getSystemLoadAverage1m();

  /**
   * Returns the system load average for the last 5 minutes.
   *
   * @return the 5-minute system load average
   */
  @JmxMetricHint("gauge")
  double getSystemLoadAverage5m();

  /**
   * Returns the system load average for the last 15 minutes.
   *
   * @return the 15-minute system load average
   */
  @JmxMetricHint("gauge")
  double getSystemLoadAverage15m();

  /**
   * Returns the ratio of time the cgroup was throttled.
   *
   * @return the cgroup cpu throttled ratio
   */
  @JmxMetricHint("gauge")
  double getCgroupCpuThrottledRatio();

  /**
   * Returns the number of times the cgroup was throttled during the last polling window.
   *
   * <p>This is a per-window delta, not a monotonic counter; use {@link
   * #getCgroupCpuThrottledPeriodsTotal()} with {@code rate()} for alerting.
   *
   * @return the cgroup cpu throttled count in the last window
   */
  @JmxMetricHint("gauge")
  long getCgroupCpuThrottledCount();

  /**
   * Returns the cumulative CPU time of all states since boot, in clock ticks ({@code USER_HZ},
   * normally 100 per second), from the aggregate line of {@code /proc/stat}.
   *
   * <p>Divide the {@code rate()} of {@link #getSystemCpuIoWaitTicks()} or {@link
   * #getSystemCpuStealTicks()} by the {@code rate()} of this value to get a ratio over any time
   * range. {@code -1} when unavailable.
   *
   * @return total CPU ticks
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuTotalTicks();

  /**
   * Returns the cumulative time CPUs spent waiting for I/O, in clock ticks. {@code -1} when
   * unavailable.
   *
   * @return I/O wait ticks
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuIoWaitTicks();

  /**
   * Returns the cumulative time stolen by the hypervisor, in clock ticks. {@code -1} when
   * unavailable.
   *
   * @return steal ticks
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuStealTicks();

  /**
   * Returns the cumulative number of periods in which the cgroup was throttled ({@code
   * nr_throttled}). {@code -1} when unavailable.
   *
   * @return throttled periods since the cgroup was created
   */
  @JmxMetricHint("counter")
  long getCgroupCpuThrottledPeriodsTotal();

  /**
   * Returns the cumulative time the cgroup spent throttled, in nanoseconds. {@code -1} when
   * unavailable.
   *
   * @return throttled time since the cgroup was created
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("nanoseconds")
  long getCgroupCpuThrottledTimeNanosTotal();

  /**
   * Returns the cumulative CPU time used by the cgroup, in nanoseconds. {@code -1} when
   * unavailable.
   *
   * @return cgroup CPU usage since the cgroup was created
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("nanoseconds")
  long getCgroupCpuUsageNanosTotal();

  /**
   * Returns the effective CPU limit of the cgroup, in cores: the tightest finite CFS quota of the
   * cgroup and its ancestors (for example the Kubernetes pod), divided by its period.
   *
   * <p>With {@code CgroupCpuUsageNanosTotal} this gives CPU use as a share of the limit: {@code
   * rate(usage_nanos[5m]) / 1e9 / limit_cores}. On cgroup v1 the value needs the {@code cpu}
   * controller directory to hold the quota files; otherwise it is {@code -1}.
   *
   * @return the limit in cores, or {@code -1} when unlimited or unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("cores")
  double getCgroupCpuLimitCores();

  /**
   * Returns the cumulative CPU time spent running normal user-space processes, in clock ticks
   * ({@code USER_HZ}, normally 100 per second), from the aggregate line of {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuUserTicks();

  /**
   * Returns the cumulative CPU time spent running user-space processes with a positive nice value
   * (lowered priority), in clock ticks ({@code USER_HZ}, normally 100 per second), from the
   * aggregate line of {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuNiceTicks();

  /**
   * Returns the cumulative CPU time spent running kernel code on behalf of processes (system
   * calls), in clock ticks ({@code USER_HZ}, normally 100 per second), from the aggregate line of
   * {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuSystemTicks();

  /**
   * Returns the cumulative CPU time spent idle, with no I/O outstanding, in clock ticks ({@code
   * USER_HZ}, normally 100 per second), from the aggregate line of {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuIdleTicks();

  /**
   * Returns the cumulative CPU time spent servicing hardware interrupts, in clock ticks ({@code
   * USER_HZ}, normally 100 per second), from the aggregate line of {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuIrqTicks();

  /**
   * Returns the cumulative CPU time spent servicing software interrupts (network receive and
   * transmit processing, timers), in clock ticks ({@code USER_HZ}, normally 100 per second), from
   * the aggregate line of {@code /proc/stat}.
   *
   * <p>{@code rate()} of this over the {@code rate()} of {@link #getSystemCpuTotalTicks()} is the
   * share of CPU time in this state over any window. {@code -1} when unavailable.
   *
   * @return ticks since boot
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("ticks")
  long getSystemCpuSoftIrqTicks();
}
