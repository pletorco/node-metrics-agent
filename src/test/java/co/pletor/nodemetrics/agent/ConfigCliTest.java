// src/test/java/co/pletor/nodemetrics/agent/ConfigCliTest.java
package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigCli}.
 * This test suite focuses on:
 * - Verifying CLI behavior for init-config and init-kafka-config
 * - Ensuring that YAML files are generated correctly
 * - Covering error paths in generateConfigFromKafka() and generateConfigFromCli()
 * - Using Java 17 features such as text blocks and pattern matching
 */
class ConfigCliTest {

  @TempDir
  Path tempDir;

  /**
   * Helper method to run the CLI main with given arguments while capturing stdout.
   * This is used for "help" and successful paths where System.exit is not invoked.
   */
  /**
   * Helper method to run the CLI execute with given arguments while capturing stdout.
   */
  private String runMainCaptureStdout(String... args) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
      System.setOut(ps);
      // We ignore the exit code here as we only care about stdout for these tests
      ConfigCli.execute(args);
    } finally {
      System.setOut(originalOut);
    }
    return baos.toString(StandardCharsets.UTF_8);
  }

  @Test
  void helpCommandShouldPrintUsageAndNotThrow() {
    String output = runMainCaptureStdout("help");
    assertTrue(output.contains("Node Metrics Agent CLI"),
        "Help output should contain main title");
    assertTrue(output.contains("init-config"),
        "Help output should mention init-config");
    assertTrue(output.contains("init-kafka-config"),
        "Help output should mention init-kafka-config");
  }

  @Test
  void initConfigShouldGenerateYamlWithDefaultsWhenNoOptions() throws IOException {
    // Given: Only use defaults; we still direct output into a temp dir
    Path output = tempDir.resolve("node-metrics-default.yml");

    int exitCode = ConfigCli.execute(new String[]{
        "init-config",
        "--output", output.toString()
    });

    assertEquals(0, exitCode, "Exit code should be 0 for success");

    assertTrue(Files.exists(output), "Config file should exist for default init-config");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);

    // Default poll_interval_sec
    // Default fsmetrics_paths contains "/"
    assertTrue(yaml.contains("fsmetrics_paths:"),
        "YAML should contain fsmetrics_paths section");
    assertTrue(yaml.contains("fsmetrics_max_partitions: " + Config.DEFAULT_FSMETRICS_MAX_PARTITIONS),
        "YAML should contain default fsmetrics_max_partitions");
    assertTrue(yaml.contains("  - /"),
        "Default fsmetrics_paths should contain root directory /");
  }

  @Test
  void initConfigShouldGenerateYamlFromCliOptions() throws IOException {
    // Given: A target output path and some filesystem paths
    Path output = tempDir.resolve("node-metrics-cli.yml");

    // When: Running init-config with custom poll interval and fs paths
    // When: Running init-config with custom poll interval and fs paths
    int exitCode = ConfigCli.execute(new String[]{
        "init-config",
        "--output", output.toString(),

        "--fs-path", "/data/logs",
        "--fs-path", "/var",
        "--fs-path", "/opt/app"
    });

    assertEquals(0, exitCode, "Exit code should be 0");

    // Then: YAML should exist and contain correct poll interval and fsmetrics_paths
    assertTrue(Files.exists(output), "CLI-based config file should exist");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);


    assertTrue(yaml.contains("fsmetrics_paths:"),
        "YAML should contain fsmetrics_paths section");
    assertTrue(yaml.contains("fsmetrics_max_partitions: " + Config.DEFAULT_FSMETRICS_MAX_PARTITIONS),
        "YAML should contain default fsmetrics_max_partitions");

    assertTrue(yaml.contains("  - /data/logs"),
        "YAML should contain /data/logs");
    assertTrue(yaml.contains("  - /var"),
        "YAML should contain /var");
    assertTrue(yaml.contains("  - /opt/app"),
        "YAML should contain /opt/app");

    // When fs-paths are explicitly provided, "/" should not be injected implicitly
    // (we only expect the paths we passed)
    assertFalse(
        yaml.lines().anyMatch(line -> line.trim().equals("- /")),
        "Root path should not be added when fs-paths are specified explicitly"
    );
  }

  @Test
  void initConfigShouldSupportCommaSeparatedFsPaths() throws IOException {
    // Given: A target output path
    Path output = tempDir.resolve("node-metrics-cli-comma.yml");

    // When: Running init-config with comma-separated and repeated --fs-path
    // When: Running init-config with comma-separated and repeated --fs-path
    int exitCode = ConfigCli.execute(new String[]{
        "init-config",
        "--output", output.toString(),

        "--fs-path", "/data/a,/data/b , /data/c",
        "--fs-path", "/var"
    });

    assertEquals(0, exitCode, "Exit code should be 0");

    assertTrue(Files.exists(output), "CLI-based config file with comma paths should exist");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);


    // From comma-separated value
    assertTrue(yaml.contains("  - /data/a"),
        "YAML should contain /data/a from comma-separated list");
    assertTrue(yaml.contains("  - /data/b"),
        "YAML should contain /data/b from comma-separated list");
    assertTrue(yaml.contains("  - /data/c"),
        "YAML should contain /data/c from comma-separated list");

    // From second --fs-path
    assertTrue(yaml.contains("  - /var"),
        "YAML should contain /var from second --fs-path");
  }

  @Test
  void generateConfigFromCliShouldFailWhenNoFsPathsProvided() {
    // Given: No filesystem paths
    Path output = tempDir.resolve("node-metrics-empty-cli.yml");

    // When / Then: Expect an IOException because fsPaths is empty
    IOException ex = assertThrows(IOException.class, () ->
    ConfigCli.generateConfigFromCli(output, List.of()));

    assertTrue(ex.getMessage().contains("No filesystem paths provided"),
        "Error message should mention missing filesystem paths");
  }

  @Test
  void generateConfigFromCliShouldOverwriteExistingFile() throws IOException {
    Path output = tempDir.resolve("node-metrics-overwrite.yml");

    // First write a file
    Files.writeString(output, "existing", StandardCharsets.UTF_8);

    // generateConfigFromCli should overwrite without needing a force flag
    ConfigCli.generateConfigFromCli(output, List.of("/data"));

    String yaml = Files.readString(output, StandardCharsets.UTF_8);

    assertTrue(yaml.contains("  - /data"),
        "Overwritten YAML should contain new fsmetrics_paths entry");
  }

  @Test
  void initConfigHelpShouldPrintUsageAndReturn() {
    String output = runMainCaptureStdout("init-config", "--help");

    assertTrue(output.contains("Node Metrics Agent CLI"),
        "Help output for init-config should contain main title");
    assertTrue(output.contains("init-config"),
        "Help output should mention init-config");
  }

  @Test
  void resourceExampleFileShouldBeAccessible() throws IOException {
    // This test verifies that node-metrics.example.yml is actually present
    // on the classpath and readable.

    Object resource = ConfigCli.class.getResourceAsStream("/node-metrics.example.yml");

    // Use "o instanceof [type] tv" pattern here as requested
    if (resource instanceof InputStream) {
      InputStream tv = (InputStream) resource;
      byte[] bytes = tv.readAllBytes();
      assertTrue(bytes.length > 0, "Example template resource should not be empty");
    } else {
      fail("node-metrics.example.yml resource not found on classpath");
    }
  }

  // ---------------------------------------------------------------------
  // init-kafka-config related tests
  // ---------------------------------------------------------------------

  @Test
  void initKafkaConfigShouldGenerateYamlFromServerProperties() throws IOException {
    // Given: Kafka server.properties with log.dirs and some extra spacing / empty parts
    Path serverProps = tempDir.resolve("server.properties");
    String props =
        "broker.id=1\n" +
        "log.dirs=/data/kafka-logs-1, /data/kafka-logs-2 , ,/data/kafka-logs-3\n";

    Files.writeString(serverProps, props, StandardCharsets.UTF_8);

    Path output = tempDir.resolve("node-metrics-kafka.yml");

    // When: Running init-kafka-config with custom server.properties, output and poll interval
    // When: Running init-kafka-config with custom server.properties, output and poll interval
    int exitCode = ConfigCli.execute(new String[]{
        "init-kafka-config",
        "--server-properties", serverProps.toString(),
        "--output", output.toString(),

    });

    assertEquals(0, exitCode, "Exit code should be 0");

    // Then: YAML should exist and contain correct poll interval and fsmetrics_paths
    assertTrue(Files.exists(output), "Kafka-based config file should exist");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);

    // All non-empty log dirs must be present (unquoted paths)
    assertTrue(yaml.contains("  - /data/kafka-logs-1"),
        "YAML should contain first log directory");
    assertTrue(yaml.contains("  - /data/kafka-logs-2"),
        "YAML should contain second log directory");
    assertTrue(yaml.contains("  - /data/kafka-logs-3"),
        "YAML should contain third log directory");

    assertTrue(yaml.contains("fsmetrics_paths:"),
        "YAML should contain fsmetrics_paths section");
    assertTrue(yaml.contains("fsmetrics_max_partitions: " + Config.DEFAULT_FSMETRICS_MAX_PARTITIONS),
        "YAML should contain default fsmetrics_max_partitions");
  }

  @Test
  void generateConfigFromKafkaShouldUseLogDirWhenLogDirsMissing() throws IOException {
    // Given: server.properties with only log.dir
    Path serverProps = tempDir.resolve("server-logdir.properties");
    String props =
        "broker.id=2\n" +
        "log.dir=/var/lib/kafka-single\n";
    Files.writeString(serverProps, props, StandardCharsets.UTF_8);

    Path output = tempDir.resolve("node-metrics-logdir.yml");

    // When: Calling generateConfigFromKafka directly
    ConfigCli.generateConfigFromKafka(serverProps, output);

    // Then: YAML should contain the single log.dir path and the correct poll interval
    assertTrue(Files.exists(output), "Config file for log.dir should exist");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);


    assertTrue(yaml.contains("  - /var/lib/kafka-single"),
        "YAML should contain the log.dir path (unquoted)");
  }
  @Test
  void generateConfigFromKafkaShouldFailWhenNoLogDirsOrLogDir() throws IOException {
    // Given: server.properties without log.dirs and log.dir
    Path serverProps = tempDir.resolve("server-nologdirs.properties");
    String props =
        "broker.id=3\n" +
        "listeners=PLAINTEXT://:9092\n";
    Files.writeString(serverProps, props, StandardCharsets.UTF_8);

    Path output = tempDir.resolve("node-metrics-invalid.yml");

    // When / Then: Expect an IOException because no log.dirs or log.dir defined
    IOException ex = assertThrows(IOException.class, () ->
        ConfigCli.generateConfigFromKafka(serverProps, output));

    assertTrue(ex.getMessage().contains("Neither log.dirs nor log.dir is defined"),
        "Error message should mention missing log.dirs/log.dir");
  }

  @Test
  void generateConfigFromKafkaShouldFailWhenFileMissing() {
    // Given: Path to a non-existing server.properties file
    Path missingProps = tempDir.resolve("missing-server.properties");
    Path output = tempDir.resolve("node-metrics-missing.yml");

    // When / Then: Expect an IOException for missing file
    IOException ex = assertThrows(IOException.class, () ->
        ConfigCli.generateConfigFromKafka(missingProps, output));

    assertTrue(ex.getMessage().contains("Kafka server.properties not found"),
        "Error message should mention missing server.properties");
  }

  @Test
  void generateConfigFromKafkaShouldIgnoreEmptyEntriesInLogDirs() throws IOException {
    // Given: server.properties where log.dirs contains empty segments
    Path serverProps = tempDir.resolve("server-empty-logdirs.properties");
    String props =
        "broker.id=4\n" +
        "log.dirs=/data/a,, /data/b,  ,/data/c\n";
    Files.writeString(serverProps, props, StandardCharsets.UTF_8);

    Path output = tempDir.resolve("node-metrics-empty-logdirs.yml");

    // When: Generate config
    ConfigCli.generateConfigFromKafka(serverProps, output);

    // Then: YAML should contain only the non-empty paths
    String yaml = Files.readString(output, StandardCharsets.UTF_8);

    assertTrue(yaml.contains("  - /data/a"), "YAML should contain /data/a");
    assertTrue(yaml.contains("  - /data/b"), "YAML should contain /data/b");
    assertTrue(yaml.contains("  - /data/c"), "YAML should contain /data/c");

    // Ensure that there is no empty entry line such as just "-"
    String[] lines = yaml.split("\\R");
    for (String line : lines) {
      assertNotEquals("-",
          line.trim(),
          "YAML should not contain empty fsmetrics_paths entries");
    }
  }

  @Test
  void initKafkaConfigHelpShouldPrintUsageAndReturn() {
    String output = runMainCaptureStdout("init-kafka-config", "--help");

    assertTrue(output.contains("Node Metrics Agent CLI"),
        "Help output for init-kafka-config should contain main title");
    assertTrue(output.contains("init-kafka-config"),
        "Help output should mention init-kafka-config");
  }

  // ---------------------------------------------------------------------
  // Refactored helper method tests
  // ---------------------------------------------------------------------

  @Test
  void parseCommaSeparatedPathsShouldHandleVariousInputs() {
    // Basic comma separated
    List<String> result = ConfigCli.parseCommaSeparatedPaths("/a,/b");
    assertEquals(List.of("/a", "/b"), result, "Should parse simple comma list");

    // With spaces
    result = ConfigCli.parseCommaSeparatedPaths(" /a , /b  , /c ");
    assertEquals(List.of("/a", "/b", "/c"), result, "Should trim whitespace");

    // Empty parts
    result = ConfigCli.parseCommaSeparatedPaths("/a,,/b");
    assertEquals(List.of("/a", "/b"), result, "Should skip empty parts");

    // Single item
    result = ConfigCli.parseCommaSeparatedPaths("/single");
    assertEquals(List.of("/single"), result, "Should handle single item");

    // Null or empty
    assertEquals(List.of(), ConfigCli.parseCommaSeparatedPaths(null), "Null should return empty list");
    assertEquals(List.of(), ConfigCli.parseCommaSeparatedPaths(""), "Empty string should return empty list");
    assertEquals(List.of(), ConfigCli.parseCommaSeparatedPaths("   "), "Blank string should return empty list");
  }

  @Test
  void buildNodeMetricsYamlShouldIncludeHeaderAndPaths() {
    String header = "# My Custom Header";
    List<String> paths = List.of("/path/1", "/path/2");
    String yaml = ConfigCli.buildNodeMetricsYaml(paths, header);

    assertTrue(yaml.contains(header), "YAML should contain provided header");

    assertTrue(yaml.contains("- /path/1"), "YAML should contain first path");
    assertTrue(yaml.contains("- /path/2"), "YAML should contain second path");
  }

  @Test
  void buildNodeMetricsYamlShouldHandleNullHeader() {
    String yaml = ConfigCli.buildNodeMetricsYaml(List.of("/a"), null);
    assertFalse(yaml.contains("# null"), "Should not print 'null' string");

  }

  @Test
  void parseInitConfigOptionsShouldThrowOnUnknownOption() {
    String[] args = {"init-config", "--unknown-opt", "val"};
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        ConfigCli.parseInitConfigOptions(args));
    assertTrue(ex.getMessage().contains("Unknown option"), "Should throw on unknown option");
  }

  @Test
  void parseInitConfigOptionsShouldThrowOnMissingValue() {
    String[] args = {"init-config", "--output"}; // missing value
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        ConfigCli.parseInitConfigOptions(args));
    assertTrue(ex.getMessage().contains("Missing value"), "Should throw on missing value");
  }

  @Test
  void parseKafkaInitOptionsShouldThrowOnUnknownOption() {
    String[] args = {"init-kafka-config", "--bad-opt"};
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        ConfigCli.parseKafkaInitOptions(args));
    assertTrue(ex.getMessage().contains("Unknown option"), "Should throw on unknown option");
  }

  @Test
  void parseKafkaInitOptionsShouldThrowOnMissingValue() {
    String[] args = {"init-kafka-config", "--server-properties"}; // missing value
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        ConfigCli.parseKafkaInitOptions(args));
    assertTrue(ex.getMessage().contains("Missing value"), "Should throw on missing value");
  }
  @Test
  void executeShouldReturnZeroAndPrintUsageWhenNoArgs() {
    String output = runMainCaptureStdout();
    assertTrue(output.contains("Node Metrics Agent CLI"), "Should print usage when no args provided");
  }

  @Test
  void executeShouldReturnOneWhenCommandIsUnknown() {
    int exitCode = ConfigCli.execute(new String[]{"unknown-cmd"});
    assertEquals(1, exitCode, "Exit code should be 1 for unknown command");
  }

  @Test
  void printUsageShouldHandleMissingResource() {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
      System.setErr(ps);
      // Valid call to package-private method for testing
      ConfigCli.printUsage("/non-existent-usage.txt");
    } finally {
      System.setErr(originalErr);
    }
    String errOutput = baos.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("not found in classpath"), "Should verify missing usage file error");
  }

  @Test
  void runCommandShouldReturnOneOnIllegalArgument() {
    // init-config with unknown option triggers IllegalArgumentException inside parseOptions
    int exitCode = ConfigCli.execute(new String[]{"init-config", "--unknown-opt"});
    assertEquals(1, exitCode, "Should return exit code 1 on illegal argument exception");
  }
}
