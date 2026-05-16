package com.kumouri.kmodigipresbe.scheduling;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * No-operation Quartz job used as a proof-of-concept for the Quartz scheduler
 * integration in Phase A.
 *
 * <p>Scheduled by {@link QuartzBootstrap} on startup (once, ~5s out) behind the
 * {@code kmosf.quartz.proof-job.enabled} property flag.  The job simply increments
 * a static counter and logs a message so tests can assert it fired without any
 * side effects.
 *
 * <p>In Phase E, real durable jobs (invoice processing, payment retry) will replace
 * this proof job and require the Quartz Mongo JobStore for persistence across restarts.
 * At that point the Mongo store library coordinates must be resolved and this class
 * may be removed.
 */
@Slf4j
public class NoOpQuartzJob implements Job {

    /**
     * Static execution counter — readable in tests via
     * {@link #getExecutionCount()} without Spring context dependency.
     */
    private static final AtomicInteger EXECUTION_COUNT = new AtomicInteger(0);

    public static int getExecutionCount() {
        return EXECUTION_COUNT.get();
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        int count = EXECUTION_COUNT.incrementAndGet();
        log.info("NoOpQuartzJob fired (execution #{}). Quartz scheduler is wired correctly.", count);
    }
}
