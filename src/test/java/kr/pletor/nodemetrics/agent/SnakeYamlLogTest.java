package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SnakeYamlLogTest {
  @Test
  void repeatedParserInstancesShareWarningThrottling() {
    List<String> messages = new CopyOnWriteArrayList<>();
    try (var capture = new AgentLogCapture(record -> messages.add(record.getMessage()))) {
      SnakeYamlLog first = SnakeYamlLog.getLogger("first parser");
      assertTrue(first.isLoggable(SnakeYamlLog.Level.WARNING));
      assertFalse(first.isLoggable(SnakeYamlLog.Level.DEBUG));
      first.debug("filtered");
      first.warn("first warning");
      SnakeYamlLog second = SnakeYamlLog.getLogger("second parser");
      second.warn("repeated warning");
    }
    assertEquals(List.of("first warning"), messages);
  }
}
