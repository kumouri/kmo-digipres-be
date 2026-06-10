package com.kumouri.kmodigipresbe.module.techcopilot.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.techcopilot.TechCopilotAutoConfiguration;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDoc;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Tech Copilot (T13) — authenticated CRUD for the {@link TechDoc} corpus (the field-tech grounding
 * corpus). Creating/updating a doc triggers the chunk-and-embed ingest (the T13 §3 crux) owned by
 * {@link TechDocService}.
 *
 * <p>The doc text is supplied/edited by an admin/tech (the floor — plain-text / paste-in manual entry; a
 * multipart PDF-extraction upload is a documented fast-follow, the RE-1 posture). Gating mirrors the
 * {@code ListingDisclosureController} precedent: {@code @ConditionalOnProperty(kmosf.modules.techcopilot)}
 * (absent from the OpenAPI spec when the module is off) + per-tenant module membership (1130/1132) +
 * {@code RoleGuard.requireRole("STAFF")} (1800). {@code 4490} on a missing doc; {@code 4491} on a blank
 * title/text.
 *
 * <p>Base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code /api/v1/techcopilot/docs...}.
 */
@RestController
@RequestMapping("/techcopilot/docs")
@ConditionalOnProperty(prefix = "kmosf.modules.techcopilot", name = "enabled")
@RequiredArgsConstructor
public class TechDocController {

    private final TechDocService docs;
    private final TenantModuleRegistry modules;

    @PostMapping
    public Mono<TechDoc> create(@RequestBody TechDocRequest body) {
        TechDoc toCreate = TechDoc.builder()
                .title(body != null ? trimOrNull(body.title()) : null)
                .equipmentType(EquipmentType.fromWire(body != null ? body.equipmentType() : null))
                .source(body != null ? trimOrNull(body.source()) : null)
                .text(body != null ? body.text() : null)
                .build();
        return guard().then(docs.create(toCreate));
    }

    @GetMapping
    public Flux<TechDoc> list() {
        return guard().thenMany(docs.list());
    }

    @GetMapping("/{id}")
    public Mono<TechDoc> get(@PathVariable UUID id) {
        return guard().then(docs.get(id));
    }

    @PutMapping("/{id}")
    public Mono<TechDoc> update(@PathVariable UUID id, @RequestBody TechDocRequest body) {
        TechDoc patch = TechDoc.builder()
                .title(body != null ? trimOrNull(body.title()) : null)
                .equipmentType(body != null && body.equipmentType() != null
                        ? EquipmentType.fromWire(body.equipmentType()) : null)
                .source(body != null ? trimOrNull(body.source()) : null)
                .text(body != null ? body.text() : null)
                .build();
        return guard().then(docs.update(id, patch));
    }

    /**
     * The doc request body.
     *
     * @param title         the doc title — required on create ({@code 4491} if blank)
     * @param equipmentType one of the {@link EquipmentType} names (unknown/blank → {@code GENERAL})
     * @param source        optional provenance note (manufacturer / model / SOP id / URL)
     * @param text          the full document text — required on create ({@code 4491} if blank)
     */
    public record TechDocRequest(String title, String equipmentType, String source, String text) {
    }

    private static String trimOrNull(String s) {
        return s == null ? null : s.trim();
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(TechCopilotAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
