package com.kumouri.kmodigipresbe.service.sequence;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.repository.SequenceEnrollmentRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SequenceCrudService {

    private final SequenceRepository sequences;
    private final SequenceEnrollmentRepository enrollments;

    public Flux<Sequence> findAll() {
        return sequences.findAll();
    }

    public Mono<Sequence> findById(UUID id) {
        return sequences.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Sequence not found", 2000, 404)));
    }

    public Mono<Sequence> create(Sequence toCreate) {
        toCreate.setId(null);
        return sequences.save(toCreate);
    }

    public Mono<Sequence> update(UUID id, Sequence patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
            if (patch.getSteps() != null) existing.setSteps(patch.getSteps());
            return sequences.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return sequences.deleteById(id);
    }

    public Mono<SequenceEnrollment> enroll(UUID sequenceId, UUID contactId) {
        return findById(sequenceId)
                .flatMap(seq -> {
                    if (seq.getStatus() != Sequence.Status.ACTIVE) {
                        return Mono.error(new DigiPresBeException(
                                "Sequence is not ACTIVE", 2001, 400));
                    }
                    return TenantContextHolder.required().map(ctx -> ctx.tenantId());
                })
                .flatMap(tenantId -> enrollments
                        .findByTenantIdAndSequenceIdAndContactId(tenantId, sequenceId, contactId)
                        .switchIfEmpty(Mono.defer(() -> enrollments.save(SequenceEnrollment.builder()
                                .sequenceId(sequenceId)
                                .contactId(contactId)
                                .enrolledAt(Instant.now())
                                .currentStepIndex(0)
                                .status(SequenceEnrollment.Status.ACTIVE)
                                .completedSteps(new ArrayList<>())
                                .build()))));
    }

    public Mono<Void> unenroll(UUID sequenceId, UUID contactId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> enrollments.findByTenantIdAndSequenceIdAndContactId(
                        ctx.tenantId(), sequenceId, contactId))
                .flatMap(enrollment -> {
                    enrollment.setStatus(SequenceEnrollment.Status.EXITED);
                    return enrollments.save(enrollment);
                })
                .then();
    }
}
