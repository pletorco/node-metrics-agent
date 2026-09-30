package kr.pletor.nodemetrics.metrics;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.management.DescriptorKey;

/**
 * Annotation to provide a "metricType" hint in JMX Descriptor.
 *
 * <p>Use this to distinguish between "counter" and "gauge".
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface JmxMetricHint {
  /**
   * The metric type hint (e.g. "gauge", "counter").
   *
   * @return the metric type string
   */
  @DescriptorKey("metricType")
  String value();
}
