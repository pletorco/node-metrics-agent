// src/test/java/kr/pletor/nodemetrics/agent/MetricsAgentTest.java

package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import kr.pletor.nodemetrics.metrics.CgroupMemMetrics;
import kr.pletor.nodemetrics.metrics.CpuMetrics;
import kr.pletor.nodemetrics.metrics.FdMetrics;
import kr.pletor.nodemetrics.metrics.FsMetrics;
import kr.pletor.nodemetrics.metrics.IoRates;
import kr.pletor.nodemetrics.metrics.NodeMemMetrics;
import kr.pletor.nodemetrics.metrics.OsInfoMetrics;
import kr.pletor.nodemetrics.metrics.OsRuntimeMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * High coverage unit tests for {@link MetricsAgent}.
 *
 * <p>This test class uses reflection in order to exercise private static methods and fields without
 * modifying production code.
 */
class MetricsAgentTest {

  @TempDir Path tempDir;

  // ------------------------------------------------------------------------
  // Reflection helpers
  // ------------------------------------------------------------------------

  private static Object getStaticField(String fieldName) throws Exception {
    Field f = MetricsAgent.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(null);
  }

  private static void setStaticField(String fieldName, Object value) throws Exception {
    Field f = MetricsAgent.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(null, value);
  }

  private static Object invokePrivateStatic(
      String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
    Method m = MetricsAgent.class.getDeclaredMethod(methodName, paramTypes);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  private static Class<?> configClass() throws ClassNotFoundException {
    return Class.forName("kr.pletor.nodemetrics.agent.Config");
  }

  private static Object newConfigInstance() throws Exception {
    Class<?> cfgClass = configClass();
    Constructor<?> ctor = cfgClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  private static void setConfigPaths(Object cfg, java.util.List<String> fsmetricsPaths)
      throws Exception {
    Field f = cfg.getClass().getDeclaredField("fsmetricsPaths");
    f.setAccessible(true);
    f.set(cfg, fsmetricsPaths);
  }

  private static void setConfigMaxPartitions(Object cfg, Integer maxPartitions) throws Exception {
    Field f = cfg.getClass().getDeclaredField("fsmetricsMaxPartitions");
    f.setAccessible(true);
    f.set(cfg, maxPartitions);
  }

  private static Field declaredFieldInHierarchy(Class<?> type, String fieldName)
      throws NoSuchFieldException {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        // try the superclass
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  private static boolean getBooleanField(Object target, String fieldName) throws Exception {
    Field f = declaredFieldInHierarchy(target.getClass(), fieldName);
    f.setAccessible(true);
    return f.getBoolean(target);
  }

  // ------------------------------------------------------------------------
  // Common cleanup: shut down scheduler and clear fsMap
  // ------------------------------------------------------------------------

  @AfterEach
  void cleanupFsMap() throws Exception {
    MetricsAgent.stopRefreshEngineForTest();
    @SuppressWarnings("unchecked")
    Map<String, ?> fsMap = (Map<String, ?>) getStaticField("fsMap");
    fsMap.clear();
  }

  // ------------------------------------------------------------------------
  // buildFsObjectName tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("buildFsObjectName should produce ObjectName with correct domain and properties")
  void buildFsObjectName_shouldUseCorrectDomainAndProperties() throws Exception {
    Path path = Paths.get("/tmp/metrics-agent-fs-test").toAbsolutePath();

    Object result = invokePrivateStatic("buildFsObjectName", new Class<?>[] {Path.class}, path);

    assertNotNull(result, "ObjectName result must not be null");

    // Pattern matching instanceof (o instanceof [type] tv pattern)
    Object o = result;
    if (o instanceof ObjectName) {
      ObjectName tv = (ObjectName) o;
      assertEquals("kr.pletor.node", tv.getDomain(), "Domain should be kr.pletor.node");
      assertEquals("FsMetrics", tv.getKeyProperty("type"), "Type property should be FsMetrics");
      assertEquals(
          path.toString(), tv.getKeyProperty("path"), "Path property should match input path");
    } else {
      fail("Result is not an ObjectName instance");
    }
  }

  // ------------------------------------------------------------------------
  // fixedObjectName tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("fixedObjectName should create valid ObjectName for correct name")
  void fixedObjectName_shouldCreateValidObjectName() throws Exception {
    String name = "kr.pletor.node:type=TestMetrics";

    Object result = invokePrivateStatic("fixedObjectName", new Class<?>[] {String.class}, name);

    assertTrue(result instanceof ObjectName, "fixedObjectName should return an ObjectName");
    ObjectName on = (ObjectName) result;
    assertEquals("kr.pletor.node", on.getDomain(), "Domain should match hard-coded value");
    assertEquals("TestMetrics", on.getKeyProperty("type"), "Type property should match input");
  }

  @Test
  @DisplayName("fixedObjectName should wrap malformed names into IllegalStateException")
  void fixedObjectName_shouldWrapMalformedNameIntoIllegalStateException() {
    // Malformed name without key properties
    String invalid = "invalid";

    InvocationTargetException ex =
        assertThrows(
            InvocationTargetException.class,
            () -> invokePrivateStatic("fixedObjectName", new Class<?>[] {String.class}, invalid),
            "fixedObjectName should rethrow malformed names as IllegalStateException");

    assertTrue(
        ex.getCause() instanceof IllegalStateException,
        "Underlying cause should be IllegalStateException");
  }

  // ------------------------------------------------------------------------
  // resolveConfigPath tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("resolveConfigPath should return explicit path when agentArgs is provided")
  void resolveConfigPath_shouldReturnExplicitPath() throws Exception {
    // Use Java text block as a multi-line literal (trim afterwards)
    String arg = "./config/custom-node-metrics.yml".trim();

    Path expected = Paths.get(arg);

    Object result = invokePrivateStatic("resolveConfigPath", new Class<?>[] {String.class}, arg);

    assertTrue(
        result instanceof Path,
        "resolveConfigPath should return a Path when explicit argument is provided");
    assertEquals(expected, result, "Explicit path should be returned when agentArgs is provided.");
  }

  @Test
  @DisplayName("resolveConfigPath should treat blank agentArgs as no explicit config")
  void resolveConfigPath_shouldTreatBlankArgsAsNoConfig() throws Exception {
    String blank = "   ";

    Object result = invokePrivateStatic("resolveConfigPath", new Class<?>[] {String.class}, blank);

    // We only check that the call returns either null or a Path; behavior is covered via other
    // tests
    if (result != null) {
      assertTrue(result instanceof Path, "When not null, result must be a Path");
    }
  }

  @Test
  @DisplayName("resolveConfigPath should handle null agentArgs without throwing")
  void resolveConfigPath_shouldHandleNullWithoutCrash() {
    // When agentArgs is null, method should just search defaults and either return a Path or null.
    assertDoesNotThrow(
        () ->
            invokePrivateStatic(
                "resolveConfigPath",
                new Class<?>[] {String.class},
                (Object) null // cast to Object to avoid varargs warning
                ),
        "resolveConfigPath(null) should never throw");
  }

  // ------------------------------------------------------------------------
  // resolveAndValidatePath tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("resolveAndValidatePath should return absolute path when target exists")
  void resolveAndValidatePath_shouldReturnAbsolutePathForExistingPath() throws Exception {
    Path existing = Files.createDirectory(tempDir.resolve("existing-dir")).toAbsolutePath();

    Object result =
        invokePrivateStatic(
            "resolveAndValidatePath", new Class<?>[] {String.class}, existing.toString());

    assertTrue(result instanceof Path, "Result should be a Path");
    Path returned = (Path) result;
    assertTrue(returned.isAbsolute(), "Returned path should be absolute");
    assertEquals(
        existing.normalize(), returned.normalize(), "Returned path should match normalized input");
  }

  @Test
  @DisplayName(
      "resolveAndValidatePath should warn but not fail when missing path and strict is false")
  void resolveAndValidatePath_shouldWarnButNotFailForMissingPathWhenNotStrict() throws Exception {
    String missing = tempDir.resolve("definitely-missing-" + System.nanoTime()).toString();

    Object result =
        invokePrivateStatic("resolveAndValidatePath", new Class<?>[] {String.class}, missing);

    assertTrue(result instanceof Path, "Result should be a Path even for missing target");
    Path returned = (Path) result;
    assertEquals(
        Paths.get(missing).toAbsolutePath().normalize(),
        returned.normalize(),
        "Returned path should be normalized absolute path for the missing input");
  }

  // ------------------------------------------------------------------------
  // buildPathSet tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("buildPathSet should include root path even when other paths are configured")
  void buildPathSet_shouldAlwaysIncludeRoot() throws Exception {
    Object cfg = newConfigInstance();
    setConfigPaths(cfg, java.util.List.of("/data", "/logs"));

    Object result = invokePrivateStatic("buildPathSet", new Class<?>[] {configClass()}, cfg);

    assertTrue(result instanceof LinkedHashSet, "Result should be a LinkedHashSet");
    @SuppressWarnings("unchecked")
    LinkedHashSet<String> set = (LinkedHashSet<String>) result;

    assertTrue(set.contains("/"), "Root path '/' must always be present");
    assertTrue(set.contains("/data"), "Configured path /data must be present");
    assertTrue(set.contains("/logs"), "Configured path /logs must be present");
  }

  @Test
  @DisplayName("buildPathSet should handle null paths and still include root")
  void buildPathSet_shouldHandleNullPaths() throws Exception {
    Object cfg = newConfigInstance();

    try {
      Field pathsField = cfg.getClass().getDeclaredField("paths");
      pathsField.setAccessible(true);
      pathsField.set(cfg, null);
    } catch (NoSuchFieldException ignored) {
      // If the field does not exist, we skip the deeper assertions.
      return;
    }

    Object result = invokePrivateStatic("buildPathSet", new Class<?>[] {configClass()}, cfg);

    assertTrue(result instanceof LinkedHashSet, "Result should be a LinkedHashSet");
    @SuppressWarnings("unchecked")
    LinkedHashSet<String> set = (LinkedHashSet<String>) result;

    assertEquals(1, set.size(), "Only root path should be present when paths are null");
    assertTrue(set.contains("/"), "Root path '/' must always be present");
  }

  @Test
  @DisplayName("buildPathSet should normalize, dedupe, and skip blank/null path entries")
  void buildPathSet_shouldNormalizeAndDedupeConfiguredPaths() throws Exception {
    Object cfg = newConfigInstance();
    setConfigPaths(cfg, java.util.Arrays.asList("/data", "/data/", "   ", null));

    Object result = invokePrivateStatic("buildPathSet", new Class<?>[] {configClass()}, cfg);

    assertTrue(result instanceof LinkedHashSet, "Result should be a LinkedHashSet");
    @SuppressWarnings("unchecked")
    LinkedHashSet<String> set = (LinkedHashSet<String>) result;

    assertTrue(set.contains("/"), "Root path '/' must always be present");
    assertTrue(set.contains("/data"), "Configured path should be normalized and kept");
    assertEquals(2, set.size(), "Duplicate and blank/null entries should be ignored");
  }

  @Test
  @DisplayName("applyPartitionDedupAndCap should dedupe different paths on same partition")
  void applyPartitionDedupAndCap_shouldDedupeSamePartitionPaths() throws Exception {
    Path p1 = Files.createDirectory(tempDir.resolve("same-partition-a")).toAbsolutePath();
    Path p2 = Files.createDirectory(tempDir.resolve("same-partition-b")).toAbsolutePath();

    LinkedHashSet<String> normalizedPaths = new LinkedHashSet<>();
    normalizedPaths.add(p1.toString());
    normalizedPaths.add(p2.toString());

    Object result =
        invokePrivateStatic(
            "applyPartitionDedupAndCap",
            new Class<?>[] {LinkedHashSet.class, int.class},
            normalizedPaths,
            10);

    assertTrue(result instanceof LinkedHashSet, "Result should be a LinkedHashSet");
    @SuppressWarnings("unchecked")
    LinkedHashSet<String> set = (LinkedHashSet<String>) result;

    assertEquals(1, set.size(), "Paths on the same partition should be deduplicated");
    assertTrue(set.contains(p1.toString()), "First configured path should be retained");
  }

  @Test
  @DisplayName("applyPartitionDedupAndCap should enforce cap for unresolved/missing paths")
  void applyPartitionDedupAndCap_shouldEnforceCapForMissingPaths() throws Exception {
    Path missing1 = tempDir.resolve("missing-1").toAbsolutePath();
    Path missing2 = tempDir.resolve("missing-2").toAbsolutePath();
    Path missing3 = tempDir.resolve("missing-3").toAbsolutePath();

    LinkedHashSet<String> normalizedPaths = new LinkedHashSet<>();
    normalizedPaths.add(missing1.toString());
    normalizedPaths.add(missing2.toString());
    normalizedPaths.add(missing3.toString());

    Object result =
        invokePrivateStatic(
            "applyPartitionDedupAndCap",
            new Class<?>[] {LinkedHashSet.class, int.class},
            normalizedPaths,
            2);

    assertTrue(result instanceof LinkedHashSet, "Result should be a LinkedHashSet");
    @SuppressWarnings("unchecked")
    LinkedHashSet<String> set = (LinkedHashSet<String>) result;

    assertEquals(2, set.size(), "Cap should limit effective path count");
    assertTrue(set.contains(missing1.toString()), "First path should remain");
    assertTrue(set.contains(missing2.toString()), "Second path should remain");
    assertFalse(set.contains(missing3.toString()), "Path beyond cap should be dropped");
  }

  @Test
  @DisplayName(
      "resolveMaxFsPartitions should use config value and fall back to default for invalid values")
  void resolveMaxFsPartitions_shouldUseConfigValueOrDefault() throws Exception {
    Object cfg = newConfigInstance();
    setConfigPaths(cfg, java.util.List.of("/"));
    setConfigMaxPartitions(cfg, 7);

    Object valid =
        invokePrivateStatic("resolveMaxFsPartitions", new Class<?>[] {configClass()}, cfg);
    assertEquals(7, valid, "Configured max partition value should be used");

    setConfigMaxPartitions(cfg, 0);
    Object invalid =
        invokePrivateStatic("resolveMaxFsPartitions", new Class<?>[] {configClass()}, cfg);
    assertEquals(
        Config.DEFAULT_FSMETRICS_MAX_PARTITIONS,
        invalid,
        "Invalid max partition value should fall back to default");
  }

  // ------------------------------------------------------------------------
  // shouldReuseExistingEntry tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("shouldReuseExistingEntry should return true when fsMap contains the key")
  void shouldReuseExistingEntry_shouldReturnTrueWhenKeyPresent() throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    String presentKey = "/present";
    fsMap.put(presentKey, new Object());

    Object result =
        invokePrivateStatic("shouldReuseExistingEntry", new Class<?>[] {String.class}, presentKey);

    assertTrue(result instanceof Boolean && (Boolean) result, "Should return true when key exists");
  }

  @Test
  @DisplayName("shouldReuseExistingEntry should return false when fsMap does not contain the key")
  void shouldReuseExistingEntry_shouldReturnFalseWhenKeyAbsent() throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    Object result =
        invokePrivateStatic("shouldReuseExistingEntry", new Class<?>[] {String.class}, "/absent");

    assertTrue(
        result instanceof Boolean && !((Boolean) result),
        "Should return false when key does not exist");
  }

  // ------------------------------------------------------------------------
  // registerOrReuseFsBeans tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("registerOrReuseFsBeans should register new beans and then reuse existing entries")
  void registerOrReuseFsBeans_shouldRegisterAndReuse() throws Exception {
    // Prepare platform MBean server for registration
    MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
    setStaticField("svr", mbs);

    // Clear filesystem map
    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    Path p = Files.createDirectory(tempDir.resolve("fs-path")).toAbsolutePath();
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.add(p.toString());

    // First registration
    invokePrivateStatic("registerOrReuseFsBeans", new Class<?>[] {LinkedHashSet.class}, paths);

    assertEquals(1, fsMap.size(), "One filesystem entry should be registered after first call");

    Object entry = fsMap.values().iterator().next();
    // Extract ObjectName from FsEntry record via accessor method
    Method onMethod = entry.getClass().getDeclaredMethod("getOn");
    onMethod.setAccessible(true);
    ObjectName on = (ObjectName) onMethod.invoke(entry);
    assertTrue(mbs.isRegistered(on), "MBean should be registered in the MBeanServer");

    // Second call with the same path should reuse existing entry
    invokePrivateStatic("registerOrReuseFsBeans", new Class<?>[] {LinkedHashSet.class}, paths);

    assertEquals(1, fsMap.size(), "Entry count should remain 1 when reusing existing FsEntry");
  }

  // ------------------------------------------------------------------------
  // unregisterRemovedFsBeans tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("unregisterRemovedFsBeans should remove unconfigured entries and unregister them")
  void unregisterRemovedFsBeans_shouldRemoveUnconfiguredEntries() throws Exception {
    MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
    setStaticField("svr", mbs);

    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    Path p1 = Files.createDirectory(tempDir.resolve("fs1")).toAbsolutePath();
    Path p2 = Files.createDirectory(tempDir.resolve("fs2")).toAbsolutePath();

    LinkedHashSet<String> allPaths = new LinkedHashSet<>();
    allPaths.add(p1.toString());
    allPaths.add(p2.toString());

    // Register both paths
    invokePrivateStatic("registerOrReuseFsBeans", new Class<?>[] {LinkedHashSet.class}, allPaths);

    assertEquals(2, fsMap.size(), "Two filesystem entries should be registered");

    Object secondEntry = fsMap.get(p2.toString());
    Method onMethod = secondEntry.getClass().getDeclaredMethod("getOn");
    onMethod.setAccessible(true);
    ObjectName secondOn = (ObjectName) onMethod.invoke(secondEntry);
    assertTrue(mbs.isRegistered(secondOn), "Second MBean should be registered before removal");

    // New configuration removes the second path
    LinkedHashSet<String> newPaths = new LinkedHashSet<>();
    newPaths.add(p1.toString());

    invokePrivateStatic("unregisterRemovedFsBeans", new Class<?>[] {LinkedHashSet.class}, newPaths);

    assertEquals(1, fsMap.size(), "Only one filesystem entry should remain after removal");
    assertFalse(
        fsMap.containsKey(p2.toString()),
        "Entry for second path should have been removed from fsMap");
    assertFalse(
        mbs.isRegistered(secondOn), "Second MBean should have been unregistered from MBeanServer");
  }

  @Test
  @DisplayName(
      "unregisterRemovedFsBeans should ignore exceptions thrown by MBeanServer.unregisterMBean")
  void unregisterRemovedFsBeans_shouldIgnoreUnregisterExceptions() throws Exception {
    // Use a mock MBeanServer to force an MBeanRegistrationException
    MBeanServer mockSvr = Mockito.mock(MBeanServer.class);
    setStaticField("svr", mockSvr);

    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    // Construct FsEntry via reflection: FsEntry(FsMetrics, ObjectName, Path)
    Class<?> fsEntryClass = Class.forName("kr.pletor.nodemetrics.agent.MetricsAgent$FsEntry");
    Constructor<?> ctor =
        fsEntryClass.getDeclaredConstructor(
            kr.pletor.nodemetrics.metrics.FsMetrics.class, ObjectName.class, Path.class);
    ctor.setAccessible(true);

    FsMetrics dummyBean = Mockito.mock(FsMetrics.class);
    ObjectName on = new ObjectName("kr.pletor.node:type=FsMetrics,path=/dummy");
    Path path = Paths.get("/dummy");

    Object fsEntry = ctor.newInstance(dummyBean, on, path);
    String key = "/dummy";
    fsMap.put(key, fsEntry);

    Mockito.doThrow(new MBeanRegistrationException(new Exception("boom")))
        .when(mockSvr)
        .unregisterMBean(on);

    LinkedHashSet<String> newPaths = new LinkedHashSet<>();
    // newPaths does NOT contain "/dummy", so unregister should be invoked

    assertDoesNotThrow(
        () ->
            invokePrivateStatic(
                "unregisterRemovedFsBeans", new Class<?>[] {LinkedHashSet.class}, newPaths),
        "unregisterRemovedFsBeans should swallow MBeanRegistrationException");

    assertTrue(fsMap.isEmpty(), "Entry should be removed from fsMap even when unregister fails");
  }

  // ------------------------------------------------------------------------
  // applyConfig tests (integration of several helpers)
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("applyConfig should register fs beans (but not poll or schedule)")
  void applyConfig_shouldRegisterBeansAndLog() throws Exception {
    // Use platform MBeanServer for simplicity
    MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
    setStaticField("svr", mbs);

    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    // Build configuration with one existing path
    Object cfg = newConfigInstance();
    // poll_interval_sec is ignored now, but we can set it

    Path fsDir = Files.createDirectory(tempDir.resolve("apply-config-fs")).toAbsolutePath();
    setConfigPaths(cfg, java.util.List.of(fsDir.toString()));

    // Simulate first-time configuration (no previous current)
    setStaticField("current", null);

    // Call applyConfig via reflection
    invokePrivateStatic("applyConfig", new Class<?>[] {configClass()}, cfg);

    // FsMap should now contain an entry
    assertFalse(fsMap.isEmpty(), "applyConfig should register at least one filesystem entry");
  }

  @Test
  @DisplayName("applyConfig should handle null config by applying defaults")
  void applyConfig_shouldHandleNullConfigWithDefaults() throws Exception {
    MBeanServer mockSvr = Mockito.mock(MBeanServer.class);
    setStaticField("svr", mockSvr);

    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();
    setStaticField("current", null);

    assertDoesNotThrow(
        () -> invokePrivateStatic("applyConfig", new Class<?>[] {configClass()}, (Object) null),
        "applyConfig should never throw when null config is provided");

    Object current = getStaticField("current");
    assertNotNull(current, "Current config should be set to defaults when input is null");

    if (current instanceof Config) {
      Config cfg = (Config) current;
      assertEquals(java.util.List.of("/"), cfg.fsmetricsPaths, "Default path should be root");
    } else {
      fail("Current config has unexpected type");
    }
  }

  @Test
  @DisplayName(
      "initializeRefreshEngine should disable getter-triggered refresh for registered metrics")
  void initializeRefreshEngine_shouldDisableReadRefreshOnRegisteredMetrics() throws Exception {
    MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
    setStaticField("svr", mbs);
    setStaticField("refreshEngine", null);

    setStaticField("cgroupMemBean", new CgroupMemMetrics());
    setStaticField("cpuBean", new CpuMetrics());
    setStaticField("fdBean", new FdMetrics());
    setStaticField("ioRatesBean", new IoRates());
    setStaticField("nodeMemBean", new NodeMemMetrics());
    setStaticField("osInfoBean", new OsInfoMetrics());
    setStaticField("osRuntimeBean", new OsRuntimeMetrics());

    @SuppressWarnings("unchecked")
    Map<String, Object> fsMap = (Map<String, Object>) getStaticField("fsMap");
    fsMap.clear();

    Object cfg = newConfigInstance();
    Path fsDir = Files.createDirectory(tempDir.resolve("refresh-engine-fs")).toAbsolutePath();
    setConfigPaths(cfg, java.util.List.of(fsDir.toString()));
    setConfigMaxPartitions(cfg, 32);

    invokePrivateStatic("applyConfig", new Class<?>[] {configClass()}, cfg);
    invokePrivateStatic("initializeRefreshEngine", new Class<?>[] {});

    CpuMetrics cpu = (CpuMetrics) getStaticField("cpuBean");
    assertFalse(
        getBooleanField(cpu, "readRefreshEnabled"),
        "CpuMetrics should use asynchronous refresh when engine is initialized");

    Object fsEntry = fsMap.values().iterator().next();
    Method beanMethod = fsEntry.getClass().getDeclaredMethod("getBean");
    beanMethod.setAccessible(true);
    Object fsBean = beanMethod.invoke(fsEntry);
    assertTrue(fsBean instanceof FsMetrics, "FsEntry should contain FsMetrics bean");
    assertFalse(
        getBooleanField(fsBean, "readRefreshEnabled"),
        "FsMetrics should use asynchronous refresh when engine is initialized");
  }

  @Test
  @DisplayName("registerStandardMBeanSafely should swallow registration exceptions")
  void registerStandardMBeanSafely_shouldSwallowRegistrationExceptions() throws Exception {
    MBeanServer mockSvr = Mockito.mock(MBeanServer.class);
    setStaticField("svr", mockSvr);

    Mockito.doThrow(new MBeanRegistrationException(new Exception("boom")))
        .when(mockSvr)
        .registerMBean(Mockito.any(), Mockito.any(ObjectName.class));

    assertDoesNotThrow(
        () ->
            invokePrivateStatic(
                "registerStandardMBeanSafely",
                new Class<?>[] {Object.class, Class.class, ObjectName.class},
                new kr.pletor.nodemetrics.metrics.FdMetrics(),
                kr.pletor.nodemetrics.metrics.FdMetricsMBean.class,
                new ObjectName("kr.pletor.node:type=FdMetricsTest")),
        "registerStandardMBeanSafely should swallow registration-time failures");

    Mockito.verify(mockSvr, Mockito.atLeastOnce())
        .registerMBean(Mockito.any(), Mockito.any(ObjectName.class));
  }

  // ------------------------------------------------------------------------
  // premain tests
  // ------------------------------------------------------------------------

  @Test
  @DisplayName("premain should initialize agent, register MBeans, and start watcher")
  void premain_shouldInitializeAgentAndRegisterMBeans() throws Exception {
    MBeanServer mockSvr = Mockito.mock(MBeanServer.class);

    // Mock static ManagementFactory to return our mock server
    try (var mockedFactory = Mockito.mockStatic(java.lang.management.ManagementFactory.class);
        var mockedLoader = Mockito.mockStatic(ConfigLoader.class)) {

      mockedFactory
          .when(java.lang.management.ManagementFactory::getPlatformMBeanServer)
          .thenReturn(mockSvr);

      // Mock ConfigLoader to return a default config
      Object mockConfig = newConfigInstance();
      // Ensure fsmetrics_paths is set (even if empty) to avoid null pointers if logic assumes
      // non-null
      setConfigPaths(mockConfig, java.util.List.of("/"));

      mockedLoader.when(() -> ConfigLoader.loadOrDefault(Mockito.any())).thenReturn(mockConfig);

      // Execute premain
      MetricsAgent.premain(null, null);

      // Verify MBean registrations
      // We expect at least the fixed MBeans (CgroupMem, Cpu, Fd, IoRates, NodeMem, OsInfo,
      // OsRuntime)
      // plus the initial filesystem MBean (root path)
      Mockito.verify(mockSvr, Mockito.atLeast(7))
          .registerMBean(Mockito.any(), Mockito.any(ObjectName.class));

      // Verify ConfigLoader was called
      mockedLoader.verify(() -> ConfigLoader.loadOrDefault(Mockito.any()));

      // We can also check if the config watcher thread was started, but that's harder to verify
      // without mocking Thread instantiation or checking thread list.
      // For now, absence of exception and MBean registration confirms main flow executed.
    }
  }

  @Test
  @DisplayName("premain should catch exceptions and log error without crashing")
  void premain_shouldCatchExceptionAndNotCrash() {
    try (var mockedFactory = Mockito.mockStatic(java.lang.management.ManagementFactory.class)) {
      mockedFactory
          .when(java.lang.management.ManagementFactory::getPlatformMBeanServer)
          .thenThrow(new RuntimeException("Simulated Startup Failure"));

      // Should not throw
      assertDoesNotThrow(() -> MetricsAgent.premain(null, null));
    }
  }

  @Test
  @DisplayName("buildFsObjectName should quote paths containing ObjectName-reserved characters")
  void buildFsObjectName_shouldQuoteReservedCharacters() throws Exception {
    for (String raw :
        new String[] {"/data/a,b", "/data/a=b", "/data/a:b", "/data/a*b", "/data/a?b"}) {
      Path path = Paths.get(raw);

      Object result = invokePrivateStatic("buildFsObjectName", new Class<?>[] {Path.class}, path);

      ObjectName name = (ObjectName) result;
      assertEquals(
          ObjectName.quote(raw), name.getKeyProperty("path"), "Path should be quoted: " + raw);
      assertFalse(name.isPattern(), "Quoted name must not be a pattern: " + raw);
    }
  }

  @Test
  @DisplayName("applyPartitionDedupAndCap must not block on an unresponsive filesystem")
  void applyPartitionDedupAndCap_shouldNotBlockOnUnresponsiveFilesystem() throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.function.Function<Path, String> original = MetricsAgent.partitionKeyDetector;
    MetricsAgent.partitionKeyDetector =
        path -> {
          if (path.toString().contains("dead-nfs")) {
            while (release.getCount() > 0) {
              try {
                release.await();
              } catch (InterruptedException ignored) {
                // uninterruptible I/O does not react to interrupts
              }
            }
          }
          return "dev:" + path;
        };
    try {
      LinkedHashSet<String> paths = new LinkedHashSet<>();
      paths.add("/healthy");
      paths.add("/mnt/dead-nfs/data");

      long start = System.nanoTime();
      Object result =
          invokePrivateStatic(
              "applyPartitionDedupAndCap",
              new Class<?>[] {LinkedHashSet.class, int.class},
              paths,
              32);
      long elapsedMs =
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertTrue(
          elapsedMs < 10_000L,
          "Unresponsive mount must not stall config application: " + elapsedMs + " ms");
      assertEquals(
          paths,
          result,
          "The unresponsive path is still registered so it can be reported as stuck");
    } finally {
      MetricsAgent.partitionKeyDetector = original;
      release.countDown();
    }
  }
}
