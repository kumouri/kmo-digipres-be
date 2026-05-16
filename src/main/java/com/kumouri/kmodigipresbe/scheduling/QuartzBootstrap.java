package com.kumouri.kmodigipresbe.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Schedules the {@link NoOpQuartzJob} proof job on application startup.
 *
 * <p>Controlled by {@code kmosf.quartz.proof-job.enabled} (default {@code true}).
 * Disabled in integration tests that don't need the background noise.
 *
 * <p>Uses {@link ApplicationReadyEvent} — not {@code @PostConstruct} — so the
 * scheduler is fully started before we attempt to schedule jobs (avoids a race on
 * slow Testcontainers starts).
 *
 * <h2>Phase A / Phase E boundary</h2>
 * This is a no-op proof of concept. Phase E will introduce durable Mongo-backed jobs
 * for invoice processing and payment retry.  At that point:
 * <ol>
 *   <li>Resolve the Quartz Mongo JobStore library coordinates (R-A4 in Phase A plan).</li>
 *   <li>Swap {@code spring.quartz.job-store-type=memory} → Mongo store config.</li>
 *   <li>Migrate relevant {@code @Scheduled} methods to {@code org.quartz.Job}
 *       implementations.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kmosf.quartz.proof-job.enabled", havingValue = "true", matchIfMissing = true)
public class QuartzBootstrap {

    private static final String JOB_NAME = "noOpProofJob";
    private static final String JOB_GROUP = "kmosf-proof";
    private static final String TRIGGER_NAME = "noOpProofTrigger";

    private final Scheduler scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleProofJob() {
        try {
            if (scheduler.checkExists(
                    new org.quartz.JobKey(JOB_NAME, JOB_GROUP))) {
                log.debug("NoOpQuartzJob already scheduled — skipping duplicate registration.");
                return;
            }

            JobDetail jobDetail = JobBuilder.newJob(NoOpQuartzJob.class)
                    .withIdentity(JOB_NAME, JOB_GROUP)
                    .withDescription("Phase A proof job — verifies Quartz scheduler is wired")
                    .storeDurably()
                    .build();

            // One-shot trigger: fires once ~5 seconds after boot
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(TRIGGER_NAME, JOB_GROUP)
                    .forJob(jobDetail)
                    .startAt(new Date(System.currentTimeMillis() + 5_000))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withRepeatCount(0))
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("NoOpQuartzJob scheduled to fire once ~5s after boot (Phase A proof).");

        } catch (SchedulerException e) {
            log.error("Failed to schedule NoOpQuartzJob — Quartz integration may be broken", e);
        }
    }
}
