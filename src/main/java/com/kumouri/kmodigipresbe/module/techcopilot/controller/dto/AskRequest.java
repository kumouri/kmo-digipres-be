package com.kumouri.kmodigipresbe.module.techcopilot.controller.dto;

/**
 * Tech Copilot (T13) — a technician's question.
 *
 * @param question      the natural-language question (required; {@code 4494} if blank)
 * @param equipmentType optional equipment hint (a UI/log label — NOT a retrieval restriction; T13-D3)
 */
public record AskRequest(String question, String equipmentType) {
}
