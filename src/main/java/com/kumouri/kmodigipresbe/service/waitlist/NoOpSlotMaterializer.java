package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * E4 — the built-in fallback {@link SlotMaterializer}. Used by {@link WaitlistClaimEngine} when no
 * consumer-contributed materializer matched the claimed offer's {@code slotType}. It creates NO domain
 * record (the engine is vertical-agnostic — it has nothing of its own to create) and returns
 * {@link MaterializedRef#none()}, so the engine is fully functional with zero consumers: the winning YES
 * still atomically claims the slot, the offer is marked CLAIMED, siblings SUPERSEDED, the entry FULFILLED,
 * the confirmation SMS sent, and {@code WAITLIST_ENGINE_SLOT_CLAIMED} fired — only the domain-record ref is
 * absent. This is the {@code DefaultHandoffIntentHandler} (E2) precedent: a universal lowest-precedence
 * fallback, excluded from the engine's first-pass {@code supports} match.
 */
@Slf4j
public class NoOpSlotMaterializer implements SlotMaterializer {

    /** The reserved key for the fallback no-op; a specific materializer must NOT use it or {@code supports} it. */
    public static final String KEY = "no-op";

    @Override
    public String key() {
        return KEY;
    }

    /**
     * The no-op claims no slot type in the first pass — the engine selects it only as the fallback. (A
     * specific materializer is matched by its own {@code supports}; this returning false keeps the no-op out
     * of the first-pass loop, mirroring {@code DefaultHandoffIntentHandler}'s exclusion.)
     */
    @Override
    public boolean supports(String slotType) {
        return false;
    }

    @Override
    public Mono<MaterializedRef> materialize(UUID tenantId, WaitlistOffer claimedOffer, WaitlistSlot slot) {
        log.debug("NoOpSlotMaterializer: slot {} ({}) claimed by contact {} for tenant {} — no domain "
                        + "record created (no consumer materializer registered for this slotType)",
                claimedOffer.getSlotKey(), claimedOffer.getSlotType(), claimedOffer.getContactId(), tenantId);
        return Mono.just(MaterializedRef.none());
    }
}
