package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.model.request.TeamMemberRequest;
import com.kumouri.kmodigipresbe.model.response.TeamMemberView;
import com.kumouri.kmodigipresbe.service.contractor.TeamService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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

/**
 * Staff / contractor directory API (Phase J). Every endpoint is ADMIN-gated via
 * {@link RoleGuard#requireRole(String)} (only the owner manages the team).
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default, the Phase-C
 * {@code ProjectController} precedent).
 */
@RestController
@RequestMapping("/team")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TeamController {

    private final TeamService service;

    @GetMapping
    public Flux<TeamMemberView> list() {
        return RoleGuard.requireRole("ADMIN").thenMany(service.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TeamMemberView> create(@RequestBody TeamMemberRequest body) {
        return RoleGuard.requireRole("ADMIN").then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<TeamMemberView> update(@PathVariable UUID id, @RequestBody TeamMemberRequest body) {
        return RoleGuard.requireRole("ADMIN").then(service.update(id, body));
    }

    @PostMapping("/{id}/disable")
    public Mono<TeamMemberView> disable(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.disable(id));
    }
}
