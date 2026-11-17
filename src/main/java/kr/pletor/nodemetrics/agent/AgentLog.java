package kr.pletor.nodemetrics.agent;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Bounded, asynchronous agent diagnostics independent of the application's JUL configuration.
 *
 * <p>Production loggers never discover a JUL logger or invoke application handlers. One private
 * daemon writes INFO and higher records to stderr. A blocked output occupies only that daemon;
 * producers discard records when its 256-entry queue is full. There is no synchronous fallback.
 */
public final class AgentLog {
  private static final int MAX_PENDING = 256;
  private static final int MAX_LINE_CHARS = 4096;
  private static final ArrayBlockingQueue<LogRecord> PENDING =
      new ArrayBlockingQueue<>(MAX_PENDING);
  private static final AtomicInteger OUTSTANDING = new AtomicInteger();
  private static final AtomicInteger WRITER_STATE = new AtomicInteger();
  private static final LongAdder DROPPED = new LongAdder();
  private static volatile Consumer<LogRecord> sink = AgentLog::writeToStderr;
  private static final String[] OWN_CLASSES = {
    "kr.pletor.nodemetrics.agent.AgentLog", "kr.pletor.nodemetrics.agent.ThrottledLogger"
  };

  private final String name;
  private final Logger injectedLogger;

  private AgentLog(String name, Logger injectedLogger) {
    this.name = name;
    this.injectedLogger = injectedLogger;
  }

  /** Returns an agent logger without initializing the JVM's LogManager. */
  public static AgentLog getLogger(String name) {
    return new AgentLog(name, null);
  }

  /** Test adapter for an explicitly supplied logger; production never discovers a JUL logger. */
  static AgentLog wrap(Logger logger) {
    return new AgentLog(logger.getName(), logger);
  }

  /** Log a message. */
  public void log(Level level, String message) {
    publish(level, message, null, null);
  }

  /** Log a message with one parameter. */
  public void log(Level level, String message, Object parameter) {
    publish(level, message, new Object[] {parameter}, null);
  }

  /** Log a message with parameters. */
  public void log(Level level, String message, Object[] parameters) {
    publish(level, message, parameters, null);
  }

  /** Log a message with its failure. */
  public void log(Level level, String message, Throwable thrown) {
    publish(level, message, null, thrown);
  }

  /** Log a lazily constructed message with its failure. */
  public void log(Level level, Throwable thrown, Supplier<String> message) {
    try {
      log(level, message.get(), thrown);
    } catch (Throwable ignored) { // NOSONAR - diagnostics must not propagate supplier failures
      publish(level, "<message unavailable>", null, thrown);
    }
  }

  /** Records discarded because of saturation, writer startup failure or an output failure. */
  public static long droppedCount() {
    return DROPPED.sum();
  }

  private void publish(Level level, String message, Object[] parameters, Throwable thrown) {
    try {
      if (injectedLogger != null) {
        injectedLogger.log(record(level, message, parameters, thrown));
        return;
      }
      if (level.intValue() < Level.INFO.intValue()) {
        return;
      }
      if (!startWriter()) {
        DROPPED.increment();
        return;
      }
      LogRecord record = record(level, message, parameters, thrown);
      OUTSTANDING.incrementAndGet();
      if (!PENDING.offer(record)) {
        OUTSTANDING.decrementAndGet();
        DROPPED.increment();
      } else if (WRITER_STATE.get() < 0 && PENDING.remove(record)) {
        // The writer can fail between the state check and offer. Do not retain a stranded record.
        OUTSTANDING.decrementAndGet();
        DROPPED.increment();
      }
    } catch (Throwable ignored) { // NOSONAR - diagnostics must never affect their caller
      // No recursive logging or synchronous fallback.
    }
  }

  private static boolean startWriter() {
    if (WRITER_STATE.compareAndSet(0, 1)) {
      try {
        Thread writer = new Thread(null, AgentLog::drain, "node-metrics-agent-log", 0L, false);
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.setUncaughtExceptionHandler((thread, error) -> disableWriter());
        writer.start();
      } catch (Throwable ignored) { // NOSONAR - abandon diagnostics if a worker cannot be started
        disableWriter();
      }
    }
    return WRITER_STATE.get() == 1;
  }

  private static void disableWriter() {
    WRITER_STATE.set(-1);
    while (PENDING.poll() != null) {
      OUTSTANDING.decrementAndGet();
      DROPPED.increment();
    }
  }

  private static void drain() {
    while (true) {
      final LogRecord record;
      try {
        record = PENDING.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        disableWriter();
        return;
      }
      try {
        sink.accept(record);
      } catch (Throwable ignored) { // NOSONAR - discard bad output; never invoke a host handler
        DROPPED.increment();
      } finally {
        OUTSTANDING.decrementAndGet();
      }
    }
  }

  private static void writeToStderr(LogRecord record) {
    String message = record.getMessage();
    Object[] parameters = record.getParameters();
    if (parameters != null && parameters.length > 0) {
      message = MessageFormat.format(message, parameters);
    }
    Throwable error = record.getThrown();
    String line =
        "[node-metrics-agent] "
            + record.getLevel().getName()
            + " "
            + record.getLoggerName()
            + ": "
            + message
            + (error == null ? "" : " (" + error.getClass().getName() + ")");
    PrintStream output = System.err;
    output.println(line.substring(0, Math.min(line.length(), MAX_LINE_CHARS)));
    if (output.checkError()) {
      DROPPED.increment();
    }
  }

  private LogRecord record(Level level, String message, Object[] parameters, Throwable thrown) {
    LogRecord record = new LogRecord(level, message);
    record.setLoggerName(name);
    record.setParameters(parameters);
    record.setThrown(thrown);
    StackWalker.StackFrame caller =
        StackWalker.getInstance()
            .walk(frames -> frames.filter(frame -> !isOwnClass(frame.getClassName())).findFirst())
            .orElse(null);
    if (caller != null) {
      record.setSourceClassName(caller.getClassName());
      record.setSourceMethodName(caller.getMethodName());
    }
    return record;
  }

  private static boolean isOwnClass(String className) {
    for (String own : OWN_CLASSES) {
      if (own.equals(className)) {
        return true;
      }
    }
    return false;
  }

  // Test seams. Swapping the sink does not change the production queue or writer behavior.
  static Consumer<LogRecord> swapSinkForTest(Consumer<LogRecord> replacement) {
    Consumer<LogRecord> previous = sink;
    sink = replacement;
    return previous;
  }

  static int pendingCount() {
    return OUTSTANDING.get();
  }

  static int queuedCount() {
    return PENDING.size();
  }
}
