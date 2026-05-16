package com.kumouri.kmodigipresbe.scheduling;

import com.kumouri.kmodigipresbe.service.recurring.RecurringInvoiceSpawnService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that materializes due recurring-invoice occurrences (Phase E — E-D3).
 *
 * <h2>Blocking-Quartz-on-reactive bridge (§9 item 2)</h2>
 * {@link #execute(JobExecutionContext)} runs on a <strong>Quartz worker thread</strong>
 * — a bounded blocking pool, NOT the Netty event loop — so calling
 * {@code spawnService.runDueOnce().block()} here is correct and safe (it is the
 * Quartz-equivalent of the {@code ServiceAgreementSchedulerService} {@code @Scheduled}
 * tick). It is the ONLY place {@code .block()} is called on this chain; the reactive
 * pipeline itself never touches the event loop and no {@code Mono} is left
 * unsubscribed.
 *
 * <h2>{@link DisallowConcurrentExecution}</h2>
 * Two overlapping fires of this job for the same JobDetail are serialized by
 * Quartz. This is necessary-but-not-sufficient for money-correctness — the
 * exactly-once guarantee is the {@code RecurringInvoiceOccurrence} unique-indexed
 * ledger (ledger-insert FIRST, E-D3); the annotation just avoids redundant work.
 *
 * <p>The Spring Boot Quartz auto-configuration autowires this job instance (its
 * {@code SchedulerFactoryBean} job factory calls {@code autowireBean}), so field
 * injection of the Spring-managed {@link RecurringInvoiceSpawnService} works even
 * though Quartz instantiates the job.
 */
@Slf4j
@DisallowConcurrentExecution
public class RecurringInvoiceSpawnJob implements Job {

    @Autowired
    private RecurringInvoiceSpawnService spawnService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            // .block() is correct HERE: this method runs on a Quartz worker
            // (bounded blocking pool), never the Netty event loop.
            spawnService.runDueOnce().block();
        } catch (Exception e) {
            // Never let a tick failure propagate uncaught — Quartz would just log
            // and reschedule; the next tick reconciles from the Mongo ledger +
            // nextRunAt cursor (bounded catch-up) so no period is lost.
            log.warn("RecurringInvoiceSpawnJob tick failed; the next tick will "
                    + "reconcile from the occurrence ledger: {}", e.toString());
        }
    }
}
