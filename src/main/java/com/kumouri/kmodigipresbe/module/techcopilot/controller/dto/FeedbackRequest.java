package com.kumouri.kmodigipresbe.module.techcopilot.controller.dto;

/**
 * Tech Copilot (T13) — a technician's usefulness rating for a persisted {@code TechQuery}.
 *
 * @param helpful {@code true}=the answer was useful, {@code false}=not (required; {@code 4495} if null)
 */
public record FeedbackRequest(Boolean helpful) {
}
