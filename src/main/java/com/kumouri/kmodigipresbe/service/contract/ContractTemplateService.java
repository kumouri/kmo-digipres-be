package com.kumouri.kmodigipresbe.service.contract;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.repository.contract.ContractTemplateRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * CRUD for {@link ContractTemplate} (Phase F — F.4).
 *
 * <p>Templates have no state machine; {@code active} is the only lifecycle flag.
 * The {@code CONTRACT_TEMPLATE_CREATED} domain event is advisory — it does NOT drive
 * any mutations (F-D4).
 *
 * <h2>§9 invariant</h2>
 * {@code switchIfEmpty} is used ONLY for genuine not-found (errorCode 3705).
 * No conditional create is gated by {@code switchIfEmpty}.
 */
@Service
@RequiredArgsConstructor
public class ContractTemplateService {

    private final ContractTemplateRepository templates;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Flux<ContractTemplate> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> templates.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<ContractTemplate> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> templates.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "ContractTemplate not found", 3705, 404)));
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates a new template. Validates required fields (3701, 3702), stamps id/tenantId,
     * and publishes {@code CONTRACT_TEMPLATE_CREATED} (advisory).
     */
    public Mono<ContractTemplate> create(ContractTemplate body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getName() == null || body.getName().isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "ContractTemplate name is required", 3701, 400));
            }
            if (body.getBodyTemplate() == null || body.getBodyTemplate().isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "ContractTemplate bodyTemplate is required", 3702, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            if (body.getKind() == null) {
                body.setKind(ContractTemplate.Kind.GENERIC);
            }
            return templates.save(body).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.CONTRACT_TEMPLATE_CREATED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("name", saved.getName())));
                return Mono.just(saved);
            });
        });
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public Mono<ContractTemplate> update(UUID id, ContractTemplate patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) {
                if (patch.getName().isBlank()) {
                    return Mono.error(new DigiPresBeException(
                            "ContractTemplate name is required", 3701, 400));
                }
                existing.setName(patch.getName());
            }
            if (patch.getBodyTemplate() != null) {
                if (patch.getBodyTemplate().isBlank()) {
                    return Mono.error(new DigiPresBeException(
                            "ContractTemplate bodyTemplate is required", 3702, 400));
                }
                existing.setBodyTemplate(patch.getBodyTemplate());
            }
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getKind() != null)        existing.setKind(patch.getKind());
            if (patch.getDefaultTitle() != null) existing.setDefaultTitle(patch.getDefaultTitle());
            existing.setActive(patch.isActive());
            return templates.save(existing);
        });
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(t -> templates.deleteById(t.getId()));
    }
}
