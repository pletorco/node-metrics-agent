package kr.pletor.nodemetrics.agent;

import java.util.logging.Level;

/**
 * Creates the agent's background threads.
 *
 * <p>Every thread is a daemon and has its own {@link Thread.UncaughtExceptionHandler}. Without one,
 * an {@link Error} escaping an agent thread would be handed to the application's default handler
 * ({@link Thread#setDefaultUncaughtExceptionHandler}), which in many applications logs and exits
 * the JVM. Agent failures must never reach the application, so they are logged here and dropped.
 * Threads do not inherit thread locals: copying them can invoke application callbacks on the
 * creating thread and retain application context for the lifetime of an agent daemon.
 */
final class AgentThreads {

  private AgentThreads() {
    // Utility class.
  }

  static Thread daemon(String name, Runnable task) {
    Thread thread = new Thread(null, task, name, 0L, false);
    thread.setDaemon(true);
    thread.setUncaughtExceptionHandler(new LoggingHandler());
    return thread;
  }

  private static final class LoggingHandler implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
      try {
        AgentLog.getLogger(AgentThreads.class.getName())
            .log(
                Level.WARNING,
                "[node-metrics-agent] thread '" + thread.getName() + "' stopped by an error",
                error);
      } catch (Throwable ignored) { // NOSONAR - a handler must never throw
        // Nothing else can be done.
      }
    }
  }
}
