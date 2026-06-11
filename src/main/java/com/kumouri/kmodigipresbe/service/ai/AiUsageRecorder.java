package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.ai.AiUsage;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.AiUsageRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Enforces per-tenant monthly AI budget caps and records spend after each call.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #checkBudget()} — called before any AI provider request. Reads
 *       the current month's {@link AiUsage} and the tenant's
 *       {@link Tenant#getAiBudgetUsd()} cap. Throws
 *       {@link DigiPresBeException} {@code errorCode=1200, status=429} when
 *       the cap is exhausted (default cap is {@link BigDecimal#ZERO} per the
 *       opt-in policy decision).</li>
 *   <li>{@link #record(long, long, BigDecimal)} — called after a successful AI
 *       call. Bumps {@code totalTokensIn / totalTokensOut / totalUsd /
 *       callCount} on the month's usage record, creating it if absent.</li>
 * </ol>
 *
 * <p>Error code 1200 sits in the reserved {@code 1200-1299} AI range
 * (re-allocated from the plan's original 1300 slot which Phase 3 already
 * occupies with RRULE errors).
 *
 * <h2>Lost-write robustness (AI-06)</h2>
 * {@link #record} is a read-modify-write on the {@link org.springframework.data.annotation.Version}ed
 * {@link AiUsage} row, so two concurrent AI calls in the same {@code (tenant, yearMonth)} race the same
 * document. There are two contention shapes: when the month's row already exists the {@code @Version} update
 * loser fails with {@link OptimisticLockingFailureException}; when it does <em>not</em> yet exist (the first
 * spend of the month) every caller loads "empty" and tries to insert, so all but one lose the unique
 * {@code tenant_month_idx} with {@link DuplicateKeyException} (the first-writer-wins insert race). Callers
 * wrap the AI leg in {@code onErrorResume(→ degrade)}, so a thrown lost-write of <em>either</em> race would
 * be <em>swallowed</em> and that spend would <strong>never be counted</strong> — silently eroding the cap
 * over time. To prevent that, the read-modify-write is wrapped in a bounded {@link Retry#fixedDelay} that
 * retries on both ({@link #isConcurrentWriteConflict}, {@link #RECORD_MAX_RETRIES} re-reads with a small
 * backoff); each retry re-reads the now-committed row so the loser's increment composes onto it (an update,
 * not a second insert). Spend is dropped only after the bounded retries are exhausted (logged), which is
 * vanishingly unlikely versus the previous single-attempt swallow.
 *
 * <p><strong>Note on the cap vs. concurrency (TOCTOU).</strong> {@link #checkBudget} is still a soft
 * pre-check — it reads spend, the call runs, and {@code record} commits <em>after</em> — so a burst of
 * concurrent calls that each pass the pre-check can still overshoot the cap by the in-flight cost. The
 * retry here closes the <em>lost-write under-count</em> (the cap-erosion bug); it does not close the
 * bounded TOCTOU overshoot. See the {@code TODO(AI-06)} on {@link #checkBudget} for the atomic
 * pre-reservation that would.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorder {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Bounded retry budget for the {@link #record} concurrent-write loop (AI-06). The contention window is a
     * single in-process document write; with the small staggering backoff a handful of re-reads resolves any
     * realistic AI burst (generate/ask are seconds apart per tenant, not dozens-simultaneous), while still
     * never letting a record() spin unbounded on the Netty path. Sized with headroom over the worst-case
     * first-of-the-month insert stampede where N writers serialize through the unique index.
     */
    static final long RECORD_MAX_RETRIES = 8;

    /** Tiny fixed backoff between record() re-reads so racing writers stagger rather than livelock. */
    private static final Duration RECORD_RETRY_BACKOFF = Duration.ofMillis(20);

    private final AiUsageRepository aiUsage;
    private final TenantRepository tenants;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;

    // TODO(AI-06): atomic pre-reservation to close the bounded TOCTOU overshoot. checkBudget reads spend,
    // the AI call runs, and record() commits the cost afterwards — so a burst of concurrent calls that each
    // pass this soft pre-check can collectively overshoot Tenant.aiBudgetUsd by the in-flight cost. The
    // robust fix is a conditional findAndModify $inc that atomically reserves an estimated cost only when
    // the post-increment total would stay <= cap (failing fast with 1200 otherwise), reconciled after the
    // call with the actual token cost. Deferred here to avoid changing the established checkBudget 1200/429
    // contract + default-ZERO opt-in cap semantics that the AI ITs pin; the shipped retry in record() closes
    // the higher-impact lost-write under-count (cap erosion). See the class javadoc.
    public Mono<Void> checkBudget() {
        return TenantContextHolder.required()
                .flatMap(ctx -> tenants.findById(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Tenant not found for AI budget check", 1201, 404)))
                        .flatMap(tenant -> checkAgainstSpend(ctx.tenantId(), tenant)));
    }

    private Mono<Void> checkAgainstSpend(UUID tenantId, Tenant tenant) {
        BigDecimal cap = tenant.getAiBudgetUsd() == null ? BigDecimal.ZERO : tenant.getAiBudgetUsd();
        return loadOrEmptyUsage(tenantId)
                .flatMap(usage -> {
                    BigDecimal spent = usage.getTotalUsd() == null ? BigDecimal.ZERO : usage.getTotalUsd();
                    if (spent.compareTo(cap) >= 0) {
                        return Mono.error(new DigiPresBeException(
                                "AI budget exceeded for this tenant (spent "
                                        + spent + " of " + cap + ")",
                                1200, 429));
                    }
                    return Mono.empty();
                });
    }

    public Mono<AiUsage> record(long inputTokens, long outputTokens, BigDecimal usd) {
        return TenantContextHolder.required()
                .flatMap(ctx -> applyIncrement(ctx.tenantId(), inputTokens, outputTokens, usd)
                        // AI-06: two concurrent record() calls on the same (tenant, yearMonth) race the same
                        // row. There are TWO contention shapes and the retry must cover both:
                        //   (1) the row already exists → the @Version update loser throws
                        //       OptimisticLockingFailureException;
                        //   (2) the row does NOT yet exist (first spend of the month) → every caller loads
                        //       "empty" and tries to INSERT, so all but one lose the unique tenant_month_idx
                        //       with DuplicateKeyException (the first-writer-wins insert race).
                        // Because callers swallow record() failures (onErrorResume → degrade), an un-retried
                        // loser of EITHER race would silently DROP that spend and erode the cap. Re-read +
                        // re-apply a bounded number of times — each re-subscription of applyIncrement reloads
                        // the now-committed row, so the loser's increment composes onto it (an update, not a
                        // second insert).
                        .retryWhen(Retry.fixedDelay(RECORD_MAX_RETRIES, RECORD_RETRY_BACKOFF)
                                .filter(AiUsageRecorder::isConcurrentWriteConflict)
                                .doBeforeRetry(sig -> log.debug(
                                        "AI usage record() concurrent-write retry {} of {} ({})",
                                        sig.totalRetries() + 1, RECORD_MAX_RETRIES,
                                        sig.failure().getClass().getSimpleName()))
                                .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                        .doOnError(AiUsageRecorder::isConcurrentWriteConflict, ex -> log.warn(
                                "AI usage record() dropped after {} concurrent-write retries — {} input / {} "
                                        + "output tokens / ${} not counted toward the cap",
                                RECORD_MAX_RETRIES, inputTokens, outputTokens,
                                usd == null ? BigDecimal.ZERO : usd)));
    }

    /**
     * The two race outcomes a concurrent {@link #record} must re-read and retry on: the optimistic-lock
     * update loser ({@link OptimisticLockingFailureException}) and the first-insert loser
     * ({@link DuplicateKeyException} on the unique {@code tenant_month_idx} when the month's row doesn't
     * exist yet). Any other error is a genuine failure and propagates.
     */
    private static boolean isConcurrentWriteConflict(Throwable ex) {
        return ex instanceof OptimisticLockingFailureException
                || ex instanceof DuplicateKeyException;
    }

    /**
     * One read-modify-write attempt: load (or default) the month's usage row, add the deltas, save. A
     * concurrent writer makes the {@link AiUsageRepository#save} fail with
     * {@link OptimisticLockingFailureException}; {@link #record} retries this whole pipeline (a fresh
     * re-read) a bounded number of times so no spend is silently dropped.
     */
    private Mono<AiUsage> applyIncrement(UUID tenantId, long inputTokens, long outputTokens, BigDecimal usd) {
        return loadOrEmptyUsage(tenantId)
                .flatMap(existing -> {
                    existing.setTotalTokensIn(existing.getTotalTokensIn() + inputTokens);
                    existing.setTotalTokensOut(existing.getTotalTokensOut() + outputTokens);
                    existing.setTotalUsd((existing.getTotalUsd() == null
                            ? BigDecimal.ZERO : existing.getTotalUsd())
                            .add(usd == null ? BigDecimal.ZERO : usd));
                    existing.setCallCount(existing.getCallCount() + 1);
                    return aiUsage.save(existing);
                });
    }

    private Mono<AiUsage> loadOrEmptyUsage(UUID tenantId) {
        String yearMonth = LocalDate.now(clockProvider.getIfAvailable(Clock::systemUTC))
                .format(YEAR_MONTH);
        return aiUsage.findByTenantIdAndYearMonth(tenantId, yearMonth)
                .defaultIfEmpty(AiUsage.builder()
                        .yearMonth(yearMonth)
                        .totalTokensIn(0L).totalTokensOut(0L)
                        .totalUsd(BigDecimal.ZERO)
                        .callCount(0L)
                        .build());
    }
}
