package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

  @Test
  void defaults_shouldSetExpectedValues() {
    Config c = Config.defaults();

    assertEquals(List.of("/"), c.fsmetricsPaths);
    assertEquals(Config.DEFAULT_FSMETRICS_MAX_PARTITIONS, c.fsmetricsMaxPartitions);
    assertEquals("DEFAULT", c.checksum);
  }

  @Test
  void equals_shouldBeTrueForSameFieldValues() {
    Config a = Config.defaults();
    Config b = Config.defaults();

    // equals(Object) true 경로
    assertEquals(a, b);
    assertEquals(b, a);

    // hashCode도 일관성 확인
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void equals_shouldBeFalseWhenAnyFieldDiffers() {
    Config base = Config.defaults();



    // fsmetrics_paths 다를 때
    Config c2 = Config.defaults();
    c2.fsmetricsPaths = List.of("/data");
    assertNotEquals(base, c2);

    // _checksum 다를 때
    Config c5 = Config.defaults();
    c5.checksum = "OTHER";
    assertNotEquals(base, c5);

    // fsmetrics_max_partitions 다를 때
    Config c6 = Config.defaults();
    c6.fsmetricsMaxPartitions = Config.DEFAULT_FSMETRICS_MAX_PARTITIONS + 1;
    assertNotEquals(base, c6);
  }

  @Test
  void equals_shouldHandleNullAndDifferentType() {
    Config c = Config.defaults();

    // equals(Object)를 직접 호출해서 instanceof 분기(false 경로) 태우기
    assertEquals(false, c.equals(null));
    assertEquals(false, c.equals("not-a-config"));

    // JUnit 헬퍼도 같이 사용 (이 쪽도 equals를 호출함)
    assertNotEquals(null, c);
    assertNotEquals("not-a-config",c);
  }

  @Test
  void hashCode_changesWhenImportantFieldsChange() {
    Config base = Config.defaults();
    int baseHash = base.hashCode();

    Config modified = Config.defaults();
    modified.fsmetricsPaths = List.of("/changed");

    // 필드 변경 후 hashCode가 달라지는 게 자연스럽다
    assertNotEquals(baseHash, modified.hashCode());
  }

  @Test
  void toString_shouldContainKeyFields() {
    Config c = Config.defaults();
    String s = c.toString();


    assertTrue(s.contains("fsmetrics_paths=[/]"));
    assertTrue(s.contains("fsmetrics_max_partitions=" + Config.DEFAULT_FSMETRICS_MAX_PARTITIONS));
  }
}
