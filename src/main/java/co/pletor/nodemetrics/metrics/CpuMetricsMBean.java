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
}
