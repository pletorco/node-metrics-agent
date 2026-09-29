package co.pletor.nodemetrics.agent;

import java.lang.instrument.Instrumentation;

/**
 * JVM entry point of the agent ({@code Premain-Class}).
 *
 * <p>This class is deliberately tiny and holds <b>no static state</b>, so nothing can fail while it
 * is loaded or initialized. That matters because an exception thrown out of {@code premain} - or
 * out of the static initialization of the class that declares it - aborts the whole JVM before the
 * application starts.
 *
 * <p>It does no real work either: it starts one low-priority daemon thread that runs the actual
 * initialization ({@link MetricsAgent#premain}) and returns immediately, so the application's main
 * thread is not delayed by JMX registration, file access or class loading. Every failure -
 * including {@link Error}s such as {@link LinkageError} - is confined to that thread and never
 * reaches the application.
 */
public final class AgentLauncher {

  private AgentLauncher() {
    // Not instantiable.
  }

  /**
   * JVM agent entry point, executed before the application's {@code main}.
   *
   * @param agentArgs agent argument string (optional configuration path)
   * @param inst instrumentation handle (not used)
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    start(new InitTask(agentArgs, inst));
  }

  // Visible for testing
  static void start(Runnable initTask) {
    try {
      Thread thread = new Thread(initTask, "node-metrics-agent-init");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      thread.setUncaughtExceptionHandler(new SwallowingHandler());
      thread.start();
    } catch (Throwable t) { // NOSONAR - the agent must never break the application's startup
      report(t);
    }
  }

  /** Runs the real initialization; nothing thrown by it may leave the thread. */
  private static final class InitTask implements Runnable {
    private final String agentArgs;
    private final Instrumentation inst;

    InitTask(String agentArgs, Instrumentation inst) {
      this.agentArgs = agentArgs;
      this.inst = inst;
    }

    @Override
    public void run() {
      try {
        MetricsAgent.premain(agentArgs, inst);
      } catch (Throwable t) { // NOSONAR
        report(t);
      }
    }
  }

  /**
   * Keeps errors of the init thread away from the application's default uncaught-exception handler.
   */
  private static final class SwallowingHandler implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
      report(error);
    }
  }

  private static void report(Throwable t) {
    try {
      System.err.println(
          "[node-metrics-agent] initialization failed, continuing without metrics: " + t);
    } catch (Throwable ignored) { // NOSONAR
      // Nothing else can be done.
    }
  }
}
