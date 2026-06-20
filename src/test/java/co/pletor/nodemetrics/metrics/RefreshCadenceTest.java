package co.pletor.nodemetrics.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshCadenceTest {

  @Test
  void cadence_shouldAllowThenThrottleUntilForced() {
    RefreshCadence cadence = new RefreshCadence(60_000L);

    assertTrue(cadence.tryAcquire(), "First acquire should pass");
    assertFalse(cadence.tryAcquire(), "Second immediate acquire should be throttled");

    cadence.force();
    assertTrue(cadence.tryAcquire(), "Force should allow immediate reacquire");
  }

  @Test
  void cadence_shouldGrantOnlyOneAcquireUnderConcurrentContention() throws Exception {
    RefreshCadence cadence = new RefreshCadence(60_000L);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger granted = new AtomicInteger(0);

    Thread t1 = new Thread(() -> runContender(cadence, ready, start, granted), "refresh-cadence-test-1");
    Thread t2 = new Thread(() -> runContender(cadence, ready, start, granted), "refresh-cadence-test-2");
    t1.start();
    t2.start();

    assertTrue(ready.await(1, TimeUnit.SECONDS), "Threads should be ready");
    start.countDown();
    t1.join(1_000L);
    t2.join(1_000L);

    assertEquals(1, granted.get(), "Only one contender should win the first acquire");
  }

  private static void runContender(
      RefreshCadence cadence,
      CountDownLatch ready,
      CountDownLatch start,
      AtomicInteger granted
  ) {
    ready.countDown();
    try {
      if (!start.await(1, TimeUnit.SECONDS)) {
        return;
      }
      if (cadence.tryAcquire()) {
        granted.incrementAndGet();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
