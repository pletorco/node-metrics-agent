package co.pletor.nodemetrics.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class to provide access to agent metadata (version, commit ID).
 */
public final class AgentMetadata {

  private static final String VERSION;
  private static final String COMMIT_ID;

  static {
    Properties props = new Properties();
    try (InputStream is = AgentMetadata.class.getResourceAsStream("/version.properties")) {
      if (is != null) {
        props.load(is);
      }
    } catch (IOException ignored) {
      // Fallback to defaults
    }
    VERSION = props.getProperty("version", "unknown");
    COMMIT_ID = props.getProperty("commitId", "unknown");
  }

  private AgentMetadata() {}

  /**
   * Returns the agent version.
   *
   * @return the agent version string
   */
  public static String getVersion() {
    return VERSION;
  }

  /**
   * Returns the agent build commit ID.
   *
   * @return the commit ID string
   */
  public static String getCommitId() {
    return COMMIT_ID;
  }
}
