package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigContentReloadTest {
  @TempDir Path directory;

  private static void check(ConfigReloader reloader) throws Exception {
    Method method = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
    method.setAccessible(true);
    method.invoke(reloader);
  }

  private static void write(Path path, int interval, FileTime time) throws Exception {
    Files.writeString(path, "refresh_interval_seconds: " + interval + "\n");
    Files.setLastModifiedTime(path, time);
  }

  @Test
  void sameSizeAndMtimeEditsAndAtomicReplacementAreApplied() throws Exception {
    Path path = directory.resolve("config.yml");
    FileTime time = FileTime.fromMillis(1_700_000_000_123L);
    write(path, 2, time);
    AtomicInteger interval = new AtomicInteger();
    ConfigReloader reloader =
        new ConfigReloader(path, cfg -> interval.set(cfg.refreshIntervalSeconds));
    check(reloader);
    assertEquals(2, interval.get());
    long size = Files.size(path);
    write(path, 9, time);
    assertEquals(size, Files.size(path));
    check(reloader);
    assertEquals(9, interval.get());
    Path replacement = directory.resolve("replacement.yml");
    write(replacement, 3, time);
    Files.move(
        replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    assertEquals(time, Files.getLastModifiedTime(path));
    check(reloader);
    assertEquals(3, interval.get());
  }

  @Test
  void rejectedEditsRetainLastGoodBytesAndRestoringThemClearsBackoff() throws Exception {
    Path path = directory.resolve("config.yml");
    FileTime time = FileTime.fromMillis(1_700_000_000_123L);
    write(path, 2, time);
    AtomicInteger applies = new AtomicInteger();
    ConfigReloader reloader = new ConfigReloader(path, cfg -> applies.incrementAndGet());
    check(reloader);
    for (String rejected : List.of("- invalid\n", "#".repeat(ConfigLoader.MAX_CONFIG_BYTES + 1))) {
      Files.writeString(path, rejected);
      Files.setLastModifiedTime(path, time);
      check(reloader);
      assertEquals(1, applies.get());
      assertFalse(reloader.pollDue(System.nanoTime()));
    }
    write(path, 2, time);
    check(reloader);
    assertEquals(1, applies.get(), "Restoring the current config must not reapply it");
    var failures = ConfigReloader.class.getDeclaredField("consecutiveReloadFailures");
    failures.setAccessible(true);
    assertEquals(0, failures.getInt(reloader));
    assertTrue(reloader.pollDue(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
    write(path, 9, time);
    check(reloader);
    assertEquals(2, applies.get());
  }

  @Test
  void failedApplierDoesNotCommitChecksumAndCanRetryTheSameBytes() throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "refresh_interval_seconds: 2\n");
    AtomicInteger attempts = new AtomicInteger();
    ConfigReloader reloader =
        new ConfigReloader(
            path,
            cfg -> {
              if (attempts.incrementAndGet() == 1) {
                throw new LinkageError("synthetic config application failure");
              }
            });
    check(reloader);
    check(reloader);
    check(reloader);
    assertEquals(2, attempts.get(), "Retry the uncommitted bytes once, then suppress duplicates");
  }

  @Test
  void missedEventsAreRecoveredByPeriodicContentChecks() throws Exception {
    exercisePolling(false);
  }

  @Test
  void unrelatedEventTrafficDoesNotStarvePeriodicContentChecks() throws Exception {
    exercisePolling(true);
  }

  private void exercisePolling(boolean unrelatedTraffic) throws Exception {
    Path path = directory.resolve("config.yml");
    FileTime time = FileTime.fromMillis(1_700_000_000_123L);
    write(path, 2, time);
    AtomicInteger applies = new AtomicInteger();
    AtomicInteger interval = new AtomicInteger(2);
    ConfigReloader reloader =
        new ConfigReloader(
            path,
            cfg -> {
              applies.incrementAndGet();
              interval.set(cfg.refreshIntervalSeconds);
            },
            ConfigLoader.load(path).checksum);
    WatchService service = mock(WatchService.class);
    WatchKey other =
        new ConfigReloaderTest.FakeWatchKey(
            List.of(new ConfigReloaderTest.FakeWatchEvent<>(Path.of("other.yml"))));
    AtomicInteger polls = new AtomicInteger();
    when(service.poll(1, TimeUnit.SECONDS))
        .thenAnswer(
            call -> {
              polls.incrementAndGet();
              Thread.sleep(unrelatedTraffic ? 10L : 1_000L);
              return unrelatedTraffic ? other : null;
            });
    try (Loop loop = new Loop(reloader, service)) {
      AsyncTestSupport.await(() -> polls.get() > 0, 3_000L, "watch loop started");
      write(path, 9, time);
      AsyncTestSupport.await(
          () -> interval.get() == 9, 5_000L, "same-mtime edit detected by timer");
      assertEquals(1, applies.get());
      assertNull(loop.failure.get());
    }
  }

  @Test
  void overflowWithNullContextBypassesFailureBackoff() throws Exception {
    Path path = directory.resolve("config.yml");
    FileTime time = FileTime.fromMillis(1_700_000_000_123L);
    write(path, 2, time);
    AtomicInteger interval = new AtomicInteger(2);
    ConfigReloader reloader =
        new ConfigReloader(
            path,
            cfg -> interval.set(cfg.refreshIntervalSeconds),
            ConfigLoader.load(path).checksum);
    for (int i = 0; i < 7; i++) {
      reloader.recordReloadFailure();
    }
    write(path, 9, time);
    WatchEvent<Object> overflow =
        new ConfigReloaderTest.FakeWatchEvent<>(null) {
          @Override
          public Kind<Object> kind() {
            return StandardWatchEventKinds.OVERFLOW;
          }
        };
    WatchKey key = new ConfigReloaderTest.FakeWatchKey(List.of(overflow));
    WatchService service = mock(WatchService.class);
    when(service.poll(1, TimeUnit.SECONDS))
        .thenReturn(key)
        .thenAnswer(
            call -> {
              Thread.sleep(1_000L);
              return null;
            });
    assertFalse(reloader.pollDue(System.nanoTime()), "Timer is backed off for sixty seconds");
    try (Loop loop = new Loop(reloader, service)) {
      AsyncTestSupport.await(
          () -> interval.get() == 9, 3_000L, "OVERFLOW forces content validation");
      assertNull(loop.failure.get());
    }
  }

  /** Fake event delivery, real watch loop, bounded teardown; all waits are test-only. */
  private static final class Loop implements AutoCloseable {
    private final ConfigReloader reloader;
    private final Thread thread;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    Loop(ConfigReloader reloader, WatchService service) throws Exception {
      this.reloader = reloader;
      Method method =
          ConfigReloader.class.getDeclaredMethod("watchLoop", WatchService.class, boolean.class);
      method.setAccessible(true);
      thread =
          new Thread(
              () -> {
                try {
                  method.invoke(reloader, service, true);
                } catch (java.lang.reflect.InvocationTargetException e) {
                  if (!(e.getCause() instanceof InterruptedException)) {
                    failure.set(e.getCause());
                  }
                } catch (Exception e) {
                  failure.set(e);
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void close() throws InterruptedException {
      reloader.stop();
      thread.interrupt();
      thread.join(3_000L);
      assertFalse(thread.isAlive());
      assertNull(failure.get());
    }
  }
}
