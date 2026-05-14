package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealCrudService {

    private final DealRepository deals;
    private final DomainEventPublisher events;

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
        return deals.save(toCreate).doOnSuccess(saved ->
                events.publish(DomainEvent.of(
                        DomainEventType.DEAL_CREATED,
                        saved.getTenantId(),
                        saved.getId(),
                        flatten(saved))));
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
        }).doOnSuccess(saved ->
                events.publish(DomainEvent.of(
                        DomainEventType.DEAL_UPDATED,
                        saved.getTenantId(),
                        saved.getId(),
                        flatten(saved))));
    }

    public Mono<Deal> moveStage(UUID id, PipelineStage target, String lostReason) {
        return findById(id).flatMap(existing -> {
            if (target == PipelineStage.LOST && (lostReason == null || lostReason.isBlank())) {
                return Mono.error(new DigiPresBeException(
                        "lostReason is required when moving to LOST", 1401, 400));
            }
            PipelineStage from = existing.getStage();
            existing.setStage(target);
            existing.setStageChangedAt(Instant.now());
            existing.setLostReason(target == PipelineStage.LOST ? lostReason : null);
            return deals.save(existing).doOnSuccess(saved -> {
                Map<String, Object> p = flatten(saved);
                p.put("fromStage", from == null ? null : from.name());
                p.put("toStage", target.name());
                events.publish(DomainEvent.of(
                        DomainEventType.DEAL_STAGE_CHANGED,
                        saved.getTenantId(),
                        saved.getId(),
                        p));
            });
        });
    }

    public Mono<Void> delete(UUID id) {
        return deals.deleteById(id);
    }

    private static Map<String, Object> flatten(Deal d) {
        Map<String, Object> m = new HashMap<>();
        m.put("dealId", d.getId() == null ? null : d.getId().toString());
        m.put("title", d.getTitle());
        m.put("stage", d.getStage() == null ? null : d.getStage().name());
        m.put("value", d.getValue());
        m.put("currency", d.getCurrency());
        m.put("primaryContactId", d.getPrimaryContactId() == null ? null : d.getPrimaryContactId().toString());
        m.put("companyId", d.getCompanyId() == null ? null : d.getCompanyId().toString());
        m.put("ownerId", d.getOwnerId() == null ? null : d.getOwnerId().toString());
        return m;
    }
}
