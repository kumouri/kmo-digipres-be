package com.kumouri.kmodigipresbe.module.dispatch.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.dispatch.model.ProposedAssignment;
import com.kumouri.kmodigipresbe.module.dispatch.model.TechCandidate;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T14 (Home "DispatchIQ") — the orchestrator: loads a day's open work orders + their job-site coordinates
 * + the tenant's technicians, runs the <strong>pure</strong> {@link DispatchOptimizerService} on
 * {@code Schedulers.boundedElastic()} (the compute is off the Netty event loop, the Safety rule), and
 * returns the proposed {@link DispatchPlan}; and <strong>applies</strong> the dispatcher's reviewed
 * assignments through the <strong>unchanged</strong> {@link WorkOrderService#update} path (reuse — apply
 * does not reinvent assignment).
 *
 * <p><strong>Reuse, not reinvent.</strong> The board ({@code DispatchBoardService}) and the WorkOrder
 * assignment seam ({@code WorkOrder.technicianUserId} / {@code WorkOrderService.update}) are unchanged;
 * T14 is the optimization layer on top. Open work orders are read from the same Mongo collection the board
 * reads ({@code work_orders}, the UTC-day {@code scheduledStart} window) excluding terminal ones; techs are
 * the tenant's STAFF {@code User}s ({@code UserRepository.findAllByTenantIdAndPortal}, the Phase-J directory
 * finder); each tech's starting load + routing anchor are derived from their work orders already assigned
 * for the day.
 *
 * <h2>Apply idempotency (the §9 invariant)</h2>
 * Apply takes an explicit list of {@code (workOrderId, techUserId)} decisions — the dispatcher's reviewed
 * (and possibly edited) proposal, never blindly the optimizer's output. For each: load the work order
 * (tenant-scoped via {@link WorkOrderService#findById}); a terminal work order is rejected (4523); a work
 * order <strong>already assigned to the target tech is skipped</strong> (the explicit-boolean
 * already-assigned guard — no save, no event), which is what makes a re-apply a no-op (no double-assign);
 * otherwise {@code WorkOrderService.update(id, patchWithOnlyTechnicianUserId)} commits the assignment.
 * <strong>Never {@code switchIfEmpty(assign)}</strong> — {@code switchIfEmpty} is only the reused service's
 * genuine not-found. The apply loop runs on {@code boundedElastic}.
 */
@Slf4j
@RequiredArgsConstructor
public class DispatchPlanService {

    private final DispatchOptimizerService optimizer;
    private final WorkOrderService workOrders;
    private final UserRepository users;
    private final ReactiveMongoOperations mongo;
    private final DomainEventPublisher events;

    /**
     * Compute the proposed dispatch {@link DispatchPlan} for {@code date} (a UTC day) — the
     * {@code GET /dispatch/optimize} result. Read-only; emits the advisory {@code DISPATCH_PLAN_PROPOSED}.
     */
    public Mono<DispatchPlan> optimize(LocalDate date) {
        return TenantContextHolder.required().flatMap(ctx ->
                loadOpenOrders(ctx.tenantId(), date)
                        .collectList()
                        .flatMap(orders -> hydrate(ctx.tenantId(), orders, date)
                                .flatMap(in -> Mono.fromCallable(() ->
                                                optimizer.optimize(date, orders, in.techs(), in.jobSites()))
                                        .subscribeOn(Schedulers.boundedElastic())))
                        .doOnNext(plan -> events.publish(DomainEvent.of(
                                DomainEventType.DISPATCH_PLAN_PROPOSED, ctx.tenantId(), null,
                                Map.of("date", date.toString(),
                                        "openCount", plan.openCount(),
                                        "assignedCount", plan.assignedCount(),
                                        "unassignedCount", plan.unassignedCount(),
                                        "skillMatchRate", plan.skillMatchRate())))));
    }

    /**
     * Apply the dispatcher's reviewed assignments — the {@code POST /dispatch/apply} action. Each decision
     * commits {@code WorkOrder.technicianUserId} via the unchanged {@link WorkOrderService#update}; a work
     * order already at the target is skipped (no double-assign). Runs on {@code boundedElastic}. Emits the
     * advisory {@code DISPATCH_PLAN_APPLIED}. Returns the applied/skipped counts.
     */
    public Mono<ApplyResult> apply(LocalDate date, List<AssignmentDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return Mono.error(new DigiPresBeException(
                    "At least one assignment decision is required", 4522, 400));
        }
        return TenantContextHolder.required().flatMap(ctx ->
                Flux.fromIterable(decisions)
                        .concatMap(this::applyOne)
                        .collectList()
                        .map(results -> {
                            int applied = (int) results.stream().filter(Boolean::booleanValue).count();
                            int skipped = results.size() - applied;
                            events.publish(DomainEvent.of(
                                    DomainEventType.DISPATCH_PLAN_APPLIED, ctx.tenantId(), null,
                                    Map.of("date", date == null ? "" : date.toString(),
                                            "appliedCount", applied,
                                            "skippedCount", skipped)));
                            return new ApplyResult(applied, skipped);
                        }));
    }

    /** @return true if the work order was (re)assigned, false if it was already at the target (skipped). */
    private Mono<Boolean> applyOne(AssignmentDecision d) {
        if (d == null || d.workOrderId() == null) {
            return Mono.error(new DigiPresBeException(
                    "Each decision needs a workOrderId", 4522, 400));
        }
        return workOrders.findById(d.workOrderId())
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Work order not found: " + d.workOrderId(), 4520, 404)))
                .flatMap(wo -> {
                    if (wo.getStatus() != null && wo.getStatus().isTerminal()) {
                        return Mono.error(new DigiPresBeException(
                                "Work order " + d.workOrderId() + " is " + wo.getStatus()
                                        + " — cannot reassign", 4523, 409));
                    }
                    // Explicit-boolean already-assigned guard → re-apply is a no-op (no double-assign).
                    if (Objects.equals(wo.getTechnicianUserId(), d.techUserId())) {
                        return Mono.just(Boolean.FALSE);
                    }
                    WorkOrder patch = new WorkOrder();
                    patch.setTechnicianUserId(d.techUserId());
                    return workOrders.update(d.workOrderId(), patch).thenReturn(Boolean.TRUE);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ── data loading (the board's read shape, reused) ───────────────────────────────

    /** Open (non-terminal) work orders for the tenant's UTC {@code date} — the board's window, sans terminal. */
    private Flux<WorkOrder> loadOpenOrders(UUID tenantId, LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("scheduledStart").gte(from).lt(to)
                .and("status").nin(WorkOrderStatus.COMPLETED.name(), WorkOrderStatus.CANCELLED.name());
        return mongo.find(new Query(c), WorkOrder.class);
    }

    /** Build the optimizer inputs: the tech candidates (with skills + starting load + anchor) + the job-site map. */
    private Mono<OptimizerInputs> hydrate(UUID tenantId, List<WorkOrder> orders, LocalDate date) {
        Mono<Map<UUID, JobSite>> jobSitesMono = hydrateJobSites(tenantId, orders);
        Mono<List<User>> techsMono = users.findAllByTenantIdAndPortal(tenantId, User.Portal.STAFF)
                .filter(u -> u.getStatus() == User.UserStatus.ACTIVE)
                .collectList();
        return Mono.zip(jobSitesMono, techsMono)
                .map(tuple -> {
                    Map<UUID, JobSite> jobSites = tuple.getT1();
                    List<User> techUsers = tuple.getT2();
                    Map<UUID, Long> loadByTech = currentLoadByTech(orders);
                    Map<UUID, LatLng> anchorByTech = anchorByTech(orders, jobSites);
                    List<TechCandidate> techs = new ArrayList<>(techUsers.size());
                    for (User u : techUsers) {
                        int load = (int) (long) loadByTech.getOrDefault(u.getId(), 0L);
                        techs.add(new TechCandidate(u.getId(), u.getDisplayName(),
                                u.getSkills(), load, anchorByTech.get(u.getId())));
                    }
                    return new OptimizerInputs(techs, jobSites);
                });
    }

    private Mono<Map<UUID, JobSite>> hydrateJobSites(UUID tenantId, List<WorkOrder> orders) {
        List<UUID> ids = orders.stream()
                .map(WorkOrder::getJobSiteId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        Criteria c = Criteria.where("_id").in(ids)
                .andOperator(Criteria.where("tenantId").is(tenantId));
        return mongo.find(new Query(c), JobSite.class).collectMap(JobSite::getId, js -> js);
    }

    /** Count of work orders already assigned to each tech for the day (the starting availability load). */
    private static Map<UUID, Long> currentLoadByTech(List<WorkOrder> orders) {
        return orders.stream()
                .filter(wo -> wo.getTechnicianUserId() != null)
                .collect(Collectors.groupingBy(WorkOrder::getTechnicianUserId, Collectors.counting()));
    }

    /** Each already-assigned tech's routing anchor = their earliest assigned stop's coordinates that day. */
    private static Map<UUID, LatLng> anchorByTech(List<WorkOrder> orders, Map<UUID, JobSite> jobSites) {
        Map<UUID, WorkOrder> earliestAssigned = new HashMap<>();
        for (WorkOrder wo : orders) {
            UUID tech = wo.getTechnicianUserId();
            if (tech == null) {
                continue;
            }
            WorkOrder cur = earliestAssigned.get(tech);
            if (cur == null || before(wo.getScheduledStart(), cur.getScheduledStart())) {
                earliestAssigned.put(tech, wo);
            }
        }
        Map<UUID, LatLng> anchors = new HashMap<>();
        earliestAssigned.forEach((tech, wo) -> {
            LatLng loc = coordsOf(wo, jobSites);
            if (loc != null) {
                anchors.put(tech, loc);
            }
        });
        return anchors;
    }

    private static boolean before(Instant a, Instant b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isBefore(b);
    }

    private static LatLng coordsOf(WorkOrder wo, Map<UUID, JobSite> jobSites) {
        if (wo.getJobSiteId() == null) {
            return null;
        }
        JobSite js = jobSites.get(wo.getJobSiteId());
        if (js == null) {
            return null;
        }
        LatLng loc = js.getLocation();
        if (loc == null || loc.getCoordinates() == null || loc.getCoordinates().length < 2) {
            return null;
        }
        return loc;
    }

    // ── value types ─────────────────────────────────────────────────────────────────

    private record OptimizerInputs(List<TechCandidate> techs, Map<UUID, JobSite> jobSites) {
    }

    /** One dispatcher decision: assign {@code workOrderId} to {@code techUserId} (null clears — unset). */
    public record AssignmentDecision(UUID workOrderId, UUID techUserId) {
    }

    /** The apply outcome: how many work orders were (re)assigned vs already at the target (skipped). */
    public record ApplyResult(int applied, int skipped) {
    }

    /** Convenience for callers/analytics: the assignments a plan would apply (the FE board refresh view). */
    public static List<ProposedAssignment> proposed(DispatchPlan plan) {
        if (plan == null) {
            return List.of();
        }
        List<ProposedAssignment> all = new ArrayList<>(plan.assignments());
        all.addAll(plan.unassigned());
        return all;
    }
}
