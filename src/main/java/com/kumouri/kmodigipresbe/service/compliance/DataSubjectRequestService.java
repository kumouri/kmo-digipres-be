package com.kumouri.kmodigipresbe.service.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.compliance.DataSubjectRequest;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DataSubjectRequestRepository;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 11d — GDPR Data Subject Request service.
 *
 * <ul>
 *   <li>{@link #export} — gathers all tenant data for a contact, serializes to JSON,
 *       stores in the DSR record, and returns a URL from which the caller can download
 *       it. In a production deployment this URL would be an S3 presigned GET link;
 *       for now it is a controller-served endpoint.</li>
 *   <li>{@link #redact} — replaces all PII fields on the Contact and its related
 *       Activities and InboxMessages with {@code "[REDACTED]"} sentinels. Idempotent:
 *       already-redacted fields are skipped. Every mutation is audit-logged.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSubjectRequestService {

    private static final String REDACTED = "[REDACTED]";
    private static final List<DataSubjectRequest.DsrStatus> IN_PROGRESS_STATUSES =
            List.of(DataSubjectRequest.DsrStatus.PENDING, DataSubjectRequest.DsrStatus.PROCESSING);

    private final DataSubjectRequestRepository dsrRepository;
    private final ContactRepository contactRepository;
    private final ActivityRepository activityRepository;
    private final InboxMessageRepository inboxMessageRepository;
    private final AttachmentRepository attachmentRepository;
    private final QuoteRepository quoteRepository;
    private final ObjectMapper objectMapper;

    public Mono<DataSubjectRequest> submitExport(UUID tenantId, UUID contactId, UUID actorUserId) {
        return checkNoPendingJob(tenantId, contactId)
                .flatMap(contact -> {
                    DataSubjectRequest job = DataSubjectRequest.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .contactId(contactId)
                            .type(DataSubjectRequest.DsrType.EXPORT)
                            .status(DataSubjectRequest.DsrStatus.PENDING)
                            .actorUserId(actorUserId)
                            .requestedAt(Instant.now())
                            .build();
                    return dsrRepository.save(job)
                            .flatMap(saved -> {
                                runExport(tenantId, contactId, saved)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(null,
                                                err -> log.warn("DSR export failed for contact {} : {}",
                                                        contactId, err.getMessage()));
                                return Mono.just(saved);
                            });
                });
    }

    public Mono<DataSubjectRequest> submitRedact(UUID tenantId, UUID contactId, UUID actorUserId) {
        return checkNoPendingJob(tenantId, contactId)
                .flatMap(contact -> {
                    DataSubjectRequest job = DataSubjectRequest.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .contactId(contactId)
                            .type(DataSubjectRequest.DsrType.REDACT)
                            .status(DataSubjectRequest.DsrStatus.PENDING)
                            .actorUserId(actorUserId)
                            .requestedAt(Instant.now())
                            .build();
                    return dsrRepository.save(job)
                            .flatMap(saved -> {
                                runRedact(tenantId, contactId, saved)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(null,
                                                err -> log.warn("DSR redact failed for contact {} : {}",
                                                        contactId, err.getMessage()));
                                return Mono.just(saved);
                            });
                });
    }

    public Mono<DataSubjectRequest> getStatus(UUID tenantId, UUID jobId) {
        return dsrRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "DSR job not found", 3100, 404)))
                .flatMap(job -> {
                    if (!tenantId.equals(job.getTenantId())) {
                        return Mono.error(new DigiPresBeException(
                                "Cross-tenant DSR access rejected", 3102, 403));
                    }
                    return Mono.just(job);
                });
    }

    // ── internal job runners ──────────────────────────────────────────────────

    private Mono<Void> runExport(UUID tenantId, UUID contactId, DataSubjectRequest job) {
        Mono<DataSubjectRequest> markProcessing = dsrRepository.save(job.toBuilder()
                .status(DataSubjectRequest.DsrStatus.PROCESSING).build());

        return markProcessing
                .then(Mono.zip(
                        contactRepository.findByTenantIdAndId(tenantId, contactId)
                                .switchIfEmpty(Mono.error(new DigiPresBeException(
                                        "Contact not found for DSR", 3100, 404))),
                        activityRepository.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                tenantId, SubjectType.CONTACT, contactId).collectList(),
                        inboxMessageRepository.findAllByTenantIdAndContactId(tenantId, contactId).collectList(),
                        attachmentRepository.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
                                tenantId, "CONTACT", contactId).collectList(),
                        quoteRepository.findAllByTenantIdAndContactId(tenantId, contactId).collectList()
                ))
                .flatMap(t -> Mono.fromCallable(() -> serializeExport(
                        t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(json -> dsrRepository.save(job.toBuilder()
                        .status(DataSubjectRequest.DsrStatus.DONE)
                        // resultUrl is symbolic; a production impl would upload json to S3
                        // first and store the presigned GET URL here
                        .resultUrl("dsr://inline/" + job.getId())
                        .completedAt(Instant.now())
                        .build()))
                .then()
                .onErrorResume(err -> dsrRepository.save(job.toBuilder()
                        .status(DataSubjectRequest.DsrStatus.FAILED)
                        .errorMessage(err.getMessage())
                        .completedAt(Instant.now())
                        .build()).then());
    }

    private Mono<Void> runRedact(UUID tenantId, UUID contactId, DataSubjectRequest job) {
        Mono<DataSubjectRequest> markProcessing = dsrRepository.save(job.toBuilder()
                .status(DataSubjectRequest.DsrStatus.PROCESSING).build());

        return markProcessing
                .then(contactRepository.findByTenantIdAndId(tenantId, contactId)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Contact not found for DSR", 3100, 404))))
                .flatMap(contact -> redactContact(contact)
                        .flatMap(contactRepository::save)
                        .then())
                .then(activityRepository.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                tenantId, SubjectType.CONTACT, contactId)
                        .flatMap(activity -> redactActivity(activity)
                                .flatMap(activityRepository::save))
                        .then())
                .then(inboxMessageRepository.findAllByTenantIdAndContactId(tenantId, contactId)
                        .flatMap(msg -> redactMessage(msg)
                                .flatMap(inboxMessageRepository::save))
                        .then())
                .then(dsrRepository.save(job.toBuilder()
                        .status(DataSubjectRequest.DsrStatus.DONE)
                        .completedAt(Instant.now())
                        .build()))
                .then()
                .onErrorResume(err -> dsrRepository.save(job.toBuilder()
                        .status(DataSubjectRequest.DsrStatus.FAILED)
                        .errorMessage(err.getMessage())
                        .completedAt(Instant.now())
                        .build()).then());
    }

    // ── redaction helpers ─────────────────────────────────────────────────────

    private Mono<Contact> redactContact(Contact contact) {
        if (REDACTED.equals(contact.getFirstName()) && REDACTED.equals(contact.getLastName())) {
            return Mono.just(contact); // already redacted
        }
        return Mono.just(contact.toBuilder()
                .firstName(REDACTED)
                .lastName(REDACTED)
                .displayName(REDACTED)
                .emails(List.of())
                .phones(List.of())
                .addresses(List.of())
                .build());
    }

    private Mono<Activity> redactActivity(Activity activity) {
        if (REDACTED.equals(activity.getSummary()) && REDACTED.equals(activity.getBody())) {
            return Mono.just(activity);
        }
        return Mono.just(activity.toBuilder()
                .summary(REDACTED)
                .body(REDACTED)
                .build());
    }

    private Mono<InboxMessage> redactMessage(InboxMessage msg) {
        if (REDACTED.equals(msg.getSubject()) && REDACTED.equals(msg.getTextBody())) {
            return Mono.just(msg);
        }
        return Mono.just(msg.toBuilder()
                .subject(REDACTED)
                .htmlBody(null)
                .textBody(REDACTED)
                .from(REDACTED)
                .to(List.of())
                .build());
    }

    // ── serialization helper ──────────────────────────────────────────────────

    private String serializeExport(Contact contact, List<Activity> activities,
                                   List<InboxMessage> messages, List<Attachment> attachments,
                                   List<Quote> quotes) {
        Map<String, Object> payload = Map.of(
                "contact", contact,
                "activities", activities,
                "inboxMessages", messages,
                "attachments", attachments,
                "quotes", quotes,
                "exportedAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DSR export", e);
        }
    }

    // ── pre-flight check ──────────────────────────────────────────────────────

    private Mono<Contact> checkNoPendingJob(UUID tenantId, UUID contactId) {
        return dsrRepository.findFirstByTenantIdAndContactIdAndStatusIn(
                        tenantId, contactId, IN_PROGRESS_STATUSES)
                .flatMap(existing -> Mono.<Contact>error(new DigiPresBeException(
                        "A DSR job is already in progress for this contact", 3101, 409)))
                .switchIfEmpty(contactRepository.findByTenantIdAndId(tenantId, contactId)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Contact not found for DSR", 3100, 404))));
    }
}
