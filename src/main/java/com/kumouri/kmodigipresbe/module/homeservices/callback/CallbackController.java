package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.callback.dto.CallbackCardDTO;
import com.kumouri.kmodigipresbe.module.homeservices.callback.dto.CallbackRecoveryStats;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — the dispatcher surface: the revenue-ranked open callback queue,
 * the dispatch transition, the recovery-funnel read, and the per-tenant copy-book CRUD.
 *
 * <h2>Why this controller injects repositories directly (the T3/T4 controller precedent)</h2>
 * The controller is gated only {@code @ConditionalOnProperty(kmosf.modules.home-services.enabled)} (so it
 * is absent from the OpenAPI spec when home-services is off). It therefore must depend ONLY on
 * always-present beans — the Spring-Data repositories + {@link TenantModuleRegistry} — never the
 * both-modules {@code CallbackIntentHandler}/{@code CallbackOfferSubscriber} beans (which exist only when
 * responder is ALSO on, in {@code CallbackAutoConfiguration}). The {@code MidnightResponderStatsController}
 * / {@code SwitchboardController} precedent: the read/transition logic is small and lives inline here over
 * the repositories; the deterministic ranking is the shared pure {@link CallbackRevenueRanker} (used at
 * write time by the handler — the read is a pure DB sort on the persisted {@code revenueScore}).
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET  /home-services/callbacks} → the revenue-ranked open queue ({@link CallbackCardDTO},
 *       highest {@code revenueScore} first).</li>
 *   <li>{@code POST /home-services/callbacks/{id}/dispatch} ({@code @IdempotentRoute}) → mark a card
 *       DISPATCHED (4400 if not found, 4402 if not REQUESTED).</li>
 *   <li>{@code GET  /home-services/callbacks/recovery-stats} → {@link CallbackRecoveryStats}.</li>
 *   <li>{@code GET  /home-services/callbacks/config} (ADMIN) → the tenant's {@link CallbackConfig}
 *       (4401 if none).</li>
 *   <li>{@code PUT  /home-services/callbacks/config} (ADMIN, body {@link ConfigRequest}) → the upserted
 *       config.</li>
 * </ul>
 *
 * <h2>Gating (BOTH modules — the {@code SwitchboardController} precedent)</h2>
 * Per-tenant membership via {@link TenantModuleRegistry#requireEnabled} for <strong>both</strong>
 * {@code home-services} AND {@code responder} (1130/1132 otherwise). {@link RoleGuard#requireRole "ADMIN"}
 * on the config CRUD only; the queue / dispatch / recovery-stats are staff-accessible (the
 * {@code /home-services/**} authenticated security chain, like {@code DispatchBoardController} /
 * {@code MissedCallInboxController}).
 *
 * <h2>Errors (4400-4409 band)</h2>
 * {@code 4400} callback request not found (404); {@code 4401} config not found on a read (404);
 * {@code 4402} callback not in REQUESTED state — cannot dispatch (409, explicit-boolean); {@code 4403}
 * invalid config body (400). {@code 4404-4409} reserved.
 */
@Slf4j
@RestController
@RequestMapping("/home-services/callbacks")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
public class CallbackController {

    private final CallbackRequestRepository callbackRequests;
    private final CallbackFunnelLogRepository funnelLogs;
    private final CallbackConfigRepository configs;
    private final TenantModuleRegistry modules;
    private final DomainEventPublisher events;

    public CallbackController(CallbackRequestRepository callbackRequests,
                             CallbackFunnelLogRepository funnelLogs,
                             CallbackConfigRepository configs,
                             TenantModuleRegistry modules,
                             DomainEventPublisher events) {
        this.callbackRequests = callbackRequests;
        this.funnelLogs = funnelLogs;
        this.configs = configs;
        this.modules = modules;
        this.events = events;
    }

    /** The revenue-ranked open callback queue (REQUESTED, highest revenueScore first — a pure DB sort). */
    @GetMapping
    public Flux<CallbackCardDTO> queue() {
        return guard()
                .thenMany(TenantContextHolder.required()
                        .flatMapMany(ctx -> callbackRequests
                                .findByTenantIdAndStatusOrderByRevenueScoreDesc(
                                        ctx.tenantId(), CallbackStatus.REQUESTED)))
                .map(CallbackCardDTO::from);
    }

    /**
     * Mark a callback DISPATCHED (idempotent route). Genuine not-found → {@code 4400}/404
     * ({@code switchIfEmpty}); a non-REQUESTED state → {@code 4402}/409 via an explicit-boolean check (a
     * double-dispatch is a defensive 409). On success, writes the DISPATCHED funnel row (best-effort).
     */
    @PostMapping("/{id}/dispatch")
    @IdempotentRoute
    public Mono<CallbackCardDTO> dispatch(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> callbackRequests.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Callback request not found", 4400, 404)))
                        .flatMap(req -> {
                            if (req.getStatus() != CallbackStatus.REQUESTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Callback is not in REQUESTED state — cannot dispatch", 4402, 409));
                            }
                            CallbackRequest updated = req.toBuilder()
                                    .status(CallbackStatus.DISPATCHED)
                                    .build();
                            return callbackRequests.save(updated)
                                    .flatMap(saved -> recordDispatchedFunnel(
                                            ctx.tenantId(), saved.getCallSid())
                                            .then(Mono.fromRunnable(() ->
                                                    emitDispatched(ctx.tenantId(), saved)))
                                            .thenReturn(CallbackCardDTO.from(saved)));
                        }));
    }

    /** The missed-call → callback recovery funnel (offered / accepted / dispatched + rates). */
    @GetMapping("/recovery-stats")
    public Mono<CallbackRecoveryStats> recoveryStats() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> Mono.zip(
                                funnelLogs.countByTenantIdAndStage(
                                        ctx.tenantId(), CallbackFunnelStage.OFFERED).defaultIfEmpty(0L),
                                funnelLogs.countByTenantIdAndStage(
                                        ctx.tenantId(), CallbackFunnelStage.ACCEPTED).defaultIfEmpty(0L),
                                funnelLogs.countByTenantIdAndStage(
                                        ctx.tenantId(), CallbackFunnelStage.DISPATCHED).defaultIfEmpty(0L))
                        .map(t -> CallbackRecoveryStats.of(t.getT1(), t.getT2(), t.getT3())));
    }

    /** Read the tenant's callback copy book (ADMIN; 4401 if none has been created yet). */
    @GetMapping("/config")
    public Mono<CallbackConfig> getConfig() {
        return adminGuard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Callback config not found for this tenant", 4401, 404))));
    }

    /**
     * Upsert the tenant's callback copy book (ADMIN, one row per tenant). Explicit-boolean
     * find-then-update (never {@code switchIfEmpty(create)}): an existing row is updated in place
     * (preserving id/version/timestamps), else a fresh row is created.
     */
    @PutMapping("/config")
    public Mono<CallbackConfig> upsertConfig(@RequestBody ConfigRequest body) {
        validate(body);
        return adminGuard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(existing -> {
                            CallbackConfig toSave = existing.isPresent()
                                    ? body.applyTo(existing.get())
                                    : body.toNewEntity(ctx.tenantId());
                            return configs.save(toSave);
                        }));
    }

    /** Record the DISPATCHED funnel row (best-effort — analytics never break the transition). */
    private Mono<Void> recordDispatchedFunnel(UUID tenantId, String callSid) {
        return funnelLogs.save(CallbackFunnelLog.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .stage(CallbackFunnelStage.DISPATCHED)
                        .callSid(callSid)
                        .occurredAt(Instant.now())
                        .build())
                .then()
                .onErrorResume(e -> {
                    log.warn("Callback DISPATCHED funnel write failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    /** Advisory {@code CALLBACK_DISPATCHED} after a card is claimed. Drives no core mutation. */
    private void emitDispatched(UUID tenantId, CallbackRequest saved) {
        Map<String, Object> payload = new HashMap<>();
        if (saved.getId() != null) {
            payload.put("callbackRequestId", saved.getId().toString());
        }
        if (saved.getCallSid() != null) {
            payload.put("callSid", saved.getCallSid());
        }
        events.publish(DomainEvent.of(
                DomainEventType.CALLBACK_DISPATCHED, tenantId, saved.getId(), payload));
    }

    /** Both modules (home-services AND responder) loaded + enabled for the tenant. */
    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(ResponderAutoConfiguration.MODULE_KEY));
    }

    /** Both modules + ADMIN (for the config CRUD). */
    private Mono<Void> adminGuard() {
        return guard().then(RoleGuard.requireRole("ADMIN"));
    }

    private static void validate(ConfigRequest body) {
        if (body == null) {
            throw new DigiPresBeException("Callback config body is required", 4403, 400);
        }
    }

    /**
     * The config upsert request — drops server-managed fields (id/tenantId/version/timestamps). Every
     * field is optional; a blank field falls back to the {@link CallbackCopy} default at send time.
     *
     * @param offerMessage            the opt-in offer SMS copy
     * @param immediateConfirmMessage the confirmation reply for an immediate callback
     * @param scheduledConfirmMessage the confirmation reply for a scheduled callback
     */
    public record ConfigRequest(
            String offerMessage,
            String immediateConfirmMessage,
            String scheduledConfirmMessage) {

        CallbackConfig toNewEntity(UUID tenantId) {
            return CallbackConfig.builder()
                    .tenantId(tenantId)
                    .offerMessage(offerMessage)
                    .immediateConfirmMessage(immediateConfirmMessage)
                    .scheduledConfirmMessage(scheduledConfirmMessage)
                    .build();
        }

        CallbackConfig applyTo(CallbackConfig existing) {
            return existing.toBuilder()
                    .offerMessage(offerMessage)
                    .immediateConfirmMessage(immediateConfirmMessage)
                    .scheduledConfirmMessage(scheduledConfirmMessage)
                    .build();
        }
    }
}
