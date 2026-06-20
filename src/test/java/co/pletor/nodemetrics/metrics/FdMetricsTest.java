package co.pletor.nodemetrics.metrics;

import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FdMetrics}.
 *
 * These tests use reflection to inject custom OperatingSystemMXBean
 * implementations so that all branches inside {@link FdMetrics#poll()} can
 * be exercised without depending on the real platform environment.
 */
class FdMetricsTest {

  /**
   * Utility helper to overwrite the private final 'base' field on FdMetrics.
   * This allows us to inject a proxy MXBean for testing different branches.
   */
  private void setBaseMxBean(FdMetrics metrics, OperatingSystemMXBean bean) {
    try {
      Field baseField = FdMetrics.class.getDeclaredField("base");
      baseField.setAccessible(true);
      baseField.set(metrics, bean);
    } catch (Exception e) {
      fail("Failed to inject test OperatingSystemMXBean: " + e);
    }
  }

  @Test
  void initialValues_shouldBeMinusOneBeforePoll() {
    FdMetrics metrics = new FdMetrics();
    bypassRefresh(metrics);

    // Before calling poll(), the default values should be -1
    assertEquals(-1L, metrics.getOpenFileDescriptorCount(),
        "Initial open file descriptor count should be -1");
    assertEquals(-1L, metrics.getMaxFileDescriptorCount(),
        "Initial max file descriptor count should be -1");

    // Demonstrate the pattern: o instanceof [type] tv
    Object o = metrics;
    if (o instanceof FdMetrics) {
      FdMetrics tv = (FdMetrics) o;
      assertSame(metrics, tv, "Pattern-matched instance should be the same object");
    } else {
      fail("Object was expected to be an instance of FdMetrics");
    }
  }

  @Test
  void poll_withUnixOperatingSystemMxBean_shouldPopulateValuesFromBean() {
    FdMetrics metrics = new FdMetrics();

    // Multiline string example for assertion message
    String message =
        "Open and max file descriptor counts should be populated\n" +
        "from the UnixOperatingSystemMXBean proxy\n";

    // Create a proxy that implements UnixOperatingSystemMXBean,
    // returning deterministic values for open/max FD counts.
    UnixOperatingSystemMXBean unixBean = (UnixOperatingSystemMXBean) Proxy.newProxyInstance(
        UnixOperatingSystemMXBean.class.getClassLoader(),
        new Class<?>[]{UnixOperatingSystemMXBean.class},
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // Only the two methods used by FdMetrics.poll() need to be implemented meaningfully.
            if ("getOpenFileDescriptorCount".equals(name)) {
              return 10L;
            }
            if ("getMaxFileDescriptorCount".equals(name)) {
              return 100L;
            }
            // For all other methods we can safely return default values.
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0.0d;
            return null;
          }
        }
    );

    setBaseMxBean(metrics, unixBean);

    // Act
    metrics.poll();

    // Assert
    assertEquals(10L, metrics.getOpenFileDescriptorCount(), message);
    assertEquals(100L, metrics.getMaxFileDescriptorCount(), message);
  }

  @Test
  void poll_withNonUnixOperatingSystemMxBean_shouldSetValuesToMinusOne() {
    FdMetrics metrics = new FdMetrics();

    // Create a proxy implementing only OperatingSystemMXBean,
    // so the instanceof UnixOperatingSystemMXBean branch is not taken.
    OperatingSystemMXBean genericBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        OperatingSystemMXBean.class.getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        (proxy, method, args) -> {
          // No Unix-specific methods will be called, but we still provide safe defaults.
          Class<?> rt = method.getReturnType();
          if (rt == void.class) return null;
          if (rt == boolean.class) return false;
          if (rt == int.class) return 0;
          if (rt == long.class) return 0L;
          if (rt == double.class) return 0.0d;
          return null;
        }
    );

    setBaseMxBean(metrics, genericBean);

    // Act
    metrics.poll();

    // When the platform MXBean is not a UnixOperatingSystemMXBean,
    // both values should be set to -1.
    assertEquals(-1L, metrics.getOpenFileDescriptorCount(),
        "Non-Unix MXBean should result in open FD count -1");
    assertEquals(-1L, metrics.getMaxFileDescriptorCount(),
        "Non-Unix MXBean should result in max FD count -1");
  }

  @Test
  void poll_whenUnderlyingBeanThrows_shouldFallBackToMinusOne() {
    FdMetrics metrics = new FdMetrics();

    // Proxy that implements UnixOperatingSystemMXBean but throws from getOpenFileDescriptorCount
    UnixOperatingSystemMXBean failingBean = (UnixOperatingSystemMXBean) Proxy.newProxyInstance(
        UnixOperatingSystemMXBean.class.getClassLoader(),
        new Class<?>[]{UnixOperatingSystemMXBean.class},
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getOpenFileDescriptorCount".equals(name)) {
              throw new RuntimeException("Simulated failure");
            }
            if ("getMaxFileDescriptorCount".equals(name)) {
              // This value should never be reached because the first call already throws.
              return 999L;
            }
            // Default values for other methods (not expected to be called).
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0.0d;
            return null;
          }
        }
    );

    setBaseMxBean(metrics, failingBean);

    // Act: any Throwable in poll() should cause both metrics to become -1.
    metrics.poll();

    assertEquals(-1L, metrics.getOpenFileDescriptorCount(),
        "On failure, open FD count should be reported as -1");
    assertEquals(-1L, metrics.getMaxFileDescriptorCount(),
        "On failure, max FD count should be reported as -1");
  }

  @Test
  void getters_shouldReturnLastObservedValues() {
    FdMetrics metrics = new FdMetrics();

    // Inject a Unix MXBean with known values
    UnixOperatingSystemMXBean unixBean = (UnixOperatingSystemMXBean) Proxy.newProxyInstance(
        UnixOperatingSystemMXBean.class.getClassLoader(),
        new Class<?>[]{UnixOperatingSystemMXBean.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("getOpenFileDescriptorCount".equals(name)) return 5L;
          if ("getMaxFileDescriptorCount".equals(name)) return 50L;
          Class<?> rt = method.getReturnType();
          if (rt == void.class) return null;
          if (rt == boolean.class) return false;
          if (rt == int.class) return 0;
          if (rt == long.class) return 0L;
          if (rt == double.class) return 0.0d;
          return null;
        }
    );

    setBaseMxBean(metrics, unixBean);

    // First poll should set values
    metrics.poll();
    assertEquals(5L, metrics.getOpenFileDescriptorCount(),
        "Getter should return the last open FD value observed");
    assertEquals(50L, metrics.getMaxFileDescriptorCount(),
        "Getter should return the last max FD value observed");

    // Replace MXBean with one that returns different values to confirm update
    UnixOperatingSystemMXBean unixBean2 = (UnixOperatingSystemMXBean) Proxy.newProxyInstance(
        UnixOperatingSystemMXBean.class.getClassLoader(),
        new Class<?>[]{UnixOperatingSystemMXBean.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("getOpenFileDescriptorCount".equals(name)) return 7L;
          if ("getMaxFileDescriptorCount".equals(name)) return 70L;
          Class<?> rt = method.getReturnType();
          if (rt == void.class) return null;
          if (rt == boolean.class) return false;
          if (rt == int.class) return 0;
          if (rt == long.class) return 0L;
          if (rt == double.class) return 0.0d;
          return null;
        }
    );

    setBaseMxBean(metrics, unixBean2);

    metrics.poll();
    assertEquals(7L, metrics.getOpenFileDescriptorCount(),
        "Getter should reflect updated open FD value after second poll");
    assertEquals(70L, metrics.getMaxFileDescriptorCount(),
        "Getter should reflect updated max FD value after second poll");
  }
  private void bypassRefresh(FdMetrics metrics) {
    metrics.setReadRefreshEnabled(false);
  }
}
