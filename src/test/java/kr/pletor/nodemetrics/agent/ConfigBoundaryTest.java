package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.error.YAMLException;

class ConfigBoundaryTest {
  @TempDir Path directory;

  @Test
  void exactByteLimitIsAcceptedAndOneExtraByteIsRejected() throws Exception {
    String prefix = "fsmetrics_paths: [/known-good]\n#";
    String yaml = prefix + " ".repeat(ConfigLoader.MAX_CONFIG_BYTES - prefix.length());
    Path path = directory.resolve("limit.yml");
    Files.writeString(path, yaml);
    assertEquals(List.of("/known-good"), ConfigLoader.load(path).fsmetricsPaths);
    Files.writeString(path, yaml + " ");
    assertThrows(IOException.class, () -> ConfigLoader.load(path));
    assertEquals("DEFAULT", ConfigLoader.loadOrDefault(path).checksum);
  }

  @Test
  void byteLimitAlsoAppliesToMultibyteUtf8() throws Exception {
    Path path = directory.resolve("unicode.yml");
    String yaml = "fsmetrics_paths: [/]\n#" + "가".repeat(ConfigLoader.MAX_CONFIG_BYTES / 2);
    assertTrue(yaml.length() < ConfigLoader.MAX_CONFIG_BYTES);
    Files.writeString(path, yaml, StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> ConfigLoader.load(path));
  }

  @Test
  void growingOrUnboundedStreamCannotBeReadBeyondLimitPlusOne() {
    AtomicInteger read = new AtomicInteger();
    InputStream growing =
        new InputStream() {
          @Override
          public int read() {
            assertTrue(
                read.incrementAndGet() <= ConfigLoader.MAX_CONFIG_BYTES + 1,
                "The read itself must be bounded, even without a trustworthy file size");
            return 'x';
          }
        };
    assertThrows(IOException.class, () -> ConfigLoader.readBounded(growing));
    assertEquals(ConfigLoader.MAX_CONFIG_BYTES + 1, read.get());
  }

  @Test
  void unsafeTagsAndRecursiveOrExcessiveCollectionAliasesAreRejected() throws Exception {
    Path path = directory.resolve("unsafe.yml");
    for (String yaml :
        List.of(
            "fsmetrics_paths: [!!java.net.URL 'https://example.invalid']\n",
            "fsmetrics_paths: &paths [*paths]\n",
            "shared: &paths [/tmp]\nfsmetrics_paths: [" + "*paths,".repeat(100) + "]\n")) {
      Files.writeString(path, yaml);
      assertThrows(YAMLException.class, () -> ConfigLoader.load(path), yaml);
    }
  }

  @Test
  void excessiveNestingIsRejectedWhileOrdinaryNestedYamlRemainsBounded() throws Exception {
    Path path = directory.resolve("depth.yml");
    Files.writeString(path, "ignored: " + "[".repeat(8) + "0" + "]".repeat(8));
    assertEquals(List.of("/"), ConfigLoader.load(path).fsmetricsPaths);
    Files.writeString(
        path,
        "ignored: "
            + "[".repeat(ConfigLoader.MAX_YAML_DEPTH + 1)
            + "0"
            + "]".repeat(ConfigLoader.MAX_YAML_DEPTH + 1));
    assertThrows(YAMLException.class, () -> ConfigLoader.load(path));
  }

  @Test
  void rejectedReloadsKeepLastGoodConfigAndLaterValidInputRecovers() throws Exception {
    Path path = directory.resolve("reload.yml");
    AtomicReference<Config> applied = new AtomicReference<>();
    AtomicInteger applications = new AtomicInteger();
    final ConfigReloader reloader =
        new ConfigReloader(
            path,
            config -> {
              applied.set(config);
              applications.incrementAndGet();
            });
    Method check = ConfigReloader.class.getDeclaredMethod("checkAndReloadIfChanged");
    check.setAccessible(true);
    long mtime = System.currentTimeMillis();
    Files.writeString(path, "fsmetrics_paths: [/known-good]\n");
    Files.setLastModifiedTime(path, FileTime.fromMillis(++mtime));
    check.invoke(reloader);
    String checksum = applied.get().checksum;
    for (String rejected :
        List.of(
            "fsmetrics_paths: &paths [*paths]",
            "fsmetrics_paths: [!!java.net.URL 'https://example.invalid']",
            "ignored: " + "[".repeat(30) + "0" + "]".repeat(30),
            "#" + "x".repeat(ConfigLoader.MAX_CONFIG_BYTES))) {
      Files.writeString(path, rejected);
      Files.setLastModifiedTime(path, FileTime.fromMillis(++mtime));
      check.invoke(reloader);
      assertEquals(1, applications.get(), "Rejected config must not reach the applier");
      assertEquals(List.of("/known-good"), applied.get().fsmetricsPaths);
      assertEquals(checksum, applied.get().checksum);
    }
    Files.writeString(path, "fsmetrics_paths: [/recovered]\n");
    Files.setLastModifiedTime(path, FileTime.fromMillis(++mtime));
    check.invoke(reloader);
    assertEquals(2, applications.get());
    assertEquals(List.of("/recovered"), applied.get().fsmetricsPaths);
    reloader.stop();
  }
}
