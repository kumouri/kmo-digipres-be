package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.compliance.ConsentRecord;
import com.kumouri.kmodigipresbe.repository.ConsentRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprConsentService {

    private final ConsentRecordRepository consentRecordRepository;

    public Flux<ConsentRecord> listConsents(UUID tenantId, UUID contactId) {
        return consentRecordRepository.findAllByTenantIdAndContactIdOrderByRecordedAtDesc(tenantId, contactId);
    }

    public Mono<ConsentRecord> recordConsent(UUID tenantId, UUID contactId, String topic,
                                             String lawfulBasis, String source,
                                             String ipAddress, UUID actorUserId) {
        ConsentRecord record = ConsentRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .topic(topic)
                .lawfulBasis(lawfulBasis)
                .status(ConsentRecord.ConsentStatus.GRANTED)
                .source(source)
                .ipAddress(ipAddress)
                .actorUserId(actorUserId)
                .recordedAt(Instant.now())
                .build();
        return consentRecordRepository.save(record);
    }

    public Mono<Void> withdraw(UUID tenantId, UUID contactId, UUID consentId) {
        return consentRecordRepository.findByTenantIdAndId(tenantId, consentId)
                .switchIfEmpty(Mono.error(new DigiPresBeException("Consent record not found", 3300, 404)))
                .flatMap(record -> {
                    if (record.getStatus() == ConsentRecord.ConsentStatus.WITHDRAWN) {
                        return Mono.error(new DigiPresBeException(
                                "Consent already withdrawn", 3301, 409));
                    }
                    if (!record.getContactId().equals(contactId)) {
                        return Mono.error(new DigiPresBeException(
                                "Consent record not found", 3300, 404));
                    }
                    return consentRecordRepository.save(record.toBuilder()
                            .status(ConsentRecord.ConsentStatus.WITHDRAWN)
                            .withdrawnAt(Instant.now())
                            .build());
                })
                .then();
    }
}
