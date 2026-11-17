package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformServerTest {
  @Test
  void findsAnAlreadyCreatedPlatformServer() {
    MBeanServer existing = ManagementFactory.getPlatformMBeanServer();
    assertSame(existing, MetricsAgent.findExistingMBeanServer());
    assertSame(existing, MetricsAgent.awaitExistingMBeanServer());
  }

  @Test
  void absenceDoesNotCreateAnyServer() {
    try (var factory = Mockito.mockStatic(MBeanServerFactory.class)) {
      factory.when(() -> MBeanServerFactory.findMBeanServer(null)).thenReturn(new ArrayList<>());
      assertNull(MetricsAgent.findExistingMBeanServer());
      factory.verify(() -> MBeanServerFactory.findMBeanServer(null));
      factory.verifyNoMoreInteractions();
    }
  }

  @Test
  void unrelatedServersAreIgnored() {
    MBeanServer unrelated = MBeanServerFactory.newMBeanServer();
    try (var factory = Mockito.mockStatic(MBeanServerFactory.class)) {
      factory
          .when(() -> MBeanServerFactory.findMBeanServer(null))
          .thenReturn(new ArrayList<>(List.of(unrelated)));
      assertNull(MetricsAgent.findExistingMBeanServer());
      factory.verify(() -> MBeanServerFactory.findMBeanServer(null));
      factory.verifyNoMoreInteractions();
    }
  }

  @Test
  void deniedDiscoveryDoesNotFallBackToCreation() {
    try (var factory = Mockito.mockStatic(MBeanServerFactory.class)) {
      factory
          .when(() -> MBeanServerFactory.findMBeanServer(null))
          .thenThrow(new SecurityException("denied"));
      assertThrows(SecurityException.class, MetricsAgent::findExistingMBeanServer);
      factory.verify(() -> MBeanServerFactory.findMBeanServer(null));
      factory.verifyNoMoreInteractions();
    }
  }

  @Test
  void interruptedInitializerStopsWaitingWithoutCreatingAnyServer() throws Exception {
    AtomicReference<MBeanServer> found = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread initializer =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              found.set(MetricsAgent.awaitExistingMBeanServer());
              interrupted.set(Thread.currentThread().isInterrupted());
            });
    initializer.start();
    initializer.join(2_000L);
    assertTrue(!initializer.isAlive());
    assertNull(found.get());
    assertTrue(interrupted.get());
  }
}
