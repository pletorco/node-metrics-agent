package kr.pletor.nodemetrics.agent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/** Real packaged agent and OS watcher; file edits are confined to a disposable directory. */
public final class ConfigReloadProbe {
  private ConfigReloadProbe() {}

  public static void main(String[] args) {
    try {
      run(Path.of(args[0]));
      System.out.println(
          "RELOAD SAFE: preserved metadata, replacement, duplicate suppression, recovery");
    } catch (Throwable error) {
      System.out.println("RELOAD FAILURE: " + error.getMessage());
      System.exit(1);
    }
  }

  private static void run(Path path) throws Exception {
    ManagementFactory
        .getPlatformMBeanServer(); // The host owns platform MBeanServer initialization.
    awaitInterval(2, "initial config was not applied");
    Field engineField = MetricsAgent.class.getDeclaredField("refreshEngine");
    engineField.setAccessible(true);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (engineField.get(null) == null
        || !((MetricsRefreshEngine) engineField.get(null)).isRunning()) {
      require(System.nanoTime() < deadline, "refresh engine did not start");
      Thread.sleep(10L);
    }
    Object engine = engineField.get(null);
    FileTime originalTime = Files.getLastModifiedTime(path);
    long originalSize = Files.size(path);
    write(path, 9, originalTime);
    require(Files.size(path) == originalSize, "test changed file size");
    require(Files.getLastModifiedTime(path).equals(originalTime), "test changed mtime");
    awaitInterval(9, "same-mtime edit was not applied");

    Config applied = current();
    Files.setLastModifiedTime(path, FileTime.fromMillis(originalTime.toMillis() + 5_000L));
    Files.writeString(path, yaml(9));
    Thread.sleep(1_800L);
    require(current() == applied, "identical bytes were reapplied after touch/events");

    Path replacement = path.resolveSibling("replacement.yml");
    write(replacement, 3, originalTime);
    Files.move(
        replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    require(Files.getLastModifiedTime(path).equals(originalTime), "replacement lost its mtime");
    require(Files.size(path) == originalSize, "replacement changed size");
    awaitInterval(3, "mtime-preserving atomic replacement was not applied");

    applied = current();
    for (String rejected :
        new String[] {"- invalid\n", "#".repeat(ConfigLoader.MAX_CONFIG_BYTES + 1)}) {
      Files.writeString(path, rejected);
      Files.setLastModifiedTime(path, originalTime);
      Thread.sleep(1_800L);
      require(current() == applied, "invalid/oversized edit replaced the last good config");
    }
    write(path, 9, originalTime);
    awaitInterval(9, "valid same-mtime config did not recover after rejected edits");
    require(engineField.get(null) == engine, "reload replaced the refresh engine");
  }

  private static String yaml(int interval) {
    return "refresh_interval_seconds: " + interval + "\nfsmetrics_paths: []\n";
  }

  private static void write(Path path, int interval, FileTime time) throws Exception {
    Files.writeString(path, yaml(interval));
    Files.setLastModifiedTime(path, time);
  }

  private static Config current() throws Exception {
    Field lock = MetricsAgent.class.getDeclaredField("APPLY_LOCK");
    lock.setAccessible(true);
    Field current = MetricsAgent.class.getDeclaredField("current");
    current.setAccessible(true);
    synchronized (lock.get(null)) {
      return (Config) current.get(null);
    }
  }

  private static void awaitInterval(int expected, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
    while (current() == null || current().refreshIntervalSeconds != expected) {
      require(System.nanoTime() < deadline, message);
      Thread.sleep(10L);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
