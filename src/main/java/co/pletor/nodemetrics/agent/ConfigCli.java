package co.pletor.nodemetrics.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Simple CLI entry point for configuration related helpers.
 * <p>
 * Sub-commands:
 * <ul>
 *   <li>init-config       – generate node-metrics.yml from CLI options</li>
 *   <li>init-kafka-config – generate node-metrics.yml from Kafka server.properties</li>
 * </ul>
 * <p>
 * This class is independent from the Java agent entry point (premain).
 * When the JAR is executed with {@code java -jar}, the {@link #main(String[])} method is used.
 */
public final class ConfigCli {
  private static final String CONFIG_DIR_1 = "config";
  private static final String OUTPUT_OPT_1 = "--output";

  private ConfigCli() {
    // Utility class; no instances.
  }

  /**
   * Common base for CLI option containers that share
   * output path, poll interval, and help flag.
   */
  private abstract static class CommonOptions {
    Path outputConfigPath = Path.of(CONFIG_DIR_1, "node-metrics.yml");

    boolean helpRequested;
  }

  /**
   * CLI entrypoint
   * @param args  command line arguments
   */
  public static void main(String[] args) {
    System.exit(execute(args));
  }

  /**
   * Internal entrypoint for testing that avoids System.exit.
   * @return exit code (0 for success, non-zero for failure)
   */
  public static int execute(String[] args) {
    if (args.length == 0) {
      printUsage();
      return 0;
    }

    String command = args[0];
    switch (command) {
      case "init-config":
        return handleInitConfig(args);
      case "init-kafka-config":
        return handleInitKafkaConfig(args);
      case "help":
      case "--help":
      case "-h":
        printUsage();
        return 0;
      default:
        System.err.println("Unknown command: " + command);
        printUsage();
        return 1;
    }
  }

  /**
   * Print simple usage help for the CLI.
   */
  private static void printUsage() {
    printUsage("/usage.txt");
  }

  // Visible for testing
  static void printUsage(String resourcePath) {
    try (InputStream in = ConfigCli.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        System.err.println("Error: " + resourcePath + " not found in classpath");
        return;
      }
      String usage = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      System.out.println(usage);
    } catch (IOException e) {
      System.err.println("Failed to load usage text: " + e.getMessage());
    }
  }

  // =====================================================================
  // Shared helpers
  // =====================================================================

  /**
   * Apply options that are common to both init-config and init-kafka-config:
   * --output, --help/-h
   *
   * @return next index to process (may be unchanged if arg is not common)
   */
  private static int applyCommonOption(String[] args, int index, CommonOptions options) {
    String arg = args[index];

    if (OUTPUT_OPT_1.equals(arg)) {
      String value = requireOptionValue(args, index, OUTPUT_OPT_1);
      options.outputConfigPath = Path.of(value);
      return index + 2;
    }

    if ("--help".equals(arg) || "-h".equals(arg)) {
      options.helpRequested = true;
      return index + 1;
    }

    // Not a common option; caller must handle
    return index;
  }

  /**
   * Ensure that an option at index has a following value, or throw an IllegalArgumentException.
   */
  private static String requireOptionValue(String[] args, int index, String optionName) {
    if (index + 1 >= args.length) {
      throw new IllegalArgumentException("Missing value for " + optionName);
    }
    return args[index + 1];
  }



  /**
   * Ensure output directory exists and write the YAML content.
   */
  private static void writeYamlFile(Path outputConfigPath, String yaml) throws IOException {
    Path parent = outputConfigPath.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
    }
    Files.writeString(outputConfigPath, yaml, StandardCharsets.UTF_8);
  }

  /**
   * Common error handler for configuration generation failures.
   */
  private static int handleGenerationFailure(String source, IOException e) {
    System.err.println("Failed to generate node-metrics.yml from " + source + ": " + e.getMessage());
    return 1;
  }

  // =====================================================================
  // init-config: build node-metrics.yml from CLI options
  // =====================================================================

  private static int handleInitConfig(String[] args) {
    return runCommand(
        args,
        ConfigCli::parseInitConfigOptions,
        opts -> generateConfigFromCli(opts.outputConfigPath, opts.fsPaths),
        "CLI-based",
        "CLI options"
    );
  }

  private static final class InitConfigOptions extends CommonOptions {
    final List<String> fsPaths = new ArrayList<>();
  }

  static InitConfigOptions parseInitConfigOptions(String[] args) {
    return parseOptions(args, InitConfigOptions::new, (a, i, opts) -> {
      if ("--fs-path".equals(a[i])) {
        String value = requireOptionValue(a, i, "--fs-path");
        opts.fsPaths.addAll(parseCommaSeparatedPaths(value));
        return i + 2;
      }
      throw new IllegalArgumentException("Unknown option for init-config: " + a[i]);
    });
  }

  /**
   * Core logic for init-config:
   * - Use CLI-provided poll_interval_sec
   * - Use CLI-provided list of fsmetrics_paths (or default if none given)
   * - Generate minimal YAML file
   * - Existing file is overwritten if present
   */
  static void generateConfigFromCli(
      Path outputConfigPath,
      List<String> fsPaths
  ) throws IOException {

    if (fsPaths == null || fsPaths.isEmpty()) {
      throw new IOException("No filesystem paths provided for fsmetrics_paths");
    }

    String header = "# Auto-generated Node Metrics Agent configuration from CLI options";
    String yaml = buildNodeMetricsYaml(fsPaths, header);
    writeYamlFile(outputConfigPath, yaml);
  }

  // =====================================================================
  // init-kafka-config: build node-metrics.yml from server.properties
  // =====================================================================

  private static int handleInitKafkaConfig(String[] args) {
    return runCommand(
        args,
        ConfigCli::parseKafkaInitOptions,
        opts -> generateConfigFromKafka(opts.serverPropertiesPath, opts.outputConfigPath),
        "Kafka-based",
        "Kafka config"
    );
  }

  private static final class KafkaInitOptions extends CommonOptions {
    Path serverPropertiesPath = Path.of(CONFIG_DIR_1, "server.properties");
  }

  static KafkaInitOptions parseKafkaInitOptions(String[] args) {
    return parseOptions(args, KafkaInitOptions::new, (a, i, opts) -> {
      if ("--server-properties".equals(a[i])) {
        String value = requireOptionValue(a, i, "--server-properties");
        opts.serverPropertiesPath = Path.of(value);
        return i + 2;
      }
      throw new IllegalArgumentException("Unknown option for init-kafka-config: " + a[i]);
    });
  }

  // =====================================================================
  // Generic Helpers
  // =====================================================================

  @FunctionalInterface
  private interface ThrowingConsumer<T, E extends Throwable> {
    void accept(T t) throws E;
  }

  @FunctionalInterface
  private interface OptionHandler<T> {
    int handle(String[] args, int index, T options);
  }

  private static <T extends CommonOptions> int runCommand(
      String[] args,
      java.util.function.Function<String[], T> parser,
      ThrowingConsumer<T, IOException> generator,
      String successPrefix,
      String failureSource
  ) {
    final T options;
    try {
      options = parser.apply(args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      printUsage();
      return 1;
    }

    if (options.helpRequested) {
      printUsage();
      return 0;
    }

    // specific post-parsing logic for init-config
    if (options instanceof InitConfigOptions) {
      InitConfigOptions initOpts = (InitConfigOptions) options;
      if (initOpts.fsPaths.isEmpty()) {
        initOpts.fsPaths.add("/");
      }
    }

    try {
      generator.accept(options);
      System.out.println(
          successPrefix + " node-metrics.yml generated at: " + options.outputConfigPath.toAbsolutePath()
      );
      return 0;
    } catch (IOException e) {
      return handleGenerationFailure(failureSource, e);
    }
  }

  private static <T extends CommonOptions> T parseOptions(
      String[] args,
      java.util.function.Supplier<T> factory,
      OptionHandler<T> specificHandler
  ) {
    T options = factory.get();
    int index = 1;
    while (index < args.length) {
      int next = applyCommonOption(args, index, options);
      if (next != index) {
        index = next;
        continue;
      }
      index = specificHandler.handle(args, index, options);
    }
    return options;
  }

  static void generateConfigFromKafka(
      Path serverPropertiesPath,
      Path outputConfigPath
  ) throws IOException {

    if (!Files.exists(serverPropertiesPath)) {
      throw new IOException("Kafka server.properties not found: " + serverPropertiesPath);
    }

    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(serverPropertiesPath)) {
      props.load(in);
    }

    String logDirs = props.getProperty("log.dirs");
    if (logDirs == null || logDirs.isBlank()) {
      logDirs = props.getProperty("log.dir");
    }

    if (logDirs == null || logDirs.isBlank()) {
      throw new IOException(
          "Neither log.dirs nor log.dir is defined in server.properties: " + serverPropertiesPath
      );
    }

    List<String> fsPaths = parseCommaSeparatedPaths(logDirs);
    if (fsPaths.isEmpty()) {
      throw new IOException(
          "log.dirs/log.dir is defined but contains no valid paths: " + logDirs
      );
    }

    String header = "# Auto-generated Node Metrics Agent configuration from Kafka server.properties" + System.lineSeparator() +
                    "# Source: " + serverPropertiesPath.toAbsolutePath();
    String yaml = buildNodeMetricsYaml(fsPaths, header);
    writeYamlFile(outputConfigPath, yaml);
  }

  /**
   * Split a comma-separated string into a list of trimmed strings.
   * Ignores empty entries.
   */
  static List<String> parseCommaSeparatedPaths(String input) {
    List<String> paths = new ArrayList<>();
    if (input == null || input.isBlank()) {
      return paths;
    }
    String[] parts = input.split(",");
    for (String raw : parts) {
      String trimmed = raw.trim();
      if (!trimmed.isEmpty()) {
        paths.add(trimmed);
      }
    }
    return paths;
  }

  /**
   * Build YAML content for node-metrics.yml.
   *
   * Example output:
   * # <header>
   *
   *   - /path/two
   */
  static String buildNodeMetricsYaml(List<String> fsPaths, String header) {
    String lineSep = System.lineSeparator();
    StringBuilder sb = new StringBuilder();

    if (header != null && !header.isEmpty()) {
      sb.append(header).append(lineSep);
      sb.append(lineSep);
    }

    sb.append("fsmetrics_max_partitions: ")
        .append(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS)
        .append(lineSep);
    sb.append("fsmetrics_paths:").append(lineSep);
    for (String path : fsPaths) {
      // Paths are written without quotes (simple YAML sequence of strings)
      sb.append("  - ").append(path).append(lineSep);
    }

    return sb.toString();
  }
}
