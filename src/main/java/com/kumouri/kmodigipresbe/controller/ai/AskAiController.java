package com.kumouri.kmodigipresbe.controller.ai;

import com.kumouri.kmodigipresbe.model.ai.AskAiRequest;
import com.kumouri.kmodigipresbe.service.ai.rag.AskAiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Phase 11b — RAG-backed question-answering endpoint.
 *
 * <p>{@code POST /ai/ask} accepts a natural-language question and an optional
 * scope. The optional {@code scope} field must be:
 * <ul>
 *   <li>{@code "all"} or absent — global search across all tenant data</li>
 *   <li>{@code "contact:<uuid>"} — restrict to activities/messages for that contact</li>
 *   <li>{@code "deal:<uuid>"} — restrict to quotes for that deal</li>
 * </ul>
 *
 * <p>The response includes an {@code answer} string and a {@code citations} list
 * so the FE can link back to the specific Activity, Quote, or InboxMessage that
 * was used to generate the answer.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AskAiController {

    private final AskAiService askAi;

    @PostMapping("/ask")
    public Mono<AskAiService.AskResult> ask(@Valid @RequestBody AskAiRequest body) {
        UUID scopeContactId = null;
        UUID scopeDealId = null;
        if (body.scope() != null) {
            if (body.scope().startsWith("contact:")) {
                scopeContactId = UUID.fromString(body.scope().substring(8));
            } else if (body.scope().startsWith("deal:")) {
                scopeDealId = UUID.fromString(body.scope().substring(5));
            }
        }
        return askAi.ask(body.question(), scopeContactId, scopeDealId);
    }
}
