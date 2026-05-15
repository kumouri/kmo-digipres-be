package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Validates a salon booking attempt before it is persisted. The two checks are:
 * <ol>
 *   <li><em>Staff eligibility</em> — the requested staff member must either have an
 *       empty {@code eligibleServiceIds} list (eligible for everything) or explicitly
 *       include the requested {@code serviceMenuItemId}.</li>
 *   <li><em>Availability</em> — no existing CONFIRMED or PENDING_DEPOSIT booking
 *       for the same staff member overlaps the requested window.</li>
 * </ol>
 *
 * <p>Both checks are skipped when {@code staffMemberId} is null (walk-in / staff-
 * assigned booking) or when {@code serviceMenuItemId} is null (general appointment).
 */
@RequiredArgsConstructor
public class BookingPolicyService {

    private final StaffMemberRepository staff;
    private final BookingRepository bookings;

    /**
     * @return an empty {@code Mono<Void>} on success; an error Mono carrying a
     *         {@link DigiPresBeException} (errorCode 2900 eligibility, 2901 availability)
     *         on failure.
     */
    public Mono<Void> validate(UUID tenantId,
                               String serviceMenuItemId,
                               UUID staffMemberId,
                               Instant scheduledStart,
                               Instant scheduledEnd) {
        if (staffMemberId == null) return Mono.empty();
        return checkEligibility(tenantId, staffMemberId, serviceMenuItemId)
                .then(checkAvailability(tenantId, staffMemberId, scheduledStart, scheduledEnd));
    }

    private Mono<Void> checkEligibility(UUID tenantId, UUID staffMemberId, String serviceMenuItemId) {
        if (serviceMenuItemId == null) return Mono.empty();
        return staff.findById(staffMemberId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "StaffMember not found", 2900, 404)))
                .flatMap(member -> {
                    if (!member.getTenantId().equals(tenantId)) {
                        return Mono.error(new DigiPresBeException(
                                "StaffMember not found", 2900, 404));
                    }
                    var eligible = member.getEligibleServiceIds();
                    if (!eligible.isEmpty() && !eligible.contains(serviceMenuItemId)) {
                        return Mono.error(new DigiPresBeException(
                                "Staff member is not eligible for service: " + serviceMenuItemId,
                                2900, 422));
                    }
                    return Mono.just(member);
                })
                .then();
    }

    private Mono<Void> checkAvailability(UUID tenantId, UUID staffMemberId,
                                          Instant start, Instant end) {
        if (start == null || end == null) return Mono.empty();
        return bookings.findByTenantIdAndStaffMemberIdAndScheduledStartBetween(
                        tenantId, staffMemberId, start.minusSeconds(1), end)
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED
                        || b.getStatus() == BookingStatus.PENDING_DEPOSIT)
                .hasElements()
                .flatMap(conflict -> {
                    if (conflict) {
                        return Mono.error(new DigiPresBeException(
                                "Staff member is not available in the requested time window",
                                2901, 409));
                    }
                    return Mono.empty();
                });
    }
}
