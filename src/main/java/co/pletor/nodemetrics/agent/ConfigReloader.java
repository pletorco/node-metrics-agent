// co.pletor.nodemetrics.agent.ConfigReloader.java
package co.pletor.nodemetrics.agent;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

/**
 * Watches the configuration file and reloads it when it changes.
 * <p>
 * This class:
 * <ul>
 *   <li>Uses {@link WatchService} to monitor the parent directory of the config file</li>
 *   <li>Falls back to periodic timestamp polling when no file events are delivered
 *       (useful for NFS/containers where watch events can be unreliable)</li>
 *   <li>Uses a checksum to avoid unnecessary reloads when only the timestamp changes</li>
 * </ul>
 */
final class ConfigReloader implements Runnable {

  /**
   * Path to the configuration file being watched.
   */
  private final Path configPath;

  /**
   * Callback used to apply a newly loaded configuration.
   */
  private final MetricsAgent.ApplyConfigFn applier;

  /**
   * Controls the main loop. When set to {@code false}, the watcher stops.
   */
  private volatile boolean running = true;

  /**
   * Last observed modification time (in milliseconds) of the config file.
   */
  private long lastSeenMtime = -1L;

  /**
   * Last applied configuration checksum, used to detect real content changes.
   */
  private String lastChecksum = null;

  private static final Logger LOGGER = Logger.getLogger(ConfigReloader.class.getName());
  private static final ThrottledLogger THROTTLED_LOGGER = new ThrottledLogger(LOGGER, 60_000L);
  private static final String LOG_KEY_WATCHER_REGISTRATION_FAILED = "watcher-registration-failed";
  private static final String LOG_KEY_CONFIG_RELOAD_FAILED = "config-reload-failed";

  private static final long BASE_RELOAD_BACKOFF_MS = 1_000L;
  private static final long MAX_RELOAD_BACKOFF_MS = 60_000L;

  /** Tracks consecutive reload failures to compute exponential backoff sleep. */
  private int consecutiveReloadFailures = 0;


  /**
   * Create a new configuration reloader.
   *
   * @param configPath path to the configuration file to watch
   * @param applier    callback that applies a new {@link Config}
   */
  ConfigReloader(Path configPath, MetricsAgent.ApplyConfigFn applier) {
    this.configPath = configPath;
    this.applier = applier;
  }

  /**
   * Signal the watcher loop to stop.
   * <p>
   * The thread will exit after the next iteration.
   */
  void stop() {
    running = false;
  }

  @Override
  public void run() {
    Path dir = resolveWatchDir(configPath);
    try (WatchService ws = FileSystems.getDefault().newWatchService()) {
      boolean watching = registerDirectorySafely(ws, dir);
      watchLoop(ws, watching);
    } catch (InterruptedException e) {
      // Preserve interrupt status and exit the watcher thread gracefully.
      Thread.currentThread().interrupt();
      LOGGER.log(Level.INFO, "[node-metrics-agent] config watcher interrupted, stopping");
    } catch (Exception e) {
      // Any other unexpected failure should be logged but must not crash the JVM.
      LOGGER.log(Level.SEVERE, "[node-metrics-agent] config watcher failed", e);
    }
  }

  private Path resolveWatchDir(Path cfgPath) {
    Path abs = cfgPath.toAbsolutePath().normalize();
    Path parent = abs.getParent();
    if (parent != null) {
      return parent;
    }
    return Paths.get(".").toAbsolutePath().normalize();
  }

  /**
   * Register the parent directory of the config file with the WatchService.
   */
  private void registerDirectory(WatchService ws, Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      Files.createDirectories(dir);
    }
    dir.register(
        ws,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE
    );
  }

  private boolean registerDirectorySafely(WatchService ws, Path dir) {
    try {
      registerDirectory(ws, dir);
      return true;
    } catch (Exception e) {
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_WATCHER_REGISTRATION_FAILED,
          e,
          () -> "[node-metrics-agent] watcher registration failed, falling back to polling only: " + dir
      );
      return false;
    }
  }

  /**
   * Main watcher loop: waits for file events or falls back to periodic polling.
   */
  private void watchLoop(WatchService ws, boolean watching) throws InterruptedException {
    while (running) {
      WatchKey key = watching ? ws.poll(1, TimeUnit.SECONDS) : null;
      if (key == null) {
        // Fallback: periodically check the timestamp directly.
        // This handles environments where WatchService is unreliable (e.g. NFS, containers).
        checkAndReloadIfChanged();
        if (!watching) {
          Thread.sleep(computePollingIntervalMs());
        }
        continue;
      }

      boolean relevant = isConfigFileEvent(key);
      boolean valid = key.reset();
      if (!valid) {
        watching = registerDirectorySafely(ws, resolveWatchDir(configPath));
      }

      if (relevant) {
        debounceAndReload();
      }
    }
  }

  /**
   * Returns the polling interval for the non-watching fallback path.
   * <p>
   * After consecutive reload failures, the interval grows exponentially
   * (base × 2^failures) up to {@code MAX_RELOAD_BACKOFF_MS} to avoid
   * a tight failure loop on persistent I/O errors.
   */
  private long computePollingIntervalMs() {
    if (consecutiveReloadFailures <= 0) {
      return BASE_RELOAD_BACKOFF_MS;
    }
    int shifts = Math.min(consecutiveReloadFailures - 1, 6); // 2^6 × 1 s = 64 s → clips to MAX 60 s
    return Math.min(BASE_RELOAD_BACKOFF_MS << shifts, MAX_RELOAD_BACKOFF_MS);
  }

  /**
   * Returns true if the WatchKey contains events related to the config file.
   */
  private boolean isConfigFileEvent(WatchKey key) {
    Path targetName = configPath.getFileName();
    if (targetName == null) {
      return false;
    }
    for (WatchEvent<?> ev : key.pollEvents()) {
      Object ctx = ev.context();
      if (ctx instanceof Path) {
        Path changed = (Path) ctx;
        if (changed.getFileName().equals(targetName)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Debounce rapid events and then perform a reload check.
   */
  private void debounceAndReload() throws InterruptedException {
    try {
      Thread.sleep(500L);
    } catch (InterruptedException e) {
      // Restore interrupted status so higher-level code can react if needed.
      Thread.currentThread().interrupt();
      throw e;
    }
    checkAndReloadIfChanged();
  }

  /**
   * Check if the configuration file has changed and reload it if necessary.
   * <p>
   * Change detection is done in two steps:
   * <ol>
   *   <li>Compare the last modified time</li>
   *   <li>If the time changed, load the file and compare checksum</li>
   * </ol>
   * This avoids reapplying the same configuration when only the timestamp changes.
   */
  private void checkAndReloadIfChanged() {
    try {
      if (!Files.isRegularFile(configPath)) {
        // Nothing to load if the file does not exist.
        return;
      }

      long mtime = Files.getLastModifiedTime(configPath).toMillis();
      if (mtime == lastSeenMtime) {
        // Timestamp unchanged; skip reload.
        return;
      }

      Config cfg = ConfigLoader.load(configPath);

      if (Objects.equals(cfg.checksum, lastChecksum)) {
        // Content unchanged; only update the timestamp.
        lastSeenMtime = mtime;
        return;
      }

      // Real change detected: apply new configuration.
      LOGGER.log(Level.INFO, "[node-metrics-agent] configuration changed, reloading: {0}", configPath);
      applier.apply(cfg);

      lastSeenMtime = mtime;
      lastChecksum = cfg.checksum;
      consecutiveReloadFailures = 0;
    } catch (Exception e) {
      // Do not break the agent on configuration reload failure;
      // keep using the last known good configuration.
      consecutiveReloadFailures++;
      THROTTLED_LOGGER.log(
          Level.WARNING,
          LOG_KEY_CONFIG_RELOAD_FAILED,
          e,
          () -> "[node-metrics-agent] configuration reload failed (keeping previous configuration)"
      );
    }
  }
}
