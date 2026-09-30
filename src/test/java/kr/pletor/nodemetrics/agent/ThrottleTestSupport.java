package kr.pletor.nodemetrics.agent;

import java.lang.reflect.Field;

/**
 * The agent's classes share static {@link ThrottledLogger}s, which suppress repeated messages for a
 * minute. Tests that assert on a log message must not depend on which test ran before, so they
 * reset the throttling state.
 */
final class ThrottleTestSupport {

  private ThrottleTestSupport() {
    // Utility class.
  }

  static void resetAll() {
    for (Class<?> owner :
        new Class<?>[] {ConfigReloader.class, MetricsAgent.class, MetricsRefreshEngine.class}) {
      try {
        Field field = owner.getDeclaredField("THROTTLED_LOGGER");
        field.setAccessible(true);
        ((ThrottledLogger) field.get(null)).reset();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Cannot reset " + owner.getSimpleName(), e);
      }
    }
  }
}
