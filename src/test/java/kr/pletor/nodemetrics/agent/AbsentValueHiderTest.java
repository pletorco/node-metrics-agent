package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import kr.pletor.nodemetrics.metrics.JmxMetricHint;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.Test;

/** An unavailable (null) counter is left out of the MBeanInfo, so no exporter ever reads it. */
class AbsentValueHiderTest {

  /** Public so that JMX accepts it as an MBean interface. */
  public interface SampleMBean {
    @JmxMetricHint("counter")
    Long getReadsTotal();

    @JmxMetricHint("counter")
    Long getWritesTotal();

    @JmxMetricHint("gauge")
    long getDepth();

    String getLabel();
  }

  /** Metric whose reads are counted, so a test can see when describing it causes reads. */
  public static class Sample implements SampleMBean, RefreshManagedMetric {
    volatile Long reads = null;
    volatile Long writes = 7L;
    final AtomicInteger getterCalls = new AtomicInteger();
    volatile boolean readRefresh = true;

    @Override
    public Long getReadsTotal() {
      getterCalls.incrementAndGet();
      return reads;
    }

    @Override
    public Long getWritesTotal() {
      getterCalls.incrementAndGet();
      return writes;
    }

    @Override
    public long getDepth() {
      getterCalls.incrementAndGet();
      return -1L;
    }

    @Override
    public String getLabel() {
      getterCalls.incrementAndGet();
      return "x";
    }

    @Override
    public void poll() {
      // not needed
    }

    @Override
    public void setReadRefreshEnabled(boolean enabled) {
      readRefresh = enabled;
    }

    @Override
    public boolean isReadRefreshEnabled() {
      return readRefresh;
    }
  }

  private static Set<String> names(javax.management.MBeanInfo info) {
    Set<String> names = new HashSet<>();
    for (MBeanAttributeInfo a : info.getAttributes()) {
      names.add(a.getName());
    }
    return names;
  }

  @Test
  void absentCounterIsListedAgainOnceItHasValue() throws Exception {
    Sample sample = new Sample();
    sample.setReadRefreshEnabled(false);
    AbsentValueHider mbean = new AbsentValueHider(sample, SampleMBean.class);

    assertEquals(Set.of("WritesTotal", "Depth", "Label"), names(mbean.getMBeanInfo()));
    assertNull(mbean.getAttribute("ReadsTotal"), "a hidden attribute still reads as null");

    sample.reads = 5L;
    assertEquals(
        Set.of("ReadsTotal", "WritesTotal", "Depth", "Label"), names(mbean.getMBeanInfo()));
  }

  @Test
  void gaugesReportingMinusOneAreKept() throws Exception {
    Sample sample = new Sample();
    sample.setReadRefreshEnabled(false);
    AbsentValueHider mbean = new AbsentValueHider(sample, SampleMBean.class);

    assertTrue(names(mbean.getMBeanInfo()).contains("Depth"));
  }

  @Test
  void descriptorsOfTheKeptAttributesSurvive() throws Exception {
    Sample sample = new Sample();
    sample.setReadRefreshEnabled(false);
    AbsentValueHider mbean = new AbsentValueHider(sample, SampleMBean.class);

    for (MBeanAttributeInfo a : mbean.getMBeanInfo().getAttributes()) {
      if (a.getName().equals("WritesTotal")) {
        assertEquals("counter", a.getDescriptor().getFieldValue("metricType"));
        return;
      }
    }
    throw new AssertionError("WritesTotal must stay listed");
  }

  @Test
  void describingTheMBeanDoesNotReadWhileReadsStillRefresh() throws Exception {
    Sample sample = new Sample(); // read refresh still enabled: registration time
    AbsentValueHider mbean = new AbsentValueHider(sample, SampleMBean.class);

    Set<String> listed = names(mbean.getMBeanInfo());

    assertEquals(0, sample.getterCalls.get(), "no getter may run, it could read /proc");
    assertTrue(listed.containsAll(Arrays.asList("ReadsTotal", "WritesTotal", "Depth", "Label")));
  }

  @Test
  void plainMetricIsNeverFiltered() throws Exception {
    SampleMBean plain =
        new SampleMBean() {
          @Override
          public Long getReadsTotal() {
            return null;
          }

          @Override
          public Long getWritesTotal() {
            return 1L;
          }

          @Override
          public long getDepth() {
            return 0L;
          }

          @Override
          public String getLabel() {
            return "";
          }
        };
    AbsentValueHider mbean = new AbsentValueHider(plain, SampleMBean.class);

    assertTrue(names(mbean.getMBeanInfo()).contains("ReadsTotal"));
  }

  @Test
  void worksThroughTheMBeanServer() throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("kr.pletor.test:type=AbsentValueHiding");
    Sample sample = new Sample();
    sample.setReadRefreshEnabled(false);
    server.registerMBean(new AbsentValueHider(sample, SampleMBean.class), name);
    try {
      assertFalse(names(server.getMBeanInfo(name)).contains("ReadsTotal"));
      sample.reads = 3L;
      assertTrue(names(server.getMBeanInfo(name)).contains("ReadsTotal"));
      assertEquals(3L, server.getAttribute(name, "ReadsTotal"));
    } finally {
      server.unregisterMBean(name);
    }
  }
}
