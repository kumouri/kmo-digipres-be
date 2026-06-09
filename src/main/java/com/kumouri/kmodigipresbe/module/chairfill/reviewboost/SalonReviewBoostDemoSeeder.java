package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.integration.SentimentSource;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T6 Salon "ReviewBoost" — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code SwitchboardDemoSeeder} / {@code MidnightResponderDemoSeeder}
 * precedent) that stands up a fictional salon tenant <strong>"Glow House Salon"</strong> wired for the full
 * ReviewBoost board: salon-spa + chairfill enabled, two stylists, completed bookings each with a per-stylist
 * SENT review-request, and a negative review with a DRAFTED on-brand reply already parked in the approval
 * queue.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-salon-reviewboost")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code glow-house-salon} — re-running does nothing. All data is
 * <strong>fictional</strong>; the sandbox {@code reviewLink} is an example URL (no live Google).
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Glow House Salon" + an ADMIN user; modules {@code salon-spa} + {@code chairfill} enabled;
 *       a non-zero {@code aiBudgetUsd}.</li>
 *   <li>An {@code IntegrationConnection(twilio)} with notify targets + a sandbox {@code reviewLink} (so the
 *       ReviewBoost config read-back shows "wired").</li>
 *   <li>Two {@link StaffMember} stylists (Maya, Jordan) + two client {@link Contact}s.</li>
 *   <li>Two COMPLETED {@link Booking}s (one per stylist) — so the per-stylist attribution shows — and a
 *       SENT {@link ReviewRequest} for each (attributed {@code STAFF + staffMemberId}, the shape the
 *       shipped {@code ReviewRequestService} stamps on {@code BOOKING_COMPLETED}). Maya gets a 2nd SENT
 *       request (a 2nd happy client) so the board has uneven per-stylist funnels.</li>
 *   <li>Three ingested {@link GbpReviewReply} reviews (two 5★ POSITIVE, one 2★ NEGATIVE) — the NEGATIVE one
 *       carries a DRAFTED on-brand salon reply, so the manager-alert + reply-draft demo surfaces in the
 *       reused approval queue.</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-salon-reviewboost,dev}) "A busy salon. Every finished
 *       appointment should ask for a Google review — but no one has time."</li>
 *   <li>{@code GET /api/v1/chairfill/reviewboost/insights} → each stylist's request→response funnel + the
 *       salon's review health, side-by-side ("which chair drives our reputation?").</li>
 *   <li>{@code GET /api/v1/gbp/review-replies} → a 2★ review came in, the manager got an alert, and a
 *       ready-to-edit on-brand AI reply already sits in the approval queue (never auto-posted).</li>
 *   <li>"ReviewBoost: every chair asks, every review gets a reply — and you see which stylist drives your
 *       reputation."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-salon-reviewboost")
public class SalonReviewBoostDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "glow-house-salon";
    private static final String ADMIN_EMAIL = "owner@glow-house-salon.example";
    private static final String NOTIFY_PHONE = "+12145550400";
    private static final String NOTIFY_EMAIL = "owner@glow-house-salon.example";
    private static final String FROM_NUMBER = "+12145550399";
    private static final String REVIEW_LINK = "https://example.com/glow-house-salon/review";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final StaffMemberRepository staffMembers;
    private final BookingRepository bookings;
    private final ReviewRequestRepository reviewRequests;
    private final GbpReviewReplyRepository reviewReplies;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public SalonReviewBoostDemoSeeder(TenantRepository tenants,
                                      UserRepository users,
                                      ContactRepository contacts,
                                      StaffMemberRepository staffMembers,
                                      BookingRepository bookings,
                                      ReviewRequestRepository reviewRequests,
                                      GbpReviewReplyRepository reviewReplies,
                                      IntegrationConnectionRepository connections,
                                      PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.contacts = contacts;
        this.staffMembers = staffMembers;
        this.bookings = bookings;
        this.reviewRequests = reviewRequests;
        this.reviewReplies = reviewReplies;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("SalonReviewBoostDemoSeeder failed", err));
    }

    private Mono<Void> seed() {
        return tenants.findBySlug(TENANT_SLUG)
                .doOnNext(t -> log.info("Demo tenant {} already seeded — skipping", TENANT_SLUG))
                .switchIfEmpty(Mono.defer(this::seedFresh))
                .then();
    }

    private Mono<Tenant> seedFresh() {
        UUID tenantId = UUID.randomUUID();
        UUID stylistMaya = UUID.randomUUID();
        UUID stylistJordan = UUID.randomUUID();
        UUID clientAva = UUID.randomUUID();
        UUID clientBen = UUID.randomUUID();

        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .slug(TENANT_SLUG)
                .displayName("Glow House Salon")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        SalonSpaAutoConfiguration.MODULE_KEY,
                        ChairFillAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();

        log.info("Seeding demo tenant {} (Glow House Salon) + 2 stylists + completed bookings + "
                + "per-stylist review-requests + a negative review with a drafted reply", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        Instant now = Instant.now();

        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(staffMembers.save(stylist(tenantId, stylistMaya, "Maya Chen")))
                        .then(staffMembers.save(stylist(tenantId, stylistJordan, "Jordan Rivera")))
                        .then(contacts.save(client(tenantId, clientAva, "Ava", "Thompson", "+12145550501")))
                        .then(contacts.save(client(tenantId, clientBen, "Ben", "Okafor", "+12145550502")))
                        // Completed bookings (one per stylist) — the per-stylist attribution anchor.
                        .then(bookings.save(completedBooking(tenantId, clientAva, stylistMaya,
                                "Cut & Color", now.minus(8, ChronoUnit.DAYS))))
                        .then(bookings.save(completedBooking(tenantId, clientBen, stylistJordan,
                                "Men's Cut", now.minus(6, ChronoUnit.DAYS))))
                        // SENT review-requests stamped STAFF + staffMemberId (the shipped engine's shape):
                        // Maya 2 SENT (two happy clients), Jordan 1 SENT — uneven per-stylist funnels.
                        .then(reviewRequests.save(sentRequest(tenantId, stylistMaya, clientAva)))
                        .then(reviewRequests.save(sentRequest(tenantId, stylistMaya, UUID.randomUUID())))
                        .then(reviewRequests.save(sentRequest(tenantId, stylistJordan, clientBen)))
                        // Ingested reviews: 2x 5★ POSITIVE, 1x 2★ NEGATIVE (the NEGATIVE carries a draft).
                        .then(reviewReplies.save(review(tenantId, "reviews/glow-1", 5,
                                ReviewSentiment.POSITIVE, "Ava T.",
                                "Maya gave me the best color I've ever had — obsessed!", null)))
                        .then(reviewReplies.save(review(tenantId, "reviews/glow-2", 5,
                                ReviewSentiment.POSITIVE, "Ben O.",
                                "Quick, friendly, great cut. Will be back.", null)))
                        .then(reviewReplies.save(review(tenantId, "reviews/glow-3", 2,
                                ReviewSentiment.NEGATIVE, "Dana P.",
                                "Waited 40 minutes past my appointment time. Disappointing.",
                                "Hi Dana, thank you for the feedback — we're so sorry about the wait; "
                                        + "that's not the experience we want for you. We'd love to make it "
                                        + "right; please reach out so we can take care of you.")))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — GET /api/v1/chairfill/reviewboost/"
                        + "insights for the per-stylist board; GET /api/v1/gbp/review-replies for the "
                        + "drafted reply to the 2-star review", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("reviewboost-demo-password"))
                .displayName("Glow House Owner")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: notify targets + a sandbox reviewLink (so ReviewBoost reads as "wired"). */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
        config.put("notifyPhone", NOTIFY_PHONE);
        config.put("notifyEmail", NOTIFY_EMAIL);
        config.put("reviewLink", REVIEW_LINK);
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "demo-sandbox-authtoken",
                        "fromNumber", FROM_NUMBER)))
                .config(config)
                .build();
    }

    private StaffMember stylist(UUID tenantId, UUID id, String displayName) {
        return StaffMember.builder()
                .id(id)
                .tenantId(tenantId)
                .displayName(displayName)
                .active(true)
                .build();
    }

    private Contact client(UUID tenantId, UUID id, String first, String last, String phone) {
        return Contact.builder()
                .id(id)
                .tenantId(tenantId)
                .firstName(first)
                .lastName(last)
                .displayName(first + " " + last)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .build();
    }

    private Booking completedBooking(UUID tenantId, UUID contactId, UUID staffMemberId,
                                     String service, Instant when) {
        return Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .staffMemberId(staffMemberId)
                .serviceMenuItemId("svc-" + service.toLowerCase().replace(' ', '-').replace("&", "and"))
                .serviceMenuItemName(service)
                .scheduledStart(when)
                .scheduledEnd(when.plus(1, ChronoUnit.HOURS))
                .status(BookingStatus.COMPLETED)
                .build();
    }

    /** A SENT review-request attributed to the stylist — the shape ReviewRequestService stamps. */
    private ReviewRequest sentRequest(UUID tenantId, UUID staffMemberId, UUID contactId) {
        Instant now = Instant.now();
        return ReviewRequest.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subjectType(ReviewSubjectType.STAFF)
                .subjectId(staffMemberId)
                .contactId(contactId)
                .sourceEventType("booking.completed")
                .status(ReviewRequest.Status.SENT)
                .dueAt(now.minus(1, ChronoUnit.DAYS))
                .sentAt(now.minus(1, ChronoUnit.DAYS))
                .build();
    }

    private GbpReviewReply review(UUID tenantId, String reviewId, Integer rating,
                                  ReviewSentiment sentiment, String reviewer, String comment,
                                  String draftedReply) {
        return GbpReviewReply.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .reviewId(reviewId)
                .rating(rating)
                .comment(comment)
                .reviewerName(reviewer)
                .reviewCreateTime(Instant.now().minus(3, ChronoUnit.DAYS))
                .sentiment(sentiment)
                .sentimentSource(SentimentSource.RATING)
                .draftedReply(draftedReply)
                .status(GbpReviewReply.Status.DRAFTED)
                .receivedAt(Instant.now().minus(3, ChronoUnit.DAYS))
                .build();
    }
}
