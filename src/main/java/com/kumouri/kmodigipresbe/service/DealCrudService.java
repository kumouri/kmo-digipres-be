package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealCrudService {

    private final DealRepository deals;

    public Flux<Deal> findAll() {
        return deals.findAll();
    }

    public Mono<Deal> findById(UUID id) {
        return deals.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Deal not found", 1400, 404)));
    }

    public Mono<Deal> create(Deal toCreate) {
        toCreate.setId(null);
        if (toCreate.getStage() == null) {
            toCreate.setStage(PipelineStage.NEW);
        }
        toCreate.setStageChangedAt(Instant.now());
        return deals.save(toCreate);
    }

    public Mono<Deal> update(UUID id, Deal patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
            if (patch.getValue() != null) existing.setValue(patch.getValue());
            if (patch.getCurrency() != null) existing.setCurrency(patch.getCurrency());
            if (patch.getExpectedCloseDate() != null) existing.setExpectedCloseDate(patch.getExpectedCloseDate());
            if (patch.getPrimaryContactId() != null) existing.setPrimaryContactId(patch.getPrimaryContactId());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getOwnerId() != null) existing.setOwnerId(patch.getOwnerId());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return deals.save(existing);
        });
    }

    public Mono<Deal> moveStage(UUID id, PipelineStage target, String lostReason) {
        return findById(id).flatMap(existing -> {
            if (target == PipelineStage.LOST && (lostReason == null || lostReason.isBlank())) {
                return Mono.error(new DigiPresBeException(
                        "lostReason is required when moving to LOST", 1401, 400));
            }
            existing.setStage(target);
            existing.setStageChangedAt(Instant.now());
            existing.setLostReason(target == PipelineStage.LOST ? lostReason : null);
            return deals.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return deals.deleteById(id);
    }
}
