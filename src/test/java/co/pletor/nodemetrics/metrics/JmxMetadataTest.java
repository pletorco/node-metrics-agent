package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.management.ManagementFactory;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import org.junit.jupiter.api.Test;

class JmxMetadataTest {

  @Test
  void testOsRuntimeMetricsMetadata() throws Exception {
    MBeanServer svr = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("co.pletor.test:type=OsRuntimeMetrics");

    OsRuntimeMetrics metrics = new OsRuntimeMetrics();
    StandardMBean mbean = new StandardMBean(metrics, OsRuntimeMetricsMBean.class);

    svr.registerMBean(mbean, name);
    try {
      MBeanInfo info = svr.getMBeanInfo(name);

      MBeanAttributeInfo uptimeAttr = findAttribute(info, "UptimeSeconds");
      assertNotNull(uptimeAttr);
      assertEquals("counter", uptimeAttr.getDescriptor().getFieldValue("metricType"));
      assertEquals("seconds", uptimeAttr.getDescriptor().getFieldValue("unit"));

      MBeanAttributeInfo mountCountAttr = findAttribute(info, "MountCount");
      assertNotNull(mountCountAttr);
      assertEquals("gauge", mountCountAttr.getDescriptor().getFieldValue("metricType"));

    } finally {
      svr.unregisterMBean(name);
    }
  }

  @Test
  void testCpuMetricsMetadata() throws Exception {
    MBeanServer svr = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("co.pletor.test:type=CpuMetrics");

    CpuMetrics metrics = new CpuMetrics();
    StandardMBean mbean = new StandardMBean(metrics, CpuMetricsMBean.class);

    svr.registerMBean(mbean, name);
    try {
      MBeanInfo info = svr.getMBeanInfo(name);

      MBeanAttributeInfo loadAttr = findAttribute(info, "SystemCpuLoad");
      assertNotNull(loadAttr);
      assertEquals("gauge", loadAttr.getDescriptor().getFieldValue("metricType"));

      MBeanAttributeInfo cpuTimeAttr = findAttribute(info, "ProcessCpuTimeNanos");
      assertNotNull(cpuTimeAttr);
      assertEquals("counter", cpuTimeAttr.getDescriptor().getFieldValue("metricType"));
      assertEquals("nanoseconds", cpuTimeAttr.getDescriptor().getFieldValue("unit"));

      MBeanAttributeInfo throttledCountAttr = findAttribute(info, "CgroupCpuThrottledCount");
      assertNotNull(throttledCountAttr);
      assertEquals("counter", throttledCountAttr.getDescriptor().getFieldValue("metricType"));

    } finally {
      svr.unregisterMBean(name);
    }
  }

  @Test
  void testNodeMemMetricsMetadata() throws Exception {
    MBeanServer svr = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("co.pletor.test:type=MemMetrics");

    NodeMemMetrics metrics = new NodeMemMetrics();
    StandardMBean mbean = new StandardMBean(metrics, NodeMemMetricsMBean.class);

    svr.registerMBean(mbean, name);
    try {
      MBeanInfo info = svr.getMBeanInfo(name);

      MBeanAttributeInfo totalMemAttr = findAttribute(info, "TotalMemoryBytes");
      assertNotNull(totalMemAttr);
      assertEquals("gauge", totalMemAttr.getDescriptor().getFieldValue("metricType"));
      assertEquals("bytes", totalMemAttr.getDescriptor().getFieldValue("unit"));

    } finally {
      svr.unregisterMBean(name);
    }
  }

  @Test
  void testOsInfoMetricsMetadata() throws Exception {
    MBeanServer svr = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("co.pletor.test:type=OsInfoMetrics");

    OsInfoMetrics metrics = new OsInfoMetrics();
    StandardMBean mbean = new StandardMBean(metrics, OsInfoMetricsMBean.class);

    svr.registerMBean(mbean, name);
    try {
      MBeanInfo info = svr.getMBeanInfo(name);

      MBeanAttributeInfo versionAttr = findAttribute(info, "AgentVersion");
      assertNotNull(versionAttr);
      assertEquals("gauge", versionAttr.getDescriptor().getFieldValue("metricType"));

      MBeanAttributeInfo commitAttr = findAttribute(info, "AgentCommitId");
      assertNotNull(commitAttr);
      assertEquals("gauge", commitAttr.getDescriptor().getFieldValue("metricType"));

    } finally {
      svr.unregisterMBean(name);
    }
  }

  @Test
  void testFdMetricsMetadata() throws Exception {
    MBeanServer svr = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("co.pletor.test:type=FdMetrics");

    FdMetrics metrics = new FdMetrics();
    StandardMBean mbean = new StandardMBean(metrics, FdMetricsMBean.class);

    svr.registerMBean(mbean, name);
    try {
      MBeanInfo info = svr.getMBeanInfo(name);

      MBeanAttributeInfo openFdAttr = findAttribute(info, "OpenFileDescriptorCount");
      assertNotNull(openFdAttr);
      assertEquals("gauge", openFdAttr.getDescriptor().getFieldValue("metricType"));

    } finally {
      svr.unregisterMBean(name);
    }
  }

  private MBeanAttributeInfo findAttribute(MBeanInfo info, String name) {
    for (MBeanAttributeInfo attr : info.getAttributes()) {
      if (attr.getName().equals(name)) {
        return attr;
      }
    }
    return null;
  }
}
