package com.kumouri.kmodigipresbe.module.techcopilot.controller.dto;

import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQuery;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotService;

import java.util.List;
import java.util.UUID;

/**
 * Tech Copilot (T13) — the grounded, cited answer (or the honest handoff) to a technician's question.
 *
 * @param answer    the grounded answer when {@code !handoff}; the "I don't have that documented" line when
 *                  {@code handoff}
 * @param handoff   true when nothing in the corpus grounded the question (no fabricated procedure ever)
 * @param citations the source doc(s) the answer was grounded in (empty on a handoff), deduped by doc
 * @param queryId   the persisted {@code TechQuery} id (the feedback target)
 */
public record AskResponse(String answer, boolean handoff, List<Citation> citations, UUID queryId) {

    /** One cited source doc — the cited-answer proof surfaced to the tech UI. */
    public record Citation(UUID techDocId, String techDocTitle, String equipmentType,
                           String contentPreview, double score) {
    }

    public static AskResponse from(TechCopilotService.CopilotAnswer ans) {
        List<Citation> cites = ans.citations() == null ? List.of()
                : ans.citations().stream().map(AskResponse::toCitation).toList();
        return new AskResponse(ans.answer(), ans.handoff(), cites, ans.queryId());
    }

    private static Citation toCitation(TechQuery.QueryCitation c) {
        return new Citation(c.getTechDocId(), c.getTechDocTitle(), c.getEquipmentType(),
                c.getContentPreview(), c.getScore());
    }
}
