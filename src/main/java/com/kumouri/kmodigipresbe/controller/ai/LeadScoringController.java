package com.kumouri.kmodigipresbe.controller.ai;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ai.scoring.LeadScoringV2Service;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Phase 11c — lead scoring endpoints.
 *
 * <ul>
 *   <li>{@code GET /contacts/{id}/lead-score} — returns the cached
 *       {@link LeadScore} embedded on the contact. 404 if the contact has not
 *       been scored yet (nightly job hasn't run, or tenant has no closed deals
 *       and even the rules fallback returned {@code INSUFFICIENT_DATA}).</li>
 *   <li>{@code POST /admin/lead-scoring/retrain} — triggers a manual retrain for
 *       the current tenant. Returns 202 with the {@link LeadScoringJob} job
 *       record so the caller can poll for completion. 409 if a job is already
 *       running (error code 3001).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class LeadScoringController {

    private final ContactRepository contactRepository;
    private final LeadScoringV2Service scoringService;

    @GetMapping("/contacts/{id}/lead-score")
    public Mono<LeadScore> getLeadScore(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> contactRepository.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Contact not found", 3000, 404)))
                        .flatMap(contact -> {
                            if (contact.getLeadScore() == null) {
                                return Mono.error(new DigiPresBeException(
                                        "No lead score available — nightly scoring has not run yet " +
                                                "or contact has insufficient activity", 3000, 404));
                            }
                            return Mono.just(contact.getLeadScore());
                        }));
    }

    @PostMapping("/admin/lead-scoring/retrain")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<LeadScoringJob> triggerRetrain() {
        return TenantContextHolder.required()
                .flatMap(ctx -> scoringService.triggerRetrain(ctx.tenantId()));
    }
}
