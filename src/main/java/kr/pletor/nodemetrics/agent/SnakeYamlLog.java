package kr.pletor.nodemetrics.agent;

/**
 * Private diagnostic bridge for the packaged SnakeYAML parser.
 *
 * <p>Shadow redirects the dependency's internal Logger references here and excludes both its base
 * and multi-release Logger classes. This prevents parser construction from initializing JUL (or the
 * JDK System.Logger bridge). The packaged host probes verify this boundary with real YAML.
 */
public final class SnakeYamlLog {
  private final ThrottledLogger logger;

  private SnakeYamlLog(String name) {
    logger = new ThrottledLogger(AgentLog.getLogger(name), 30_000L);
  }

  /** Levels used by SnakeYAML's internal diagnostic API. */
  public enum Level {
    WARNING,
    DEBUG
  }

  /** Creates the parser's private logger without touching application logging. */
  public static SnakeYamlLog getLogger(String name) {
    return new SnakeYamlLog(name);
  }

  /** Only warning diagnostics are enabled. */
  public boolean isLoggable(Level level) {
    return level == Level.WARNING;
  }

  /** Rate-limit repeated parser warnings before offering them to the private writer. */
  public void warn(String message) {
    logger.log(java.util.logging.Level.WARNING, "yaml-warning", () -> message);
  }

  /** Debug output is disabled, consistently with the agent's INFO threshold. */
  public void debug(String message) {
    // Deliberately disabled; no host logger or synchronous fallback.
  }
}
