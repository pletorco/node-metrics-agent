package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

final class AsyncTestSupport {
  private AsyncTestSupport() {}

  static void await(BooleanSupplier condition, long timeoutMs, String message) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (!condition.getAsBoolean()) {
      if (Thread.currentThread().isInterrupted()) {
        fail("Interrupted while waiting: " + message);
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        fail("Timed out waiting: " + message);
      }
      LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10L)));
    }
  }
}
