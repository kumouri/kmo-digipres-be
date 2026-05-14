package com.kumouri.kmodigipresbe.service.report;

import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.repository.SavedReportRepository;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Polls scheduled {@link SavedReport}s on a fixed-rate tick. For each report
 * whose {@code scheduleCron} would have fired in the last tick window, render
 * via {@link ReportRunner} and email via Phase 9c
 * {@link TransactionalEmailService}.
 *
 * <p>This is a stop-gap implementation — the plan §4.7 specifies Quartz cron
 * triggers per report. For 9e the lightweight "scan-and-fire" loop is adequate
 * and avoids the Quartz job-registration plumbing; swap to Quartz when the
 * report set grows past ~50 active schedules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportScheduler {

    public static final String SYSTEM_ROLE = "REPORT_SCHEDULER";

    private final SavedReportRepository savedReports;
    private final ReportRunner runner;
    private final TransactionalEmailService email;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;

    @Value("${kmosf.report.scheduler.from:no-reply@kmosolutionsfoundry.com}")
    private String defaultFrom;

    /**
     * Runs every minute. For each scheduled report, check whether the cron's
     * next-fire instant from {@code now - tickWindow} falls inside the current
     * window — if so, fire.
     */
    @Scheduled(
            fixedRateString = "${kmosf.report.scheduler.tick-ms:60000}",
            initialDelayString = "${kmosf.report.scheduler.initial-delay-ms:60000}")
    public void tick() {
        runDueOnce()
                .onErrorContinue((err, evt) ->
                        log.warn("ReportScheduler tick dropped {}: {}", evt, err.toString()))
                .subscribe();
    }

    /**
     * Public for tests — runs one scan synchronously when blocked.
     */
    public Mono<Void> runDueOnce() {
        Duration window = Duration.ofMillis(60_000);
        LocalDateTime now = LocalDateTime.now(clockProvider.getIfAvailable(Clock::systemUTC));
        LocalDateTime windowStart = now.minus(window);
        return savedReports.findAllScheduled()
                .filter(r -> shouldFire(r, windowStart, now))
                .flatMap(this::renderAndSend)
                .then();
    }

    private static boolean shouldFire(SavedReport report, LocalDateTime windowStart, LocalDateTime now) {
        if (report.getScheduleCron() == null || report.getScheduleCron().isBlank()) return false;
        CronExpression cron;
        try {
            cron = CronExpression.parse(report.getScheduleCron());
        } catch (RuntimeException ex) {
            return false;
        }
        LocalDateTime next = cron.next(windowStart);
        return next != null && !next.isAfter(now);
    }

    private Mono<Void> renderAndSend(SavedReport report) {
        TenantContext ctx = new TenantContext(report.getTenantId(), null, Set.of(SYSTEM_ROLE));
        return runner.runForTenant(report, report.getTenantId())
                .flatMap(rows -> sendEmail(report, rows))
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> sendEmail(SavedReport report, List<Map<String, Object>> rows) {
        List<String> recipients = report.getScheduleRecipients() == null
                ? List.of()
                : report.getScheduleRecipients();
        if (recipients.isEmpty()) {
            log.debug("Scheduled report {} has no recipients — skipping send", report.getId());
            return Mono.empty();
        }
        String subject = "Scheduled report: " + (report.getName() == null ? "Untitled" : report.getName());
        String textBody = renderTextBody(report, rows);
        return email.send(new TransactionalSendRequest(
                        recipients,
                        defaultFrom,
                        subject,
                        null,
                        textBody,
                        "scheduled-report",
                        Map.of("kmosf_report_id", report.getId() == null ? "" : report.getId().toString())))
                .then();
    }

    private static String renderTextBody(SavedReport report, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.getName() == null ? "Report" : report.getName()).append('\n');
        if (report.getDescription() != null && !report.getDescription().isBlank()) {
            sb.append(report.getDescription()).append('\n');
        }
        sb.append("Generated at ").append(java.time.Instant.now()).append('\n').append('\n');
        if (rows.isEmpty()) {
            sb.append("(no rows)\n");
            return sb.toString();
        }
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Visible for FE-rendered ad-hoc reports (testing only currently). */
    public Flux<SavedReport> debugScheduled() {
        return savedReports.findAllScheduled();
    }

    /** Convenience for ad-hoc UUID generation; isolates the import. */
    @SuppressWarnings("unused")
    private static UUID newId() { return UUID.randomUUID(); }
}
