package kr.pletor.nodemetrics.agent;

/**
 * Private diagnostic bridge for the packaged SnakeYAML parser.
 *
 * <p>Shadow redirects the dependency's internal Logger references here and excludes both its base
 * and multi-release Logger classes. This prevents parser construction from initializing JUL (or the
 * JDK System.Logger bridge). The packaged host probes verify this boundary with real YAML.
 */
public final class SnakeYamlLog {
  private static final SnakeYamlLog INSTANCE = new SnakeYamlLog();
  private static final ThrottledLogger WARNINGS =
      new ThrottledLogger(AgentLog.getLogger(SnakeYamlLog.class.getName()), 30_000L);

  private SnakeYamlLog() {}

  /** Levels used by SnakeYAML's internal diagnostic API. */
  public enum Level {
    WARNING,
    DEBUG
  }

  /** Creates the parser's private logger without touching application logging. */
  public static SnakeYamlLog getLogger(String name) {
    // A new parser is constructed on every reload; its warnings share one bounded throttle.
    return INSTANCE;
  }

  /** Only warning diagnostics are enabled. */
  public boolean isLoggable(Level level) {
    return level == Level.WARNING;
  }

  /** Rate-limit repeated parser warnings before offering them to the private writer. */
  public void warn(String message) {
    WARNINGS.log(java.util.logging.Level.WARNING, "yaml-warning", () -> message);
  }

  /** Debug output is disabled, consistently with the agent's INFO threshold. */
  public void debug(String message) {
    // Deliberately disabled; no host logger or synchronous fallback.
  }
}
