package com.kumouri.kmodigipresbe.integration.postmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagementEvent;
import com.kumouri.kmodigipresbe.repository.EmailEngagementRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles inbound Postmark engagement webhooks. Mirrors the Phase 8
 * {@code StripeWebhookService} flow:
 * <ol>
 *   <li>Resolve the tenant's {@link IntegrationConnection} for provider {@code postmark}</li>
 *   <li>Verify the {@code Authorization: Basic} header against the tenant's stored
 *       {@code webhookBasicAuthPassword}</li>
 *   <li>Parse the JSON body</li>
 *   <li>Establish a synthetic tenant context via {@link TenantContextHolder#write}
 *       and persist an {@link EmailEngagement}</li>
 *   <li>Publish a matching {@link DomainEvent} — Phase 9d sequences subscribe to
 *       these to branch on opened/clicked</li>
 * </ol>
 *
 * <p>Postmark sends one record per webhook call. {@code RecordType} drives the
 * mapping to {@link EmailEngagementEvent}.
 *
 * <p>{@code contactId} resolution: prefer {@code Metadata.kmosf_contact_id} on the
 * payload (round-tripped by the send), fall back to looking up by recipient
 * email — for Phase 9c we only persist the metadata-resolved id; recipient-based
 * lookup is wired in Phase 9d when sequences need it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostmarkWebhookService {

    public static final String PROVIDER = "postmark";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final EmailEngagementRepository engagements;
    private final DomainEventPublisher events;

    public Mono<Void> handle(UUID tenantId, String authHeader, String rawBody) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Postmark is not connected for this tenant", 1702, 404)))
                .flatMap(conn -> {
                    String password = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("webhookBasicAuthPassword");
                    if (password == null || password.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's Postmark webhookBasicAuthPassword is not configured",
                                1703, 412));
                    }
                    if (!PostmarkAuthVerifier.verify(authHeader, password)) {
                        return Mono.error(new DigiPresBeException(
                                "Postmark webhook auth invalid", 1704, 401));
                    }
                    return process(tenantId, rawBody);
                });
    }

    private Mono<Void> process(UUID tenantId, String rawBody) {
        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Postmark webhook body is not JSON: " + ex.getMessage(), 1705, 400));
        }
        String recordType = body.path("RecordType").asText("");
        EmailEngagementEvent event = mapRecordType(recordType);
        if (event == null) {
            log.debug("Postmark RecordType {} ignored", recordType);
            return Mono.empty();
        }

        String messageId = body.path("MessageID").asText(null);
        String recipient = body.path("Recipient").asText(body.path("Email").asText(null));
        Instant eventAt = parseInstant(body.path("ReceivedAt").asText(
                body.path("BouncedAt").asText(
                        body.path("DeliveredAt").asText(null))));

        UUID contactId = extractContactId(body);

        Map<String, Object> payloadMap = jsonToMap(body);

        EmailEngagement entity = EmailEngagement.builder()
                .contactId(contactId)
                .messageId(messageId)
                .event(event)
                .eventAt(eventAt != null ? eventAt : Instant.now())
                .recipient(recipient)
                .payload(payloadMap)
                .build();

        TenantContext synthetic = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_POSTMARK"));
        Map<String, Object> domainPayload = new HashMap<>();
        domainPayload.put("messageId", messageId);
        domainPayload.put("recipient", recipient);
        if (contactId != null) domainPayload.put("contactId", contactId.toString());
        domainPayload.put("event", event.name());

        return engagements.save(entity)
                .doOnNext(saved -> events.publish(DomainEvent.of(
                        domainEventTypeOf(event), tenantId, saved.getId(), domainPayload)))
                .then()
                .contextWrite(TenantContextHolder.write(synthetic));
    }

    private static EmailEngagementEvent mapRecordType(String recordType) {
        return switch (recordType) {
            case "Open" -> EmailEngagementEvent.OPEN;
            case "Click" -> EmailEngagementEvent.CLICK;
            case "Bounce" -> EmailEngagementEvent.BOUNCE;
            case "SpamComplaint" -> EmailEngagementEvent.SPAM;
            case "Delivery" -> EmailEngagementEvent.DELIVERED;
            default -> null;
        };
    }

    private static String domainEventTypeOf(EmailEngagementEvent event) {
        return switch (event) {
            case OPEN -> DomainEventType.EMAIL_OPENED;
            case CLICK -> DomainEventType.EMAIL_CLICKED;
            case BOUNCE -> DomainEventType.EMAIL_BOUNCED;
            case SPAM -> DomainEventType.EMAIL_SPAM;
            case DELIVERED -> DomainEventType.EMAIL_DELIVERED;
        };
    }

    private static UUID extractContactId(JsonNode body) {
        JsonNode metadata = body.path("Metadata");
        if (metadata.isMissingNode() || !metadata.isObject()) return null;
        String value = metadata.path("kmosf_contact_id").asText(null);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Postmark metadata.kmosf_contact_id '{}' is not a UUID", value);
            return null;
        }
    }

    private Map<String, Object> jsonToMap(JsonNode body) {
        try {
            return objectMapper.convertValue(body, Map.class);
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
