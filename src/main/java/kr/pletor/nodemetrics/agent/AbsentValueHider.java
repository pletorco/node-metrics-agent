package kr.pletor.nodemetrics.agent;

import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;

/**
 * A {@link StandardMBean} that leaves an attribute out of its {@link MBeanInfo} while its value is
 * {@code null}, which is how the agent reports an unavailable counter.
 *
 * <p>The Prometheus JMX exporter builds a scrape from the attribute list of each MBean. It skips a
 * {@code null} value, but the exporter 1.6.0 fails the whole scrape with a {@code
 * NullPointerException} when a {@code null} value sits in an MBean that a rule customizes with
 * {@code attributesAsLabels} (the example rules do that for the cgroup memory MBean). An attribute
 * that is not listed is never read, so the scrape works with every exporter version, and the
 * attribute appears once its source becomes available.
 *
 * <p>The values are only inspected once the refresh engine owns the metric, so that describing the
 * MBean (which registering it does) never triggers a read of {@code /proc} or cgroup files.
 */
final class AbsentValueHider extends StandardMBean {

  private final Object implementation;

  <T> AbsentValueHider(T implementation, Class<T> mbeanInterface)
      throws NotCompliantMBeanException {
    super(implementation, mbeanInterface);
    this.implementation = implementation;
  }

  @Override
  public MBeanInfo getMBeanInfo() {
    MBeanInfo full = super.getMBeanInfo();
    if (!(implementation instanceof RefreshManagedMetric)
        || ((RefreshManagedMetric) implementation).isReadRefreshEnabled()) {
      return full;
    }
    MBeanAttributeInfo[] all = full.getAttributes();
    List<MBeanAttributeInfo> kept = new ArrayList<>(all.length);
    for (MBeanAttributeInfo attribute : all) {
      if (!(attribute.isReadable() && isAbsent(attribute.getName()))) {
        kept.add(attribute);
      }
    }
    if (kept.size() == all.length) {
      return full;
    }
    return new MBeanInfo(
        full.getClassName(),
        full.getDescription(),
        kept.toArray(new MBeanAttributeInfo[0]),
        full.getConstructors(),
        full.getOperations(),
        full.getNotifications(),
        full.getDescriptor());
  }

  private boolean isAbsent(String attribute) {
    try {
      return getAttribute(attribute) == null;
    } catch (Exception e) { // NOSONAR - keep the attribute listed; the reader reports the error
      return false;
    }
  }
}
