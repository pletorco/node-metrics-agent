package kr.pletor.nodemetrics.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the paired-line format of {@code /proc/net/snmp} and {@code /proc/net/netstat}: a
 * header line {@code Tcp: RtoAlgorithm RtoMin ...} immediately followed by a value line {@code Tcp:
 * 1 200 ...} with the same prefix.
 */
final class ProcNetTable {

  private ProcNetTable() {
    // Utility class; no instances.
  }

  /**
   * Returns the named values of one table.
   *
   * @param lines the file's lines
   * @param prefix the table name without the colon, e.g. {@code "Tcp"} or {@code "TcpExt"}
   * @return values by column name; empty when the table is absent. Columns whose value is not a
   *     non-negative number are left out, so callers see them as unavailable.
   */
  static Map<String, Long> parse(List<String> lines, String prefix) {
    Map<String, Long> out = new HashMap<>();
    String marker = prefix + ":";
    for (int i = 0; i + 1 < lines.size(); i++) {
      String header = lines.get(i);
      String values = lines.get(i + 1);
      if (!header.startsWith(marker) || !values.startsWith(marker)) {
        continue;
      }
      String[] names = header.substring(marker.length()).trim().split("\\s+");
      String[] numbers = values.substring(marker.length()).trim().split("\\s+");
      int n = Math.min(names.length, numbers.length);
      for (int c = 0; c < n; c++) {
        try {
          long v = Long.parseLong(numbers[c]);
          if (v >= 0L) {
            out.put(names[c], v);
          }
        } catch (NumberFormatException ignored) {
          // Not a number: leave the column out.
        }
      }
      break;
    }
    return out;
  }
}
