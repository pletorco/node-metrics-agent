// co.pletor.nodemetrics.agent.MetricsAgent.java
package co.pletor.nodemetrics.agent;

import co.pletor.nodemetrics.metrics.CgroupMemMetrics;
import co.pletor.nodemetrics.metrics.CpuMetrics;
import co.pletor.nodemetrics.metrics.FdMetrics;
import co.pletor.nodemetrics.metrics.FsMetrics;
import co.pletor.nodemetrics.metrics.IoRates;
import co.pletor.nodemetrics.metrics.NodeMemMetrics;
import co.pletor.nodemetrics.metrics.OsInfoMetrics;
import co.pletor.nodemetrics.metrics.OsRuntimeMetrics;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java agent entry point that exposes node / process metrics as JMX MBeans.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Resolve and load configuration from YAML</li>
 *   <li>Register fixed (non-filesystem) MBeans</li>
 *   <li>Dynamically register filesystem MBeans based on configuration</li>
 *   <li>Schedule periodic polling of all metrics</li>
 *   <li>Watch the configuration file and apply changes at runtime</li>
 * </ul>
 */
public class MetricsAgent {

  /**
   * This class is not meant to be instantiated.
   * <p>
   * All behavior is provided via static methods and static state.
   */
  private MetricsAgent() {
    // Prevent instantiation of utility/agent class.
  }

  /**
   * Underlying JUL logger used by the agent.
   */
  private static final Logger LOGGER = Logger.getLogger(MetricsAgent.class.getName());
  private static final ThrottledLogger THROTTLED_LOGGER = new ThrottledLogger(LOGGER, 60_000L);


  /**
   * Lock used to serialize configuration updates (including MBean changes).
   */
  private static final Object APPLY_LOCK = new Object();

  /**
   * Map of configured filesystem paths to their corresponding MBean entries.
   * <p>
   * Key: configured path string (as in {@link Config#fsmetricsPaths})<br>
   * Value: {@link FsEntry} holding the bean instance, {@link ObjectName},
   * and resolved {@link Path}.
   */
  private static final Map<String, FsEntry> fsMap = new LinkedHashMap<>();



  /**
   * Platform MBean server used to register all metrics.
   */
  private static MBeanServer svr;

  /**
   * Last applied configuration.
   */
  private static Config current;
  private static final TelemetryModeState TELEMETRY_MODE_STATE = new TelemetryModeState();
  private static final TelemetryModeMetrics TELEMETRY_MODE_METRICS =
      new TelemetryModeMetrics(TELEMETRY_MODE_STATE);
  private static MetricsRefreshEngine refreshEngine;
  private static final AgentObservabilityMetrics AGENT_OBSERVABILITY_METRICS =
      new AgentObservabilityMetrics(
          MetricsAgent::refreshEngineInstance,
          MetricsAgent::currentTelemetryMode,
          MetricsAgent::throttledLoggerOverflowCount
      );
  private static final long REFRESH_DISPATCH_INTERVAL_MS = 500L;
  private static final int REFRESH_QUEUE_CAPACITY = 1024;

  private static CgroupMemMetrics cgroupMemBean;
  private static CpuMetrics cpuBean;
  private static FdMetrics fdBean;
  private static IoRates ioRatesBean;
  private static NodeMemMetrics nodeMemBean;
  private static OsInfoMetrics osInfoBean;
  private static OsRuntimeMetrics osRuntimeBean;

  private static final String LOG_KEY_AGENT_STARTUP_FAILURE = "agent-startup-failure";
  private static final String LOG_KEY_BLANK_FSMETRICS_PATH = "blank-fsmetrics-path";
  private static final String LOG_KEY_INVALID_FSMETRICS_PATH = "invalid-fsmetrics-path";
  private static final String LOG_KEY_REGISTER_FS_FAILURE = "register-fs-failure";
  private static final String LOG_KEY_INVALID_RESOLVED_PATH = "invalid-resolved-path";
  private static final String LOG_KEY_MISSING_CONFIGURED_PATH = "missing-configured-path";
  private static final String LOG_KEY_FIXED_MBEAN_REGISTRATION_FAILURE = "fixed-mbean-registration-failure";
  private static final String LOG_KEY_NULL_CONFIG_APPLIED = "null-config-applied";
  private static final String LOG_KEY_PARTITION_CAP_REACHED = "partition-cap-reached";

  /**
   * Functional interface for applying a new configuration.
   * <p>
   * This allows decoupling the {@link ConfigReloader} from the agent logic
   * by injecting an {@link ApplyConfigFn} that knows how to update the
   * running agent state.
   */
  @FunctionalInterface
  public interface ApplyConfigFn {

    /**
     * Applies the given configuration to the running agent.
     * <p>
     * Implementations are expected to be thread-safe and should perform
     * any necessary MBean registration, unregistration, and scheduling
     * adjustments.
     *
     * @param cfg configuration to apply; never {@code null}
     * @throws Exception if the configuration cannot be applied
     */
    void apply(Config cfg) throws Exception;
  }

  /**
   * Internal data holder to track filesystem metrics beans and their
   * JMX {@link ObjectName}s.
   */
  private static final class FsEntry {
    private final FsMetrics bean;
    private final ObjectName on;
    private final Path path;

    FsEntry(FsMetrics bean, ObjectName on, Path path) {
      this.bean = bean;
      this.on = on;
      this.path = path;
    }

    FsMetrics getBean() {
      return bean;
    }

    ObjectName getOn() {
      return on;
    }

    Path getPath() {
      return path;
    }
  }

  /**
   * JVM agent entry point, executed before the main application.
   * <p>
   * Agent arguments (if present) are interpreted as the path to the YAML
   * configuration file.
   *
   * @param agentArgs agent argument string (optional configuration path)
   * @param inst      instrumentation handle (not used here but required by the JVM)
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      // Resolve configuration file and load initial configuration (or defaults).
      Path cfgPath = resolveConfigPath(agentArgs);
      current = ConfigLoader.loadOrDefault(cfgPath);

      // Get the platform MBeanServer.
      svr = ManagementFactory.getPlatformMBeanServer();

      // ----- Register fixed, non-filesystem MBeans -----
      cgroupMemBean = new CgroupMemMetrics();
      registerStandardMBeanSafely(cgroupMemBean, co.pletor.nodemetrics.metrics.CgroupMemMetricsMBean.class,
          fixedObjectName("co.pletor.cgroup:type=MemMetrics"));

      cpuBean = new CpuMetrics();
      registerStandardMBeanSafely(cpuBean, co.pletor.nodemetrics.metrics.CpuMetricsMBean.class,
          fixedObjectName("co.pletor.node:type=CpuMetrics"));

      fdBean = new FdMetrics();
      registerStandardMBeanSafely(fdBean, co.pletor.nodemetrics.metrics.FdMetricsMBean.class,
          fixedObjectName("co.pletor.proc:type=FdMetrics"));

      ioRatesBean = new IoRates();
      registerStandardMBeanSafely(ioRatesBean, co.pletor.nodemetrics.metrics.IoRatesMBean.class,
          fixedObjectName("co.pletor.node:type=IoRates"));

      nodeMemBean = new NodeMemMetrics();
      registerStandardMBeanSafely(nodeMemBean, co.pletor.nodemetrics.metrics.NodeMemMetricsMBean.class,
          fixedObjectName("co.pletor.node:type=MemMetrics"));

      osInfoBean = new OsInfoMetrics();
      registerStandardMBeanSafely(osInfoBean, co.pletor.nodemetrics.metrics.OsInfoMetricsMBean.class,
          fixedObjectName("co.pletor.node:type=OsInfoMetrics"));

      osRuntimeBean = new OsRuntimeMetrics();
      registerStandardMBeanSafely(osRuntimeBean, co.pletor.nodemetrics.metrics.OsRuntimeMetricsMBean.class,
          fixedObjectName("co.pletor.node:type=OsRuntimeMetrics"));

      registerStandardMBeanSafely(
          TELEMETRY_MODE_METRICS,
          TelemetryModeMetricsMBean.class,
          fixedObjectName("co.pletor.agent:type=TelemetryMode")
      );
      registerStandardMBeanSafely(
          AGENT_OBSERVABILITY_METRICS,
          AgentObservabilityMetricsMBean.class,
          fixedObjectName("co.pletor.agent:type=Observability")
      );

      // ----- Apply initial config (filesystem MBeans + scheduler) -----
      applyConfig(current);
      initializeRefreshEngine();

      // ----- Start configuration watcher -----
      // If cfgPath is null, watch the default config location (for hot creation).
      Path watchTarget = (cfgPath != null) ? cfgPath : Paths.get("./config/node-metrics.yml");
      ConfigReloader reloader = new ConfigReloader(watchTarget, MetricsAgent::applyConfig);

      Thread watcherThread = new Thread(reloader, "node-metrics-config-watcher");
      watcherThread.setDaemon(true);
      watcherThread.start();

      LOGGER.log(Level.INFO, "[node-metrics-agent] started. cfg={0}",
          new Object[]{cfgPath != null ? cfgPath : "(default)"});
    } catch (Throwable t) { // NOSONAR
      // Prevent the agent from crashing the main application startup.
      THROTTLED_LOGGER.log(
          Level.SEVERE,
          LOG_KEY_AGENT_STARTUP_FAILURE,
          t,
          () -> "[node-metrics-agent] Failed to start agent. The application will continue without metrics."
      );
    }
  }


  /**
   * Build the normalized set of filesystem paths from the configuration.
   * <p>
   * The root path ({@code "/"}) is always included as a fallback.
   *
   * @param cfg configuration containing {@link Config#fsmetricsPaths}
   * @return a {@link LinkedHashSet} of normalized path strings
   */
  private static LinkedHashSet<String> buildPathSet(Config cfg) {
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    java.util.List<String> configured = (cfg.fsmetricsPaths == null)
        ? java.util.List.of("/")
        : cfg.fsmetricsPaths;

    for (String raw : configured) {
      String normalized = normalizeConfiguredPath(raw);
      if (normalized != null) {
        paths.add(normalized);
      }
    }

    String normalizedRoot = normalizeConfiguredPath("/");
    paths.add(normalizedRoot == null ? "/" : normalizedRoot);
    return paths;
  }

  private static String normalizeConfiguredPath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_BLANK_FSMETRICS_PATH,
          () -> "[node-metrics-agent] ignoring blank fsmetrics_paths entry"
      );
      return null;
    }
    try {
      return Paths.get(rawPath.trim()).toAbsolutePath().normalize().toString();
    } catch (InvalidPathException | SecurityException e) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_INVALID_FSMETRICS_PATH,
          e,
          () -> "[node-metrics-agent] ignoring invalid fsmetrics_paths entry: " + rawPath
      );
      return null;
    }
  }

  private static int resolveMaxFsPartitions(Config cfg) {
    if (cfg == null || cfg.fsmetricsMaxPartitions == null || cfg.fsmetricsMaxPartitions < 1) {
      return Config.DEFAULT_FSMETRICS_MAX_PARTITIONS;
    }
    return cfg.fsmetricsMaxPartitions;
  }

  private static LinkedHashSet<String> applyPartitionDedupAndCap(
      LinkedHashSet<String> normalizedPaths,
      int maxPartitions
  ) {
    LinkedHashSet<String> effectivePaths = new LinkedHashSet<>();
    LinkedHashMap<String, String> partitionToPath = new LinkedHashMap<>();
    int duplicatePartitionCount = 0;
    int capExceededCount = 0;

    for (String normalizedPath : normalizedPaths) {
      Path path = Paths.get(normalizedPath);
      String partitionKey = detectPartitionKey(path);

      boolean duplicatePartition = partitionToPath.containsKey(partitionKey);
      boolean capReached = partitionToPath.size() >= maxPartitions;

      if (duplicatePartition) {
        duplicatePartitionCount++;
      } else if (capReached) {
        capExceededCount++;
      } else {
        partitionToPath.put(partitionKey, normalizedPath);
        effectivePaths.add(normalizedPath);
      }
    }

    if (duplicatePartitionCount > 0) {
      LOGGER.log(
          Level.INFO,
          "[node-metrics-agent] deduplicated {0} filesystem path(s) on already-covered partitions",
          duplicatePartitionCount
      );
    }

    if (capExceededCount > 0) {
      final int droppedCount = capExceededCount;
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_PARTITION_CAP_REACHED,
          () -> "[node-metrics-agent] fsmetrics partition cap reached (cap="
              + maxPartitions + ", dropped=" + droppedCount + ")"
      );
    }

    return effectivePaths;
  }

  private static String detectPartitionKey(Path path) {
    // Missing paths cannot be mapped reliably to a mounted partition.
    // Keep them unique by absolute path so cap logic remains deterministic.
    if (!Files.exists(path)) {
      return "missing:" + path;
    }

    try {
      Object dev = Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS);
      if (dev != null) {
        return "unix:dev:" + dev;
      }
    } catch (Exception ignored) {
      // Fall back to FileStore-based key.
    }

    try {
      FileStore fs = Files.getFileStore(path);
      return "filestore:" + fs.name() + "|" + fs.type();
    } catch (Exception ignored) {
      // Last-resort key keeps behavior deterministic even on lookup failures.
      return "path:" + path;
    }
  }

  /**
   * Register filesystem MBeans for all configured paths or keep existing ones
   * when they can be reused.
   *
   * @param newPaths normalized set of configured paths
   * @throws InstanceAlreadyExistsException if an MBean with the same name is already registered
   * @throws MBeanRegistrationException     if an MBean cannot be registered
   * @throws NotCompliantMBeanException     if an MBean does not comply with JMX requirements
   */
  private static void registerOrReuseFsBeans(LinkedHashSet<String> newPaths) {

    for (String p : newPaths) {
      if (!shouldReuseExistingEntry(p)) {
        try {
          Path path = resolveAndValidatePath(p);
          if (path != null) {
            registerFsBean(p, path);
          }
        } catch (InstanceAlreadyExistsException
                 | MBeanRegistrationException
                 | NotCompliantMBeanException
                 | RuntimeException e) {
          THROTTLED_LOGGER.log(
              Level.WARNING,
              LOG_KEY_REGISTER_FS_FAILURE,
              e,
              () -> "[node-metrics-agent] failed to register filesystem metrics for path: " + p
          );
        }
      }
    }
  }

  private static void registerFsBean(String configuredPath, Path path)
      throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException {
    FsMetrics bean = new FsMetrics(path);
    ObjectName on = buildFsObjectName(path);
    svr.registerMBean(new StandardMBean(bean, co.pletor.nodemetrics.metrics.FsMetricsMBean.class), on);
    fsMap.put(configuredPath, new FsEntry(bean, on, path));
    LOGGER.log(Level.INFO, "Registered MBean: {0}", on);
  }

  /**
   * Decide whether we can reuse an existing filesystem entry for the given key.
   *
   * @param key configuration key representing the filesystem path
   * @return {@code true} if an entry for this key already exists, otherwise {@code false}
   */
  private static boolean shouldReuseExistingEntry(String key) {
    return fsMap.containsKey(key);
  }

  /**
   * Resolve and validate the filesystem path for a configured entry.
   * <p>
   * If the path does not exist, a warning is logged but the path is still used
   * for registration to avoid hard-failing on transient or mounted-later paths.
   *
   * @param rawPath configured path string
   * @return normalized absolute {@link Path}
   */
  private static Path resolveAndValidatePath(String rawPath) {
    final Path path;
    try {
      path = Paths.get(rawPath).toAbsolutePath().normalize();
    } catch (InvalidPathException | SecurityException e) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_INVALID_RESOLVED_PATH,
          e,
          () -> "[node-metrics-agent] invalid path, skipping registration: " + rawPath
      );
      return null;
    }

    if (!Files.exists(path)) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_MISSING_CONFIGURED_PATH,
          () -> "[node-metrics-agent] WARN missing path (registering anyway): " + path
      );
    }

    return path;
  }

  /**
   * Build the JMX {@link ObjectName} for a filesystem metrics bean.
   * <p>
   * Any {@link MalformedObjectNameException} is wrapped into
   * {@link IllegalArgumentException}, as it indicates a programming error.
   *
   * @param path filesystem path being monitored
   * @return constructed {@link ObjectName}
   */
  @SuppressWarnings("java:S1149") // JMX ObjectName API requires Hashtable
  private static ObjectName buildFsObjectName(Path path) {
    try {
      // Let ObjectName handle the quoting rules for each key/value pair.
      Hashtable<String, String> props = new Hashtable<>();
      props.put("type", "FsMetrics");
      props.put("path", path.toString());

      // This produces something like:
      //   co.pletor.node:type=FsMetrics,path=/mnt/data
      // without extra quotes around the path value.
      return new ObjectName("co.pletor.node", props);
    } catch (MalformedObjectNameException e) {
      throw new IllegalArgumentException("Invalid ObjectName for path: " + path, e);
    }
  }

  /**
   * Build {@link ObjectName} for fixed (hard-coded) beans.
   * <p>
   * Any {@link MalformedObjectNameException} is treated as a programming error
   * and wrapped into {@link IllegalStateException}.
   *
   * @param name hard-coded {@link ObjectName} string
   * @return constructed {@link ObjectName}
   */
  private static ObjectName fixedObjectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      // This should never happen for hard-coded names; if it does,
      // fail fast with a clear message.
      throw new IllegalStateException("Invalid hard-coded ObjectName: " + name, e);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void registerStandardMBeanSafely(Object bean, Class<?> mbeanInterface, ObjectName objectName) {
    try {
      StandardMBean wrapped = new StandardMBean(bean, (Class) mbeanInterface);
      svr.registerMBean(wrapped, objectName);
    } catch (Exception e) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_FIXED_MBEAN_REGISTRATION_FAILURE,
          e,
          () -> "[node-metrics-agent] failed to register fixed MBean: " + objectName
      );
    }
  }

  /**
   * Unregister filesystem MBeans for paths that are no longer configured.
   * <p>
   * Any failures during unregistration are logged and ignored so that the
   * agent can continue operating.
   *
   * @param newPaths normalized set of currently configured paths
   */
  private static void unregisterRemovedFsBeans(LinkedHashSet<String> newPaths) {
    Iterator<Map.Entry<String, FsEntry>> it = fsMap.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, FsEntry> e = it.next();
      if (!newPaths.contains(e.getKey())) {
        try {
          svr.unregisterMBean(e.getValue().getOn());
          LOGGER.log(Level.INFO, "Unregistered MBean: {0}", e.getValue().getOn());
        } catch (InstanceNotFoundException | MBeanRegistrationException | RuntimeException ignore) {
          // Already unregistered or unregistration failed; safe to proceed.
        }
        it.remove();
      }
    }
  }

  private static void initializeRefreshEngine() {
    synchronized (APPLY_LOCK) {
      if (refreshEngine != null) {
        return;
      }
      refreshEngine = new MetricsRefreshEngine(
          REFRESH_DISPATCH_INTERVAL_MS,
          REFRESH_QUEUE_CAPACITY,
          MetricsAgent::transitionTelemetryMode
      );
      updateRefreshEngineTasksLocked();
      refreshEngine.start();
    }
  }

  private static void updateRefreshEngineTasksLocked() {
    if (refreshEngine == null) {
      return;
    }
    List<MetricsRefreshEngine.RefreshTask> tasks = buildRefreshTasksLocked();
    refreshEngine.setTasks(tasks);
  }

  private static List<MetricsRefreshEngine.RefreshTask> buildRefreshTasksLocked() {
    List<MetricsRefreshEngine.RefreshTask> tasks = new ArrayList<>();
    addHighPriorityTask(tasks, "cgroup-mem", cgroupMemBean);
    addHighPriorityTask(tasks, "cpu", cpuBean);
    addHighPriorityTask(tasks, "fd", fdBean);
    addHighPriorityTask(tasks, "io-rates", ioRatesBean);
    addHighPriorityTask(tasks, "node-mem", nodeMemBean);
    addHighPriorityTask(tasks, "os-info", osInfoBean);
    addHighPriorityTask(tasks, "os-runtime", osRuntimeBean);

    for (Map.Entry<String, FsEntry> entry : fsMap.entrySet()) {
      FsMetrics fsBean = entry.getValue().getBean();
      if (fsBean != null) {
        fsBean.setReadRefreshEnabled(false);
        tasks.add(new MetricsRefreshEngine.RefreshTask("fs:" + entry.getKey(), fsBean, true));
      }
    }
    return tasks;
  }

  private static void addHighPriorityTask(
      List<MetricsRefreshEngine.RefreshTask> tasks,
      String name,
      co.pletor.nodemetrics.metrics.RefreshManagedMetric metric
  ) {
    if (metric == null) {
      return;
    }
    metric.setReadRefreshEnabled(false);
    tasks.add(new MetricsRefreshEngine.RefreshTask(name, metric, false));
  }


  /**
   * Apply a new configuration to the running agent:
   * <ul>
   *   <li>Reconcile filesystem MBeans (add / remove)</li>
   * </ul>
   * The entire operation is serialized using {@link #APPLY_LOCK}.
   *
   * @param newCfg the configuration to apply
   * @throws InstanceAlreadyExistsException if an MBean with the same name is already registered
   * @throws MBeanRegistrationException     if an MBean cannot be registered or unregistered
   * @throws NotCompliantMBeanException     if an MBean does not comply with JMX requirements
   */
  private static void applyConfig(Config newCfg) {
    if (newCfg == null) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_NULL_CONFIG_APPLIED,
          () -> "[node-metrics-agent] null config received, applying defaults"
      );
      newCfg = Config.defaults();
    }

    synchronized (APPLY_LOCK) {
      // 1) Build the path set (always including "/").
      LinkedHashSet<String> requestedPaths = buildPathSet(newCfg);
      int maxPartitions = resolveMaxFsPartitions(newCfg);
      LinkedHashSet<String> newPaths = applyPartitionDedupAndCap(requestedPaths, maxPartitions);

      // 2) Ensure beans exist for all configured paths.
      registerOrReuseFsBeans(newPaths);

      // 3) Unregister beans for paths no longer configured.
      unregisterRemovedFsBeans(newPaths);

      // 4) Update async refresh targets.
      updateRefreshEngineTasksLocked();

      // 5) Update current config and log.
      current = newCfg;
      LOGGER.log(Level.INFO, "[node-metrics-agent] config applied: {0}", current);
    }
  }

  /**
   * Resolve the configuration file path from agent arguments or known defaults.
   * <p>
   * Resolution strategy:
   * <ol>
   *   <li>If {@code agentArgs} is non-blank, treat it as an explicit config path.</li>
   *   <li>Otherwise, try known default locations:
   *     <ul>
   *       <li>{@code /monitor/node-metrics.yml}</li>
   *       <li>{@code ./config/node-metrics.yml}</li>
   *     </ul>
   *   </li>
   *   <li>If no file is found, return {@code null}.</li>
   * </ol>
   *
   * @param agentArgs optional agent argument specifying the config path
   * @return a path to an existing configuration file, or {@code null} if none is found
   */
  private static Path resolveConfigPath(String agentArgs) {
    // 1) Explicit path from agent arguments
    if (agentArgs != null && !agentArgs.isBlank()) {
      return Paths.get(agentArgs.trim());
    }

    // 2) Known default locations
    String[] candidates = {
        "/monitor/node-metrics.yml",
        "./config/node-metrics.yml"
    };

    for (String c : candidates) {
      Path p = Paths.get(c);
      if (Files.isRegularFile(p)) {
        return p;
      }
    }

    // 3) No config file found
    return null;
  }

  static void transitionTelemetryMode(TelemetryMode nextMode) {
    if (nextMode != null) {
      TELEMETRY_MODE_STATE.transitionTo(nextMode);
    }
  }

  static TelemetryMode currentTelemetryMode() {
    return TELEMETRY_MODE_STATE.current();
  }

  private static MetricsRefreshEngine refreshEngineInstance() {
    return refreshEngine;
  }

  static long throttledLoggerOverflowCount() {
    return THROTTLED_LOGGER.overflowCount();
  }

  static void stopRefreshEngineForTest() {
    synchronized (APPLY_LOCK) {
      if (refreshEngine != null) {
        refreshEngine.stop();
        refreshEngine = null;
      }
    }
  }
}
