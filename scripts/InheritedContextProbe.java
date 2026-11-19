package kr.pletor.nodemetrics.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Hostile host context installed before the packaged agent; never shipped in production. */
public final class InheritedContextProbe {
  private static final AtomicInteger CALLBACKS = new AtomicInteger();
  private static final AtomicInteger HOST_ERRORS = new AtomicInteger();
  private static String mode;
  private static final InheritableThreadLocal<Object> HOST_CONTEXT =
      new InheritableThreadLocal<>() {
        @Override
        protected Object childValue(Object parent) {
          // JDK-owned threads can also inherit. Inject only on agent creation paths so the
          // control process can run normally and failures identify the component under test.
          boolean agentThread = false;
          for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.equals(AgentLauncher.class.getName())
                || owner.equals(AgentThreads.class.getName())
                || owner.equals(AgentLog.class.getName())) {
              agentThread = true;
            }
          }
          if (!agentThread) {
            return parent;
          }
          CALLBACKS.incrementAndGet();
          if ("block".equals(mode)) {
            try {
              new CountDownLatch(1).await(); // The Python runner bounds this disposable JVM.
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          } else if ("throw".equals(mode)) {
            throw new LinkageError("host childValue must not run during agent thread creation");
          } else if ("copy".equals(mode)) {
            return new byte[8 * 1024 * 1024];
          }
          return parent;
        }
      };

  private InheritedContextProbe() {}

  public static void premain(String args, Instrumentation instrumentation) {
    mode = args;
    HOST_CONTEXT.set(new Object());
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> HOST_ERRORS.incrementAndGet());
  }

  public static void main(String[] args) throws Exception {
    require(HOST_CONTEXT.get() != null, "pre-agent did not install host context");
    require(mode.equals("block") || mode.equals("throw") || mode.equals("copy"), "unknown mode");
    if (args.length == 0 || !"control".equals(args[0])) {
      // The host owns platform-server initialization; the agent waits for it by design (#69).
      ManagementFactory.getPlatformMBeanServer();
      CountDownLatch launched = new CountDownLatch(1);
      AtomicReference<Object> launcherContext = new AtomicReference<>(new Object());
      AgentLauncher.start(() -> {
        require(Thread.currentThread().isDaemon(), "initializer is not a daemon");
        require(Thread.currentThread().getPriority() == Thread.MIN_PRIORITY, "init priority");
        launcherContext.set(HOST_CONTEXT.get());
        launched.countDown();
      });
      require(launched.await(3, TimeUnit.SECONDS), "initializer did not complete");
      require(launcherContext.get() == null, "initializer retained host context");

      AtomicReference<Object> workerContext = new AtomicReference<>(new Object());
      Thread worker =
          AgentThreads.daemon("context-probe", () -> workerContext.set(HOST_CONTEXT.get()));
      require(worker.isDaemon(), "worker is not a daemon");
      require(worker.getUncaughtExceptionHandler() != null, "worker has no exception handler");
      worker.start();
      worker.join(3_000L); // Test host only; production never waits for telemetry.
      require(!worker.isAlive(), "worker did not complete");
      require(workerContext.get() == null, "worker retained host context");

      // Verify the real packaged premain progressed to engine and config-watcher startup.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean ready = false;
      while (!ready && System.nanoTime() < deadline) {
        boolean watcher = false;
        boolean refresher = false;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
          watcher |= thread.getName().equals("node-metrics-config-watcher");
          refresher |= thread.getName().equals("node-metrics-refresh-worker-1");
        }
        ready = watcher && refresher;
        if (!ready) {
          Thread.sleep(10L);
        }
      }
      require(ready, "packaged agent did not start its workers and config watcher");
    }
    require(CALLBACKS.get() == 0, "agent invoked host childValue: " + CALLBACKS.get());
    require(HOST_ERRORS.get() == 0, "agent reached host uncaught-exception handler");
    System.out.println("INHERITED CONTEXT SAFE: " + mode);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      System.out.println("INHERITED CONTEXT FAILURE: " + message);
      System.exit(1);
    }
  }
}
