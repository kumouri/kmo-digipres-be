package com.kumouri.kmodigipresbe.controller.automation;

import com.kumouri.kmodigipresbe.automation.WorkflowRule;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
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
@RequestMapping("/automation/rules")
@RequiredArgsConstructor
public class WorkflowRuleController {

    private final WorkflowRuleRepository rules;

    @GetMapping
    public Flux<WorkflowRule> list() {
        return rules.findAll();
    }

    @GetMapping("/{id}")
    public Mono<WorkflowRule> get(@PathVariable UUID id) {
        return rules.findById(id).switchIfEmpty(Mono.error(() ->
                new DigiPresBeException("WorkflowRule not found", 1900, 404)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WorkflowRule> create(@RequestBody WorkflowRule body) {
        body.setId(null);
        return rules.save(body);
    }

    @PutMapping("/{id}")
    public Mono<WorkflowRule> update(@PathVariable UUID id, @RequestBody WorkflowRule body) {
        return rules.findById(id).flatMap(existing -> {
            if (body.getName() != null) existing.setName(body.getName());
            if (body.getDescription() != null) existing.setDescription(body.getDescription());
            if (body.getTrigger() != null) existing.setTrigger(body.getTrigger());
            if (body.getConditions() != null) existing.setConditions(body.getConditions());
            if (body.getActions() != null) existing.setActions(body.getActions());
            existing.setActive(body.isActive());
            return rules.save(existing);
        }).switchIfEmpty(Mono.error(() ->
                new DigiPresBeException("WorkflowRule not found", 1900, 404)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return rules.deleteById(id);
    }
}
