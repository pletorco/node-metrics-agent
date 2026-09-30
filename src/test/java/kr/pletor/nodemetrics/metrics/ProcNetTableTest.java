package kr.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcNetTableTest {

  private static final List<String> SNMP =
      List.of(
          "Ip: Forwarding DefaultTTL",
          "Ip: 1 64",
          "Tcp: RtoAlgorithm MaxConn OutSegs RetransSegs CurrEstab",
          "Tcp: 1 -1 5000 25 12",
          "Udp: InDatagrams",
          "Udp: 7");

  @Test
  void parsesTheRequestedTableOnly() {
    Map<String, Long> tcp = ProcNetTable.parse(SNMP, "Tcp");

    assertEquals(5000L, tcp.get("OutSegs"));
    assertEquals(25L, tcp.get("RetransSegs"));
    assertEquals(12L, tcp.get("CurrEstab"));
    assertNull(tcp.get("DefaultTTL"), "other tables are not mixed in");
    assertNull(tcp.get("InDatagrams"));
  }

  @Test
  void negativeAndNonNumericColumnsAreLeftOut() {
    Map<String, Long> tcp = ProcNetTable.parse(SNMP, "Tcp");
    assertNull(tcp.get("MaxConn"), "-1 means 'no limit', not a count");

    Map<String, Long> odd = ProcNetTable.parse(List.of("Tcp: A B C", "Tcp: 1 x 3"), "Tcp");
    assertEquals(1L, odd.get("A"));
    assertNull(odd.get("B"));
    assertEquals(3L, odd.get("C"));
  }

  @Test
  void prefixMustMatchWholeTableName() {
    List<String> lines = List.of("TcpExt: ListenDrops", "TcpExt: 9", "Tcp: OutSegs", "Tcp: 4");

    assertEquals(9L, ProcNetTable.parse(lines, "TcpExt").get("ListenDrops"));
    assertEquals(4L, ProcNetTable.parse(lines, "Tcp").get("OutSegs"));
    assertNull(ProcNetTable.parse(lines, "Tcp").get("ListenDrops"));
  }

  @Test
  void absentTruncatedOrMismatchedTablesGiveAnEmptyMap() {
    assertTrue(ProcNetTable.parse(List.of(), "Tcp").isEmpty());
    assertTrue(ProcNetTable.parse(List.of("Tcp: OutSegs"), "Tcp").isEmpty());
    assertTrue(ProcNetTable.parse(List.of("Tcp: OutSegs", "Udp: 1"), "Tcp").isEmpty());
    assertTrue(ProcNetTable.parse(SNMP, "Sctp").isEmpty());
  }

  @Test
  void extraColumnsOnEitherSideAreIgnored() {
    Map<String, Long> t = ProcNetTable.parse(List.of("Tcp: A B", "Tcp: 1 2 3"), "Tcp");

    assertEquals(2, t.size());
    assertEquals(2L, t.get("B"));
  }
}
