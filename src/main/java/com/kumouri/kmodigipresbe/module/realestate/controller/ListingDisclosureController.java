package com.kumouri.kmodigipresbe.module.realestate.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.model.DisclosureType;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
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
 * Real Estate Concierge (RE-1) — authenticated agent CRUD for {@link ListingDisclosure}, the grounding
 * corpus. Creating/updating a disclosure triggers the disclosure-text indexing (the RE-1 §3 / §6.3 crux)
 * owned by {@link ListingDisclosureService}.
 *
 * <p>The disclosure text is supplied/edited by the agent (the RE-1 floor — plain-text/manual entry; PDF
 * extraction is a fast-follow). Gating: module-property + per-tenant module membership + {@code STAFF}.
 * {@code 4253} on a missing listing/disclosure; a blank disclosure text is accepted but indexed only when
 * non-blank ({@code 4252} best-effort, never thrown).
 */
@RestController
@RequestMapping("/realestate/listings/{listingId}/disclosures")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class ListingDisclosureController {

    private final ListingDisclosureService disclosures;
    private final TenantModuleRegistry modules;

    @PostMapping
    public Mono<ListingDisclosure> create(@PathVariable UUID listingId,
                                          @RequestBody DisclosureRequest body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Disclosure text is required", 4253, 400));
        }
        ListingDisclosure toCreate = ListingDisclosure.builder()
                .disclosureType(DisclosureType.fromWire(body.disclosureType()))
                .text(body.text().trim())
                .sourceDocAttachmentId(body.sourceDocAttachmentId())
                .build();
        return guard().then(disclosures.create(listingId, toCreate));
    }

    @GetMapping
    public Flux<ListingDisclosure> list(@PathVariable UUID listingId) {
        return guard().thenMany(disclosures.listForListing(listingId));
    }

    @PutMapping("/{id}")
    public Mono<ListingDisclosure> update(@PathVariable UUID listingId,
                                          @PathVariable UUID id,
                                          @RequestBody DisclosureRequest body) {
        ListingDisclosure patch = ListingDisclosure.builder()
                .disclosureType(body != null && body.disclosureType() != null
                        ? DisclosureType.fromWire(body.disclosureType()) : null)
                .text(body != null ? body.text() : null)
                .sourceDocAttachmentId(body != null ? body.sourceDocAttachmentId() : null)
                .build();
        return guard().then(disclosures.update(id, patch));
    }

    /**
     * The agent disclosure request body.
     *
     * @param disclosureType one of the {@link DisclosureType} names (unknown/blank → {@code GENERAL})
     * @param text           the disclosure line/section — required on create ({@code 4253} if blank)
     * @param sourceDocAttachmentId optional reference to the original uploaded document Attachment
     */
    public record DisclosureRequest(String disclosureType, String text, UUID sourceDocAttachmentId) {
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
