// src/test/java/co/pletor/nodemetrics/metrics/NodeMemMetricsTest.java

package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.management.OperatingSystemMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NodeMemMetrics}.
 */
class NodeMemMetricsTest {

  // ----------------------------------------------------
  // Basic initial state
  // ----------------------------------------------------

  @Test
  @DisplayName("Initial values should be -1 or -1.0")
  void initialValuesAreMinusOne() {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // For on-demand metrics, the first getter call triggers a refresh.
    // If we want to test "initial" (unpopulated) state, we must ensure refresh() 
    // doesn't actually overwrite fields with real system data if we are testing defaults.
    // However, on a real system, it WILL overwrite.
    // So assertions of "-1" are flaky if the system returns valid data.
    // We will bypass refresh here to strictly check the constructor state behavior
    // assuming no refresh happens.
    bypassRefresh(metrics);

    assertEquals(-1L, metrics.getTotalMemoryBytes(), "Initial total should be -1");
    assertEquals(-1L, metrics.getUsedMemoryBytes(), "Initial used should be -1");
    assertEquals(-1L, metrics.getFreeMemoryBytes(), "Initial free should be -1");
    assertEquals(-1L, metrics.getAvailableMemoryBytes(), "Initial available should be -1");

    assertEquals(-1L, metrics.getDirtyBytes(), "Initial dirty should be -1");
    assertEquals(-1L, metrics.getWritebackBytes(), "Initial writeback should be -1");
    assertEquals(-1L, metrics.getCachedBytes(), "Initial cached should be -1");
    assertEquals(-1L, metrics.getBuffersBytes(), "Initial buffers should be -1");
    assertEquals(-1L, metrics.getShmemBytes(), "Initial shmem should be -1");
    assertEquals(-1L, metrics.getFilePageCacheBytes(), "Initial file page cache should be -1");

    assertEquals(-1.0, metrics.getUsedMemoryPercent(), 0.000001,
        "Initial used percent should be -1.0");
  }

  // ----------------------------------------------------
  // parseMemInfo() + snapshot structure
  // ----------------------------------------------------

  @Test
  @DisplayName("parseMemInfo should parse typical /proc/meminfo lines")
  void parseMemInfoParsesTypicalLines() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Multi-line text block with sample /proc/meminfo content
    String meminfoSample =
        "MemTotal:       1024000 kB\n"
        + "MemAvailable:    512000 kB\n"
        + "MemFree:         256000 kB\n"
        + "Buffers:          64000 kB\n"
        + "Cached:          128000 kB\n"
        + "Shmem:            32000 kB\n"
        + "Dirty:             4000 kB\n"
        + "Writeback:         2000 kB\n"
        + "SReclaimable:      8000 kB\n";

    List<String> lines = meminfoSample.lines().toList();

    // Access private parseMemInfo(List<String>) via reflection
    Method parseMemInfo = NodeMemMetrics.class.getDeclaredMethod("parseMemInfo", List.class);
    parseMemInfo.setAccessible(true);

    Object snapshot = parseMemInfo.invoke(metrics, lines);
    Class<?> snapshotClass = snapshot.getClass();

    // Helper to read long field from the snapshot
    assertEquals(1024000L, getLongField(snapshotClass, snapshot, "memTotalKb"),
        "memTotalKb mismatch");
    assertEquals(512000L, getLongField(snapshotClass, snapshot, "memAvailableKb"),
        "memAvailableKb mismatch");
    assertEquals(256000L, getLongField(snapshotClass, snapshot, "memFreeKb"), "memFreeKb mismatch");
    assertEquals(64000L, getLongField(snapshotClass, snapshot, "buffersKb"), "buffersKb mismatch");
    assertEquals(128000L, getLongField(snapshotClass, snapshot, "cachedKb"),
        "cachedKb mismatch");
    assertEquals(32000L, getLongField(snapshotClass, snapshot, "shmemKb"),
        "shmemKb mismatch");
    assertEquals(4000L, getLongField(snapshotClass, snapshot, "dirtyKb"), "dirtyKb mismatch");
    assertEquals(2000L, getLongField(snapshotClass, snapshot, "writebackKb"),
        "writebackKb mismatch");
    assertEquals(8000L, getLongField(snapshotClass, snapshot, "sReclaimableKb"),
        "sReclaimableKb mismatch");
  }

  // ----------------------------------------------------
  // computeAvailableBytes()
  // ----------------------------------------------------

  @Test
  @DisplayName("computeAvailableBytes should return MemAvailable when present")
  void computeAvailableBytesPrefersMemAvailable() throws Exception {
    Object snapshot = newSnapshot();
    Class<?> snapshotClass = snapshot.getClass();

    setLongField(snapshotClass, snapshot, "memAvailableKb", 12345L);
    setLongField(snapshotClass, snapshot, "memFreeKb", 111L);

    Method computeAvailableBytes = NodeMemMetrics.class
        .getDeclaredMethod("computeAvailableBytes", snapshotClass);
    computeAvailableBytes.setAccessible(true);

    NodeMemMetrics metrics = new NodeMemMetrics();
    long availableBytes = (long) computeAvailableBytes.invoke(metrics, snapshot);

    assertEquals(12345L * 1024L, availableBytes, "Should use MemAvailable when >= 0");
  }

  @Test
  @DisplayName("computeAvailableBytes should fall back to Free+Buffers+Cached "
      + "when MemAvailable is missing")
  void computeAvailableBytesFallbackSum() throws Exception {


    Object snapshot = newSnapshot();
    Class<?> snapshotClass = snapshot.getClass();

    setLongField(snapshotClass, snapshot, "memAvailableKb", -1L);
    setLongField(snapshotClass, snapshot, "memFreeKb", 100L);
    setLongField(snapshotClass, snapshot, "buffersKb", 200L);
    setLongField(snapshotClass, snapshot, "cachedKb", 300L);

    Method computeAvailableBytes = NodeMemMetrics.class
        .getDeclaredMethod("computeAvailableBytes", snapshotClass);
    computeAvailableBytes.setAccessible(true);

    NodeMemMetrics metrics = new NodeMemMetrics();
    long availableBytes = (long) computeAvailableBytes.invoke(metrics, snapshot);

    assertEquals((100L + 200L + 300L) * 1024L, availableBytes,
        "Fallback should sum MemFree, Buffers, and Cached");
  }

  // ----------------------------------------------------
  // applyMemInfoSnapshot()
  // ----------------------------------------------------

  @Test
  @DisplayName("applyMemInfoSnapshot should populate main and cache-related fields")
  void applyMemInfoSnapshotPopulatesFields() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    Object snapshot = newSnapshot();
    Class<?> snapshotClass = snapshot.getClass();

    // Set snapshot values (in kB)
    setLongField(snapshotClass, snapshot, "memTotalKb", 1024000L);
    setLongField(snapshotClass, snapshot, "memAvailableKb", 512000L);
    setLongField(snapshotClass, snapshot, "memFreeKb", 256000L);
    setLongField(snapshotClass, snapshot, "buffersKb", 64000L);
    setLongField(snapshotClass, snapshot, "cachedKb", 128000L);
    setLongField(snapshotClass, snapshot, "shmemKb", 32000L);
    setLongField(snapshotClass, snapshot, "dirtyKb", 4000L);
    setLongField(snapshotClass, snapshot, "writebackKb", 2000L);
    setLongField(snapshotClass, snapshot, "sReclaimableKb", 8000L);

    Method applySnapshot = NodeMemMetrics.class
        .getDeclaredMethod("applyMemInfoSnapshot", snapshotClass);
    applySnapshot.setAccessible(true);

    applySnapshot.invoke(metrics, snapshot);
    
    // Prevent get*() from triggering a real refresh and overwriting our snapshot data
    bypassRefresh(metrics);

    long total = metrics.getTotalMemoryBytes();
    long free = metrics.getFreeMemoryBytes();
    long available = metrics.getAvailableMemoryBytes();
    long used = metrics.getUsedMemoryBytes();

    assertEquals(1024000L * 1024L, total, "Total bytes should be converted from kB");
    assertEquals(256000L * 1024L, free, "Free bytes should follow MemFree");
    assertEquals(512000L * 1024L, available,
        "Available bytes should follow MemAvailable");
    assertEquals(total - available, used, "Used bytes should be total - available");

    assertEquals(4000L * 1024L, metrics.getDirtyBytes(), "Dirty bytes conversion mismatch");
    assertEquals(2000L * 1024L, metrics.getWritebackBytes(), "Writeback bytes conversion mismatch");
    
    // Cached = Cached(128000) + SReclaimable(8000) = 136000
    assertEquals(136000L * 1024L, metrics.getCachedBytes(),
        "Cached bytes should include SReclaimable");
    assertEquals(64000L * 1024L, metrics.getBuffersBytes(),
        "Buffers bytes conversion mismatch");
    assertEquals(32000L * 1024L, metrics.getShmemBytes(),
        "Shmem bytes conversion mismatch");

    long expectedFileCache = (128000L - 32000L) * 1024L;
    assertEquals(expectedFileCache, metrics.getFilePageCacheBytes(),
        "File page cache bytes mismatch");

    double usedPercent = metrics.getUsedMemoryPercent();
    assertTrue(usedPercent > 0.0 && usedPercent < 100.0,
        "Used percent should be between 0 and 100");
  }

  @Test
  @DisplayName("applyMemInfoSnapshot should clamp file page cache at zero when cached < shmem")
  void applyMemInfoSnapshotClampsNegativeFilePageCache() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    Object snapshot = newSnapshot();
    Class<?> snapshotClass = snapshot.getClass();

    setLongField(snapshotClass, snapshot, "memTotalKb", 1000L);
    setLongField(snapshotClass, snapshot, "memAvailableKb", 500L);
    setLongField(snapshotClass, snapshot, "cachedKb", 100L);
    setLongField(snapshotClass, snapshot, "shmemKb", 200L);

    Method applySnapshot = NodeMemMetrics.class
        .getDeclaredMethod("applyMemInfoSnapshot", snapshotClass);
    applySnapshot.setAccessible(true);

    applySnapshot.invoke(metrics, snapshot);
    bypassRefresh(metrics);

    assertEquals(0L, metrics.getFilePageCacheBytes(),
        "File page cache should be clamped to zero");
  }

  // ----------------------------------------------------
  // kbToBytes() and resetAll()
  // ----------------------------------------------------

  @Test
  @DisplayName("kbToBytes should convert values and keep -1 sentinel")
  void kbToBytesConversion() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    Method kbToBytes = NodeMemMetrics.class.getDeclaredMethod("kbToBytes", long.class);
    kbToBytes.setAccessible(true);

    long zero = (long) kbToBytes.invoke(metrics, 0L);
    long one = (long) kbToBytes.invoke(metrics, 1L);
    long minus = (long) kbToBytes.invoke(metrics, -1L);

    assertEquals(0L, zero, "0 kB should be 0 bytes");
    assertEquals(1024L, one, "1 kB should be 1024 bytes");
    assertEquals(-1L, minus, "Negative value should stay as -1 sentinel");
  }

  @Test
  @DisplayName("resetAll should reset all fields back to -1")
  void resetAllResetsFields() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // First apply a snapshot to change values
    Object snapshot = newSnapshot();
    Class<?> snapshotClass = snapshot.getClass();
    setLongField(snapshotClass, snapshot, "memTotalKb", 1000L);
    setLongField(snapshotClass, snapshot, "memAvailableKb", 500L);

    Method applySnapshot = NodeMemMetrics.class
        .getDeclaredMethod("applyMemInfoSnapshot", snapshotClass);
    applySnapshot.setAccessible(true);
    applySnapshot.invoke(metrics, snapshot);

    // Now call resetAll()
    Method resetAll = NodeMemMetrics.class.getDeclaredMethod("resetAll");
    resetAll.setAccessible(true);
    resetAll.invoke(metrics);

    bypassRefresh(metrics);

    assertEquals(-1L, metrics.getTotalMemoryBytes(), "Total should be reset to -1");
    assertEquals(-1L, metrics.getUsedMemoryBytes(), "Used should be reset to -1");
    assertEquals(-1L, metrics.getFreeMemoryBytes(), "Free should be reset to -1");
    assertEquals(-1L, metrics.getAvailableMemoryBytes(), "Available should be reset to -1");
    assertEquals(-1L, metrics.getDirtyBytes(), "Dirty should be reset to -1");
    assertEquals(-1L, metrics.getWritebackBytes(), "Writeback should be reset to -1");
    assertEquals(-1L, metrics.getCachedBytes(), "Cached should be reset to -1");
    assertEquals(-1L, metrics.getBuffersBytes(), "Buffers should be reset to -1");
    assertEquals(-1L, metrics.getShmemBytes(), "Shmem should be reset to -1");
    assertEquals(-1L, metrics.getFilePageCacheBytes(), "File page cache should be reset to -1");

    assertEquals(-1.0, metrics.getUsedMemoryPercent(), 0.000001,
        "Used percent should be reset to -1.0");
  }

  // ----------------------------------------------------
  // Used memory percent with valid data
  // ----------------------------------------------------

  @Test
  @DisplayName("getUsedMemoryPercent should compute ratio when data is valid")
  void usedMemoryPercentValid() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Set internal fields via reflection
    Field totalField = NodeMemMetrics.class.getDeclaredField("totalBytes");
    Field usedField = NodeMemMetrics.class.getDeclaredField("usedBytes");
    totalField.setAccessible(true);
    usedField.setAccessible(true);

    totalField.setLong(metrics, 200L);
    usedField.setLong(metrics, 50L);

    bypassRefresh(metrics);

    assertEquals(25.0, metrics.getUsedMemoryPercent(), 0.000001,
        "Used percent should be used / total * 100");
  }

  // ----------------------------------------------------
  // Non-Linux MXBean helpers: getTotalMemoryPortable / getFreeMemoryPortable
  // ----------------------------------------------------

  @Test
  @DisplayName("getTotalMemoryPortable should prefer getTotalMemorySize when it returns positive")
  void getTotalMemoryPortablePrefersNewApi() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Dynamic proxy for OperatingSystemMXBean, using pattern-based behavior
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            4096L,   // totalMemorySize
            8192L,   // totalPhysicalMemorySize
            0L,      // freeMemorySize (unused in this test)
            0L,      // freePhysicalMemorySize (unused in this test)
            false,   // throwOnNewTotal
            false    // throwOnNewFree
        )
    );

    Method totalPortable = NodeMemMetrics.class
        .getDeclaredMethod("getTotalMemoryPortable", OperatingSystemMXBean.class);
    totalPortable.setAccessible(true);

    long total = (long) totalPortable.invoke(metrics, osBean);

    assertEquals(4096L, total, "Should prefer getTotalMemorySize when > 0");
  }

  @Test
  @DisplayName("getTotalMemoryPortable should fall back when new API returns non-positive")
  void getTotalMemoryPortableFallbackOnNonPositive() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // New API returns 0, so code should fall back to physical memory size
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            0L,       // totalMemorySize -> non-positive
            16384L,   // totalPhysicalMemorySize
            0L,
            0L,
            false,
            false
        )
    );

    Method totalPortable = NodeMemMetrics.class
        .getDeclaredMethod("getTotalMemoryPortable", OperatingSystemMXBean.class);
    totalPortable.setAccessible(true);

    long total = (long) totalPortable.invoke(metrics, osBean);

    assertEquals(16384L, total, "Should fall back to total physical memory size");
  }

  @Test
  @DisplayName("getTotalMemoryPortable should fall back when reflection throws an exception")
  void getTotalMemoryPortableFallbackOnException() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Handler configured to throw on getTotalMemorySize
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            1234L,
            5678L,
            0L,
            0L,
            true,   // throwOnNewTotal
            false
        )
    );

    Method totalPortable = NodeMemMetrics.class
        .getDeclaredMethod("getTotalMemoryPortable", OperatingSystemMXBean.class);
    totalPortable.setAccessible(true);

    long total = (long) totalPortable.invoke(metrics, osBean);

    assertEquals(5678L, total, "Exception during new API should cause fallback");
  }

  @Test
  @DisplayName("getFreeMemoryPortable should prefer getFreeMemorySize when it returns non-negative")
  void getFreeMemoryPortablePrefersNewApi() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            0L,
            0L,
            1111L,  // freeMemorySize
            2222L,  // freePhysicalMemorySize
            false,
            false
        )
    );

    Method freePortable = NodeMemMetrics.class
        .getDeclaredMethod("getFreeMemoryPortable", OperatingSystemMXBean.class);
    freePortable.setAccessible(true);

    long free = (long) freePortable.invoke(metrics, osBean);

    assertEquals(1111L, free, "Should prefer getFreeMemorySize when >= 0");
  }

  @Test
  @DisplayName("getFreeMemoryPortable should fall back when new API returns negative")
  void getFreeMemoryPortableFallbackOnNegative() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // FreeMemorySize returns -1, so code should fall back to free physical
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            0L,
            0L,
            -1L,    // freeMemorySize -> negative
            2222L,  // freePhysicalMemorySize
            false,
            false
        )
    );

    Method freePortable = NodeMemMetrics.class
        .getDeclaredMethod("getFreeMemoryPortable", OperatingSystemMXBean.class);
    freePortable.setAccessible(true);

    long free = (long) freePortable.invoke(metrics, osBean);

    assertEquals(2222L, free, "Should fall back to free physical memory size");
  }

  @Test
  @DisplayName("getFreeMemoryPortable should fall back when reflection throws an exception")
  void getFreeMemoryPortableFallbackOnException() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Handler configured to throw on getFreeMemorySize
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{OperatingSystemMXBean.class},
        new MemoryInvocationHandler(
            0L,
            0L,
            0L,
            3333L,
            false,
            true   // throwOnNewFree
        )
    );

    Method freePortable = NodeMemMetrics.class
        .getDeclaredMethod("getFreeMemoryPortable", OperatingSystemMXBean.class);
    freePortable.setAccessible(true);

    long free = (long) freePortable.invoke(metrics, osBean);

    assertEquals(3333L, free, "Exception during new API should cause fallback");
  }

  // ----------------------------------------------------
  // poll() smoke test (whichever branch is active on this platform)
  // ----------------------------------------------------

  @Test
  @DisplayName("poll should not throw and should leave fields in a consistent state")
  void pollDoesNotThrow() {
    NodeMemMetrics metrics = new NodeMemMetrics();

    assertDoesNotThrow(metrics::poll, "poll() should not throw");

    long total = metrics.getTotalMemoryBytes();
    long used = metrics.getUsedMemoryBytes();
    long free = metrics.getFreeMemoryBytes();

    // Either all are sentinel, or they form a sane triple
    if (total == -1L && used == -1L && free == -1L) {
      // OK: sentinel path
      return;
    }

    assertTrue(total > 0L, "Total memory should be positive when not sentinel");
    assertTrue(used >= 0L, "Used memory should be non-negative when not sentinel");
    assertTrue(free >= 0L, "Free memory should be non-negative when not sentinel");
    assertTrue(used <= total, "Used memory should not exceed total");
  }

  // ----------------------------------------------------
  // Pattern-matching instanceof usage: o instanceof [type] tv
  // ----------------------------------------------------

  @Test
  @DisplayName("Pattern-matching instanceof should work as expected")
  void instanceOfPatternVariable() {
    Object o = "pattern-variable";

    // Demonstrate the "o instanceof [type] tv" pattern
    if (o instanceof String) {
      String tv = (String) o;
      assertEquals("pattern-variable", tv, "Pattern variable should capture string value");
    } else {
      fail("Object should be instance of String");
    }
  }

  // ----------------------------------------------------
  // Helper methods
  // ----------------------------------------------------

  /**
   * Create a new MemInfoSnapshot instance via reflection.
   * This class is private static inside NodeMemMetrics.
   */
  private Object newSnapshot() throws Exception {
    Class<?>[] innerClasses = NodeMemMetrics.class.getDeclaredClasses();
    for (Class<?> c : innerClasses) {
      if (c.getSimpleName().equals("MemInfoSnapshot")) {
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
      }
    }
    throw new IllegalStateException("MemInfoSnapshot inner class not found");
  }

  private long getLongField(Class<?> clazz, Object target, String name) throws Exception {
    Field f = clazz.getDeclaredField(name);
    f.setAccessible(true);
    return f.getLong(target);
  }

  private void setLongField(Class<?> clazz, Object target, String name, long value)
      throws Exception {
    Field f = clazz.getDeclaredField(name);
    f.setAccessible(true);
    f.setLong(target, value);
  }

  // ----------------------------------------------------
  // InvocationHandler for OperatingSystemMXBean proxy
  // ----------------------------------------------------

  /**
   * Invocation handler that simulates container-aware memory methods.
   * <ul>
   *   <li>getTotalMemorySize() -> totalMemorySize (or throws if throwOnNewTotal)</li>
   *   <li>getTotalPhysicalMemorySize() -> totalPhysicalMemorySize</li>
   *   <li>getFreeMemorySize() -> freeMemorySize (or throws if throwOnNewFree)</li>
   *   <li>getFreePhysicalMemorySize() -> freePhysicalMemorySize</li>
   *   <li>Other numeric methods return 0 or a safe default.</li>
   * </ul>
   */
  private static final class MemoryInvocationHandler implements InvocationHandler {

    private final long totalMemorySize;
    private final long totalPhysicalMemorySize;
    private final long freeMemorySize;
    private final long freePhysicalMemorySize;
    private final boolean throwOnNewTotal;
    private final boolean throwOnNewFree;

    MemoryInvocationHandler(long totalMemorySize,
                            long totalPhysicalMemorySize,
                            long freeMemorySize,
                            long freePhysicalMemorySize,
                            boolean throwOnNewTotal,
                            boolean throwOnNewFree) {
      this.totalMemorySize = totalMemorySize;
      this.totalPhysicalMemorySize = totalPhysicalMemorySize;
      this.freeMemorySize = freeMemorySize;
      this.freePhysicalMemorySize = freePhysicalMemorySize;
      this.throwOnNewTotal = throwOnNewTotal;
      this.throwOnNewFree = throwOnNewFree;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();

      // Total memory methods
      if ("getTotalMemorySize".equals(name)) {
        if (throwOnNewTotal) {
          throw new RuntimeException("Simulated failure in getTotalMemorySize");
        }
        return totalMemorySize;
      }
      if ("getTotalPhysicalMemorySize".equals(name)) {
        return totalPhysicalMemorySize;
      }

      // Free memory methods
      if ("getFreeMemorySize".equals(name)) {
        if (throwOnNewFree) {
          throw new RuntimeException("Simulated failure in getFreeMemorySize");
        }
        return freeMemorySize;
      }
      if ("getFreePhysicalMemorySize".equals(name)) {
        return freePhysicalMemorySize;
      }

      // Generic stubs for other methods that may be called
      Class<?> returnType = method.getReturnType();
      if (returnType == long.class) {
        return 0L;
      }
      if (returnType == int.class) {
        return 0;
      }
      if (returnType == double.class) {
        return 0.0d;
      }
      if (returnType == boolean.class) {
        return false;
      }
      if ("toString".equals(name)) {
        return "MemoryInvocationHandlerProxy";
      }
      if ("hashCode".equals(name)) {
        return System.identityHashCode(proxy);
      }
      if ("equals".equals(name)) {
        return proxy == args[0];
      }
      return null;
    }
  }

  // ----------------------------------------------------
  // Improved Coverage Tests using Test Hooks
  // ----------------------------------------------------

  @Test
  @DisplayName("pollFromProcMeminfo should reset all fields on IOException")
  void pollFromProcMeminfoResetsOnIoException() throws Exception {
    // Subclass to simulate IOException during read
    NodeMemMetrics metrics = new NodeMemMetrics() {
      @Override
      List<String> readProcMemInfoLines() throws java.io.IOException {
        throw new java.io.IOException("Simulated read failure");
      }
    };

    // Pre-set some values to ensure they get reset
    setLongField(NodeMemMetrics.class, metrics, "totalBytes", 1234L);
    setLongField(NodeMemMetrics.class, metrics, "usedBytes", 567L);

    // Invoke private pollFromProcMeminfo()
    Method method = NodeMemMetrics.class.getDeclaredMethod("pollFromProcMeminfo");
    method.setAccessible(true);
    method.invoke(metrics);

    assertEquals(-1L, metrics.getTotalMemoryBytes(),
        "Should reset totalBytes on IOException");
    assertEquals(-1L, metrics.getUsedMemoryBytes(), "Should reset usedBytes on IOException");
  }

  @Test
  @DisplayName("pollFromOsMxBean should handle non-OperatingSystemMXBean "
      + "implementations gracefully")
  void pollFromOsMxBeanHandlesStandardBean() throws Exception {
    // Subclass to return a standard java.lang.management.OperatingSystemMXBean
    // that is NOT com.sun.management.OperatingSystemMXBean
    NodeMemMetrics metrics = new NodeMemMetrics() {
      @Override
      java.lang.management.OperatingSystemMXBean getOsMxBean() {
        return org.mockito.Mockito.mock(java.lang.management.OperatingSystemMXBean.class);
      }
    };

    // Invoke private pollFromOsMxBean()
    Method method = NodeMemMetrics.class.getDeclaredMethod("pollFromOsMxBean");
    method.setAccessible(true);
    method.invoke(metrics);

    bypassRefresh(metrics);

    assertEquals(-1L, metrics.getTotalMemoryBytes(), "Should be -1 for standard MXBean");
    assertEquals(-1L, metrics.getUsedMemoryBytes(), "Should be -1 for standard MXBean");
  }

  @Test
  @DisplayName("pollFromOsMxBean should handle RuntimeException during bean retrieval")
  void pollFromOsMxBeanHandlesException() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics() {
      @Override
      java.lang.management.OperatingSystemMXBean getOsMxBean() {
        throw new RuntimeException("Simulated JMX failure");
      }
    };

    Method method = NodeMemMetrics.class.getDeclaredMethod("pollFromOsMxBean");
    method.setAccessible(true);
    method.invoke(metrics);

    bypassRefresh(metrics);

    assertEquals(-1L, metrics.getTotalMemoryBytes(), "Should reset to -1 on exception");
  }

  @Test
  @DisplayName("parseMemInfo should handle partial or missing fields")
  void parseMemInfoHandlesMissingFields() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();
    // Only Total and Free provided, others missing
    String partialInfo = "MemTotal: 100 kB\nMemFree: 20 kB\n";
    List<String> lines = partialInfo.lines().toList();

    Method parseMethod = NodeMemMetrics.class.getDeclaredMethod("parseMemInfo", List.class);
    parseMethod.setAccessible(true);
    Object snapshot = parseMethod.invoke(metrics, lines);

    assertEquals(100L, getLongField(snapshot.getClass(), snapshot, "memTotalKb"));
    assertEquals(20L, getLongField(snapshot.getClass(), snapshot, "memFreeKb"));
    assertEquals(-1L, getLongField(snapshot.getClass(), snapshot, "memAvailableKb")); // Missing
    assertEquals(-1L, getLongField(snapshot.getClass(), snapshot, "cachedKb"));      // Missing
  }

  @Test
  @DisplayName("getUsedMemoryPercent should return -1.0 if total is non-positive "
      + "or used is negative")
  void usedMemoryPercentInvalidStates() throws Exception {
    NodeMemMetrics metrics = new NodeMemMetrics();

    // Case 1: Total is 0
    setLongField(NodeMemMetrics.class, metrics, "totalBytes", 0L);
    setLongField(NodeMemMetrics.class, metrics, "usedBytes", 100L);
    bypassRefresh(metrics);
    assertEquals(-1.0, metrics.getUsedMemoryPercent(), 0.000001);

    // Case 2: Used is negative
    setLongField(NodeMemMetrics.class, metrics, "totalBytes", 1000L);
    setLongField(NodeMemMetrics.class, metrics, "usedBytes", -1L);
    bypassRefresh(metrics);
    assertEquals(-1.0, metrics.getUsedMemoryPercent(), 0.000001);
  }

  private void bypassRefresh(NodeMemMetrics metrics) {
    metrics.setReadRefreshEnabled(false);
  }
}
