package com.kumouri.kmodigipresbe.controller.forms;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.forms.FormDefinition;
import com.kumouri.kmodigipresbe.model.forms.FormSubmission;
import com.kumouri.kmodigipresbe.repository.forms.FormDefinitionRepository;
import com.kumouri.kmodigipresbe.repository.forms.FormSubmissionRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/forms")
@RequiredArgsConstructor
public class FormDefinitionController {

    private final FormDefinitionRepository forms;
    private final FormSubmissionRepository submissions;

    @GetMapping
    public Flux<FormDefinition> list() {
        return forms.findAll();
    }

    @GetMapping("/{id}")
    public Mono<FormDefinition> get(@PathVariable UUID id) {
        return forms.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Form not found", 3110, 404)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FormDefinition> create(@RequestBody FormDefinition body) {
        return RoleGuard.requireRole("ADMIN").then(Mono.defer(() -> {
            body.setId(null);
            return forms.save(body);
        }));
    }

    @PutMapping("/{id}")
    public Mono<FormDefinition> update(@PathVariable UUID id,
                                        @RequestBody FormDefinition patch) {
        return RoleGuard.requireRole("ADMIN")
                .then(forms.findById(id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Form not found", 3110, 404)))
                .flatMap(existing -> {
                    if (patch.getName() != null) existing.setName(patch.getName());
                    if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
                    if (patch.getFields() != null) existing.setFields(patch.getFields());
                    if (patch.getOnSubmit() != null) existing.setOnSubmit(patch.getOnSubmit());
                    return forms.save(existing);
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(forms.deleteById(id));
    }

    @GetMapping("/{id}/submissions")
    public Flux<FormSubmission> listSubmissions(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> submissions
                        .findByTenantIdAndFormIdOrderBySubmittedAtDesc(ctx.tenantId(), id));
    }
}
