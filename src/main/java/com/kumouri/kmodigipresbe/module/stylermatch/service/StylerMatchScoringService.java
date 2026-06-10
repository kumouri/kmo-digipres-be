package com.kumouri.kmodigipresbe.module.stylermatch.service;

import com.kumouri.kmodigipresbe.module.salonspa.model.AvailabilityWindow;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.stylermatch.model.MatchRequest;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the <strong>pure, deterministic, explainable</strong> stylist-match
 * engine. Given a {@link MatchRequest} (the requested service + style + optional slot/preference) and
 * the tenant's stylists + service menus + the requesting contact's prior bookings, it scores each
 * stylist and returns a {@link RankedMatch} list <strong>sorted best-fit first</strong>, each with a
 * human-readable rationale + a per-component breakdown + a confidence.
 *
 * <p><strong>Not an LLM call</strong> — like the T9 {@code StyleRecommendationService} margin-ranking
 * and the CF-1 {@code NoShowRiskScoringService}/{@code WaitlistMatchService} deterministic scoring, the
 * ranking is a transparent weighted sum that is test-stable and defensible. Pure / total / stateless:
 * no repository calls inside the scorer — the orchestrator ({@code StylerMatchService}) loads the data
 * and passes the lists in (the {@code WaitlistMatchService.rankCandidates} shape), so this never blocks
 * the event loop.
 *
 * <h2>The composite score (each component in [0,1])</h2>
 * <ol>
 *   <li><strong>Specialty fit</strong> ({@link #WEIGHT_SPECIALTY}) — the soft style signal: overlap
 *       between the requested style ({@code styleCategory}/{@code color}/{@code texture} expanded to a
 *       keyword set by the same style&rarr;keyword rules as T9, replicated locally so T9's private
 *       surface isn't widened) + the requested service name, and the stylist's free-text
 *       {@link StaffMember#getSpecialties()}. More overlap &rArr; higher. A stylist with no declared
 *       specialty scores a neutral-low {@link #SPECIALTY_NEUTRAL} (ranked, never excluded).</li>
 *   <li><strong>Availability</strong> ({@link #WEIGHT_AVAILABILITY}) — the time signal (only when a
 *       slot is supplied): a stylist whose {@link AvailabilityWindow}s cover the requested slot scores
 *       high; one with an existing CONFIRMED/PENDING_DEPOSIT booking overlapping the slot scores 0 (the
 *       {@code BookingPolicyService.checkAvailability} shape, read-only). With no slot the component is
 *       a neutral {@link #AVAILABILITY_NEUTRAL} and is omitted from the rationale.</li>
 *   <li><strong>Preference</strong> ({@link #WEIGHT_PREFERENCE}) — the loyalty signal: an explicit
 *       {@code preferredStaffMemberId} is the largest bump; otherwise a stylist the contact has seen
 *       before (prior COMPLETED bookings) gets a bump scaled by visit count.</li>
 * </ol>
 * A requested-service <strong>eligibility</strong> miss ({@code eligibleServiceIds} non-empty and
 * lacking the requested service) applies the {@link #ELIGIBILITY_PENALTY} multiplier to the composite
 * (heavily penalized but still <em>visible</em> and rankable — the office sees the whole board; the
 * actual booking re-validates and hard-rejects via {@code BookingPolicyService}).
 *
 * <h2>The never-auto-book guardrail</h2>
 * Every rationale carries the central {@link #STYLIST_CONFIRM_NOTE}, set here so it can never be
 * omitted (a release-blocking fence — the T9 {@code STYLIST_CONFIRM_NOTE} pattern).
 *
 * <p>Always returns SOME ranked stylists when any active stylist exists (a salon is never handed an
 * empty board) — the T9 "a consult is never empty" discipline. Ties are broken deterministically
 * (displayName, then id) so the order is stable across runs (the {@code WaitlistMatchService}
 * {@code scoresStableAcrossRuns} discipline).
 */
@Slf4j
public class StylerMatchScoringService {

    /** The central never-auto-book guardrail appended to EVERY match rationale. */
    public static final String STYLIST_CONFIRM_NOTE =
            "This is a suggested match — the salon will confirm your stylist; nothing is booked "
            + "automatically.";

    // ── component weights (sum to 1.0) ──────────────────────────────────────────
    static final double WEIGHT_SPECIALTY = 0.50;
    static final double WEIGHT_AVAILABILITY = 0.30;
    static final double WEIGHT_PREFERENCE = 0.20;

    /** Specialty-fit score for a stylist with no declared specialty + no keyword hit (ranked, not excluded). */
    static final double SPECIALTY_NEUTRAL = 0.30;
    /** Availability score when no slot was requested (neutral — the component is omitted from the rationale). */
    static final double AVAILABILITY_NEUTRAL = 0.50;
    /** Multiplier applied to the composite when the stylist is NOT eligible for the requested service. */
    static final double ELIGIBILITY_PENALTY = 0.20;

    /** The explicit-preferred-stylist preference component (the largest bump). */
    static final double PREFERENCE_EXPLICIT = 1.0;
    /** Lapsed/lookback cap for the visit-count preference scaling. */
    static final int PREFERENCE_VISIT_CAP = 4;

    private final ZoneId defaultZone;

    public StylerMatchScoringService(String defaultZoneId) {
        ZoneId z;
        try {
            z = (defaultZoneId == null || defaultZoneId.isBlank())
                    ? ZoneOffset.UTC : ZoneId.of(defaultZoneId);
        } catch (RuntimeException e) {
            log.warn("StylerMatch: invalid default zone '{}', falling back to UTC", defaultZoneId);
            z = ZoneOffset.UTC;
        }
        this.defaultZone = z;
    }

    /**
     * Rank {@code stylists} for the {@code request}, best-fit first. Pure + deterministic. {@code menus}
     * supplies the requested service's name (for the specialty-fit keyword match); {@code allBookings} is
     * the tenant's bookings — the requesting contact's COMPLETED ones drive the preference signal, and
     * each stylist's active (CONFIRMED/PENDING_DEPOSIT) ones drive the slot-conflict availability check.
     * Returns an empty list only when {@code stylists} is empty (the caller maps that to 4482); otherwise
     * always returns a non-empty ranked board.
     */
    public List<RankedMatch> rank(MatchRequest request,
                                  List<StaffMember> stylists,
                                  List<ServiceMenu> menus,
                                  List<Booking> allBookings) {
        if (stylists == null || stylists.isEmpty()) {
            return List.of();
        }
        MatchRequest req = request == null ? new MatchRequest() : request;

        Set<String> wantKeywords = styleKeywords(req);
        String serviceName = serviceName(menus, req.getServiceMenuItemId());
        if (serviceName != null) {
            for (String tok : tokenize(serviceName)) {
                wantKeywords.add(tok);
            }
        }
        Map<UUID, Long> completedVisitsByStylist = completedVisitsByStylist(req.getContactId(), allBookings);
        Map<UUID, List<Booking>> activeByStylist = activeBookingsByStylist(allBookings);
        double confidence = confidence(req);

        List<RankedMatch> ranked = new ArrayList<>(stylists.size());
        for (StaffMember s : stylists) {
            if (s == null || s.getId() == null) {
                continue;
            }
            List<Booking> stylistActive = activeByStylist.getOrDefault(s.getId(), List.of());
            ranked.add(scoreOne(req, s, wantKeywords, serviceName, completedVisitsByStylist,
                    stylistActive, confidence));
        }
        // Highest composite first; deterministic tiebreak (displayName then id) for stable ordering.
        ranked.sort(Comparator
                .comparingDouble(RankedMatch::getScore).reversed()
                .thenComparing(r -> nullSafe(r.getDisplayName()))
                .thenComparing(r -> r.getStaffMemberId().toString()));
        return ranked;
    }

    private RankedMatch scoreOne(MatchRequest req, StaffMember s, Set<String> wantKeywords,
                                 String serviceName, Map<UUID, Long> completedVisits,
                                 List<Booking> stylistActive, double confidence) {
        double specialty = specialtyFit(s, wantKeywords);
        boolean eligible = eligibleForService(s, req.getServiceMenuItemId());
        double availability = availability(req, s, stylistActive);
        double preference = preference(req, s, completedVisits);

        double composite = WEIGHT_SPECIALTY * specialty
                + WEIGHT_AVAILABILITY * availability
                + WEIGHT_PREFERENCE * preference;
        if (!eligible) {
            composite *= ELIGIBILITY_PENALTY;
        }
        composite = clamp(composite);

        String rationale = rationale(req, s, specialty, availability, preference, eligible,
                serviceName, completedVisits);

        return RankedMatch.builder()
                .staffMemberId(s.getId())
                .displayName(s.getDisplayName())
                .score(round(composite))
                .confidence(round(confidence))
                .rationale(rationale)
                .specialtyFit(round(specialty))
                .availability(round(availability))
                .preference(round(preference))
                .eligibleForRequestedService(eligible)
                .build();
    }

    // ── specialty fit ───────────────────────────────────────────────────────────

    private double specialtyFit(StaffMember s, Set<String> wantKeywords) {
        List<String> specialties = s.getSpecialties();
        if (wantKeywords.isEmpty()) {
            // No style signal at all → everyone is an equally-neutral fit (a slot/preference-only request).
            return SPECIALTY_NEUTRAL;
        }
        if (specialties == null || specialties.isEmpty()) {
            return SPECIALTY_NEUTRAL; // no declared specialty → neutral-low, never excluded
        }
        Set<String> have = new HashSet<>();
        for (String sp : specialties) {
            have.addAll(tokenize(sp));
        }
        int hits = 0;
        for (String kw : wantKeywords) {
            if (have.contains(kw)) {
                hits++;
            }
        }
        if (hits == 0) {
            return SPECIALTY_NEUTRAL;
        }
        // Scale by the share of requested keywords matched, floored above neutral so any hit beats a
        // no-declared-specialty stylist, capped at 1.0.
        double share = (double) hits / wantKeywords.size();
        return clamp(SPECIALTY_NEUTRAL + (1.0 - SPECIALTY_NEUTRAL) * Math.min(1.0, share + 0.34 * hits));
    }

    private boolean eligibleForService(StaffMember s, String serviceMenuItemId) {
        if (serviceMenuItemId == null || serviceMenuItemId.isBlank()) {
            return true; // no service requested → eligibility is not a discriminator
        }
        List<String> eligible = s.getEligibleServiceIds();
        if (eligible == null || eligible.isEmpty()) {
            return true; // empty = certified for everything (the BookingPolicyService semantics)
        }
        return eligible.contains(serviceMenuItemId);
    }

    // ── availability ──────────────────────────────────────────────────────────────

    private double availability(MatchRequest req, StaffMember s, List<Booking> stylistActive) {
        Instant slotStart = req.getSlotStart();
        if (slotStart == null) {
            return AVAILABILITY_NEUTRAL; // no slot requested → neutral, omitted from the rationale
        }
        Instant slotEnd = req.getSlotEnd() != null ? req.getSlotEnd() : slotStart;
        // A booked conflict makes the stylist unavailable for this slot regardless of their window
        // (the BookingPolicyService.checkAvailability shape: a CONFIRMED/PENDING_DEPOSIT overlap blocks).
        if (hasConflict(stylistActive, slotStart, slotEnd)) {
            return 0.0;
        }
        // Within a declared availability window → strong; outside (or none declared) → low-but-nonzero
        // (the salon may still fit them in; the booking step is the authority).
        return coversSlot(s, slotStart, slotEnd) ? 1.0 : 0.25;
    }

    /** True iff the stylist has an {@link AvailabilityWindow} covering the slot's local-time span. */
    private boolean coversSlot(StaffMember s, Instant slotStart, Instant slotEnd) {
        List<AvailabilityWindow> windows = s.getAvailabilityWindows();
        if (windows == null || windows.isEmpty()) {
            return false;
        }
        ZonedDateTime startZ = slotStart.atZone(defaultZone);
        ZonedDateTime endZ = slotEnd.atZone(defaultZone);
        LocalTime startT = startZ.toLocalTime();
        // For a slot ending exactly at/after midnight or spanning days we only require the start day's
        // window to cover the start; the booking step does exact validation. Use the start's end-time too.
        LocalTime endT = endZ.toLocalDate().equals(startZ.toLocalDate()) ? endZ.toLocalTime() : LocalTime.MAX;
        for (AvailabilityWindow w : windows) {
            if (w == null || w.getDayOfWeek() == null || w.getStartTime() == null || w.getEndTime() == null) {
                continue;
            }
            if (!w.getDayOfWeek().equals(startZ.getDayOfWeek())) {
                continue;
            }
            // window [start, end) must contain [slotStart local, slotEnd local)
            boolean startOk = !startT.isBefore(w.getStartTime());
            boolean endOk = !endT.isAfter(w.getEndTime());
            if (startOk && endOk) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff any of the stylist's active bookings overlaps the half-open slot {@code [slotStart,
     * slotEnd)} — the read-only mirror of {@code BookingPolicyService.checkAvailability}. A booking with
     * null start/end is ignored. Standard half-open interval overlap: {@code bStart < slotEnd &&
     * bEnd > slotStart} (a booking abutting the slot edge does not conflict).
     */
    private static boolean hasConflict(List<Booking> stylistActive, Instant slotStart, Instant slotEnd) {
        for (Booking b : stylistActive) {
            Instant bStart = b.getScheduledStart();
            Instant bEnd = b.getScheduledEnd() != null ? b.getScheduledEnd() : bStart;
            if (bStart == null || bEnd == null) {
                continue;
            }
            if (bStart.isBefore(slotEnd) && bEnd.isAfter(slotStart)) {
                return true;
            }
        }
        return false;
    }

    // ── preference ────────────────────────────────────────────────────────────────

    private double preference(MatchRequest req, StaffMember s, Map<UUID, Long> completedVisits) {
        if (req.getPreferredStaffMemberId() != null
                && req.getPreferredStaffMemberId().equals(s.getId())) {
            return PREFERENCE_EXPLICIT;
        }
        long visits = completedVisits.getOrDefault(s.getId(), 0L);
        if (visits <= 0) {
            return 0.0;
        }
        return clamp((double) Math.min(visits, PREFERENCE_VISIT_CAP) / PREFERENCE_VISIT_CAP);
    }

    /** Per-stylist count of the requesting contact's COMPLETED bookings (the preference signal). */
    private Map<UUID, Long> completedVisitsByStylist(UUID contactId, List<Booking> allBookings) {
        Map<UUID, Long> counts = new HashMap<>();
        if (contactId == null || allBookings == null) {
            return counts;
        }
        for (Booking b : allBookings) {
            if (b == null || b.getStaffMemberId() == null) {
                continue;
            }
            if (contactId.equals(b.getContactId()) && b.getStatus() == BookingStatus.COMPLETED) {
                counts.merge(b.getStaffMemberId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** Per-stylist list of active (CONFIRMED/PENDING_DEPOSIT) bookings (the slot-conflict universe). */
    private Map<UUID, List<Booking>> activeBookingsByStylist(List<Booking> allBookings) {
        Map<UUID, List<Booking>> byStylist = new HashMap<>();
        if (allBookings == null) {
            return byStylist;
        }
        for (Booking b : allBookings) {
            if (b == null || b.getStaffMemberId() == null) {
                continue;
            }
            if (b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_DEPOSIT) {
                byStylist.computeIfAbsent(b.getStaffMemberId(), k -> new ArrayList<>()).add(b);
            }
        }
        return byStylist;
    }

    // ── confidence ────────────────────────────────────────────────────────────────

    /** Fraction of the three discriminating signals (style, slot, preference) present in the request. */
    private double confidence(MatchRequest req) {
        int present = 0;
        if (!styleKeywords(req).isEmpty()) {
            present++;
        }
        if (req.getSlotStart() != null) {
            present++;
        }
        if (req.getPreferredStaffMemberId() != null || req.getContactId() != null) {
            present++;
        }
        return present / 3.0;
    }

    // ── rationale ─────────────────────────────────────────────────────────────────

    private String rationale(MatchRequest req, StaffMember s, double specialty, double availability,
                             double preference, boolean eligible, String serviceName,
                             Map<UUID, Long> completedVisits) {
        StringBuilder sb = new StringBuilder();
        String name = s.getDisplayName() != null ? s.getDisplayName() : "This stylist";

        // Specialty.
        if (!styleKeywords(req).isEmpty() && specialty > SPECIALTY_NEUTRAL) {
            String style = firstNonBlank(req.getStyleCategory(), req.getColor(), req.getTexture());
            sb.append(name).append(" specializes in ")
                    .append(style != null ? style.trim() : "this look").append(". ");
        } else if (s.getSpecialties() == null || s.getSpecialties().isEmpty()) {
            sb.append(name).append(" is a versatile stylist. ");
        } else {
            sb.append(name).append(" can take this on. ");
        }

        // Eligibility (the hard signal — surfaced clearly when missing).
        if (!eligible && serviceName != null) {
            sb.append("Note: not certified for ").append(serviceName.trim())
                    .append(" — the salon will confirm a suitable stylist. ");
        }

        // Availability.
        if (req.getSlotStart() != null) {
            if (availability >= 1.0) {
                sb.append("Available at your requested time. ");
            } else if (availability <= 0.0) {
                sb.append("May not be free at your requested time. ");
            } else {
                sb.append("The salon will confirm availability for your time. ");
            }
        }

        // Preference.
        if (req.getPreferredStaffMemberId() != null
                && req.getPreferredStaffMemberId().equals(s.getId())) {
            sb.append("Your requested stylist. ");
        } else {
            long visits = completedVisits.getOrDefault(s.getId(), 0L);
            if (visits == 1) {
                sb.append("You've seen ").append(name).append(" before. ");
            } else if (visits > 1) {
                sb.append("You've seen ").append(name).append(" ").append(visits).append(" times before. ");
            }
        }

        sb.append(STYLIST_CONFIRM_NOTE);
        return sb.toString();
    }

    // ── style → keyword rules (replicated from T9 StyleRecommendationService.keywordsFor, locally) ──

    /** The style keyword set for a request: styleCategory/color/texture expanded to match keywords. */
    private Set<String> styleKeywords(MatchRequest req) {
        Set<String> kws = new LinkedHashSet<>();
        String style = lower(req.getStyleCategory());
        String color = lower(req.getColor());
        String texture = lower(req.getTexture());
        if (style != null) {
            if (style.contains("balayage")) addAll(kws, "balayage", "color", "highlight");
            if (style.contains("highlight")) addAll(kws, "highlight", "color", "foil");
            if (style.contains("color") || style.contains("colour")) addAll(kws, "color", "colour", "gloss");
            if (style.contains("blonde") || style.contains("blond")) addAll(kws, "blonde", "color", "toner");
            if (style.contains("keratin") || style.contains("smooth")) addAll(kws, "keratin", "smoothing", "treatment");
            if (style.contains("curl") || style.contains("perm")) addAll(kws, "curl", "curly", "perm");
            if (style.contains("cut") || style.contains("bob") || style.contains("trim")
                    || style.contains("lob") || style.contains("layer")) addAll(kws, "cut", "trim", "style");
            if (style.contains("extension")) addAll(kws, "extension");
            if (style.contains("gloss") || style.contains("shine")) addAll(kws, "gloss", "shine");
            if (style.contains("bridal") || style.contains("wedding") || style.contains("updo")) {
                addAll(kws, "bridal", "wedding", "updo");
            }
            for (String tok : tokenize(style)) {
                addAll(kws, tok);
            }
        }
        if (color != null) {
            addAll(kws, "color", "colour");
            if (color.contains("blonde") || color.contains("blond")) addAll(kws, "blonde", "toner");
            for (String tok : tokenize(color)) {
                addAll(kws, tok);
            }
        }
        if (texture != null) {
            if (texture.contains("curl") || texture.contains("coil")) addAll(kws, "curl", "curly", "coily");
            for (String tok : tokenize(texture)) {
                addAll(kws, tok);
            }
        }
        return kws;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static String serviceName(List<ServiceMenu> menus, String serviceMenuItemId) {
        if (menus == null || serviceMenuItemId == null) {
            return null;
        }
        for (ServiceMenu m : menus) {
            if (m == null || m.getServices() == null) {
                continue;
            }
            for (ServiceMenuItem item : m.getServices()) {
                if (item != null && serviceMenuItemId.equals(item.getId())) {
                    return item.getName();
                }
            }
        }
        return null;
    }

    /** Lowercase word tokens of length ≥ 2 (so "curly hair" → {curly, hair}). */
    private static Set<String> tokenize(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        for (String tok : s.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (tok.length() >= 2) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.US);
    }

    private static void addAll(Set<String> target, String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                target.add(v);
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
