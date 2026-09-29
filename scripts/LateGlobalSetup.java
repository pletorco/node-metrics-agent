import java.lang.management.ManagementFactory;
import java.util.logging.LogManager;
import javax.management.MBeanServer;
import javax.management.MBeanServerBuilder;
import javax.management.MBeanServerDelegate;
import javax.management.ObjectName;

/**
 * Stand-in for an application that configures JVM-wide singletons from main(), after the agent has
 * started (Quarkus and Log4j2 setups do this for java.util.logging). Used by
 * scripts/smoke-test.sh to check that the agent does not create those singletons first, which
 * would make the JVM ignore the application's settings.
 *
 * <p>Must be compiled and run from the class path: the JVM loads the classes named by the
 * properties through the system class loader.
 */
public class LateGlobalSetup {

  /** The application's own LogManager. */
  public static class AppLogManager extends LogManager {}

  /** The application's own MBeanServerBuilder; counts the servers it builds. */
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
    // Longer than the agent needs to start, much shorter than the agent's grace periods.
    Thread.sleep(1_000L);
    System.setProperty("java.util.logging.manager", AppLogManager.class.getName());
    System.setProperty("javax.management.builder.initial", AppMBeanServerBuilder.class.getName());

    String manager = LogManager.getLogManager().getClass().getName();
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    if (!AppLogManager.class.getName().equals(manager)) {
      fail("the agent created the LogManager before the application: " + manager);
    }
    if (AppMBeanServerBuilder.built < 1) {
      fail("the agent created the platform MBeanServer before the application");
    }

    // The agent must have used the application's server rather than given up.
    ObjectName cpu = new ObjectName("co.pletor.node:type=CpuMetrics");
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (!server.isRegistered(cpu)) {
      if (System.nanoTime() > deadline) {
        fail("the agent did not register its MBeans in the application's MBeanServer");
      }
      Thread.sleep(100L);
    }
    System.out.println("LATE SETUP OK");
  }

  private static void fail(String message) {
    System.out.println("LATE SETUP FAILED: " + message);
    System.exit(1);
  }
}
