import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.management.MBeanServer;
import javax.management.MBeanServerBuilder;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import kr.pletor.nodemetrics.agent.AgentLog;

/** Separate host fixture: the application owns singleton initialization and logging handlers. */
public class LateGlobalSetup {
  public static class AppLogManager extends LogManager {}

  public static class AppMBeanServerBuilder extends MBeanServerBuilder {
    static volatile int built;

    @Override
    public MBeanServer newMBeanServer(
        String defaultDomain, MBeanServer outer, MBeanServerDelegate delegate) {
      built++;
      return super.newMBeanServer(defaultDomain, outer, delegate);
    }
  }

  public static void main(String[] args) throws Exception {
    Thread.sleep(args.length == 0 ? 1000L : Long.parseLong(args[0]));
    String mode = args.length < 2 ? "late" : args[1];
    boolean expectAgent = args.length < 3 || Boolean.parseBoolean(args[2]);
    if ("no-jmx".equals(mode)) {
      require(MBeanServerFactory.findMBeanServer(null).isEmpty(), "agent created a JMX server");
    }
    System.setProperty("java.util.logging.manager", AppLogManager.class.getName());
    System.setProperty("javax.management.builder.initial", AppMBeanServerBuilder.class.getName());
    require(
        LogManager.getLogManager().getClass() == AppLogManager.class,
        "agent created LogManager before the application");
    if ("no-jmx".equals(mode)) {
      require(AppMBeanServerBuilder.built == 0, "agent used the application JMX builder");
      System.out.println("LATE SETUP OK");
      return;
    }
    AtomicInteger hostHandlerCalls = new AtomicInteger();
    AtomicInteger outputAttempts = new AtomicInteger();
    boolean blocked = "blocked-output".equals(mode);
    if (blocked || "throwing-output".equals(mode)) {
      CountDownLatch neverRelease = new CountDownLatch(1);
      Logger root = Logger.getLogger("");
      for (Handler handler : root.getHandlers()) {
        root.removeHandler(handler);
      }
      root.addHandler(
          new Handler() {
            @Override
            public void publish(LogRecord record) {
              if (record.getLoggerName().startsWith("kr.pletor")) {
                hostHandlerCalls.incrementAndGet();
                throw new AssertionError("agent called an application handler");
              }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
          });
      System.setErr(
          new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(String value) {
              outputAttempts.incrementAndGet();
              if (blocked) {
                try {
                  neverRelease.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else {
                throw new AssertionError("injected output failure");
              }
            }
          });
      AgentLog logger = AgentLog.getLogger("kr.pletor.audit");
      long before = System.nanoTime();
      for (int i = 0; i < 1000; i++) {
        logger.log(Level.WARNING, "test output isolation");
      }
      require(
          System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2), "log caller waited on output");
      require(hostHandlerCalls.get() == 0, "agent used an application handler");
    }
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    require(AppMBeanServerBuilder.built > 0, "agent created the platform server first");
    if (expectAgent) {
      ObjectName health = new ObjectName("kr.pletor.agent:type=Observability");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!server.isRegistered(health)
          || ((Number) server.getAttribute(health, "ProcessedCount")).longValue() == 0) {
        require(System.nanoTime() < deadline, "agent did not resume collection in the host server");
        Thread.sleep(50L);
      }
      String expectedPath = args.length < 4 ? "/" : args[3];
      require(
          server.isRegistered(new ObjectName("kr.pletor.node:type=FsMetrics,path=" + expectedPath)),
          "packaged parser did not apply the requested filesystem path");
      if (blocked || "throwing-output".equals(mode)) {
        require(outputAttempts.get() > 0, "output failure was not exercised");
        require(
            ((Number) server.getAttribute(health, "DroppedLogCount")).longValue() > 0,
            "dropped diagnostic records were not exposed");
        require(hostHandlerCalls.get() == 0, "agent called an application handler");
      }
    }
    System.out.println("LATE SETUP OK");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      System.out.println("LATE SETUP FAILED: " + message);
      System.exit(1);
    }
  }
}
