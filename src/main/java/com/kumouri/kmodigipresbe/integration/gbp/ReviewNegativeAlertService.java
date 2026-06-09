package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Best-effort manager alert on a <strong>negative</strong> ingested Google review (E3 Review Engine —
 * sentiment triage). Reuses the {@code GbpReviewPoller.notifyRob} shape: email + SMS to the per-tenant
 * Twilio {@link IntegrationConnection}'s {@code config["notifyEmail"]}/{@code ["notifyPhone"]} (the
 * established per-tenant notify-target home; <strong>NOT hardcoded</strong>). Every step is
 * {@code onErrorResume}'d so a missing connection / target / send failure is swallowed — the alert is
 * advisory and <strong>must never fail the review ingest</strong>.
 *
 * <h2>DEFAULT-OFF (regression-gate-friendly)</h2>
 * {@link #maybeAlert} is a no-op unless {@code kmosf.review-engine.negative-alert-enabled=true} (DEFAULT
 * OFF). The poller already notifies Rob on every new review, so the negative alert is an opt-in extra
 * signal; keeping it default-OFF means the surgical {@code GbpReviewPoller} seam adds NO extra SMS/email
 * in the default path (the existing {@code GbpReviewPollerIT} stays byte-identical) — a deployment opts
 * the alert in with {@code KMOSF_REVIEW_ENGINE_NEGATIVE_ALERT_ENABLED=true}.
 *
 * <h2>Negative threshold</h2>
 * {@link #isNegative} = {@code rating <= kmosf.review-engine.negative-rating-threshold} (default 3) OR
 * {@code sentiment == NEGATIVE}. The caller (the surgical {@code GbpReviewPoller} seam) calls
 * {@link #maybeAlert} after the sentiment is stored; a non-negative review (or alert-disabled) is a no-op.
 */
@Slf4j
@Service
public class ReviewNegativeAlertService {

    private final IntegrationConnectionRepository connections;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final int negativeRatingThreshold;
    private final boolean alertEnabled;
    private final String notifyFromAddress;

    public ReviewNegativeAlertService(
            IntegrationConnectionRepository connections,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.review-engine.negative-rating-threshold:3}") int negativeRatingThreshold,
            @Value("${kmosf.review-engine.negative-alert-enabled:false}") boolean alertEnabled,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.connections = connections;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.negativeRatingThreshold = negativeRatingThreshold;
        this.alertEnabled = alertEnabled;
        this.notifyFromAddress = notifyFromAddress;
    }

    /** True if the review is negative by rating threshold or by classified sentiment. */
    public boolean isNegative(GbpReviewReply row) {
        boolean lowRating = row.getRating() != null && row.getRating() <= negativeRatingThreshold;
        boolean negSentiment = row.getSentiment() == ReviewSentiment.NEGATIVE;
        return lowRating || negSentiment;
    }

    /**
     * Best-effort alert if the review is negative; a no-op otherwise. Never throws (all sends are
     * {@code onErrorResume}'d) — the caller chains this after the sentiment store and it must not
     * affect the ingest outcome.
     */
    public Mono<Void> maybeAlert(UUID tenantId, GbpReviewReply row) {
        if (!alertEnabled || !isNegative(row)) {
            return Mono.empty();
        }
        return connections.findByTenantIdAndProvider(tenantId, TwilioSmsService.PROVIDER)
                .flatMap(conn -> dispatch(tenantId, conn, row))
                .onErrorResume(e -> {
                    log.warn("GBP negative-review alert failed (best-effort, ignored): {}", e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> emitAlerted(tenantId, row)));
    }

    private Mono<Void> dispatch(UUID tenantId, IntegrationConnection conn, GbpReviewReply row) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");

        String reviewer = row.getReviewerName() != null ? row.getReviewerName() : "a customer";
        String stars = row.getRating() != null ? row.getRating() + "★" : "no rating";
        String headline = "Negative Google review from " + reviewer + " (" + stars + ")";

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            StringBuilder html = new StringBuilder();
            html.append("<p>").append(HtmlUtils.htmlEscape(headline)).append("</p>");
            if (row.getComment() != null) {
                html.append("<p>Review: ").append(HtmlUtils.htmlEscape(row.getComment())).append("</p>");
            }
            html.append("<p>Please review and respond promptly via the GBP review queue.</p>");
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject(headline)
                    .body(html.toString())
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("GBP negative-alert email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            String smsBody = headline + " — please review and respond promptly.";
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(smsBody)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("GBP negative-alert SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private void emitAlerted(UUID tenantId, GbpReviewReply row) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", row.getReviewId());
        if (row.getId() != null) payload.put("reviewReplyId", row.getId().toString());
        if (row.getRating() != null) payload.put("rating", row.getRating());
        if (row.getSentiment() != null) payload.put("sentiment", row.getSentiment().name());
        events.publish(DomainEvent.of(
                DomainEventType.GBP_REVIEW_NEGATIVE_ALERTED, tenantId,
                row.getId() != null ? row.getId() : UUID.randomUUID(), payload));
    }
}
