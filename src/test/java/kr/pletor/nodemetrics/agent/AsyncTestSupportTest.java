package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AsyncTestSupportTest {
  @Test
  void missingReadinessFailsInsteadOfContinuingTheTest() {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> AsyncTestSupport.await(() -> false, 0L, "refresh never started"));
    assertTrue(failure.getMessage().contains("refresh never started"));
  }

  @Test
  void interruptionFailsAndPreservesTheInterruptFlag() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          AssertionError.class,
          () -> AsyncTestSupport.await(() -> false, 1_000L, "interrupted refresh"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
