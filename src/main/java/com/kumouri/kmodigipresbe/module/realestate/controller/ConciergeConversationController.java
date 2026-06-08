package com.kumouri.kmodigipresbe.module.realestate.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.controller.dto.ConciergeConversationDetailDTO;
import com.kumouri.kmodigipresbe.module.realestate.controller.dto.ConciergeConversationSummaryDTO;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-5a) — the staff-facing concierge conversation read. RE-1..RE-3 built the
 * {@link ConciergeConversation} model + the inbound router (which mints conversations, turns + citations,
 * the accumulated qualification, and the linked Deal) but exposed no admin read; this is that read, so
 * the RE-5b FE can show the concierge's work: the per-listing thread list and a single thread's full
 * transcript + citations + qualification + lead tier.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /realestate/conversations} — the list: every conversation for the tenant, newest
 *       activity first, each a lean {@link ConciergeConversationSummaryDTO} (id, listingId, buyer
 *       contactId, state, leadTier, turnCount, lastActivityAt). {@code listingId} optionally filters
 *       to one listing's threads.</li>
 *   <li>{@code GET /realestate/conversations/{id}} — the detail: the
 *       {@link ConciergeConversationDetailDTO} with the ordered turn transcript (each assistant turn's
 *       citations), the accumulated qualification, and the linked dealId + leadTier.</li>
 * </ul>
 *
 * <h2>Gating (the {@code ListingController} / {@code WaitlistBoardController} precedent)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.realestate.enabled} — so this
 *       controller is absent from the generated OpenAPI spec when the module is off (the
 *       {@code ListingController} precedent, where {@code OpenApiEndpointIT} runs without the realestate
 *       flag);</li>
 *   <li>per-tenant module membership via {@link TenantModuleRegistry#requireEnabled(String)} — a tenant
 *       without {@code realestate} gets the shared {@code 1130}/{@code 1132} module-gate not-enabled
 *       response (the {@code WaitlistBoardController} 1132 posture);</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} — a concierge transcript is staff-only ({@code 1800}
 *       otherwise), matching {@code ListingController}.</li>
 * </ul>
 *
 * <p>Purely additive: a read over the RE-1..RE-3 collections that does not touch RE-1..RE-4 behavior.
 * The conversation is tenant-scoped on the by-id fetch, so a thread can never be read for a foreign
 * tenant ({@code 4270} on a missing / not-owned conversation — the RE-5a band; {@code 4271-4274}
 * reserved for RE-5a read growth). The {@code leadTier} enrichment is the one cross-collection read:
 * HOT/WARM/COLD off the buyer {@link com.kumouri.kmodigipresbe.model.contact.Contact}'s
 * {@code leadScore} (the same tier the RE-2 hot-handoff keys on), or null when the buyer is unscored /
 * not yet materialized.
 */
@RestController
@RequestMapping("/realestate/conversations")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class ConciergeConversationController {

    private final ConciergeConversationRepository conversations;
    private final ContactRepository contacts;
    private final TenantModuleRegistry modules;

    /**
     * The concierge conversation list, newest activity first. STAFF + module gated. {@code listingId}
     * optionally narrows to a single listing's threads (else the tenant's full list).
     */
    @GetMapping
    public Flux<ConciergeConversationSummaryDTO> list(
            @RequestParam(name = "listingId", required = false) UUID listingId) {
        return guard().thenMany(TenantContextHolder.required().flatMapMany(ctx -> {
            Flux<ConciergeConversation> source = (listingId == null)
                    ? conversations.findByTenantIdOrderByLastInboundAtDesc(ctx.tenantId())
                    : conversations.findByTenantIdAndListingIdOrderByLastInboundAtDesc(
                            ctx.tenantId(), listingId);
            return source.concatMap(c -> resolveLeadTier(ctx.tenantId(), c.getContactId())
                    .map(tier -> ConciergeConversationSummaryDTO.from(c, tier.orElse(null))));
        }));
    }

    /**
     * One conversation's full detail (transcript + citations + qualification + dealId + leadTier).
     * STAFF + module gated; {@code 4270}/404 on a missing / not-owned conversation.
     */
    @GetMapping("/{id}")
    public Mono<ConciergeConversationDetailDTO> get(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required().flatMap(ctx ->
                conversations.findByIdAndTenantId(id, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Conversation not found", 4270, 404)))
                        .flatMap(c -> resolveLeadTier(ctx.tenantId(), c.getContactId())
                                .map(tier -> ConciergeConversationDetailDTO.from(c, tier.orElse(null))))));
    }

    /**
     * The buyer's lead tier (HOT/WARM/COLD) off the resolved Contact's {@code leadScore}, or an empty
     * optional when there is no contact yet (RE-2 not run) or the contact is unscored (no nightly score
     * yet) — the FE renders "unscored". Best-effort: a missing contact never fails the read.
     */
    private Mono<Optional<String>> resolveLeadTier(UUID tenantId, UUID contactId) {
        if (contactId == null) {
            return Mono.just(Optional.empty());
        }
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .map(contact -> {
                    LeadScore ls = contact.getLeadScore();
                    return Optional.ofNullable(ls == null ? null : ls.tier());
                })
                .defaultIfEmpty(Optional.empty());
    }

    /** realestate module loaded + enabled for the tenant, then STAFF — the ListingController order. */
    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
