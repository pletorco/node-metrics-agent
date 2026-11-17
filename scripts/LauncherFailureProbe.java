package kr.pletor.nodemetrics.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;

/** Separate host program for packaged-launcher failure tests; never shipped in the agent. */
public final class LauncherFailureProbe {
  private static volatile boolean logManagerCreated;
  private static final AtomicInteger APPLICATION_ERRORS = new AtomicInteger();
  private static final AtomicInteger STDERR_WRITES = new AtomicInteger();
  private static final CountDownLatch WRITING_STDERR = new CountDownLatch(1);
  private static boolean guardsInstalled;

  private LauncherFailureProbe() {}

  /** The runner selects this manager at JVM startup; agent failure reporting must not create it. */
  public static final class UnexpectedLogManager extends LogManager {
    public UnexpectedLogManager() {
      logManagerCreated = true;
    }
  }

  /** Test-only first agent: protect the host before the candidate's premain is invoked. */
  public static void premain(String args, Instrumentation instrumentation) {
    installHostGuards();
  }

  private static void installHostGuards() {
    if (guardsInstalled) {
      return;
    }
    CountDownLatch blockedOutput = new CountDownLatch(1);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> APPLICATION_ERRORS.incrementAndGet());
    System.setErr(
        new PrintStream(
            new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                STDERR_WRITES.incrementAndGet();
                WRITING_STDERR.countDown();
                try {
                  blockedOutput.await(); // Deliberately blocked; the runner bounds this process.
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IOException(e);
                }
              }
            }));
    guardsInstalled = true;
  }

  public static void main(String[] args) throws Exception {
    installHostGuards();
    String scenario = args[0];
    if ("thread-creation".equals(scenario)) {
      AtomicInteger inheritanceCalls = new AtomicInteger();
      CountDownLatch started = new CountDownLatch(1);
      InheritableThreadLocal<Object> failingInheritance =
          new InheritableThreadLocal<>() {
            @Override
            protected Object childValue(Object parentValue) {
              inheritanceCalls.incrementAndGet();
              throw new LinkageError("injected thread creation failure");
            }
          };
      failingInheritance.set(new Object());
      try {
        long before = System.nanoTime();
        AgentLauncher.start(started::countDown);
        require(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "launcher waited");
        require(started.await(3, TimeUnit.SECONDS), "initializer did not run");
        require(inheritanceCalls.get() == 0, "launcher invoked the application's childValue");
      } finally {
        failingInheritance.remove();
      }
      require(STDERR_WRITES.get() == 0, "startup failure wrote to stderr");
    } else if ("background-error".equals(scenario)) {
      long before = System.nanoTime();
      AgentLauncher.start(
          () -> {
            require(Thread.currentThread().isDaemon(), "initializer is not a daemon");
            throw new LinkageError("injected background initialization failure");
          });
      require(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "launcher waited");
      require(WRITING_STDERR.await(3, TimeUnit.SECONDS), "background failure was not exercised");
      // The daemon is still blocked reporting its error; main must finish and the JVM must exit.
    } else {
      require("application".equals(scenario), "unknown scenario");
      Thread.sleep(200L); // Give the broken agent's daemon time to encounter its missing class.
    }
    require(APPLICATION_ERRORS.get() == 0, "agent reached the application's error handler");
    require(!logManagerCreated, "agent initialized the application's LogManager");
    System.out.println("APPLICATION STARTED: " + scenario);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      // stderr is intentionally blocked, so failures must remain visible to the bounded runner.
      System.out.println("LAUNCHER FAILURE: " + message);
      System.exit(1);
    }
  }
}
