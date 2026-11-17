package kr.pletor.nodemetrics.agent;

import java.util.function.Consumer;
import java.util.logging.LogRecord;

/** Swap only the asynchronous output, keeping production queue/worker behavior in tests. */
final class AgentLogCapture implements AutoCloseable {
  private final Consumer<LogRecord> previous;

  AgentLogCapture(Consumer<LogRecord> output) {
    awaitIdle();
    previous = AgentLog.swapSinkForTest(output);
  }

  static void awaitIdle() {
    AsyncTestSupport.await(() -> AgentLog.pendingCount() == 0, 3_000L, "diagnostic output drain");
  }

  @Override
  public void close() {
    try {
      awaitIdle();
    } finally {
      AgentLog.swapSinkForTest(previous);
    }
  }
}
