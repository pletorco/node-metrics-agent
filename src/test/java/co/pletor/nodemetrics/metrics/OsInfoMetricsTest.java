package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OsInfoMetrics} using mocked {@link SystemAccess}.
 */
class OsInfoMetricsTest {

  // ----------------------------------------------------
  // Mock SystemAccess implementation
  // ----------------------------------------------------
  static class MockSystemAccess implements SystemAccess {
    Map<String, String> properties = new HashMap<>();
    Map<String, String> env = new HashMap<>();
    Map<String, String> files = new HashMap<>();
    Map<String, Boolean> isDir = new HashMap<>();
    boolean isLinux = true;
    LinuxProcFs.CgroupInfo cgroupInfo = new LinuxProcFs.CgroupInfo();

    @Override
    public String getProperty(String key, String def) {
      return properties.getOrDefault(key, def);
    }

    @Override
    public String getEnv(String name) {
      return env.get(name);
    }

    @Override
    public boolean isRegularFile(Path path) {
      return files.containsKey(path.toString());
    }

    @Override
    public boolean isDirectory(Path path) {
      return isDir.getOrDefault(path.toString(), false);
    }

    @Override
    public String readString(Path path) throws IOException {
      String content = files.get(path.toString());
      if (content == null) {
        throw new IOException("File not found: " + path);
      }
      return content;
    }

    @Override
    public boolean isLinux() {
      return isLinux;
    }

    @Override
    public List<String> readLines(Path path) throws IOException {
      String content = readString(path);
      if (content.isEmpty()) {
        return Collections.emptyList();
      }
      return List.of(content.split("\n"));
    }

    @Override
    public LinuxProcFs.CgroupInfo detectCgroup() {
      return cgroupInfo;
    }
  }

  // ----------------------------------------------------
  // Basic initialization tests
  // ----------------------------------------------------

  @Test
  @DisplayName("Default constructor should initialize with safe defaults")
  void defaultConstructor() {
    OsInfoMetrics metrics = new OsInfoMetrics();
    bypassRefresh(metrics);
    assertEquals(1, metrics.getInfoFlag());
    assertEquals("unknown", metrics.getEnvironmentType());
    // We can't easily check internal SystemAccess, but we assume it works.
  }

  @Test
  @DisplayName("Initial values should be defaults before poll()")
  void initialValues() {
    MockSystemAccess mock = new MockSystemAccess();
    OsInfoMetrics metrics = new OsInfoMetrics(mock);

    bypassRefresh(metrics);

    assertEquals("", metrics.getOsRelease());
    assertEquals("", metrics.getKernelVersion());
    assertEquals("", metrics.getOsName());
    assertEquals("", metrics.getOsArch());
    assertEquals("unknown", metrics.getEnvironmentType());
  }

  // ----------------------------------------------------
  // Linux Polling Tests
  // ----------------------------------------------------

  @Test
  @DisplayName("poll() on Linux should read /etc/os-release correctly")
  void pollLinuxOsRelease() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.properties.put("os.name", "Linux");
    mock.properties.put("os.arch", "amd64");

    // Mock /etc/os-release
    mock.files.put("/etc/os-release",
        "PRETTY_NAME=\"Ubuntu 22.04.1 LTS\"\n"
            + "NAME=\"Ubuntu\"\n"
            + "VERSION_ID=\"22.04\"\n"
            + "VERSION=\"22.04.1 LTS (Jammy Jellyfish)\"\n"
            + "ID=ubuntu\n"
    );

    // Mock kernel version
    mock.files.put("/proc/sys/kernel/osrelease", "5.15.0-56-generic\n");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("Ubuntu 22.04.1 LTS", metrics.getOsRelease());
    assertEquals("5.15.0-56-generic", metrics.getKernelVersion());
    assertEquals("Linux", metrics.getOsName());
    assertEquals("amd64", metrics.getOsArch());
  }

  @Test
  @DisplayName("poll() should handle quoted values in os-release")
  void pollLinuxOsReleaseQuoted() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/etc/os-release",
        "NAME=\"Alpine Linux\"\n"
            + "ID=\"alpine\"\n"
            + "VERSION_ID=\"3.17.0\"\n"
            + "PRETTY_NAME=\"Alpine Linux v3.17\"\n"
    );
    mock.files.put("/proc/sys/kernel/osrelease", "5.15.0");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("Alpine Linux v3.17", metrics.getOsRelease());
  }

  @Test
  @DisplayName("poll() should fallback to ID + VERSION_ID if PRETTY_NAME missing")
  void pollLinuxOsReleaseFallbackId() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/etc/os-release",
        "ID=rocky\n"
            + "VERSION_ID=9.1\n"
    );

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("rocky 9.1", metrics.getOsRelease());
  }

  @Test
  @DisplayName("poll() should fallback to ID if VERSION_ID missing")
  void pollLinuxOsReleaseFallbackIdOnly() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/etc/os-release", "ID=arch\n");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("arch", metrics.getOsRelease());
  }

  @Test
  @DisplayName("poll() should use os.name + os.version if /etc/os-release missing/invalid")
  void pollLinuxOsReleaseMissing() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.properties.put("os.name", "Linux");
    mock.properties.put("os.version", "5.10.0");
    // No /etc/os-release file

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("Linux 5.10.0", metrics.getOsRelease());
  }

  // ----------------------------------------------------
  // Kernel Version Tests
  // ----------------------------------------------------

  @Test
  @DisplayName("Kernel version from /proc/version if osrelease missing")
  void pollKernelFromProcVersion() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    // Missing /proc/sys/kernel/osrelease

    mock.files.put("/proc/version", "Linux version 6.1.0-1-amd64 (debian@debian) ...\n");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("6.1.0-1-amd64", metrics.getKernelVersion());
  }

  @Test
  @DisplayName("Kernel version fallbacks to os.version")
  void pollKernelFallback() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.properties.put("os.version", "4.19.0");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("4.19.0", metrics.getKernelVersion());
  }

  // ----------------------------------------------------
  // Non-Linux Tests
  // ----------------------------------------------------

  @Test
  @DisplayName("poll() on non-Linux should use JVM properties")
  void pollNonLinux() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = false;
    mock.properties.put("os.name", "Mac OS X");
    mock.properties.put("os.version", "13.0.1");
    mock.properties.put("os.arch", "aarch64");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("Mac OS X 13.0.1", metrics.getOsRelease());
    assertEquals("13.0.1", metrics.getKernelVersion());
    assertEquals("Mac OS X", metrics.getOsName());
    assertEquals("aarch64", metrics.getOsArch());
    assertEquals("unknown", metrics.getEnvironmentType());
  }

  // ----------------------------------------------------
  // Environment Type Tests
  // ----------------------------------------------------

  @Test
  @DisplayName("Environment: Kubernetes via Service Account")
  void envKubernetesServiceAccount() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.isDir.put("/var/run/secrets/kubernetes.io/serviceaccount", true);

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("kubernetes", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Kubernetes via Env Vars")
  void envKubernetesEnvVars() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.env.put("KUBERNETES_SERVICE_HOST", "10.96.0.1");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("kubernetes", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Kubernetes via Cgroup")
  void envKubernetesCgroup() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.cgroupInfo.path = "/kubepods/burstable/pod123";

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("kubernetes", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Container via .dockerenv")
  void envContainerDockerEnv() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/.dockerenv", "");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("container", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Container via /run/.containerenv (Podman)")
  void envContainerPodmanEnv() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/run/.containerenv", "");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("container", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Container via Cgroup (docker)")
  void envContainerCgroup() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.cgroupInfo.path = "/docker/abcdef123456";

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("container", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Container via /proc/1/cgroup")
  void envContainerProc1Cgroup() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.files.put("/proc/1/cgroup", "1:name=systemd:/docker/container-id\n");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("container", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("Environment: Host/VM (default fallback)")
  void envHostOrVm() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    // No container markers

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    assertEquals("host_or_vm", metrics.getEnvironmentType());
  }

  @Test
  @DisplayName("poll() should cache values and not re-read from system")
  void pollShouldReadOnceAndCache() {
    MockSystemAccess mock = new MockSystemAccess();
    mock.isLinux = true;
    mock.properties.put("os.name", "Linux");
    mock.files.put("/etc/os-release", "ID=original\n");

    OsInfoMetrics metrics = new OsInfoMetrics(mock);
    metrics.poll();

    // First check
    assertEquals("original", metrics.getOsRelease());
    assertEquals("Linux", metrics.getOsName());

    // Change system state (this should be ignored due to caching)
    mock.files.put("/etc/os-release", "ID=changed\n");
    mock.properties.put("os.name", "ChangedOS");

    metrics.poll();

    // Assert values remain unchanged
    assertEquals("original", metrics.getOsRelease());
    assertEquals("Linux", metrics.getOsName());
  }

  private void bypassRefresh(OsInfoMetrics metrics) {
    metrics.setReadRefreshEnabled(false);
  }
}
