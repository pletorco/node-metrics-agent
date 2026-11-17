package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class CollectorInputTest {
  @TempDir Path root;

  @AfterEach
  void restoreRoots() {
    LinuxProcFs.setProcRoot(Path.of("/proc"));
    LinuxProcFs.setSysRoot(Path.of("/sys"));
  }

  private static final class CountingInput extends ByteArrayInputStream {
    int consumed;

    CountingInput(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized int read(byte[] bytes, int offset, int length) {
      int count = super.read(bytes, offset, length);
      consumed += Math.max(0, count);
      return count;
    }
  }

  @Test
  void stopsAtTotalByteBudgetPlusOneWithoutUsingFileSize() throws Exception {
    CountingInput source = new CountingInput("abc\nabc\n".repeat(1000));
    long before = CollectorInput.limitExceededCount();
    try (InputStream input =
        CollectorInput.limited(source, new CollectorInput.Limits(8, 8, 8), false)) {
      IOException failure = assertThrows(IOException.class, input::readAllBytes);
      assertTrue(failure.getMessage().contains("bytes limit"));
    }
    assertEquals(9, source.consumed);
    assertEquals(before + 1, CollectorInput.limitExceededCount());
  }

  @Test
  void singleLongLineStopsBeforeUnboundedReadLineAllocation() throws Exception {
    CountingInput source = new CountingInput("x".repeat(10000));
    try (InputStream input =
        CollectorInput.limited(source, new CollectorInput.Limits(10000, 4, 20), false)) {
      IOException failure = assertThrows(IOException.class, input::readAllBytes);
      assertTrue(failure.getMessage().contains("line bytes"));
    }
    assertEquals(5, source.consumed);
  }

  @Test
  void manyShortLinesStopWithBoundedReadAhead() throws Exception {
    CountingInput source = new CountingInput("\n".repeat(10000));
    try (InputStream input =
        CollectorInput.limited(source, new CollectorInput.Limits(10000, 100, 2), false)) {
      IOException failure = assertThrows(IOException.class, input::readAllBytes);
      assertTrue(failure.getMessage().contains("lines limit"));
    }
    assertTrue(source.consumed <= 101);
  }

  @Test
  void singleByteAndEmptyReadsRespectEndOfFirstLine() throws Exception {
    try (InputStream input =
        CollectorInput.limited(
            new CountingInput("a\nignored"), new CollectorInput.Limits(100, 100, 10), true)) {
      assertEquals(0, input.read(new byte[1], 0, 0));
      assertEquals('a', input.read());
      assertEquals('\n', input.read());
      assertEquals(-1, input.read());
    }
  }

  @Test
  void exactBoundariesIncludeCrLfAndUnterminatedLastLine() throws Exception {
    Path file = root.resolve("table");
    Files.writeString(file, "ab\r\nc");
    assertEquals(
        List.of("ab", "c"), CollectorInput.readLines(file, new CollectorInput.Limits(6, 2, 2)));
    Files.writeString(file, "ab\r\nc\r\n");
    assertEquals(
        List.of("ab", "c"), CollectorInput.readLines(file, new CollectorInput.Limits(8, 2, 2)));
    Files.writeString(file, "ab\r\nc\r\n\n");
    assertThrows(
        IOException.class,
        () -> CollectorInput.readLines(file, new CollectorInput.Limits(100, 2, 2)));
  }

  @Test
  void limitsUtf8BytesAndRejectsMalformedEncoding() throws Exception {
    Path file = root.resolve("utf8");
    Files.writeString(file, "가나");
    assertEquals("가나", CollectorInput.readString(file, new CollectorInput.Limits(6, 6, 1)));
    assertThrows(
        IOException.class,
        () -> CollectorInput.readLines(file, new CollectorInput.Limits(6, 5, 1)));
    Files.write(file, new byte[] {(byte) 0xc3, 0x28});
    assertThrows(IOException.class, () -> CollectorInput.readLines(file, CollectorInput.SMALL));
  }

  @Test
  void aggregateCpuLineDoesNotReadOrValidateTheRestOfProcStat() throws Exception {
    Path file = root.resolve("stat");
    String cpu = "cpu 1 2 3 4 5 6 7 8";
    Files.writeString(file, cpu + "\n" + "x".repeat(100000));
    assertEquals(cpu, CollectorInput.readFirstLine(file));
    Files.writeString(file, "x".repeat(CollectorInput.NUMBER.lineBytes + 1));
    assertThrows(IOException.class, () -> CollectorInput.readFirstLine(file));
  }

  @Test
  void oversizedStatusKeepsLastSnapshotAndRecoversOnNextPoll() throws Exception {
    LinuxProcFs.setProcRoot(root);
    Path status = Files.createDirectories(root.resolve("self")).resolve("status");
    Files.writeString(status, "VmRSS: 12 kB\n");
    ProcessMetrics metric = new ProcessMetrics();
    metric.setReadRefreshEnabled(false);
    metric.poll();
    assertEquals(12288, metric.getResidentSetBytes());
    Files.writeString(status, "VmRSS: 99 kB\n" + "\n".repeat(CollectorInput.TABLE.lines));
    metric.poll();
    assertEquals(12288, metric.getResidentSetBytes());
    assertNotNull(metric.lastRefreshError());
    Files.writeString(status, "VmRSS: 21 kB\n");
    metric.poll();
    assertEquals(21504, metric.getResidentSetBytes());
    assertNull(metric.lastRefreshError());
  }

  @Test
  void oversizedMountListIsUnavailableInsteadOfPublishingPartialCounts() throws Exception {
    LinuxProcFs.setProcRoot(root);
    Files.createDirectories(root.resolve("self"));
    Files.writeString(root.resolve("uptime"), "123 0\n");
    Path mounts = root.resolve("self/mounts");
    Files.writeString(mounts, "tmpfs / tmpfs rw 0 0\n");
    OsRuntimeMetrics metric = new OsRuntimeMetrics();
    metric.setReadRefreshEnabled(false);
    metric.poll();
    assertEquals(1, metric.getMountCount());
    Files.writeString(mounts, "tmpfs / tmpfs rw 0 0\n".repeat(CollectorInput.TABLE.lines + 1));
    metric.poll();
    assertEquals(-1, metric.getMountCount());
    assertEquals(0, metric.getMounts().length);
    assertNotNull(metric.lastRefreshError());
    Files.writeString(mounts, "tmpfs / tmpfs rw 0 0\n");
    metric.poll();
    assertEquals(1, metric.getMountCount());
    assertNull(metric.lastRefreshError());
  }

  @Test
  void numericAndKeyValueSourcesRejectOversizedInput() throws Exception {
    Path file = root.resolve("number");
    Files.writeString(file, "9".repeat(CollectorInput.NUMBER.bytes + 1));
    assertEquals(-1L, LinuxProcFs.readFirstNumber(file));
    assertThrows(
        IOException.class, () -> SystemAccess.defaultInstance().readString(writeLarge(file)));
    Files.writeString(file, "key 1\n".repeat(CollectorInput.SMALL.lines + 1));
    assertThrows(IOException.class, () -> MemStatsUtil.readKeyValues(file));
  }

  private Path writeLarge(Path file) throws IOException {
    Files.writeString(file, "x".repeat(CollectorInput.SMALL.bytes + 1));
    return file;
  }

  @Test
  void memoryMapFailureKeepsCountAndRecovers() throws Exception {
    LinuxProcFs.setProcRoot(root);
    Path maps = Files.createDirectories(root.resolve("self")).resolve("maps");
    Files.writeString(maps, "mapping\nmapping\n");
    MemoryMapMetrics metric = new MemoryMapMetrics();
    metric.setReadRefreshEnabled(false);
    metric.poll();
    assertEquals(2, metric.getMemoryMapCount());
    Files.writeString(maps, "x".repeat(CollectorInput.MAPS.lineBytes + 1));
    metric.poll();
    assertEquals(2, metric.getMemoryMapCount());
    assertNotNull(metric.lastRefreshError());
    Files.writeString(maps, "mapping\n");
    metric.poll();
    assertEquals(1, metric.getMemoryMapCount());
    assertNull(metric.lastRefreshError());
  }

  @Test
  void interfaceCacheKeepsHardCapAcrossChangingNamesWithoutTruncatingTotals() throws Exception {
    LinuxProcFs.setProcRoot(root);
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    Path net = Files.createDirectories(root.resolve("net")).resolve("dev");
    java.lang.reflect.Field field = LinuxProcFs.class.getDeclaredField("PHYSICAL_IFACE_CACHE");
    field.setAccessible(true);
    Map<?, ?> cache = (Map<?, ?>) field.get(null);
    for (String prefix : List.of("vethfirst", "vethsecond")) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < CollectorInput.TABLE.lines; i++) {
        text.append(prefix).append(i).append(": 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0\n");
      }
      Files.writeString(net, text);
      assertEquals(CollectorInput.TABLE.lines, LinuxProcFs.readNetTotals().rxBytes);
      assertEquals(CollectorInput.MAX_INTERFACE_CACHE_ENTRIES, cache.size());
    }
  }

  @Test
  void blockDeviceEnumerationStopsAtItsLimitAndClosesTheStream() throws Exception {
    LinuxProcFs.setProcRoot(root);
    LinuxProcFs.setSysRoot(root);
    AtomicBoolean closed = new AtomicBoolean();
    Path block = root.resolve("block");
    try (var files = Mockito.mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(root.resolve("diskstats"))).thenReturn(true);
      files.when(() -> Files.isDirectory(block)).thenReturn(true);
      files
          .when(() -> Files.list(block))
          .thenReturn(Stream.generate(() -> block.resolve("dev")).onClose(() -> closed.set(true)));
      assertThrows(CollectorInput.LimitExceededException.class, LinuxProcFs::readDiskTotals);
    }
    assertTrue(closed.get());
  }

  @Test
  void nonLinuxMountEnumerationRejectsOversizedResults() {
    OsRuntimeMetrics metric = new OsRuntimeMetrics();
    metric.setReadRefreshEnabled(false);
    java.nio.file.FileSystem system = Mockito.mock(java.nio.file.FileSystem.class);
    java.nio.file.FileStore store = Mockito.mock(java.nio.file.FileStore.class);
    Mockito.when(store.name()).thenReturn("root");
    Mockito.when(store.type()).thenReturn("test");
    Mockito.when(system.getFileStores())
        .thenReturn(java.util.Collections.nCopies(CollectorInput.TABLE.lines + 1, store));
    try (var linux = Mockito.mockStatic(LinuxProcFs.class);
        var systems = Mockito.mockStatic(java.nio.file.FileSystems.class)) {
      linux.when(LinuxProcFs::isLinux).thenReturn(false);
      systems.when(java.nio.file.FileSystems::getDefault).thenReturn(system);
      metric.poll();
    }
    assertEquals(-1, metric.getMountCount());
    assertEquals(0, metric.getMounts().length);
    assertNotNull(metric.lastRefreshError());
  }

  @Test
  void excessiveCgroupDepthDoesNotPublishPartiallyEvaluatedLimits() throws Exception {
    Path directory = root;
    for (int i = 0; i < CollectorInput.MAX_CGROUP_ANCESTORS; i++) {
      directory = directory.resolve("a");
    }
    assertTrue(!CollectorInput.excessiveAncestors(directory, root));
    directory = Files.createDirectories(directory.resolve("last"));
    Files.writeString(directory.resolve("cpu.max"), "100000 100000");
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(directory, root));
  }

  @Test
  void oversizedCpuQuotaCannotBeTreatedAsAnUnlimitedHierarchyLevel() throws Exception {
    Path child = Files.createDirectory(root.resolve("child"));
    Files.writeString(root.resolve("cpu.max"), "200000 100000");
    Files.writeString(child.resolve("cpu.max"), "x".repeat(CollectorInput.NUMBER.bytes + 1));
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(child, root));
    Files.writeString(child.resolve("cpu.max"), "100000 100000");
    Files.writeString(root.resolve("cpu.max"), "x".repeat(CollectorInput.NUMBER.bytes + 1));
    assertEquals(-1.0, CgroupCpuLimit.effectiveCores(child, root));
    Files.writeString(root.resolve("cpu.max"), "200000 100000");
    assertEquals(1.0, CgroupCpuLimit.effectiveCores(child, root));
  }

  @Test
  void limitedCgroupHierarchyBecomesUnavailableAndRecovers() throws Exception {
    LinuxProcFs.setProcRoot(root);
    LinuxProcFs.setSysRoot(root.resolve("sys"));
    Path cgroup = Files.createDirectories(root.resolve("sys/fs/cgroup"));
    final Path child = Files.createDirectory(cgroup.resolve("child"));
    Files.createDirectory(root.resolve("self"));
    Files.writeString(root.resolve("self/cgroup"), "0::/child\n");
    Files.writeString(cgroup.resolve("cgroup.controllers"), "memory pids");
    for (Path level : List.of(cgroup, child)) {
      Files.writeString(level.resolve("memory.max"), "1000");
      Files.writeString(level.resolve("memory.current"), "10");
      Files.writeString(level.resolve("pids.max"), "100");
      Files.writeString(level.resolve("pids.current"), "5");
    }
    CgroupMemMetrics memory = new CgroupMemMetrics();
    PidsMetrics pids = new PidsMetrics();
    memory.setReadRefreshEnabled(false);
    pids.setReadRefreshEnabled(false);
    memory.poll();
    pids.poll();
    assertEquals(1000, memory.getMemoryLimitBytes());
    assertEquals(100, pids.getPidsLimit());
    Files.writeString(child.resolve("memory.max"), "9".repeat(CollectorInput.NUMBER.bytes + 1));
    Files.writeString(child.resolve("pids.max"), "9".repeat(CollectorInput.NUMBER.bytes + 1));
    memory.poll();
    pids.poll();
    assertEquals(-1, memory.getMemoryLimitBytes());
    assertEquals(-1, pids.getPidsLimit());
    assertNotNull(memory.lastRefreshError());
    assertNotNull(pids.lastRefreshError());
    Files.writeString(child.resolve("memory.max"), "200");
    Files.writeString(child.resolve("pids.max"), "20");
    memory.poll();
    pids.poll();
    assertEquals(200, memory.getMemoryLimitBytes());
    assertEquals(20, pids.getPidsLimit());
    assertNull(memory.lastRefreshError());
    assertNull(pids.lastRefreshError());
    Files.writeString(root.resolve("self/cgroup"), "x".repeat(CollectorInput.SMALL.lineBytes + 1));
    assertEquals("none", LinuxProcFs.detectCgroup().version);
  }
}
