package co.pletor.nodemetrics.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs filesystem calls that may block (for example {@code stat} on a path under a dead NFS mount)
 * on separate daemon threads, so the caller waits at most a bounded time.
 *
 * <p>A blocked call cannot be interrupted from Java and keeps its thread until the kernel returns.
 * The number of such threads is capped: once every thread is busy, further calls return the
 * fallback immediately instead of piling up more threads. Without this guard, a dead mount listed
 * in the configuration would stall the thread applying it, and at startup that is the application's
 * own main thread.
 */
final class FilesystemProbe {
  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

  private final ThreadPoolExecutor executor;

  FilesystemProbe(int maxThreads) {
    this.executor =
        new ThreadPoolExecutor(
            0,
            maxThreads,
            30L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
              Thread t =
                  new Thread(runnable, "node-metrics-fs-probe-" + THREAD_COUNTER.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Run {@code task}, waiting at most {@code maxWaitMs}.
   *
   * @return the task's result, or {@code fallback} if it timed out, failed, was rejected because
   *     all probe threads are busy, or the wait was interrupted
   */
  <T> T call(Callable<T> task, T fallback, long maxWaitMs) {
    if (maxWaitMs <= 0L) {
      return fallback;
    }
    Future<T> future;
    try {
      future = executor.submit(task);
    } catch (RejectedExecutionException e) {
      return fallback;
    }
    try {
      return future.get(maxWaitMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return fallback;
    } catch (ExecutionException e) {
      return fallback;
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return fallback;
    }
  }
}
