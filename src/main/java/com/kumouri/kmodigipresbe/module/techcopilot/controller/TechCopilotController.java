package com.kumouri.kmodigipresbe.module.techcopilot.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.techcopilot.TechCopilotAutoConfiguration;
import com.kumouri.kmodigipresbe.module.techcopilot.controller.dto.AskRequest;
import com.kumouri.kmodigipresbe.module.techcopilot.controller.dto.AskResponse;
import com.kumouri.kmodigipresbe.module.techcopilot.controller.dto.FeedbackRequest;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQuery;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Tech Copilot (T13) — the authenticated tech-Q&amp;A surface: ask a question (RAG-grounded, cited, or an
 * honest "I don't have that documented" handoff), list recent Q&amp;A, and rate an answer's usefulness.
 *
 * <p>Gating mirrors {@link TechDocController}: {@code @ConditionalOnProperty(kmosf.modules.techcopilot)}
 * (absent from the spec when off) + per-tenant module membership (1130/1132) + STAFF (1800). No
 * {@code @IdempotentRoute} — {@code ask} produces a fresh Q&amp;A artifact (the RE/listing-prep generate
 * posture); there is no external side-effecting POST here.
 */
@RestController
@RequestMapping("/techcopilot")
@ConditionalOnProperty(prefix = "kmosf.modules.techcopilot", name = "enabled")
@RequiredArgsConstructor
public class TechCopilotController {

    private final TechCopilotService copilot;
    private final TenantModuleRegistry modules;

    @PostMapping("/ask")
    public Mono<AskResponse> ask(@RequestBody AskRequest body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            return Mono.error(new DigiPresBeException("Question is required", 4494, 400));
        }
        EquipmentType hint = body.equipmentType() != null
                ? EquipmentType.fromWire(body.equipmentType()) : null;
        return guard()
                .then(copilot.ask(body.question(), hint))
                .map(AskResponse::from);
    }

    @GetMapping("/queries")
    public Flux<TechQuery> recentQueries() {
        return guard().thenMany(copilot.recentQueries());
    }

    @PostMapping("/queries/{id}/feedback")
    public Mono<TechQuery> feedback(@PathVariable UUID id, @RequestBody FeedbackRequest body) {
        if (body == null || body.helpful() == null) {
            return Mono.error(new DigiPresBeException(
                    "feedback 'helpful' (true/false) is required", 4495, 400));
        }
        return guard().then(copilot.recordFeedback(id, body.helpful()));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(TechCopilotAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
