package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * E4 — the plug point for vertical slot creation. When the first YES atomically claims a freed slot, the
 * generic {@link WaitlistClaimEngine} delegates the <strong>actual domain record creation</strong> to a
 * {@code SlotMaterializer} the consumer contributes — so the engine itself stays
 * <strong>vertical-agnostic</strong> (no salon {@code Booking} / health {@code Appointment} coupling,
 * design directive #2).
 *
 * <h2>Registration (the E2 {@code IntentHandler} precedent — no engine edit to add a materializer)</h2>
 * A consumer (the Health "RescheduleFlow" T7, a salon adapter, …) registers a {@code SlotMaterializer}
 * purely by contributing a {@code @Bean}. The {@link WaitlistClaimEngine} auto-discovers every
 * {@code SlotMaterializer} via a {@code List<SlotMaterializer>} inject and dispatches to the first whose
 * {@link #supports(String)} returns true for the claimed offer's {@code slotType}. <strong>No engine edit
 * is ever needed.</strong>
 *
 * <p>The built-in {@link NoOpSlotMaterializer} (key {@value NoOpSlotMaterializer#KEY}) is the fallback
 * used when no specific materializer matched; it marks the claim won with a {@code null}
 * {@link MaterializedRef} (the engine is functional with zero consumers — useful for demos / tests). A
 * specific materializer's {@link #supports} must NOT match the no-op key; the engine excludes the no-op
 * from the first-pass match (the {@code DefaultHandoffIntentHandler} precedent).
 *
 * <h2>PHI-free by construction</h2>
 * The materializer receives only the resolved {@code tenantId}, the {@link WaitlistOffer} (phone + slot
 * snapshot), and the reconstructed {@link WaitlistSlot} — never clinical/PII data. T7 can therefore create
 * a real {@code Appointment} PHI-free: the engine arbitrates the race; the consumer owns the domain write.
 */
public interface SlotMaterializer {

    /** A stable unique key for this materializer (e.g. {@code "salon-booking"}, {@code "health-appt"}). */
    String key();

    /**
     * Which {@link WaitlistSlot#slotType()} this materializer creates a domain record for. Default:
     * {@code key().equals(slotType)} — override only if one materializer should claim several slot types.
     */
    default boolean supports(String slotType) {
        return key().equals(slotType);
    }

    /**
     * Create the consumer's real domain record for a just-claimed slot. Called <strong>only</strong> on the
     * winning YES (the slot-level {@code findAndModify} already resolved the race). Returns a
     * {@link MaterializedRef} identifying what was created (e.g. {@code ("BOOKING", bookingId)}), echoed in
     * the {@code WAITLIST_ENGINE_SLOT_CLAIMED} event + the claim doc. A failure here makes the claim
     * report {@code LOST} (the engine leaves the offer OFFERED for retry/expiry and apologizes — never a
     * silent drop), so a materializer should be best-effort-correct.
     *
     * @param tenantId      the resolved tenant (the engine's synthetic context tenant)
     * @param claimedOffer  the OFFERED→CLAIMED offer (phone + slot snapshot + the winning contactId)
     * @param slot          the {@link WaitlistSlot} reconstructed from the offer's snapshot columns
     */
    Mono<MaterializedRef> materialize(UUID tenantId, WaitlistOffer claimedOffer, WaitlistSlot slot);

    /**
     * What a materializer created. {@code refType} is the consumer's record kind (e.g. {@code "BOOKING"},
     * {@code "APPOINTMENT"}); {@code refId} its id. Both may be null (e.g. the no-op fallback) — the engine
     * still records the claim and supersedes siblings; only the echoed ref is absent.
     */
    record MaterializedRef(String refType, UUID refId) {

        public static MaterializedRef none() {
            return new MaterializedRef(null, null);
        }

        public static MaterializedRef of(String refType, UUID refId) {
            return new MaterializedRef(refType, refId);
        }
    }
}
