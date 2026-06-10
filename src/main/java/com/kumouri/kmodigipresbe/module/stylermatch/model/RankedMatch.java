package com.kumouri.kmodigipresbe.module.stylermatch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — one ranked stylist match: a {@code StaffMember} scored against a
 * {@link MatchRequest}, with an <strong>explained rationale</strong> + the per-component breakdown so the
 * office (and the FE) can see <em>why</em> the stylist ranked where they did. Embedded on a
 * {@link StylerMatch}. The stylist-side twin of the T9 {@code ServiceRecommendation} (same
 * snapshot-plus-rationale shape).
 *
 * <p>{@code score} is the composite [0,1] the list is sorted by (descending). The three components
 * ({@code specialtyFit}, {@code availability}, {@code preference}) are each [0,1] and feed the
 * composite + the rationale. {@code eligibleForRequestedService} reflects the HARD eligibility check
 * ({@code StaffMember.eligibleServiceIds}); a stylist who is not eligible is shown but heavily
 * penalized (the booking step re-validates and hard-rejects via {@code BookingPolicyService}). The
 * {@code rationale} always carries the central
 * {@code StylerMatchScoringService.STYLIST_CONFIRM_NOTE} — a suggested match, never an auto-book.
 *
 * @param staffMemberId               the matched stylist
 * @param displayName                 the stylist's name snapshot
 * @param score                       the composite match score in [0,1] (the sort key, desc)
 * @param confidence                  how many signals were available to score on, in [0,1]
 * @param rationale                   the human-readable why (always includes the stylist-confirm note)
 * @param specialtyFit                the specialty/style-fit component in [0,1]
 * @param availability                the slot-availability component in [0,1] (0.5 neutral when no slot)
 * @param preference                  the past-/explicit-preference component in [0,1] (0 when none)
 * @param eligibleForRequestedService the HARD eligibility result for the requested service (true when no
 *                                    service was requested, or the stylist is certified / eligible-for-all)
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RankedMatch {

    private UUID staffMemberId;
    private String displayName;
    private double score;
    private double confidence;
    private String rationale;
    private double specialtyFit;
    private double availability;
    private double preference;
    private boolean eligibleForRequestedService;
}
