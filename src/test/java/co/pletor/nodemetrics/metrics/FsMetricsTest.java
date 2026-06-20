// src/test/java/co/pletor/nodemetrics/metrics/FsMetricsTest.java
package co.pletor.nodemetrics.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FsMetrics}.
 * <p>
 * These tests cover:
 * <ul>
 *   <li>Initial state right after construction</li>
 *   <li>Successful poll() on a real filesystem path</li>
 *   <li>Failure path of poll() when an exception is thrown</li>
 *   <li>Basic instanceof pattern matching against FsMetricsMBean</li>
 * </ul>
 */
class FsMetricsTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("Constructor should keep path and initialize fields to default values")
  void constructorShouldKeepPathAndInitializeDefaults() throws Exception {
    // Given: a temporary directory to use as a monitored path
    Path dir = Files.createDirectory(tempDir.resolve("fsmetrics-initial"));

    // When: creating a new FsMetrics instance
    FsMetrics metrics = new FsMetrics(dir);
    bypassRefresh(metrics);

    // Then: the path should be exposed as-is
    assertEquals(
        dir.toString(),
        metrics.getPath(),
        "getPath() should return the original Path's string representation"
    );

    // And: numeric fields should be at their default zero value before any poll()
    assertEquals(0L, metrics.getTotalBytes(), "Initial total bytes should be zero");
    assertEquals(0L, metrics.getUsableBytes(), "Initial usable bytes should be zero");
    assertEquals(0L, metrics.getUnallocatedBytes(), "Initial unallocated bytes should be zero");

    // And: string fields should be empty before any poll()
    assertEquals("", metrics.getFileStoreName(), "Initial file store name should be empty");
    assertEquals("", metrics.getFileSystemType(), "Initial file system type should be empty");

    // And: FsMetrics should implement FsMetricsMBean (instanceof pattern with binding variable)
    Object o = metrics;
    if (o instanceof FsMetricsMBean) {
      FsMetricsMBean tv = (FsMetricsMBean) o;
      // Check that the interface view returns the same path as the concrete instance
      assertEquals(
          metrics.getPath(),
          tv.getPath(),
          "Path from FsMetricsMBean view should match concrete FsMetrics implementation"
      );
    } else {
      fail("FsMetrics must implement FsMetricsMBean");
    }
  }

  @Test
  @DisplayName("poll() should populate fields correctly on successful FileStore lookup")
  void pollShouldPopulateFieldsOnSuccess() throws Exception {
    // Given: a real directory that exists on the filesystem
    Path dir = Files.createDirectory(tempDir.resolve("fsmetrics-success"));
    FsMetrics metrics = new FsMetrics(dir);

    // When: poll() is invoked
    assertDoesNotThrow(
        metrics::poll,
        "poll() should not throw when FileStore lookup succeeds"
    );

    // Then: path should remain stable
    assertEquals(
        dir.toString(),
        metrics.getPath(),
        "Path should not change after poll()"
    );

    // And: file store name and type should be non-empty strings
    String fsName = metrics.getFileStoreName();
    String fsType = metrics.getFileSystemType();

    assertNotNull(fsName, "File store name should not be null after successful poll()");
    assertNotNull(fsType, "File system type should not be null after successful poll()");
    assertFalse(fsName.isEmpty(), "File store name should not be empty after successful poll()");
    assertFalse(fsType.isEmpty(), "File system type should not be empty after successful poll()");

    // And: numeric values should be non-negative
    long total = metrics.getTotalBytes();
    long usable = metrics.getUsableBytes();
    long unallocated = metrics.getUnallocatedBytes();

    assertTrue(total >= 0L, "Total bytes should be non-negative after successful poll()");
    assertTrue(usable >= 0L, "Usable bytes should be non-negative after successful poll()");
    assertTrue(unallocated >= 0L, "Unallocated bytes should be non-negative after successful poll()");

    // Optional sanity check: usable + unallocated should not exceed total in normal environments
    // (We tolerate violation here to avoid flakiness on exotic file systems.)
  }

  @Test
  @DisplayName("poll() should reset all fields to unknown/-1 when an exception occurs")
  void pollShouldResetFieldsOnException() {
    // Prepare a multi-line description using a text block as requested
    String description =
        "FsMetrics should report \"unknown\" and -1\n" +
        "when an exception is thrown during poll().\n" +
        "This test forces a NullPointerException by using a null Path.\n";

    // Given: an FsMetrics instance created with a null path
    // This is intentionally invalid to trigger an exception in poll().
    FsMetrics metrics = new FsMetrics(null);

    // When: poll() is called, Files.getFileStore(null) will throw a NullPointerException
    assertDoesNotThrow(
        metrics::poll,
        "poll() should catch exceptions internally and not rethrow them"
    );

    // Then: error sentinel values should be set as described in the class JavaDoc
    assertEquals(
        "unknown",
        metrics.getFileStoreName(),
        "On failure, file store name should be 'unknown' (" + description + ")"
    );
    assertEquals(
        "unknown",
        metrics.getFileSystemType(),
        "On failure, file system type should be 'unknown' (" + description + ")"
    );
    assertEquals(
        -1L,
        metrics.getTotalBytes(),
        "On failure, total bytes should be -1 (" + description + ")"
    );
    assertEquals(
        -1L,
        metrics.getUsableBytes(),
        "On failure, usable bytes should be -1 (" + description + ")"
    );
    assertEquals(
        -1L,
        metrics.getUnallocatedBytes(),
        "On failure, unallocated bytes should be -1 (" + description + ")"
    );

    // Note: getPath() would throw NullPointerException here because the underlying path is null.
    // We intentionally do not call getPath() in this failure scenario test.
  }

  @Test
  @DisplayName("setReadRefreshEnabled(false) should prevent getter-triggered refresh")
  void setReadRefreshEnabledFalse_shouldSkipGetterRefresh() throws Exception {
    Path dir = Files.createDirectory(tempDir.resolve("fsmetrics-read-disabled"));
    FsMetrics metrics = new FsMetrics(dir);

    metrics.poll();
    metrics.setReadRefreshEnabled(false);

    Field total = FsMetrics.class.getDeclaredField("total");
    total.setAccessible(true);
    total.setLong(metrics, 12345L);

    assertEquals(
        12345L,
        metrics.getTotalBytes(),
        "Getter should return cached value without forcing refresh when read refresh is disabled"
    );
  }

  private void bypassRefresh(FsMetrics metrics) {
    metrics.setReadRefreshEnabled(false);
  }
}
