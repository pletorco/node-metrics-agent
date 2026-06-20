package co.pletor.nodemetrics.metrics;

import javax.management.DescriptorKey;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to provide a "unit" hint in JMX Descriptor.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface JmxMetricUnit {
    /**
     * The unit string (e.g. "bytes", "seconds").
     *
     * @return the unit string
     */
    @DescriptorKey("unit")
    String value();
}
