package kr.pletor.nodemetrics.metrics;

/**
 * JMX MBean interface for the process (PID) limit of the JVM's cgroup.
 *
 * <p>Every thread counts as a process here. When the cgroup reaches {@code pids.max} the kernel
 * refuses to create more, and the JVM fails with {@code OutOfMemoryError: unable to create native
 * thread} although heap and RAM are fine. On Kubernetes the limit is usually set on the pod's
 * cgroup ({@code podPidsLimit}), not on the container's, so both attributes describe the level
 * where the tightest limit applies.
 *
 * <p>Both attributes are {@code -1} when unavailable (no cgroup, no {@code pids} controller).
 */
public interface PidsMetricsMBean {

  /**
   * Returns the number of processes and threads in the cgroup that enforces the limit: the tightest
   * finite {@code pids.max} of the JVM's cgroup and its ancestors. Without any limit it is the
   * count of the JVM's own cgroup.
   *
   * @return the count, or {@code -1} when unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("pids")
  long getPidsCurrent();

  /**
   * Returns the effective process limit: the tightest finite {@code pids.max} of the JVM's cgroup
   * and its ancestors. The ratio of {@code PidsCurrent} to this value is how close the cgroup is to
   * refusing new threads.
   *
   * @return the limit, or {@code -1} when unlimited or unavailable
   */
  @JmxMetricHint("gauge")
  @JmxMetricUnit("pids")
  long getPidsLimit();
}
