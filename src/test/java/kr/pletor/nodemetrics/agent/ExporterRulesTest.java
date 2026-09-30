package kr.pletor.nodemetrics.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.pletor.nodemetrics.metrics.CgroupMemMetricsMBean;
import kr.pletor.nodemetrics.metrics.CpuMetricsMBean;
import kr.pletor.nodemetrics.metrics.DiskIoMetricsMBean;
import kr.pletor.nodemetrics.metrics.FdMetricsMBean;
import kr.pletor.nodemetrics.metrics.FsMetricsMBean;
import kr.pletor.nodemetrics.metrics.IoRatesMBean;
import kr.pletor.nodemetrics.metrics.JmxMetricHint;
import kr.pletor.nodemetrics.metrics.MemoryMapMetricsMBean;
import kr.pletor.nodemetrics.metrics.NetworkMetricsMBean;
import kr.pletor.nodemetrics.metrics.NodeMemMetricsMBean;
import kr.pletor.nodemetrics.metrics.OsInfoMetricsMBean;
import kr.pletor.nodemetrics.metrics.OsRuntimeMetricsMBean;
import kr.pletor.nodemetrics.metrics.PidsMetricsMBean;
import kr.pletor.nodemetrics.metrics.PressureMetricsMBean;
import kr.pletor.nodemetrics.metrics.ProcessMetricsMBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the example exporter rules, the MBean attributes and the example alerts consistent.
 *
 * <p>Every numeric attribute needs a {@link JmxMetricHint} and a rule that types it accordingly.
 * Cumulative counters must not be exported as {@code COUNTER}: they report {@code -1} when their
 * source is unavailable, and the Prometheus JMX exporter 1.x fails the entire scrape (HTTP 500) on
 * a negative counter. Only the agent's own counters, which are never negative, may be {@code
 * COUNTER}.
 */
class ExporterRulesTest {

  private static final String AGENT_DOMAIN = "kr.pletor.agent";

  /** One registered MBean: its interface and the JMX name it is registered under. */
  private static final class Bean {
    private final Class<?> type;
    private final String domain;
    private final String properties;

    Bean(Class<?> type, String domain, String properties) {
      this.type = type;
      this.domain = domain;
      this.properties = properties;
    }

    Class<?> type() {
      return type;
    }

    String domain() {
      return domain;
    }

    String exporterName(String attribute) {
      return domain + "<" + properties + "><>" + attribute + ":";
    }
  }

  private static final List<Bean> BEANS =
      List.of(
          new Bean(AgentObservabilityMetricsMBean.class, AGENT_DOMAIN, "type=Observability"),
          new Bean(TelemetryModeMetricsMBean.class, AGENT_DOMAIN, "type=TelemetryMode"),
          new Bean(CgroupMemMetricsMBean.class, "kr.pletor.cgroup", "type=MemMetrics"),
          new Bean(PidsMetricsMBean.class, "kr.pletor.cgroup", "type=PidsMetrics"),
          new Bean(PressureMetricsMBean.class, "kr.pletor.cgroup", "type=PressureMetrics"),
          new Bean(PressureMetricsMBean.class, "kr.pletor.node", "type=PressureMetrics"),
          new Bean(CpuMetricsMBean.class, "kr.pletor.node", "type=CpuMetrics"),
          new Bean(DiskIoMetricsMBean.class, "kr.pletor.node", "type=DiskIoMetrics"),
          new Bean(FsMetricsMBean.class, "kr.pletor.node", "type=FsMetrics, path=/data"),
          new Bean(IoRatesMBean.class, "kr.pletor.node", "type=IoRates"),
          new Bean(NetworkMetricsMBean.class, "kr.pletor.node", "type=NetworkMetrics"),
          new Bean(NodeMemMetricsMBean.class, "kr.pletor.node", "type=MemMetrics"),
          new Bean(OsInfoMetricsMBean.class, "kr.pletor.node", "type=OsInfoMetrics"),
          new Bean(OsRuntimeMetricsMBean.class, "kr.pletor.node", "type=OsRuntimeMetrics"),
          new Bean(FdMetricsMBean.class, "kr.pletor.proc", "type=FdMetrics"),
          new Bean(MemoryMapMetricsMBean.class, "kr.pletor.proc", "type=MemoryMapMetrics"),
          new Bean(ProcessMetricsMBean.class, "kr.pletor.proc", "type=ProcessMetrics"));

  private static final class Rule {
    private final Pattern pattern;
    private final String name;
    private final String type;

    Rule(Pattern pattern, String name, String type) {
      this.pattern = pattern;
      this.name = name;
      this.type = type;
    }

    Pattern pattern() {
      return pattern;
    }

    String name() {
      return name;
    }

    String type() {
      return type;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYaml(String resource) throws IOException {
    try (InputStream in = ExporterRulesTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, resource + " must be on the classpath");
      try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return new Yaml().load(reader);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Rule> loadRules() throws IOException {
    List<Rule> rules = new ArrayList<>();
    for (Map<String, Object> rule :
        (List<Map<String, Object>>) loadYaml("/jmx_exporter_rules_example.yml").get("rules")) {
      rules.add(
          new Rule(
              Pattern.compile((String) rule.get("pattern")),
              (String) rule.get("name"),
              (String) rule.get("type")));
    }
    return rules;
  }

  /** Numeric getters of an MBean interface, by attribute name. */
  private static List<Method> numericGetters(Class<?> type) {
    List<Method> getters = new ArrayList<>();
    for (Method m : type.getMethods()) {
      String n = m.getName();
      boolean getter =
          (n.startsWith("get") && n.length() > 3) || (n.startsWith("is") && n.length() > 2);
      if (getter && m.getParameterCount() == 0 && m.getReturnType() != String.class) {
        getters.add(m);
      }
    }
    return getters;
  }

  private static String attribute(Method getter) {
    String n = getter.getName();
    return n.startsWith("get") ? n.substring(3) : n.substring(2);
  }

  /** The first rule matching the way the exporter picks one, or null. */
  private static Matcher firstMatch(List<Rule> rules, String beanName, Rule[] matched) {
    for (Rule rule : rules) {
      Matcher m = rule.pattern().matcher(beanName);
      if (m.matches()) {
        matched[0] = rule;
        return m;
      }
    }
    return null;
  }

  /** The exported metric name: the rule's template expanded and lower-cased. */
  private static String metricName(Rule rule, Matcher m) {
    String name = rule.name();
    for (int group = m.groupCount(); group >= 1; group--) {
      name = name.replace("$" + group, m.group(group) == null ? "" : m.group(group));
    }
    return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
  }

  @Test
  void everyNumericAttributeHasHintAndMatchingRule() throws IOException {
    List<Rule> rules = loadRules();
    List<String> problems = new ArrayList<>();
    for (Bean bean : BEANS) {
      for (Method getter : numericGetters(bean.type())) {
        String attribute = attribute(getter);
        String where = bean.exporterName(attribute);
        JmxMetricHint hint = getter.getAnnotation(JmxMetricHint.class);
        if (hint == null) {
          problems.add(where + " has no @JmxMetricHint");
          continue;
        }
        Rule[] matched = new Rule[1];
        if (firstMatch(rules, where, matched) == null) {
          problems.add(where + " is matched by no rule");
          continue;
        }
        String expected;
        if ("gauge".equals(hint.value())) {
          expected = "GAUGE";
        } else if ("counter".equals(hint.value())) {
          expected = AGENT_DOMAIN.equals(bean.domain()) ? "COUNTER" : "UNTYPED";
        } else {
          problems.add(where + " has unknown hint " + hint.value());
          continue;
        }
        if (!expected.equals(matched[0].type())) {
          problems.add(
              where
                  + ": hint '"
                  + hint.value()
                  + "' needs rule type "
                  + expected
                  + " but the rule says "
                  + matched[0].type());
        }
      }
    }
    assertTrue(
        problems.isEmpty(),
        "Exporter rules and MBean hints disagree:\n" + String.join("\n", problems));
  }

  @Test
  void noCounterRuleOutsideTheAgentDomain() throws IOException {
    for (Rule rule : loadRules()) {
      if ("COUNTER".equals(rule.type())) {
        assertTrue(
            rule.pattern().pattern().startsWith(AGENT_DOMAIN),
            "A COUNTER rule fails the whole scrape when the value is -1: " + rule.pattern());
      }
    }
  }

  @Test
  void everyMBeanInterfaceIsInTheCatalog() throws URISyntaxException, IOException {
    URL location = CpuMetricsMBean.class.getResource("CpuMetricsMBean.class");
    Assumptions.assumeTrue(location != null && "file".equals(location.getProtocol()));
    Set<String> known = new HashSet<>();
    BEANS.forEach(b -> known.add(b.type().getSimpleName()));
    Set<String> found = new TreeSet<>();
    for (Class<?> anchor : List.of(CpuMetricsMBean.class, AgentObservabilityMetricsMBean.class)) {
      Path dir =
          Paths.get(anchor.getResource(anchor.getSimpleName() + ".class").toURI()).getParent();
      try (Stream<Path> files = Files.list(dir)) {
        files
            .map(p -> p.getFileName().toString())
            .filter(n -> n.endsWith("MBean.class"))
            .map(n -> n.substring(0, n.length() - ".class".length()))
            .forEach(found::add);
      }
    }
    found.removeAll(known);
    assertTrue(
        found.isEmpty(), "MBean interfaces missing from the exporter-rule catalog: " + found);
  }

  @Test
  @SuppressWarnings("unchecked")
  void exampleAlertsOnlyUseMetricsTheExporterProduces() throws IOException {
    List<Rule> rules = loadRules();
    Set<String> exported = new HashSet<>();
    for (Bean bean : BEANS) {
      for (Method getter : numericGetters(bean.type())) {
        Rule[] matched = new Rule[1];
        Matcher m = firstMatch(rules, bean.exporterName(attribute(getter)), matched);
        if (m != null) {
          exported.add(metricName(matched[0], m));
        }
      }
    }

    Map<String, Object> alerts = loadYaml("/prometheus_alerts_example.yml");
    List<Map<String, Object>> groups = (List<Map<String, Object>>) alerts.get("groups");
    assertFalse(groups.isEmpty());
    Set<String> alertNames = new HashSet<>();
    Pattern metric = Pattern.compile("pletor_[a-z0-9_]+");
    int expressions = 0;
    for (Map<String, Object> group : groups) {
      for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
        String name = (String) rule.get("alert");
        assertNotNull(name, "every rule is an alert");
        assertTrue(alertNames.add(name), "duplicate alert name " + name);
        String expr = (String) rule.get("expr");
        assertNotNull(expr, name + " needs an expr");
        Matcher m = metric.matcher(expr);
        boolean any = false;
        while (m.find()) {
          any = true;
          assertTrue(exported.contains(m.group()), name + " uses unknown metric " + m.group());
        }
        assertTrue(any, name + " uses no pletor_ metric");
        expressions++;
      }
    }
    assertEquals(alertNames.size(), expressions);
  }
}
