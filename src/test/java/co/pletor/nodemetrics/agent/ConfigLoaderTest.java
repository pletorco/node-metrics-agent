package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

  @TempDir
  Path tempDir;

  // util: 파일 생성
  private Path writeYaml(String fileName, String content) {
    try {
      Path p = tempDir.resolve(fileName);
      Files.writeString(p, content, StandardCharsets.UTF_8);
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // util: 테스트용 SHA-256 계산 (ConfigLoader와 동일 로직)
  private String sha256String(byte[] bytes) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(bytes);
    StringBuilder sb = new StringBuilder(d.length * 2);
    for (byte b : d) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  @Test
  void constructor_isPrivateAndThrowsIllegalStateException() throws Exception {
    var ctor = ConfigLoader.class.getDeclaredConstructor();

    // 진짜 private 인지 확인
    assertTrue(Modifier.isPrivate(ctor.getModifiers()));

    // 접근 가능하게 바꾸고 호출 시 예외 확인
    ctor.setAccessible(true);
    InvocationTargetException ex =
        assertThrows(InvocationTargetException.class, ctor::newInstance);

    assertTrue(ex.getCause() instanceof IllegalStateException);
    assertEquals("Utility class", ex.getCause().getMessage());
  }

  @Test
  void loadOrDefault_returnsDefaults_whenPathIsNull() throws Exception {
    Config c = ConfigLoader.loadOrDefault(null);

    assertEquals(java.util.List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
    assertEquals("DEFAULT", c.checksum);
  }

  @Test
  void loadOrDefault_returnsDefaults_whenFileDoesNotExist() throws Exception {
    Path nonExisting = tempDir.resolve("no-such-file.yml");

    Config c = ConfigLoader.loadOrDefault(nonExisting);

    assertEquals(java.util.List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
    assertEquals("DEFAULT", c.checksum);
  }

  @Test
  void loadOrDefault_loadsConfig_whenRegularFileExists() throws Exception {
    String yaml =

        "fsmetrics_paths:\n" +
        "  - /var\n" +
        "  - /opt";

    Path p = writeYaml("config-load-or-default.yml", yaml);

    Config c = ConfigLoader.loadOrDefault(p);

    // loadOrDefault가 load(path)를 타며 값이 잘 로딩되는지

    assertEquals(java.util.List.of("/var", "/opt"), c.fsmetricsPaths);

    // checksum도 실제 파일 기준으로 생성되는지 확인
    String expectedChecksum = sha256String(Files.readAllBytes(p));
    assertEquals(expectedChecksum, c.checksum);
  }

  @Test
  void loadOrDefault_returnsDefaults_whenConfigIsInvalid() throws Exception {
    String invalidYaml =
        "- 1\n" +
        "- 2\n";

    Path p = writeYaml("config-invalid-load-or-default.yml", invalidYaml);
    Config c = ConfigLoader.loadOrDefault(p);

    assertEquals(java.util.List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
    assertEquals("DEFAULT", c.checksum);
  }

  @Test
  void load_loadsAllFieldsFromYaml() throws Exception {
    String yaml =

        "fsmetrics_paths:\n" +
        "  - /var\n" +
        "  - /opt\n" +
        "fsmetrics_max_partitions: 64\n";

    Path p = writeYaml("config.yml", yaml);

    Config c = ConfigLoader.load(p);


    assertEquals(java.util.List.of("/var", "/opt"), c.fsmetricsPaths);
    assertEquals(64, c.fsmetricsMaxPartitions);

    // checksum은 파일 내용의 SHA-256 이어야 한다
    String expectedChecksum = sha256String(Files.readAllBytes(p));
    assertEquals(expectedChecksum, c.checksum);
  }

  @Test
  void load_appliesDefaultsWhenKeysMissing() throws Exception {
    // poll_interval_sec, fsmetrics_paths, flags가 없는 경우
    String yaml = "some_other_key: 123\n";

    Path p = writeYaml("config-missing.yml", yaml);

    Config c = ConfigLoader.load(p);


    // fsmetrics_paths 기본값 "/"
    assertEquals(java.util.List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
  }

  @Test
  void load_acceptsStringAndNonStringTypes() throws Exception {
    String yaml =

        "fsmetrics_paths:\n" +
        "  - 1\n" +
        "  - 2\n";

    Path p = writeYaml("config-string-types.yml", yaml);

    Config c = ConfigLoader.load(p);



    // fsmetrics_paths Object -> String::valueOf 로 변환
    assertEquals(java.util.List.of("1", "2"), c.fsmetricsPaths);
  }

  @Test
  void load_usesDefaultPathsWhenPathsIsNotList() throws Exception {
    String yaml =

        "fsmetrics_paths: /not-a-list\n";

    Path p = writeYaml("config-paths-not-list.yml", yaml);

    Config c = ConfigLoader.load(p);


    // paths가 리스트가 아니면 기본값 "/"
    assertEquals(java.util.List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
  }

  @Test
  void load_usesDefaultMaxPartitionsWhenValueIsInvalid() throws Exception {
    String yaml =
        "fsmetrics_paths:\n" +
            "  - /var\n" +
            "fsmetrics_max_partitions: -1\n";

    Path p = writeYaml("config-max-invalid.yml", yaml);
    Config c = ConfigLoader.load(p);

    assertEquals(java.util.List.of("/var"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
  }

  @Test
  void load_acceptsStringMaxPartitions() throws Exception {
    String yaml =
        "fsmetrics_paths:\n" +
            "  - /var\n" +
            "fsmetrics_max_partitions: \"16\"\n";

    Path p = writeYaml("config-max-string.yml", yaml);
    Config c = ConfigLoader.load(p);

    assertEquals(java.util.List.of("/var"), c.fsmetricsPaths);
    assertEquals(16, c.fsmetricsMaxPartitions);
  }

  @Test
  void load_throwsIllegalArgumentException_whenYamlIsNotMap() {
    // 루트가 리스트인 YAML -> Map 아님
    String yaml =
        "- 1\n" +
        " 2";
    Path p = writeYaml("config-list.yml", yaml);

    assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(p));
  }
}
