package kr.pletor.nodemetrics.metrics;

import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic proc/cgroup trees only; no real process is moved or host cgroup written. */
final class MigrationFixture {
  final Path membership;
  final boolean legacy;
  final CgroupMemMetrics memory;
  final PidsMetrics pids;
  final PressureMetrics psi;

  MigrationFixture(Path root, boolean legacy) throws Exception {
    this.legacy = legacy;
    Path proc = Files.createDirectories(root.resolve("proc/self"));
    Path mount = Files.createDirectories(root.resolve("sys/fs/cgroup"));
    membership = proc.resolve("cgroup");
    LinuxProcFs.setProcRoot(root.resolve("proc"));
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    if (!legacy) {
      Files.writeString(mount.resolve("cgroup.controllers"), "memory pids cpu\n");
    }
    for (int i = 1; i <= 2; i++) {
      Path memoryRoot = legacy ? mount.resolve("memory") : mount;
      Path dir = Files.createDirectories(memoryRoot.resolve("group" + i + "/leaf"));
      Files.writeString(
          dir.getParent().resolve(legacy ? "memory.limit_in_bytes" : "memory.max"), "" + i * 1000);
      Files.writeString(dir.resolve(legacy ? "memory.limit_in_bytes" : "memory.max"), "9000");
      Files.writeString(
          dir.resolve(legacy ? "memory.usage_in_bytes" : "memory.current"), "" + i * 100);
      Files.writeString(dir.resolve("memory.stat"), "inactive_file 10\ntotal_inactive_file 10\n");
      Files.writeString(
          dir.resolve("memory.events"), "oom_kill " + (i == 1 ? 10 : 2) + "\nmax 0\nhigh 0\n");
      Files.writeString(dir.resolve("memory.oom_control"), "oom_kill " + (i == 1 ? 10 : 2) + "\n");
      Path pids = legacy ? Files.createDirectories(mount.resolve("pids/pid" + i + "/leaf")) : dir;
      Files.writeString(pids.resolve("pids.max"), "90");
      Files.writeString(pids.resolve("pids.current"), "" + i);
      Files.writeString(pids.getParent().resolve("pids.max"), "" + i * 10);
      Files.writeString(pids.getParent().resolve("pids.current"), "" + i * 3);
      for (String resource : new String[] {"cpu", "memory", "io"}) {
        Files.writeString(
            dir.resolve(resource + ".pressure"),
            "some avg10="
                + i
                + " avg60=0 avg300=0 total="
                + i * 100
                + "\n"
                + "full avg10=0 avg60=0 avg300=0 total=0\n");
      }
    }
    move(1);
    memory = new CgroupMemMetrics();
    pids = new PidsMetrics();
    psi = PressureMetrics.forCgroup();
    for (RefreshManagedMetric bean : new RefreshManagedMetric[] {memory, pids, psi}) {
      bean.setReadRefreshEnabled(false);
    }
    poll();
  }

  void move(int index) throws Exception {
    Files.writeString(
        membership,
        legacy
            ? "4:memory:/group" + index + "/leaf\n5:pids:/pid" + index + "/leaf\n"
            : "0::/group" + index + "/leaf\n");
  }

  void poll() {
    memory.poll();
    pids.poll();
    psi.poll();
  }

  java.lang.reflect.Field[] cacheFields() throws Exception {
    java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
    for (Object bean : new Object[] {memory, pids, psi}) {
      try {
        var field = bean.getClass().getDeclaredField("binding");
        field.setAccessible(true);
        var cached = field.get(bean).getClass().getDeclaredField("cached");
        cached.setAccessible(true);
        fields.add(cached);
      } catch (NoSuchFieldException ignored) {
        return new java.lang.reflect.Field[0]; // Baseline has permanently fixed bindings.
      }
    }
    return fields.toArray(new java.lang.reflect.Field[0]);
  }

  Object[] bindings() throws Exception {
    if (cacheFields().length == 0) {
      return new Object[0];
    }
    Object[] bindings = new Object[3];
    Object[] beans = {memory, pids, psi};
    for (int i = 0; i < beans.length; i++) {
      var field = beans[i].getClass().getDeclaredField("binding");
      field.setAccessible(true);
      bindings[i] = field.get(beans[i]);
    }
    return bindings;
  }

  void expire() throws Exception {
    var fields = cacheFields();
    var bindings = bindings();
    for (int i = 0; i < fields.length; i++) {
      fields[i].set(bindings[i], null); // Test only: exercise expiry without a one-minute sleep.
    }
  }
}
