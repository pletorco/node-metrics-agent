package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigReloader}.
 *
 * These tests cover:
 * - Core reload logic via checkAndReloadIfChanged()
 * - The main run() loop, including WatchService fallback and stop()
 * - Error handling in run()
 * - isConfigFileEvent() behavior via a fake WatchKey implementation
 */
class ConfigReloaderTest {

  @TempDir
  Path tempDir;

  // --- Simple test doubles for logger and applier ---

  static class ListHandler extends Handler {
    final List<String> infos = new ArrayList<>();
    final List<String> warns = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public void publish(LogRecord logRecord) {
      if (Level.INFO.equals(logRecord.getLevel())) {
        infos.add(logRecord.getMessage());
      } else if (Level.WARNING.equals(logRecord.getLevel())) {
        warns.add(logRecord.getMessage());
      } else if (Level.SEVERE.equals(logRecord.getLevel())) {
        errors.add(logRecord.getMessage() + " :: " + logRecord.getThrown());
      }
    }

    @Override
    public void flush() {
      // No-op for test handler
    }

    @Override
    public void close() throws SecurityException {
      // No-op for test handler
    }
  }

  static class RecordingApplier implements MetricsAgent.ApplyConfigFn {
    final AtomicInteger callCount = new AtomicInteger();
    final AtomicReference<Config> lastConfig = new AtomicReference<>();

    @Override
    public void apply(Config cfg) {
      callCount.incrementAndGet();
      lastConfig.set(cfg);
    }
  }

  // --- Tests: core checkAndReloadIfChanged() behavior ---

  @Test
  void whenFileDoesNotExist_nothingIsApplied() throws Exception {
    Path cfgPath = tempDir.resolve("missing-config.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }

    assertEquals(0, applier.callCount.get(), "Applier must not be called when file is missing");

    assertTrue(handler.infos.isEmpty(), "No info logs expected");
  }

  @Test
  void firstLoad_shouldApplyConfigAndRecordState() throws Exception {
    Path cfgPath = tempDir.resolve("config.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    // Minimal YAML for ConfigLoader; adjust if your ConfigLoader expects other keys
    String yaml =

        "paths:\n" +
        "  - /";

    Files.writeString(cfgPath, yaml, StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }

    assertEquals(1, applier.callCount.get(), "Applier should be called once on first load");
    Config applied = applier.lastConfig.get();
    assertNotNull(applied, "Last applied config should not be null");


    long lastSeenMtime = getLongField(reloader, "lastSeenMtime");
    String lastChecksum = (String) getField(reloader, "lastChecksum");

    assertTrue(lastSeenMtime > 0, "lastSeenMtime should be updated after first load");
    assertNotNull(lastChecksum, "lastChecksum should be set after first load");
  }

  @Test
  void unchangedTimestamp_shouldSkipReload() throws Exception {
    Path cfgPath = tempDir.resolve("config.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    String yaml =

        "paths:\n" +
        "  - /\n";

    Files.writeString(cfgPath, yaml, StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // First load
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }
    assertEquals(1, applier.callCount.get(), "Applier should be called once on first load");

    long lastSeenMtimeAfterFirst = getLongField(reloader, "lastSeenMtime");

    // Second check without touching the file: mtime is the same
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }
    assertEquals(1, applier.callCount.get(), "Applier must not be called again if mtime is unchanged");

    long lastSeenMtimeAfterSecond = getLongField(reloader, "lastSeenMtime");
    assertEquals(lastSeenMtimeAfterFirst, lastSeenMtimeAfterSecond,
        "lastSeenMtime should not change when mtime is unchanged");
  }

  @Test
  void changedTimestampAndContent_shouldReload() throws Exception {
    Path cfgPath = tempDir.resolve("config.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    String yaml1 =

        "paths:\n" +
        "  - /\n";

    String yaml2 =
        "paths:\n" +
        "  - /changed\n";

    // First version
    Files.writeString(cfgPath, yaml1, StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // First load
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }
    assertEquals(1, applier.callCount.get(), "Applier should be called once on first load");
    String firstChecksum = (String) getField(reloader, "lastChecksum");
    long firstMtime = getLongField(reloader, "lastSeenMtime");

    // Change content and (likely) mtime
    Thread.sleep(20L);
    Files.writeString(cfgPath, yaml2, StandardCharsets.UTF_8);

    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }

    assertEquals(2, applier.callCount.get(), "Applier should be called again when content changes");

    String secondChecksum = (String) getField(reloader, "lastChecksum");
    long secondMtime = getLongField(reloader, "lastSeenMtime");

    assertNotEquals(firstChecksum, secondChecksum, "Checksum should change when content changes");
    assertTrue(secondMtime >= firstMtime, "lastSeenMtime should be updated");

  }

  @Test
  void changedTimestampButSameContent_shouldNotReapplyConfig() throws Exception {
    Path cfgPath = tempDir.resolve("config.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    String yaml =

        "paths:\n" +
        "  - /\n";

    Files.writeString(cfgPath, yaml, StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // First load
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }
    assertEquals(1, applier.callCount.get(), "Applier should be called once on first load");

    String checksumAfterFirst = (String) getField(reloader, "lastChecksum");

    // Simulate a "touch": force lastSeenMtime to a different value so that
    // checkAndReloadIfChanged sees a changed timestamp, but keep the file
    // content identical so checksum remains the same.
    setLongField(reloader, "lastSeenMtime", 0L);

    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      invokeCheckAndReload(reloader);
    }

    assertEquals(1, applier.callCount.get(),
        "Applier must not be called again when checksum is unchanged");
    String checksumAfterSecond = (String) getField(reloader, "lastChecksum");
    assertEquals(checksumAfterFirst, checksumAfterSecond,
        "Checksum should remain the same for identical content");
  }

  // --- Tests: run() loop & stop() ---

  @Test
  void run_shouldEventuallyApplyConfigAndReactToChanges() throws Exception {
    Path cfgPath = tempDir.resolve("config-run.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    String yaml1 =

        "paths:\n" +
        "  - /\n";

    String yaml2 =
        "paths:\n" +
        "  - /changed\n";

    // Initial content
    Files.writeString(cfgPath, yaml1, StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);
    Thread t;
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      t = new Thread(reloader, "config-reloader-test-run");
      t.start();

      // Wait until first apply happens (via fallback or watch event)
      waitUntil(() -> applier.callCount.get() >= 1, 5_000, "First apply did not happen via run()");

      // Modify config to trigger another reload
      Thread.sleep(20L);
      Files.writeString(cfgPath, yaml2, StandardCharsets.UTF_8);

      // Wait until second apply happens
      waitUntil(() -> applier.callCount.get() >= 2, 5_000, "Second apply did not happen via run()");
    }

    // Stop and join
    reloader.stop();
    t.join(2_000);
    assertFalse(t.isAlive(), "ConfigReloader thread should stop after stop() is called");


  }

  @Test
  void run_shouldKeepRunningForParentlessConfigPath() throws Exception {
    // Parentless relative paths should be handled safely by resolving to the
    // current directory instead of crashing watcher setup.
    Path cfgPath = Paths.get("config-without-parent.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);
    Thread t;
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      t = new Thread(reloader, "config-reloader-test-error");
      t.start();
      Thread.sleep(250L);
      assertTrue(t.isAlive(), "Thread should keep running and use polling fallback");
      reloader.stop();
      t.join(2_000);
    }

    assertFalse(t.isAlive(), "Thread should stop after stop() is called");
    assertTrue(handler.errors.isEmpty(), "run() should not log SEVERE for parentless config paths");
  }

  @Test
  void run_shouldKeepRunningWhenConfigIsTemporarilyInvalid() throws Exception {
    Path cfgPath = tempDir.resolve("invalid-then-valid.yml");
    RecordingApplier applier = new RecordingApplier();
    ListHandler handler = new ListHandler();

    // Initial config is invalid for ConfigLoader.load(Path): top-level YAML is not a map.
    Files.writeString(cfgPath, "- 1\n- 2\n", StandardCharsets.UTF_8);

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);
    Thread t;
    try (LogHandlerResource ignored = LogHandlerResource.attach(handler)) {
      t = new Thread(reloader, "config-reloader-test-invalid-then-valid");
      t.start();

      // Invalid config should not kill the reloader thread.
      Thread.sleep(1_200L);
      assertTrue(t.isAlive(), "Thread should keep running even when config parse fails");
      assertEquals(0, applier.callCount.get(), "Invalid config should not be applied");

      // Recover with a valid config; reloader should eventually apply it.
      Thread.sleep(20L);
      Files.writeString(
          cfgPath,
          "fsmetrics_paths:\n  - /\n",
          StandardCharsets.UTF_8
      );
      waitUntil(() -> applier.callCount.get() >= 1, 5_000, "Valid config was not applied after recovery");

      reloader.stop();
      t.join(2_000);
    }

    assertFalse(t.isAlive(), "Thread should stop after stop() is called");
    assertTrue(handler.errors.isEmpty(), "No SEVERE log should be emitted for temporary parse failures");
    assertTrue(
        handler.warns.stream().anyMatch(msg -> msg.contains("configuration reload failed")),
        "Parse failure should be logged as warning while keeping previous configuration"
    );
  }

  // --- Tests: isConfigFileEvent() via a fake WatchKey ---

  @Test
  void isConfigFileEvent_shouldReturnTrueWhenEventMatchesConfigFile() throws Exception {
    Path cfgPath = tempDir.resolve("target.yml");
    RecordingApplier applier = new RecordingApplier();

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // Create a fake WatchKey whose event context matches the config file name
    WatchKey key = new FakeWatchKey(List.of(
        new FakeWatchEvent<>(cfgPath.getFileName())
    ));

    Method m = ConfigReloader.class.getDeclaredMethod("isConfigFileEvent", WatchKey.class);
    m.setAccessible(true);
    boolean relevant = (boolean) m.invoke(reloader, key);

    assertTrue(relevant, "Event for the config file should be considered relevant");
  }

  @Test
  void isConfigFileEvent_shouldReturnFalseWhenNoEventMatchesConfigFile() throws Exception {
    Path cfgPath = tempDir.resolve("target.yml");
    RecordingApplier applier = new RecordingApplier();

    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // Event for a different file name
    WatchKey key = new FakeWatchKey(List.of(
        new FakeWatchEvent<>(Paths.get("other.yml"))
    ));

    Method m = ConfigReloader.class.getDeclaredMethod("isConfigFileEvent", WatchKey.class);
    m.setAccessible(true);
    boolean relevant = (boolean) m.invoke(reloader, key);

    assertFalse(relevant, "Event for a different file should not be considered relevant");
  }

  // --- Fake WatchKey / WatchEvent implementations for isConfigFileEvent() ---

  static class FakeWatchEvent<T> implements WatchEvent<T> {
    private final T context;

    FakeWatchEvent(T context) {
      this.context = context;
    }

    @Override
    public Kind<T> kind() {
      // Kind is not used in isConfigFileEvent(), so a dummy kind is fine.
      return new Kind<>() {
        @Override
        public String name() {
          return "FAKE";
        }

        @Override
        public Class<T> type() {
          @SuppressWarnings("unchecked")
          Class<T> type = (Class<T>) Object.class;
          return type;
        }
      };
    }

    @Override
    public int count() {
      return 1;
    }

    @Override
    public T context() {
      return context;
    }
  }

  static class FakeWatchKey implements WatchKey {
    private final List<WatchEvent<?>> events;
    private boolean valid = true;

    FakeWatchKey(List<WatchEvent<?>> events) {
      this.events = events;
    }

    @Override
    public boolean isValid() {
      return valid;
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
      return events;
    }

    @Override
    public boolean reset() {
      valid = false;
      return true;
    }

    @Override
    public void cancel() {
      valid = false;
    }

    @Override
    public Watchable watchable() {
      return null;
    }
  }

  // --- Tests: backoff on repeated reload failures ---

  @Test
  void computePollingInterval_shouldGrowExponentiallyWithConsecutiveFailures() throws Exception {
    // Test computePollingIntervalMs() directly by setting consecutiveReloadFailures via reflection,
    // avoiding any ThrottledLogger interaction (which is static and shared across tests).
    Path cfgPath = tempDir.resolve("backoff-test.yml");
    ConfigReloader reloader = new ConfigReloader(cfgPath, cfg -> { });

    // 0 failures → base interval
    setIntField(reloader, "consecutiveReloadFailures", 0);
    assertEquals(1_000L, invokeComputePollingInterval(reloader), "Base interval should be 1 s");

    // 1 failure → 1 s (2^0 × base)
    setIntField(reloader, "consecutiveReloadFailures", 1);
    assertEquals(1_000L, invokeComputePollingInterval(reloader), "1 failure → 1 s");

    // 2 failures → 2 s (2^1 × base)
    setIntField(reloader, "consecutiveReloadFailures", 2);
    assertEquals(2_000L, invokeComputePollingInterval(reloader), "2 failures → 2 s");

    // 3 failures → 4 s (2^2 × base)
    setIntField(reloader, "consecutiveReloadFailures", 3);
    assertEquals(4_000L, invokeComputePollingInterval(reloader), "3 failures → 4 s");

    // 7+ failures → capped at MAX (60 s): 2^6 × 1 s = 64 s clips to 60 s
    setIntField(reloader, "consecutiveReloadFailures", 7);
    assertEquals(60_000L, invokeComputePollingInterval(reloader), "7+ failures → capped at 60 s");

    setIntField(reloader, "consecutiveReloadFailures", 20);
    assertEquals(60_000L, invokeComputePollingInterval(reloader), "20 failures → still capped at 60 s");
  }

  @Test
  void successAfterFailures_shouldResetConsecutiveFailureCounter() throws Exception {
    Path cfgPath = tempDir.resolve("recover-config.yml");
    // Start with a valid config file so the first load succeeds and doesn't log
    Files.writeString(cfgPath, "fsmetrics_paths:\n  - /\n", java.nio.charset.StandardCharsets.UTF_8);

    RecordingApplier applier = new RecordingApplier();
    ConfigReloader reloader = new ConfigReloader(cfgPath, applier);

    // Manually set the failure counter (bypassing ThrottledLogger) and verify reset on success
    setIntField(reloader, "consecutiveReloadFailures", 3);
    assertEquals(3, getIntField(reloader, "consecutiveReloadFailures"), "Counter set via reflection");

    // Perform a successful reload: load valid config so apply() is called
    invokeCheckAndReload(reloader);

    assertEquals(0, getIntField(reloader, "consecutiveReloadFailures"),
        "Consecutive failure counter must reset to 0 after a successful reload");
    long interval = invokeComputePollingInterval(reloader);
    assertEquals(1_000L, interval, "Polling interval should return to base 1 s after recovery");
  }

  // --- Reflection helpers to access private members ---

  private static void invokeCheckAndReload(ConfigReloader reloader) throws Exception {
    Method m = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
    m.setAccessible(true);
    m.invoke(reloader);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static long getLongField(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.getLong(target);
  }

  private static void setLongField(Object target, String name, long value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.setLong(target, value);
  }

  private static int getIntField(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.getInt(target);
  }

  private static void setIntField(Object target, String name, int value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.setInt(target, value);
  }

  private static long invokeComputePollingInterval(ConfigReloader reloader) throws Exception {
    Method m = ConfigReloader.class.getDeclaredMethod("computePollingIntervalMs");
    m.setAccessible(true);
    return (long) m.invoke(reloader);
  }

  private static void waitUntil(BooleanSupplier condition, long timeoutMillis, String message)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20L);
    }
    fail(message);
  }

  @FunctionalInterface
  interface BooleanSupplier {
    boolean getAsBoolean();
  }

  /*
   * Helper resource for attaching/detaching a handler to the ConfigReloader class logger.
   */
  static class LogHandlerResource implements AutoCloseable {
    private final Logger targetLogger;
    private final Handler handler;

    private LogHandlerResource(Logger logger, Handler handler) {
      this.targetLogger = logger;
      this.handler = handler;
      targetLogger.addHandler(handler);
    }

    static LogHandlerResource attach(Handler handler) {
      // Find the logger used by ConfigReloader (based on class name refactoring)
      Logger logger = Logger.getLogger(ConfigReloader.class.getName());
      // Ensure we catch everything for tests
      logger.setLevel(Level.ALL);
      return new LogHandlerResource(logger, handler);
    }

    @Override
    public void close() {
      targetLogger.removeHandler(handler);
    }
  }

  // NOTE: removed createLogger() as it's no longer used
}
