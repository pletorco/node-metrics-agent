package co.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import org.junit.jupiter.api.Test;

class PlatformServerTest {

  @Test
  void returnsThePlatformServerAtOnceWhenOneExists() {
    MBeanServer existing = ManagementFactory.getPlatformMBeanServer();

    long start = System.nanoTime();
    MBeanServer found = MetricsAgent.awaitPlatformMBeanServer(30_000L);

    assertSame(existing, found);
    assertTrue(
        System.nanoTime() - start < 5_000_000_000L, "must not wait when a server already exists");
  }

  @Test
  void neverWaitsWhenTheWaitIsZero() {
    assertSame(
        ManagementFactory.getPlatformMBeanServer(), MetricsAgent.awaitPlatformMBeanServer(0L));
  }
}
