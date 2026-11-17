package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerFactory;
import javax.management.MBeanServerNotification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import kr.pletor.nodemetrics.metrics.CpuMetrics;
import kr.pletor.nodemetrics.metrics.CpuMetricsMBean;
import kr.pletor.nodemetrics.metrics.RefreshManagedMetric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** Reads at publication time must stay safe even before the refresh engine can take ownership. */
class MBeanPublicationSafetyTest {
  @TempDir Path temporary;
  private final Map<Field, Object> savedFields = new LinkedHashMap<>();
  private final Map<String, Object> savedFilesystems = new LinkedHashMap<>();
  private final SlowCpu metric = new SlowCpu();
  private MBeanServer server;

  static final class SlowCpu extends CpuMetrics {
    final AtomicInteger reads = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean blocked;

    @Override
    protected void doRefresh() {
      reads.incrementAndGet();
      entered.countDown();
      if (blocked) {
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @BeforeEach
  void isolateAgentState() throws Exception {
    Set<String> fields =
        Set.of("svr", "current", "refreshEngine", "fastRefreshIntervalMs", "partitionKeyDetector");
    for (Field field : MetricsAgent.class.getDeclaredFields()) {
      boolean metricField =
          !Modifier.isFinal(field.getModifiers())
              && RefreshManagedMetric.class.isAssignableFrom(field.getType());
      if (fields.contains(field.getName()) || metricField) {
        field.setAccessible(true);
        savedFields.put(field, field.get(null));
        if (metricField) {
          field.set(null, null);
        }
      }
    }
    savedFilesystems.putAll(filesystems());
    filesystems().clear();
    server = MBeanServerFactory.newMBeanServer();
    set("svr", server);
    set("current", null);
    set("refreshEngine", null);
    MetricsAgent.partitionKeyDetector = path -> path.toString();
  }

  @AfterEach
  void restoreAgentState() throws Exception {
    metric.release.countDown();
    MetricsAgent.stopRefreshEngineForTest();
    filesystems().clear();
    filesystems().putAll(savedFilesystems);
    for (Map.Entry<Field, Object> entry : savedFields.entrySet()) {
      entry.getKey().set(null, entry.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> filesystems() throws Exception {
    Field field = MetricsAgent.class.getDeclaredField("fsMap");
    field.setAccessible(true);
    return (Map<String, Object>) field.get(null);
  }

  private static void set(String name, Object value) throws Exception {
    Field field = MetricsAgent.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object invoke(String name, Class<?>[] types, Object... arguments)
      throws Exception {
    Method method = MetricsAgent.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(null, arguments);
  }

  private ObjectName publishCpu() throws Exception {
    ObjectName name = new ObjectName("kr.pletor.test:type=PublicationCpu");
    assertNotNull(
        invoke(
            "createAndRegister",
            new Class<?>[] {String.class, Supplier.class, Class.class, String.class},
            "publication CPU",
            (Supplier<CpuMetrics>) () -> metric,
            CpuMetricsMBean.class,
            name.toString()));
    return name;
  }

  @Test
  void registrationCollisionDoesNotReturnOrScheduleAnInvisibleReplacement() throws Exception {
    ObjectName name = publishCpu();
    SlowCpu replacement = new SlowCpu();
    Object registered =
        invoke(
            "createAndRegister",
            new Class<?>[] {String.class, Supplier.class, Class.class, String.class},
            "colliding CPU",
            (Supplier<CpuMetrics>) () -> replacement,
            CpuMetricsMBean.class,
            name.toString());

    assertNull(registered, "a failed registration must not become a refresh target");
    set("cpuBean", registered);
    List<?> tasks = (List<?>) invoke("buildRefreshTasksLocked", new Class<?>[] {});
    assertTrue(tasks.isEmpty());
    assertTrue(server.isRegistered(name), "the existing owner's MBean must remain registered");
    assertEquals(-1.0, server.getAttribute(name, "SystemCpuLoad"));
    assertFalse(replacement.isReadRefreshEnabled());
    assertEquals(0, replacement.reads.get());
  }

  @Test
  void registrationNotificationReadsAndDescriptionsNeverRefresh() throws Exception {
    AtomicReference<Throwable> failed = new AtomicReference<>();
    AtomicInteger observed = new AtomicInteger();
    NotificationListener listener =
        (notification, handback) -> {
          if (!(notification instanceof MBeanServerNotification)) {
            return;
          }
          try {
            ObjectName name = ((MBeanServerNotification) notification).getMBeanName();
            server.getMBeanInfo(name);
            assertEquals(-1.0, server.getAttribute(name, "SystemCpuLoad"));
            assertEquals(0, metric.reads.get(), "publication-time reads must not refresh");
            observed.incrementAndGet();
          } catch (Throwable error) {
            failed.set(error);
          }
        };
    server.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener, null, null);

    final ObjectName name = publishCpu();

    assertNull(failed.get(), () -> String.valueOf(failed.get()));
    assertEquals(1, observed.get(), "the registration-time observer must actually run");
    assertFalse(metric.isReadRefreshEnabled());
    server.getMBeanInfo(name);
    server.getAttributes(name, new String[] {"SystemCpuLoad", "ProcessCpuLoad"});
    assertEquals(0, metric.reads.get());
  }

  @Test
  void configReloadPublishesNewFilesystemAsUnavailableWithoutReadingIt() throws Exception {
    invoke("applyConfig", new Class<?>[] {Config.class}, Config.defaults());
    MetricsRefreshEngine engine = new MetricsRefreshEngine(10L, 8);
    set("refreshEngine", engine);
    engine.start();
    AtomicInteger observed = new AtomicInteger();
    AtomicReference<Throwable> failed = new AtomicReference<>();
    AtomicInteger fileReads = new AtomicInteger();
    Path added = Files.createDirectory(temporary.resolve("added"));
    FileStore store = Mockito.mock(FileStore.class);
    try (var files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files
          .when(() -> Files.getFileStore(added))
          .thenAnswer(
              invocation -> {
                fileReads.incrementAndGet();
                return store;
              });
      server.addNotificationListener(
          MBeanServerDelegate.DELEGATE_NAME,
          (notification, handback) -> {
            if (!(notification instanceof MBeanServerNotification)) {
              return;
            }
            try {
              ObjectName name = ((MBeanServerNotification) notification).getMBeanName();
              server.getMBeanInfo(name);
              for (String attribute : List.of("TotalBytes", "UsableBytes", "UnallocatedBytes")) {
                assertEquals(-1L, server.getAttribute(name, attribute));
              }
              observed.incrementAndGet();
            } catch (Throwable error) {
              failed.set(error);
            }
          },
          null,
          null);
      Config changed = Config.defaults();
      changed.fsmetricsPaths = List.of(added.toString());
      invoke("applyConfig", new Class<?>[] {Config.class}, changed);
      assertNull(failed.get(), () -> String.valueOf(failed.get()));
      assertEquals(1, observed.get(), "the new filesystem must be read during registration");
      assertEquals(0, fileReads.get(), "registration must not stat the filesystem on its caller");
    }
  }

  @Test
  void engineConstructionFailureCannotEnableJmxRefresh() throws Exception {
    final ObjectName name = publishCpu();
    set("cpuBean", metric);
    try (var construction =
        Mockito.mockConstruction(
            MetricsRefreshEngine.class,
            (mock, context) -> {
              throw new IllegalStateException("injected engine construction failure");
            })) {
      assertThrows(
          InvocationTargetException.class,
          () -> invoke("initializeRefreshEngine", new Class<?>[] {}));
      server.getMBeanInfo(name);
      assertEquals(-1.0, server.getAttribute(name, "SystemCpuLoad"));
      assertEquals(0, metric.reads.get());
    }
  }

  @Test
  void callerReadsStayCachedWhileBackgroundCollectorIsHung() throws Exception {
    final ObjectName name = publishCpu();
    metric.blocked = true;
    MetricsRefreshEngine engine = new MetricsRefreshEngine(10L, 8);
    set("refreshEngine", engine);
    engine.setTasks(List.of(new MetricsRefreshEngine.RefreshTask("cpu", metric, false)));
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      engine.start();
      assertTrue(metric.entered.await(2, TimeUnit.SECONDS));
      // Cross the standalone metric's 500 ms cadence: the cadence is not an I/O isolation guard.
      Thread.sleep(550L);
      assertEquals(
          -1.0,
          caller
              .submit(
                  () -> {
                    server.getMBeanInfo(name);
                    return server.getAttribute(name, "SystemCpuLoad");
                  })
              .get(500, TimeUnit.MILLISECONDS));
      assertEquals(1, metric.reads.get(), "only the background worker may enter the collector");
    } finally {
      metric.release.countDown();
      caller.shutdownNow();
      assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void standaloneMetricStillSupportsReadTriggeredRefresh() {
    assertTrue(metric.isReadRefreshEnabled());
    metric.getSystemCpuLoad();
    assertEquals(1, metric.reads.get());
  }
}
