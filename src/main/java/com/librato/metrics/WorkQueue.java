package com.librato.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * This class handles posting batch data to Librato.  If Librato is unavailable, it will
 * queue jobs up to a max age and max queue length, and periodically retry them.
 */
public class WorkQueue {
    private static final Logger logger = LoggerFactory.getLogger(WorkQueue.class);
    private static final long maxQueueAgeSeconds = TimeUnit.HOURS.toSeconds(2);
    private final ExecutorService executor;

    public WorkQueue(int maxLength) {
        final int corePoolSize = 1;
        final int maxPoolSize = 1;
        final long keepAlive = 1;
        final TimeUnit keepAliveTimeUnit = TimeUnit.SECONDS;
        final ThreadFactory threadFactory = new WorkQueueThreadFactory();
        final RejectedExecutionHandler rejectedHandler = new WorkQueueRejectionHandler();
        final BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<Runnable>(maxLength);
        this.executor = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAlive, keepAliveTimeUnit, workQueue, threadFactory, rejectedHandler);
    }

    /**
     * Submits a job to be run as soon as possible.
     */
    public void submit(Runnable runnable) {
        executor.submit(new WorkQueueRunnable(runnable));
    }

    /**
     * Removes the oldest work item in the queue.
     */
    private static class WorkQueueRejectionHandler implements RejectedExecutionHandler {
        private final RejectedExecutionHandler delegate = new ThreadPoolExecutor.DiscardOldestPolicy();
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            logger.error("Rejecting oldest librato api call");
            delegate.rejectedExecution(r, executor);
        }
    }

    private static class WorkQueueThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "work-queue-worker");
        }
    }

    private static class WorkQueueRunnable implements Runnable {
        private final Runnable delegate;
        private final long startTime;

        private WorkQueueRunnable(Runnable delegate) {
            this.delegate = delegate;
            this.startTime = System.currentTimeMillis();
        }

        private boolean isTooOld() {
            final long age = System.currentTimeMillis() - startTime;
            final long seconds = TimeUnit.MILLISECONDS.toSeconds(age);
            return seconds > maxQueueAgeSeconds;
        }

        private void backoff() {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                throw new RuntimeException("Could not sleep. Interruption.");
            }
        }

        public void run() {
            while (!isTooOld()) {
                try {
                    delegate.run();
                } catch (Exception e) {
                    backoff();
                }
            }
        }
    }

}


