package kr.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for pressure stall information (PSI): the share of time tasks were stalled
 * waiting for CPU, memory or I/O.
 *
 * <p>Registered twice: for the node ({@code kr.pletor.node:type=PressureMetrics}, from {@code
 * /proc/pressure}, which shows the whole host even inside a container) and for the agent's own
 * cgroup ({@code kr.pletor.cgroup:type=PressureMetrics}, cgroup v2 only).
 *
 * <p>"some" means at least one task was stalled, "full" means all non-idle tasks were stalled at
 * the same time. Every attribute is {@code -1} when PSI is unavailable (kernel older than 4.20, PSI
 * disabled, cgroup v1, non-Linux) or, for the CPU "full" line, when the kernel predates 5.13. The
 * {@code TotalMicros} attributes are cumulative counters: {@code rate(x[5m]) / 1e6} is the stalled
 * share over any window, which is more precise than the kernel's fixed averages.
 */
public interface PressureMetricsMBean {

  /**
   * Returns the share of the last 10 seconds during which at least one task was stalled waiting for
   * CPU, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getCpuSomeAvg10();

  /**
   * Returns the cumulative time during which at least one task was stalled waiting for CPU, in
   * microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getCpuSomeTotalMicros();

  /**
   * Returns the share of the last 10 seconds during which all non-idle tasks were stalled at the
   * same time waiting for CPU, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getCpuFullAvg10();

  /**
   * Returns the cumulative time during which all non-idle tasks were stalled at the same time
   * waiting for CPU, in microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getCpuFullTotalMicros();

  /**
   * Returns the share of the last 10 seconds during which at least one task was stalled waiting for
   * memory, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getMemorySomeAvg10();

  /**
   * Returns the cumulative time during which at least one task was stalled waiting for memory, in
   * microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getMemorySomeTotalMicros();

  /**
   * Returns the share of the last 10 seconds during which all non-idle tasks were stalled at the
   * same time waiting for memory, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getMemoryFullAvg10();

  /**
   * Returns the cumulative time during which all non-idle tasks were stalled at the same time
   * waiting for memory, in microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getMemoryFullTotalMicros();

  /**
   * Returns the share of the last 10 seconds during which at least one task was stalled waiting for
   * I/O, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getIoSomeAvg10();

  /**
   * Returns the cumulative time during which at least one task was stalled waiting for I/O, in
   * microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getIoSomeTotalMicros();

  /**
   * Returns the share of the last 10 seconds during which all non-idle tasks were stalled at the
   * same time waiting for I/O, in percent.
   *
   * @return percent from 0 to 100, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("percent")
  double getIoFullAvg10();

  /**
   * Returns the cumulative time during which all non-idle tasks were stalled at the same time
   * waiting for I/O, in microseconds.
   *
   * @return microseconds since boot (or cgroup creation), or {@code -1} when unavailable
   */
  @JmxMetricHint("counter")
  @JmxMetricUnit("microseconds")
  long getIoFullTotalMicros();
}
