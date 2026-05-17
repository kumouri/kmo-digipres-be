package com.kumouri.kmodigipresbe.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
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
 * Durably registers the {@link RecurringInvoiceSpawnJob} on application startup
 * (Phase E — E-D3). Mirrors the {@code QuartzBootstrap.java:52-82}
 * {@code scheduler.checkExists} idempotent pattern: re-registration on every boot
 * is a no-op if the job already exists.
 *
 * <p>Repeating trigger (default 1h, {@code kmosf.recurring-invoice.spawn-job.interval-ms}).
 * Because the E-D5 RAM Quartz store is non-durable, a stateless hourly re-run of
 * {@code runDueOnce()} that reconciles from the {@code RecurringInvoiceOccurrence}
 * Mongo ledger + {@code nextRunAt} cursor IS the durability mechanism (bounded
 * catch-up) — exactly the {@code ServiceAgreementSchedulerService} model.
 *
 * <p>{@code @ConditionalOnProperty(kmosf.recurring-invoice.spawn-job.enabled,
 * matchIfMissing=true)} so integration tests disable the background trigger and
 * drive {@code RecurringInvoiceSpawnService.runDueOnce()} deterministically.
 *
 * <p>Uses {@link ApplicationReadyEvent} (not {@code @PostConstruct}) so the
 * scheduler is fully started before registration (avoids a race on slow
 * Testcontainers starts) — the same rationale as {@code QuartzBootstrap}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kmosf.recurring-invoice.spawn-job.enabled", havingValue = "true", matchIfMissing = true)
public class RecurringInvoiceJobScheduler {

    private static final String JOB_NAME = "recurringInvoiceSpawnJob";
    private static final String JOB_GROUP = "kmosf-recurring";
    private static final String TRIGGER_NAME = "recurringInvoiceSpawnTrigger";

    private final Scheduler scheduler;

    @Value("${kmosf.recurring-invoice.spawn-job.interval-ms:3600000}")
    private long intervalMs;

    @Value("${kmosf.recurring-invoice.spawn-job.initial-delay-ms:60000}")
    private long initialDelayMs;

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleSpawnJob() {
        try {
            if (scheduler.checkExists(new JobKey(JOB_NAME, JOB_GROUP))) {
                log.debug("RecurringInvoiceSpawnJob already scheduled — skipping "
                        + "duplicate registration.");
                return;
            }

            JobDetail jobDetail = JobBuilder.newJob(RecurringInvoiceSpawnJob.class)
                    .withIdentity(JOB_NAME, JOB_GROUP)
                    .withDescription("Phase E — materializes due recurring-invoice "
                            + "occurrences (one DRAFT invoice per cadence period)")
                    .storeDurably()
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(TRIGGER_NAME, JOB_GROUP)
                    .forJob(jobDetail)
                    .startAt(new Date(System.currentTimeMillis() + initialDelayMs))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(intervalMs)
                            .repeatForever())
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("RecurringInvoiceSpawnJob scheduled — first run in {}ms, then "
                    + "every {}ms (E-D3; RAM store + Mongo occurrence-ledger durability).",
                    initialDelayMs, intervalMs);

        } catch (SchedulerException e) {
            log.error("Failed to schedule RecurringInvoiceSpawnJob — recurring "
                    + "billing will not tick until restart", e);
        }
    }
}
