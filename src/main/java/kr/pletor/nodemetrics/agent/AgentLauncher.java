package kr.pletor.nodemetrics.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * thread does not wait for JMX registration or file access. Task creation and thread startup are
 * guarded as well: failures on the JVM's startup thread are dropped without logging, because even
 * stderr may block. Initialization failures on the daemon are reported there and never handed to
 * the application's default uncaught-exception handler.
 */
public final class AgentLauncher {

  private AgentLauncher() {
    // Not instantiable.
  }

  /**
   * JVM agent entry point, executed before the application's {@code main}.
   *
   * <p>The first invocation selects the startup arguments. Later invocations, including those
   * during initialization or after a failed attempt, return without spawning another daemon.
   *
   * @param agentArgs agent argument string (optional configuration path)
   * @param inst instrumentation handle (not used)
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      if (!StartOnce.CLAIMED.compareAndSet(false, true)) {
        return;
      }
      start(new InitTask(agentArgs, inst));
    } catch (Throwable ignored) { // NOSONAR - even task creation can fail before start is entered
      // Do not log on the application's startup thread: stderr and JUL can block or initialize
      // application-wide state. Losing metrics is preferable to delaying or aborting the host.
    }
  }

  /** Loaded only inside premain's protection; the entry-point class itself has no static state. */
  private static final class StartOnce {
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
  }

  // Visible for testing
  static void start(Runnable initTask) {
    try {
      // Inheritance can invoke application childValue callbacks synchronously on the startup
      // thread. They can block or retain host context even though initialization is offloaded.
      Thread thread = new Thread(null, initTask, "node-metrics-agent-init", 0L, false);
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      thread.setUncaughtExceptionHandler(new SwallowingHandler());
      thread.start();
    } catch (Throwable ignored) { // NOSONAR - thread creation/start must not break host startup
      // This can also run on the application's startup thread. Do not attempt failure reporting.
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
