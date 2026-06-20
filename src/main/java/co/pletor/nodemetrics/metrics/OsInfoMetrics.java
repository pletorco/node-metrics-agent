package co.pletor.nodemetrics.metrics;

import co.pletor.nodemetrics.agent.AgentMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Operating system information metrics MBean implementation.
 * <p>
 * This class collects a small set of OS-level properties:
 * <ul>
 *   <li>OS release (e.g. from /etc/os-release on Linux)</li>
 *   <li>Kernel version (e.g. /proc/sys/kernel/osrelease)</li>
 *   <li>Basic JVM OS properties (name, arch)</li>
 *   <li>Best-effort environment type (host/VM vs generic container vs Kubernetes)</li>
 * </ul>
 * <p>
 * All values are cached in volatile fields and refreshed on {@link #poll()}.
 * On any error, individual fields may fall back to empty string or -1.
 */
public class OsInfoMetrics implements OsInfoMetricsMBean, RefreshManagedMetric {

  /**
   * Constant info metric for use as a Prometheus *_info value.
   * Always 1.
   */
  private static final int INFO_FLAG = 1;

  private static final String OSVERSION_1 = "os.version";
  private static final String CONTAINER_1 = "container";
  private static final String KUBERNETES_1 = "kubernetes";
  private static final String UNKNOWN_1 = "unknown";

  private final SystemAccess sys;

  private volatile String osRelease = "";
  private volatile String kernelVersion = "";
  private volatile String osName = "";
  private volatile String osArch = "";
  private volatile String environmentType = UNKNOWN_1;

  /**
   * Creates a new {@code OsInfoMetrics} instance with default values.
   * <p>
   * All fields are initialized to empty strings or {@code "unknown"} and
   * will be populated on the first JMX query.
   */
  public OsInfoMetrics() {
    this(SystemAccess.defaultInstance());
  }

  /**
   * Constructor for testing.
   */
  OsInfoMetrics(SystemAccess sys) {
    this.sys = sys;
  }

  // ------------------------------------------------------------------------
  // Polling / Refeshing
  // ------------------------------------------------------------------------

  private static final long REFRESH_INTERVAL_MS = 500L;
  private final RefreshCadence refreshCadence = new RefreshCadence(REFRESH_INTERVAL_MS);
  private volatile boolean readRefreshEnabled = true;

  /**
   * Refresh metrics if the cache is stale.
   */
  private void refresh() {
    if (!refreshCadence.tryAcquire()) {
      return;
    }

    // Optimization: OS info is static. If already populated, do not re-read.
    if (!osName.isEmpty()) {
      return;
    }

    try {
      refreshValues();
    } catch (Exception e) {
      applyFallbackValues();
    }
  }

  private void refreshValues() {
    osName = safeProperty("os.name");
    osArch = safeProperty("os.arch");

    if (sys.isLinux()) {
      pollLinux();
    } else {
      pollNonLinux();
    }

    // Environment type detection is best-effort and cheap enough
    // to run on each poll.
    environmentType = detectEnvironmentType();
  }

  private void applyFallbackValues() {
    if (osName == null) {
      osName = "";
    }
    if (osArch == null) {
      osArch = "";
    }
    if (osRelease == null) {
      osRelease = "";
    }
    if (kernelVersion == null) {
      kernelVersion = "";
    }
    if (environmentType == null || environmentType.isEmpty()) {
      environmentType = UNKNOWN_1;
    }
  }



  /**
   * Force refresh of metrics (for testing).
   */
  public void poll() {
    refreshCadence.force();
    refresh();
  }

  @Override
  public void setReadRefreshEnabled(boolean enabled) {
    readRefreshEnabled = enabled;
  }

  private void refreshOnRead() {
    if (readRefreshEnabled) {
      refresh();
    }
  }

  // ----- Linux-specific implementation -----

  private void pollLinux() {
    osRelease = readOsReleaseLinux();
    kernelVersion = readKernelVersionLinux();
  }

  private String readOsReleaseLinux() {
    Path osReleasePath = Path.of("/etc/os-release");
    if (!sys.isRegularFile(osReleasePath)) {
      // Fallback: simple combination of os.name + os.version
      return buildFallbackOsRelease();
    }

    try {
      List<String> lines = sys.readLines(osReleasePath);

      String pretty = null;
      String id = null;
      String versionId = null;

      for (String line : lines) {
        if (line.startsWith("PRETTY_NAME=")) {
          pretty = unquoteOsReleaseValue(line.substring("PRETTY_NAME=".length()));
        } else if (line.startsWith("ID=")) {
          id = unquoteOsReleaseValue(line.substring("ID=".length()));
        } else if (line.startsWith("VERSION_ID=")) {
          versionId = unquoteOsReleaseValue(line.substring("VERSION_ID=".length()));
        }
      }

      if (pretty != null && !pretty.isEmpty()) {
        return pretty;
      }

      if (id != null && !id.isEmpty()) {
        if (versionId != null && !versionId.isEmpty()) {
          return id + " " + versionId;
        }
        return id;
      }

      return buildFallbackOsRelease();
    } catch (IOException e) {
      return buildFallbackOsRelease();
    }
  }

  private String unquoteOsReleaseValue(String raw) {
    String v = raw.trim();
    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
      v = v.substring(1, v.length() - 1);
    }
    return v;
  }

  private String buildFallbackOsRelease() {
    String name = safeProperty("os.name");
    String version = safeProperty(OSVERSION_1);
    if (!name.isEmpty() && !version.isEmpty()) {
      return name + " " + version;
    }
    return name.isEmpty() ? "" : name;
  }

  private String readKernelVersionLinux() {
    // 1) Read directly from /proc/sys/kernel/osrelease
    String fromOsrelease = readTrimmedFileIfNonEmpty(Path.of("/proc/sys/kernel/osrelease"));
    if (!fromOsrelease.isEmpty()) {
      return fromOsrelease;
    }

    // 2) Extract kernel token from /proc/version
    String fromProcVersion = readKernelFromProcVersion();
    if (!fromProcVersion.isEmpty()) {
      return fromProcVersion;
    }

    // 3) Final fallback: JVM os.version
    return safeProperty(OSVERSION_1);
  }

  /**
   * Read a file as UTF-8, trim it, and return the result.
   * Returns an empty string when the file does not exist, is empty,
   * or cannot be read.
   */
  private String readTrimmedFileIfNonEmpty(Path path) {
    if (!sys.isRegularFile(path)) {
      return "";
    }
    try {
      String s = sys.readString(path).trim();
      return s.isEmpty() ? "" : s;
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * Read /proc/version and extract a kernel-like token.
   * Example line:
   *   "Linux version 5.14.0-... (builder@...) ..."
   * In that case, "5.14.0-..." is returned.
   * If no such token is found, the full trimmed line is returned.
   * Returns an empty string on error.
   */
  private String readKernelFromProcVersion() {
    Path p = Path.of("/proc/version");
    String raw = readTrimmedFileIfNonEmpty(p);
    if (raw.isEmpty()) {
      return "";
    }

    String[] parts = raw.split("\\s+");
    for (String part : parts) {
      if (!part.isEmpty()) {
        char c = part.charAt(0);
        if (c >= '0' && c <= '9') {
          return part;
        }
      }
    }

    // Fallback: return entire line if we did not find a numeric token
    return raw;
  }

  // ----- Non-Linux implementation -----

  private void pollNonLinux() {
    osRelease = buildFallbackOsRelease();
    kernelVersion = safeProperty(OSVERSION_1);
  }

  // ----- Environment type detection -----

  /**
   * Best-effort detection of whether we are running inside a container,
   * and if so whether it looks like a Kubernetes pod.
   * <p>
   * This method is intentionally conservative and may return "unknown"
   * when it cannot make a confident guess.
   * <p>
   * Values:
   * <ul>
   *   <li>"kubernetes"  – container and strong Kubernetes markers detected</li>
   *   <li>"container"   – container-like environment, but no clear Kubernetes markers</li>
   *   <li>"host_or_vm"  – looks like bare host or virtual machine</li>
   *   <li>"unknown"     – non-Linux or insufficient information</li>
   * </ul>
   */
  private String detectEnvironmentType() {
    // We only try container/Kubernetes heuristics on Linux.
    if (!sys.isLinux()) {
      return UNKNOWN_1;
    }

    boolean kubernetesLike = hasKubernetesMarker();
    boolean containerLike = hasContainerMarker(kubernetesLike);

    if (kubernetesLike) {
      return KUBERNETES_1; // "kubernetes"
    }
    if (containerLike) {
      return CONTAINER_1;  // "container"
    }

    // If we reach here, we did not see strong container markers.
    return "host_or_vm";
  }

  /**
   * Returns true if we see any Kubernetes-specific markers
   * (cgroup patterns, service account, or environment variables).
   */
  private boolean hasKubernetesMarker() {
    return hasKubernetesLikeCgroup()
        || hasKubernetesServiceAccount()
        || hasKubernetesEnvVars();
  }

  /**
   * Returns true if we see generic container-like markers.
   * <p>
   * Kubernetes is always considered container-like, so the
   * kubernetesLike flag is folded into the result.
   */
  private boolean hasContainerMarker(boolean kubernetesLike) {
    if (kubernetesLike) {
      return true;
    }

    if (isContainerRuntimeFilePresent()) {
      return true;
    }

    return hasContainerLikeCgroup();
  }

  /**
   * Runtime marker files used by common container runtimes
   * such as Docker, Podman, and containerd.
   */
  private boolean isContainerRuntimeFilePresent() {
    return sys.isRegularFile(Path.of("/.dockerenv"))
        || sys.isRegularFile(Path.of("/run/.containerenv"));
  }

  /**
   * Check cgroup information for generic container markers.
   */
  private boolean hasContainerLikeCgroup() {
    // 1) Primary cgroup info detected by LinuxProcFs.
    LinuxProcFs.CgroupInfo cg = sys.detectCgroup();
    String path = cg.path == null ? "" : cg.path.toLowerCase(Locale.ROOT);
    if (containsContainerMarker(path)) {
      return true;
    }

    // 2) /proc/1/cgroup is often more revealing inside containers.
    Path cgroupFile = Path.of("/proc/1/cgroup");
    if (!sys.isRegularFile(cgroupFile)) {
      return false;
    }

    try {
      for (String line : sys.readLines(cgroupFile)) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (containsContainerMarker(lower)) {
          return true;
        }
      }
    } catch (IOException ignore) {
      // Best-effort only; ignore failures.
    }

    return false;
  }

  /**
   * Check cgroup information for Kubernetes-specific markers.
   */
  private boolean hasKubernetesLikeCgroup() {
    // 1) Primary cgroup info detected by LinuxProcFs.
    LinuxProcFs.CgroupInfo cg = sys.detectCgroup();
    String path = cg.path == null ? "" : cg.path.toLowerCase(Locale.ROOT);
    if (containsKubernetesMarker(path)) {
      return true;
    }

    // 2) /proc/1/cgroup for extra hints.
    Path cgroupFile = Path.of("/proc/1/cgroup");
    if (!sys.isRegularFile(cgroupFile)) {
      return false;
    }

    try {
      for (String line : sys.readLines(cgroupFile)) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (containsKubernetesMarker(lower)) {
          return true;
        }
      }
    } catch (IOException ignore) {
      // Best-effort only; ignore failures.
    }

    return false;
  }

  /**
   * Kubernetes service account directory is mounted inside pods.
   */
  private boolean hasKubernetesServiceAccount() {
    Path saDir = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    return sys.isDirectory(saDir) || sys.isRegularFile(saDir.resolve("token"));
  }

  /**
   * Environment variables exported by kubelet for in-cluster clients.
   */
  private boolean hasKubernetesEnvVars() {
    return sys.getEnv("KUBERNETES_SERVICE_HOST") != null
        || sys.getEnv("KUBERNETES_PORT") != null;
  }

  private boolean containsContainerMarker(String s) {
    // Generic container / runtime markers shared by Docker, containerd, Podman, etc.
    return s.contains("docker")
        || s.contains("containerd")
        || s.contains("podman")
        || s.contains("cri-containerd");
  }

  private boolean containsKubernetesMarker(String s) {
    // Kubernetes-specific cgroup markers (v1/v2).
    return s.contains("kubepods")
        || s.contains("kube.slice")
        || s.contains("kubepods.slice");
  }

  // ----- Utility helpers -----

  private String safeProperty(String key) {
    return sys.getProperty(key, "");
  }

  // ----- MBean getters -----

  @Override
  public int getInfoFlag() {
    return INFO_FLAG;
  }

  @Override
  public String getOsRelease() {
    refreshOnRead();
    return osRelease;
  }

  @Override
  public String getKernelVersion() {
    refreshOnRead();
    return kernelVersion;
  }

  @Override
  public String getOsName() {
    refreshOnRead();
    return osName;
  }

  @Override
  public String getOsArch() {
    refreshOnRead();
    return osArch;
  }

  @Override
  public String getEnvironmentType() {
    refreshOnRead();
    return environmentType;
  }

  @Override
  public String getAgentVersion() {
    return AgentMetadata.getVersion();
  }

  @Override
  public String getAgentCommitId() {
    return AgentMetadata.getCommitId();
  }
}
