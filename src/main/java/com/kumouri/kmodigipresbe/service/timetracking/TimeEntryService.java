package com.kumouri.kmodigipresbe.service.timetracking;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.TimeEntrySource;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages the {@link TimeEntry} lifecycle (Phase D — D-D3, D-D4, D-D6).
 *
 * <h2>§9 Invariant: explicit boolean idempotency — NEVER switchIfEmpty(create)</h2>
 * <ul>
 *   <li>{@link #startTimer}: {@code findFirst…EndedAtIsNull} → if present → error 3505
 *       (already running); else → create. Branch is {@code if/else}, not
 *       {@code switchIfEmpty(create)}.</li>
 *   <li>{@link #stopTimer}: {@code findFirst…EndedAtIsNull} → if present → close+split;
 *       else → error 3506 (no timer running). Branch is {@code if/else}.</li>
 *   <li>{@link #createInvoiceFromTime}: filtered UNBILLED candidate list +
 *       explicit {@code if (candidates.isEmpty())} → error 3520. Never switchIfEmpty.</li>
 * </ul>
 *
 * <p>{@code switchIfEmpty} is used only for genuine entity-not-found errors (3500).
 */
@Service
@RequiredArgsConstructor
public class TimeEntryService {

    private final TimeEntryRepository timeEntries;
    private final ProjectRepository projects;
    private final InvoiceService invoiceService;
    private final TimeSplitService splitter;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Flux<TimeEntry> findByUser(UUID userId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> timeEntries.findAllByTenantIdAndUserIdOrderByStartedAtDesc(
                        ctx.tenantId(), userId));
    }

    public Flux<TimeEntry> findWeekly(Instant from, Instant to, UUID userId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> timeEntries.findAllByTenantIdAndUserIdAndStartedAtBetweenOrderByStartedAtAsc(
                        ctx.tenantId(), userId, from, to));
    }

    public Mono<TimeEntry> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> timeEntries.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("TimeEntry not found", 3500, 404)));
    }

    /**
     * Returns the currently running timer for {@code userId}, or empty if none is running.
     * Does NOT error on empty — callers that need an error use {@link #findById} or the
     * explicit-boolean pattern.
     */
    public Mono<TimeEntry> findRunningTimer(UUID userId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> timeEntries.findFirstByTenantIdAndUserIdAndEndedAtIsNull(
                        ctx.tenantId(), userId));
    }

    // -------------------------------------------------------------------------
    // Create (manual — first-class, Decision D9)
    // -------------------------------------------------------------------------

    /**
     * Creates a manual time entry (D-D4). Runs through the D-D3 split if the
     * {@code [startedAt, endedAt)} interval spans a local-day boundary.
     */
    public Mono<TimeEntry> create(TimeEntry body, String zoneId) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getUserId() == null) {
                body.setUserId(ctx.userId());
            }
            if (body.getStartedAt() == null) {
                return Mono.error(new DigiPresBeException("startedAt is required", 3502, 400));
            }
            if (body.getEndedAt() != null && !body.getEndedAt().isAfter(body.getStartedAt())) {
                return Mono.error(new DigiPresBeException("endedAt must be after startedAt", 3503, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            if (body.getSource() == null) {
                body.setSource(TimeEntrySource.MANUAL);
            }
            body.setBillingStatus(BillingStatus.UNBILLED);
            body.setInvoicedInvoiceId(null);
            body.setSplitGroupId(null);

            if (body.getEndedAt() != null) {
                // Closed manual entry — run the split
                List<TimeEntry> segments = splitter.split(body, zoneId);
                if (segments.size() == 1) {
                    return timeEntries.save(segments.get(0))
                            .flatMap(saved -> publishLogged(ctx.tenantId(), saved).thenReturn(saved));
                } else {
                    return timeEntries.saveAll(segments).collectList()
                            .flatMap(saved -> {
                                // Return the first segment; publish for each
                                for (TimeEntry seg : saved) {
                                    publishLogged(ctx.tenantId(), seg).subscribe();
                                }
                                return Mono.just(saved.get(0));
                            });
                }
            } else {
                // Open entry (running timer via manual create — allowed)
                body.setDurationSeconds(0L);
                return timeEntries.save(body)
                        .flatMap(saved -> publishLogged(ctx.tenantId(), saved).thenReturn(saved));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public Mono<TimeEntry> update(UUID id, TimeEntry patch, String zoneId) {
        return findById(id).flatMap(existing -> {
            if (existing.getBillingStatus() == BillingStatus.INVOICED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot edit an INVOICED time entry", 3504, 409));
            }
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getProjectId() != null)   existing.setProjectId(patch.getProjectId());
            if (patch.getTaskId() != null)       existing.setTaskId(patch.getTaskId());
            if (patch.getRateAmount() != null)   existing.setRateAmount(patch.getRateAmount());
            if (patch.isBillable() != existing.isBillable()) existing.setBillable(patch.isBillable());

            boolean timesChanged = false;
            if (patch.getStartedAt() != null) {
                existing.setStartedAt(patch.getStartedAt());
                timesChanged = true;
            }
            if (patch.getEndedAt() != null) {
                if (!patch.getEndedAt().isAfter(existing.getStartedAt())) {
                    return Mono.error(new DigiPresBeException("endedAt must be after startedAt", 3503, 400));
                }
                existing.setEndedAt(patch.getEndedAt());
                timesChanged = true;
            }

            if (timesChanged && existing.getEndedAt() != null) {
                // Re-run the split when times changed on a closed entry
                List<TimeEntry> segments = splitter.split(existing, zoneId);
                if (segments.size() == 1) {
                    return timeEntries.save(segments.get(0));
                } else {
                    return timeEntries.saveAll(segments).next();
                }
            }
            return timeEntries.save(existing);
        });
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(e -> timeEntries.deleteById(e.getId()));
    }

    // -------------------------------------------------------------------------
    // Timer API (D-D3a)
    // -------------------------------------------------------------------------

    /**
     * Starts a timer. Explicit boolean guard: if a running entry exists → error 3505;
     * else → create a new entry with endedAt=null (D-D3a §9 invariant).
     *
     * <p>Uses {@code hasElement()} to resolve the optional to a boolean and branches
     * explicitly — NEVER uses {@code switchIfEmpty(create)} (the §9 trap).
     */
    public Mono<TimeEntry> startTimer(TimeEntry body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID userId = body.getUserId() != null ? body.getUserId() : ctx.userId();
            return timeEntries.findFirstByTenantIdAndUserIdAndEndedAtIsNull(ctx.tenantId(), userId)
                    .hasElement()
                    .flatMap(isRunning -> {
                        if (isRunning) {
                            // Explicit branch: timer IS running → error 3505
                            return Mono.<TimeEntry>error(new DigiPresBeException(
                                    "A timer is already running for this user", 3505, 409));
                        }
                        // Explicit branch: no running timer → create one
                        body.setId(null);
                        body.setTenantId(ctx.tenantId());
                        body.setUserId(userId);
                        body.setSource(TimeEntrySource.TIMER);
                        body.setEndedAt(null);
                        body.setDurationSeconds(0L);
                        body.setBillingStatus(BillingStatus.UNBILLED);
                        body.setInvoicedInvoiceId(null);
                        body.setSplitGroupId(null);
                        if (body.getStartedAt() == null) {
                            body.setStartedAt(Instant.now());
                        }
                        return timeEntries.save(body)
                                .flatMap(saved -> {
                                    events.publish(DomainEvent.of(
                                            DomainEventType.TIMER_STARTED, ctx.tenantId(), saved.getId(),
                                            Map.of("userId", userId.toString())));
                                    return Mono.just(saved);
                                });
                    });
        });
    }

    /**
     * Stops the running timer for the given user, applying the D-D3 midnight split.
     * Explicit boolean guard: if no running timer → error 3506 (NOT a switchIfEmpty create).
     * The idempotency-key at the HTTP layer is the belt-and-suspenders; the domain guard
     * is this explicit branch.
     *
     * @param userId  the user whose timer to stop
     * @param endedAt the wall-clock time the timer was stopped (defaults to Instant.now())
     * @param zoneId  optional zone for the midnight-split boundary calculation
     */
    public Mono<List<TimeEntry>> stopTimer(UUID userId, Instant endedAt, String zoneId) {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID effectiveUserId = userId != null ? userId : ctx.userId();
            // Resolve the optional running timer to a boolean then branch explicitly.
            // This is the §9 explicit-boolean pattern applied to stopTimer:
            //   present → close + split
            //   absent  → error 3506 (no timer running to stop)
            // NEVER uses switchIfEmpty here.
            Mono<java.util.Optional<TimeEntry>> running =
                    timeEntries.findFirstByTenantIdAndUserIdAndEndedAtIsNull(ctx.tenantId(), effectiveUserId)
                            .map(java.util.Optional::of)
                            .defaultIfEmpty(java.util.Optional.empty());

            return running.flatMap(opt -> {
                if (opt.isEmpty()) {
                    // Explicit branch: absent → error 3506
                    return Mono.<List<TimeEntry>>error(new DigiPresBeException(
                            "No timer is currently running for this user", 3506, 409));
                }
                // Explicit branch: present → close + split
                TimeEntry r = opt.get();
                Instant stopAt = endedAt != null ? endedAt : Instant.now();
                if (!stopAt.isAfter(r.getStartedAt())) {
                    stopAt = r.getStartedAt(); // zero-length stop
                }
                r.setEndedAt(stopAt);

                List<TimeEntry> segments = splitter.split(r, zoneId);
                if (segments.size() == 1) {
                    Instant finalStopAt = stopAt;
                    return timeEntries.save(segments.get(0))
                            .flatMap(saved -> {
                                events.publish(DomainEvent.of(
                                        DomainEventType.TIMER_STOPPED, ctx.tenantId(), saved.getId(),
                                        Map.of("userId", effectiveUserId.toString(),
                                                "durationSeconds", saved.getDurationSeconds())));
                                return Mono.just(List.of(saved));
                            });
                } else {
                    return timeEntries.saveAll(segments).collectList()
                            .flatMap(saved -> {
                                UUID groupId = saved.get(0).getSplitGroupId();
                                events.publish(DomainEvent.of(
                                        DomainEventType.TIMER_STOPPED, ctx.tenantId(), saved.get(0).getId(),
                                        Map.of("userId", effectiveUserId.toString(),
                                                "splitGroupId", groupId != null ? groupId.toString() : "",
                                                "segmentCount", saved.size())));
                                return Mono.just(saved);
                            });
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Invoice-from-time (D-D6)
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT invoice from unbilled time entries via the existing
     * {@link InvoiceService#create(Invoice)} (D-D6 / §9 item 3).
     *
     * <p><strong>Idempotency:</strong> the {@code billingStatus==UNBILLED} filter is the
     * explicit domain guard. A re-invoke finds zero candidates and returns 3520 — never
     * double-bills. The {@code @IdempotentRoute} annotation on the controller is the
     * belt-and-suspenders at the HTTP layer.
     *
     * <p>Does NOT use {@code switchIfEmpty} around any conditional create — see §9 item 2.
     */
    public Mono<Invoice> createInvoiceFromTime(InvoiceFromTimeRequest request) {
        return TenantContextHolder.required().flatMap(ctx -> {
            // Step 1: load candidates (only UNBILLED + billable + stopped entries)
            Mono<List<TimeEntry>> candidatesMono;
            if (request.timeEntryIds() != null && !request.timeEntryIds().isEmpty()) {
                // Explicit list
                candidatesMono = Flux.fromIterable(request.timeEntryIds())
                        .flatMap(id -> timeEntries.findByTenantIdAndId(ctx.tenantId(), id))
                        .collectList();
            } else if (request.projectId() != null) {
                candidatesMono = timeEntries.findAllByTenantIdAndProjectIdAndBillingStatus(
                        ctx.tenantId(), request.projectId(), BillingStatus.UNBILLED)
                        .collectList();
            } else {
                return Mono.error(new DigiPresBeException(
                        "Either projectId or timeEntryIds must be supplied", 3501, 400));
            }

            return candidatesMono.flatMap(all -> {
                // Filter: billable + stopped + UNBILLED
                List<TimeEntry> candidates = all.stream()
                        .filter(e -> e.isBillable()
                                && e.getEndedAt() != null
                                && e.getBillingStatus() == BillingStatus.UNBILLED)
                        .toList();

                // Explicit isEmpty check — NOT switchIfEmpty (§9 invariant)
                if (candidates.isEmpty()) {
                    // If all were already invoiced → 3522, else 3520
                    boolean allInvoiced = !all.isEmpty() && all.stream()
                            .allMatch(e -> e.getBillingStatus() == BillingStatus.INVOICED);
                    if (allInvoiced) {
                        return Mono.error(new DigiPresBeException(
                                "All selected time entries are already invoiced", 3522, 409));
                    }
                    return Mono.error(new DigiPresBeException(
                            "No unbilled time entries to invoice", 3520, 409));
                }

                // Step 2: build line items (split-aware aggregation D-D6a)
                List<LineItem> lineItems = buildLineItems(candidates, request.defaultRateAmount());
                if (lineItems == null) {
                    return Mono.error(new DigiPresBeException(
                            "A billable time entry has no rate and no default rate was supplied", 3521, 400));
                }

                // Step 3: resolve invoice header (from project if scoped)
                Mono<InvoiceHeader> headerMono;
                if (request.projectId() != null
                        && (request.contactId() == null && request.companyId() == null && request.dealId() == null)) {
                    headerMono = projects.findByTenantIdAndId(ctx.tenantId(), request.projectId())
                            .map(p -> new InvoiceHeader(p.getPrimaryContactId(), p.getCompanyId(),
                                    p.getDealId(), request.projectId()))
                            .switchIfEmpty(Mono.just(new InvoiceHeader(null, null, null, request.projectId())));
                } else {
                    headerMono = Mono.just(new InvoiceHeader(
                            request.contactId(), request.companyId(), request.dealId(), request.projectId()));
                }

                return headerMono.flatMap(header -> {
                    Invoice invoice = Invoice.builder()
                            .tenantId(ctx.tenantId())
                            .projectId(header.projectId())
                            .contactId(header.contactId())
                            .companyId(header.companyId())
                            .dealId(header.dealId())
                            .currency("USD")
                            .lineItems(lineItems)
                            .status(Invoice.Status.DRAFT)
                            .build();

                    // Step 4: create DRAFT invoice via the existing InvoiceService.create
                    // (no INVOICE_FINALIZED emitted — only the DRAFT→SENT edge does that,
                    // verified InvoiceService.java:103-105; consistent with Phase-C milestone path)
                    return invoiceService.create(invoice)
                            .flatMap(created -> {
                                // Step 5: mark all source rows INVOICED (the durable idempotency anchor)
                                UUID invoiceId = created.getId();
                                List<TimeEntry> toSave = candidates.stream()
                                        .map(e -> e.toBuilder()
                                                .billingStatus(BillingStatus.INVOICED)
                                                .invoicedInvoiceId(invoiceId)
                                                .build())
                                        .toList();
                                return timeEntries.saveAll(toSave).collectList()
                                        .flatMap(saved -> {
                                            // Publish TIME_INVOICED event
                                            long totalSeconds = candidates.stream()
                                                    .mapToLong(TimeEntry::getDurationSeconds).sum();
                                            Map<String, Object> payload = new HashMap<>();
                                            payload.put("invoiceId", invoiceId.toString());
                                            payload.put("entryCount", candidates.size());
                                            payload.put("totalSeconds", totalSeconds);
                                            events.publish(DomainEvent.of(
                                                    DomainEventType.TIME_INVOICED,
                                                    ctx.tenantId(), invoiceId, payload));
                                            return Mono.just(created);
                                        });
                            });
                });
            });
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Mono<Void> publishLogged(UUID tenantId, TimeEntry saved) {
        events.publish(DomainEvent.of(DomainEventType.TIME_ENTRY_LOGGED, tenantId, saved.getId(),
                Map.of("userId", saved.getUserId().toString())));
        return Mono.empty();
    }

    /**
     * Builds {@link LineItem}s from candidates (D-D6a split-aware aggregation).
     * Entries sharing a {@code splitGroupId} are summed into one line item.
     * Returns {@code null} if any billable entry lacks a rate and no default is supplied.
     */
    private List<LineItem> buildLineItems(List<TimeEntry> candidates, BigDecimal defaultRate) {
        // Group by splitGroupId (null → each entry is its own group)
        Map<Optional<UUID>, List<TimeEntry>> groups = candidates.stream()
                .collect(Collectors.groupingBy(e -> Optional.ofNullable(e.getSplitGroupId())));

        List<LineItem> lines = new ArrayList<>();
        for (Map.Entry<Optional<UUID>, List<TimeEntry>> grp : groups.entrySet()) {
            List<TimeEntry> group = grp.getValue();
            long totalSeconds = group.stream().mapToLong(TimeEntry::getDurationSeconds).sum();
            BigDecimal hours = BigDecimal.valueOf(totalSeconds)
                    .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);

            // Use the first entry's rate (all entries in a split group share provenance)
            TimeEntry rep = group.get(0);
            BigDecimal rate = rep.getRateAmount() != null ? rep.getRateAmount() : defaultRate;
            if (rate == null) {
                return null; // signal 3521
            }

            String desc = rep.getDescription() != null ? rep.getDescription()
                    : "Time — " + (rep.getProjectId() != null ? rep.getProjectId() : "");

            lines.add(LineItem.builder()
                    .description(desc)
                    .quantity(hours)
                    .unitPrice(rate)
                    .discountPercent(BigDecimal.ZERO)
                    .taxPercent(BigDecimal.ZERO)
                    .build());
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // Nested record types (request payload carriers — not MapStruct DTOs, per plan §7)
    // -------------------------------------------------------------------------

    /**
     * Request payload for {@link #createInvoiceFromTime}.
     * Either {@code projectId} or {@code timeEntryIds} must be supplied.
     */
    public record InvoiceFromTimeRequest(
            UUID projectId,
            List<UUID> timeEntryIds,
            UUID contactId,
            UUID companyId,
            UUID dealId,
            BigDecimal defaultRateAmount
    ) {}

    /** Resolved invoice header context. */
    private record InvoiceHeader(UUID contactId, UUID companyId, UUID dealId, UUID projectId) {}
}
