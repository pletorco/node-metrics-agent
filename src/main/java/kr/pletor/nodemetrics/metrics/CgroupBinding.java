package kr.pletor.nodemetrics.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One bounded membership cache per collector, refreshed only from its polling path. */
final class CgroupBinding {
  static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60L);
  private final Supplier<LinuxProcFs.CgroupInfo> detector;
  private final LongSupplier clock;
  private final AtomicBoolean resolving = new AtomicBoolean();
  private volatile Entry cached;

  private static final class Entry {
    final LinuxProcFs.CgroupInfo info;
    final long checkedAt;

    Entry(LinuxProcFs.CgroupInfo info, long checkedAt) {
      this.info = info;
      this.checkedAt = checkedAt;
    }
  }

  CgroupBinding(Supplier<LinuxProcFs.CgroupInfo> detector) {
    this(detector, System::nanoTime);
  }

  CgroupBinding(Supplier<LinuxProcFs.CgroupInfo> detector, LongSupplier clock) {
    this.detector = detector;
    this.clock = clock;
  }

  LinuxProcFs.CgroupInfo current() {
    long now = clock.getAsLong();
    Entry entry = cached;
    if (entry != null && now - entry.checkedAt < TTL_NANOS) {
      return entry.info;
    }
    if (!resolving.compareAndSet(false, true)) {
      // Never wait behind a stalled resolver, or return an expired binding while it resolves.
      return new LinuxProcFs.CgroupInfo();
    }
    try {
      entry = cached;
      now = clock.getAsLong();
      if (entry != null && now - entry.checkedAt < TTL_NANOS) {
        return entry.info;
      }
      LinuxProcFs.CgroupInfo info = detector.get();
      cached = new Entry(info, clock.getAsLong());
      return info;
    } catch (Throwable failure) { // NOSONAR - expire an old binding even on detector failure
      cached = new Entry(new LinuxProcFs.CgroupInfo(), clock.getAsLong());
      throw failure;
    } finally {
      resolving.set(false);
    }
  }
}
