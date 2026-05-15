package com.kumouri.kmodigipresbe.model.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /ai/ask}.
 *
 * <p>The optional {@code scope} field restricts retrieval to a specific entity:
 * <ul>
 *   <li>{@code "all"} or {@code null} — search across all tenant data</li>
 *   <li>{@code "contact:<uuid>"} — restrict to this contact's activities and messages</li>
 *   <li>{@code "deal:<uuid>"} — restrict to quotes associated with this deal</li>
 * </ul>
 */
public record AskAiRequest(
        @NotBlank
        @Size(max = 2000)
        String question,
        String scope) {
}
