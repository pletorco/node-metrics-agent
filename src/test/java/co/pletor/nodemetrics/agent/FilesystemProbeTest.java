package co.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FilesystemProbeTest {

  /** Blocks like a stat on a dead mount: ignores interrupts until released. */
  private static <T> java.util.concurrent.Callable<T> hang(CountDownLatch release, T value) {
    return () -> {
      while (release.getCount() > 0) {
        try {
          release.await();
        } catch (InterruptedException ignored) {
          // uninterruptible I/O does not react to interrupts
        }
      }
      return value;
    };
  }

  @Test
  void call_shouldReturnResultOfHealthyTask() {
    FilesystemProbe probe = new FilesystemProbe(2);

    assertEquals("ok", probe.call(() -> "ok", "fallback", 1_000L));
  }

  @Test
  void call_shouldReturnFallbackWithinBoundWhenTaskHangs() {
    FilesystemProbe probe = new FilesystemProbe(2);
    CountDownLatch release = new CountDownLatch(1);
    try {
      long start = System.nanoTime();
      String result = probe.call(hang(release, "late"), "fallback", 100L);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals("fallback", result);
      assertTrue(
          elapsedMs < 1_000L, "Caller must not wait for a hung call, waited " + elapsedMs + " ms");
    } finally {
      release.countDown();
    }
  }

  @Test
  void call_shouldNotSpawnUnboundedThreadsWhenEveryProbeThreadIsHung() {
    FilesystemProbe probe = new FilesystemProbe(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger started = new AtomicInteger();
    try {
      for (int i = 0; i < 6; i++) {
        probe.call(
            () -> {
              started.incrementAndGet();
              return hang(release, "x").call();
            },
            "fallback",
            50L);
      }
      assertEquals(2, started.get(), "Only the capped number of probe threads may be pinned");

      long start = System.nanoTime();
      assertEquals("fallback", probe.call(() -> "healthy", "fallback", 5_000L));
      assertTrue(
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000L,
          "Once saturated, calls must fall back immediately instead of waiting");
    } finally {
      release.countDown();
    }
  }

  @Test
  void call_shouldReturnFallbackWithoutRunningWhenNoTimeIsLeft() {
    FilesystemProbe probe = new FilesystemProbe(2);
    AtomicInteger ran = new AtomicInteger();

    assertEquals(
        "fallback",
        probe.call(
            () -> {
              ran.incrementAndGet();
              return "ran";
            },
            "fallback",
            0L));
    assertEquals(0, ran.get());
  }

  @Test
  void call_shouldReturnFallbackWhenTaskThrows() {
    FilesystemProbe probe = new FilesystemProbe(2);

    assertEquals(
        "fallback",
        probe.call(
            () -> {
              throw new IllegalStateException("boom");
            },
            "fallback",
            1_000L));
  }
}
