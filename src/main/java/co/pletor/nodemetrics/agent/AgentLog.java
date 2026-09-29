package co.pletor.nodemetrics.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logging facade that keeps the agent from initializing {@code java.util.logging} early.
 *
 * <p>The first use of {@link Logger#getLogger(String)} creates the JVM-wide {@code LogManager} from
 * the {@code java.util.logging.manager} and {@code java.util.logging.config.file} properties as
 * they are at that moment. An application that sets them from {@code main} (Quarkus and Log4j2
 * setups do) would have them ignored if the agent, which starts before {@code main}, got there
 * first. So this class does not touch {@code java.util.logging} until it has something that must
 * not wait:
 *
 * <ul>
 *   <li>{@code WARNING} and above are published right away.
 *   <li>Anything below that (startup information, debug details) is held in a small bounded queue,
 *       keeping its original time, and published once {@link #DEFAULT_DEFER_MS} has passed, or
 *       earlier when a {@code WARNING} needs the log anyway. The refresh engine calls {@link
 *       #flushIfDue()} on every cycle.
 * </ul>
 *
 * <p>Logging never throws.
 */
public final class AgentLog {

  /** How long records below {@code WARNING} are held back after the class is first used. */
  static final long DEFAULT_DEFER_MS = 10_000L;

  private static final int MAX_PENDING = 256;
  // Names, not class literals: this class must stay usable when ThrottledLogger cannot be loaded.
  private static final String[] OWN_CLASSES = {
    "co.pletor.nodemetrics.agent.AgentLog", "co.pletor.nodemetrics.agent.ThrottledLogger"
  };

  private static final Object LOCK = new Object();
  private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();

  /** Guarded by {@link #LOCK}; {@code nanoTime} value from which records are no longer held. */
  private static long deferUntilNanos = System.nanoTime() + ms(DEFAULT_DEFER_MS);

  private static volatile boolean hasPending;

  private final String name;
  private final boolean immediate;
  private volatile Logger jul;

  private AgentLog(String name, Logger jul) {
    this.name = name;
    this.jul = jul;
    this.immediate = jul != null;
  }

  /**
   * Returns a logger for the given name. Does not touch {@code java.util.logging}.
   *
   * @param name the logger name, usually the class name
   * @return the logger
   */
  public static AgentLog getLogger(String name) {
    return new AgentLog(name, null);
  }

  /** Wraps an existing JUL logger; records are published immediately (used by tests). */
  static AgentLog wrap(Logger logger) {
    return new AgentLog(logger.getName(), logger);
  }

  /**
   * Logs a message.
   *
   * @param level the level
   * @param message the message
   */
  public void log(Level level, String message) {
    publish(level, message, null, null);
  }

  /**
   * Logs a message with one parameter.
   *
   * @param level the level
   * @param message the message pattern
   * @param param the parameter for {@code {0}}
   */
  public void log(Level level, String message, Object param) {
    publish(level, message, new Object[] {param}, null);
  }

  /**
   * Logs a message with parameters.
   *
   * @param level the level
   * @param message the message pattern
   * @param params the parameters
   */
  public void log(Level level, String message, Object[] params) {
    publish(level, message, params, null);
  }

  /**
   * Logs a message with an exception.
   *
   * @param level the level
   * @param message the message
   * @param thrown the exception
   */
  public void log(Level level, String message, Throwable thrown) {
    publish(level, message, null, thrown);
  }

  /**
   * Logs a lazily built message with an exception.
   *
   * @param level the level
   * @param thrown the exception
   * @param message supplies the message
   */
  public void log(Level level, Throwable thrown, Supplier<String> message) {
    publish(level, resolve(message), null, thrown);
  }

  /** Publishes records that were held back, if their time has come. Cheap when nothing is held. */
  static void flushIfDue() {
    if (hasPending && !deferring()) {
      flushPending();
    }
  }

  // Visible for testing: start over with the given deferral window and no held records.
  static void resetForTest(long deferMs) {
    synchronized (LOCK) {
      PENDING.clear();
      hasPending = false;
      deferUntilNanos = System.nanoTime() + ms(deferMs);
    }
  }

  // Visible for testing.
  static int pendingCount() {
    synchronized (LOCK) {
      return PENDING.size();
    }
  }

  private static long ms(long millis) {
    return TimeUnit.MILLISECONDS.toNanos(millis);
  }

  private static boolean deferring() {
    long until;
    synchronized (LOCK) {
      until = deferUntilNanos;
    }
    return System.nanoTime() - until < 0L;
  }

  private static String resolve(Supplier<String> message) {
    try {
      return message.get();
    } catch (Throwable t) { // NOSONAR - logging must never throw
      return "<message unavailable: " + t + ">";
    }
  }

  private void publish(Level level, String message, Object[] params, Throwable thrown) {
    try {
      boolean urgent = immediate || level.intValue() >= Level.WARNING.intValue();
      if (!urgent && deferring()) {
        hold(level, message, params, thrown);
        return;
      }
      flushPending();
      Logger target = target();
      if (target.isLoggable(level)) {
        target.log(record(level, message, params, thrown));
      }
    } catch (Throwable ignored) { // NOSONAR - logging must never throw
      // Nothing else can be done.
    }
  }

  private void hold(Level level, String message, Object[] params, Throwable thrown) {
    LogRecord record = record(level, message, params, thrown);
    synchronized (LOCK) {
      if (PENDING.size() < MAX_PENDING) {
        PENDING.add(new Pending(this, record));
        hasPending = true;
      }
    }
  }

  private static void flushPending() {
    List<Pending> drained;
    synchronized (LOCK) {
      if (PENDING.isEmpty()) {
        return;
      }
      drained = new ArrayList<>(PENDING);
      PENDING.clear();
      hasPending = false;
    }
    for (Pending p : drained) {
      try {
        Logger target = p.owner.target();
        if (target.isLoggable(p.record.getLevel())) {
          target.log(p.record);
        }
      } catch (Throwable ignored) { // NOSONAR - logging must never throw
        // Skip this record.
      }
    }
  }

  private Logger target() {
    Logger l = jul;
    if (l == null) {
      l = Logger.getLogger(name);
      jul = l;
    }
    return l;
  }

  private LogRecord record(Level level, String message, Object[] params, Throwable thrown) {
    LogRecord r = new LogRecord(level, message);
    r.setLoggerName(name);
    if (params != null) {
      r.setParameters(params);
    }
    if (thrown != null) {
      r.setThrown(thrown);
    }
    // Keep the "class method" prefix that JUL would infer from the call stack; it cannot infer
    // it for a record published later, or through this class.
    StackWalker.StackFrame caller =
        StackWalker.getInstance()
            .walk(frames -> frames.filter(f -> !isOwnClass(f.getClassName())).findFirst())
            .orElse(null);
    if (caller != null) {
      r.setSourceClassName(caller.getClassName());
      r.setSourceMethodName(caller.getMethodName());
    }
    return r;
  }

  private static boolean isOwnClass(String className) {
    for (String own : OWN_CLASSES) {
      if (own.equals(className)) {
        return true;
      }
    }
    return false;
  }

  private static final class Pending {
    final AgentLog owner;
    final LogRecord record;

    Pending(AgentLog owner, LogRecord record) {
      this.owner = owner;
      this.record = record;
    }
  }
}
