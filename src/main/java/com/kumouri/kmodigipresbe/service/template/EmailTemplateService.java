package com.kumouri.kmodigipresbe.service.template;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.template.EmailTemplate;
import com.kumouri.kmodigipresbe.repository.EmailTemplateRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository templates;

    public Flux<EmailTemplate> findAll() {
        return templates.findAll();
    }

    public Mono<EmailTemplate> findById(UUID id) {
        return templates.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "EmailTemplate not found", 1610, 404)));
    }

    public Mono<EmailTemplate> findByName(String name) {
        return TenantContextHolder.required()
                .flatMap(ctx -> templates.findByTenantIdAndName(ctx.tenantId(), name))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "EmailTemplate not found: " + name, 1610, 404)));
    }

    public Mono<EmailTemplate> create(EmailTemplate toCreate) {
        toCreate.setId(null);
        return templates.save(toCreate);
    }

    public Mono<EmailTemplate> update(UUID id, EmailTemplate patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getFromAddress() != null) existing.setFromAddress(patch.getFromAddress());
            if (patch.getSubject() != null) existing.setSubject(patch.getSubject());
            if (patch.getBody() != null) existing.setBody(patch.getBody());
            return templates.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return templates.deleteById(id);
    }
}
