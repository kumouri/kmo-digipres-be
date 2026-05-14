package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FieldDefinitionService {

    private final FieldDefinitionRepository repo;

    public Flux<FieldDefinition> listAll() {
        return repo.findAll();
    }

    public Flux<FieldDefinition> listByEntity(String entityType) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> repo.findAllByTenantIdAndEntityType(ctx.tenantId(), entityType));
    }

    public Mono<FieldDefinition> findById(UUID id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Field definition not found", 1110, 404)));
    }

    public Mono<FieldDefinition> create(FieldDefinition def) {
        return validateDefinition(def)
                .then(Mono.defer(() -> {
                    def.setId(null);
                    return repo.save(def);
                }));
    }

    public Mono<FieldDefinition> update(UUID id, FieldDefinition patch) {
        return findById(id)
                .flatMap(existing -> {
                    if (patch.getLabel() != null) existing.setLabel(patch.getLabel());
                    if (patch.getType() != null) existing.setType(patch.getType());
                    if (patch.getOptions() != null) existing.setOptions(patch.getOptions());
                    if (patch.getLookupTarget() != null) existing.setLookupTarget(patch.getLookupTarget());
                    existing.setRequired(patch.isRequired());
                    return validateDefinition(existing).thenReturn(existing);
                })
                .flatMap(repo::save);
    }

    public Mono<Void> delete(UUID id) {
        return repo.deleteById(id);
    }

    private Mono<Void> validateDefinition(FieldDefinition def) {
        if (def.getEntityType() == null || def.getEntityType().isBlank()) {
            return Mono.error(new DigiPresBeException("entityType is required", 1111, 400));
        }
        if (def.getKey() == null || def.getKey().isBlank()) {
            return Mono.error(new DigiPresBeException("key is required", 1112, 400));
        }
        if (def.getType() == null) {
            return Mono.error(new DigiPresBeException("type is required", 1113, 400));
        }
        if (def.getType() == FieldType.ENUM && (def.getOptions() == null || def.getOptions().isEmpty())) {
            return Mono.error(new DigiPresBeException(
                    "ENUM custom field requires at least one option", 1114, 400));
        }
        if (def.getType() == FieldType.LOOKUP && (def.getLookupTarget() == null || def.getLookupTarget().isBlank())) {
            return Mono.error(new DigiPresBeException(
                    "LOOKUP custom field requires lookupTarget", 1115, 400));
        }
        return Mono.empty();
    }
}
