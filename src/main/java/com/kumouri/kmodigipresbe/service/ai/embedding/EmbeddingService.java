package com.kumouri.kmodigipresbe.service.ai.embedding;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Converts a text snippet into a dense vector for storage in the vector index.
 *
 * <p>The {@code tenantId} argument is required — there is no overload that omits
 * it. This invariant is enforced at the architectural level by
 * {@code ArchUnitVectorTenancyTest} so the embedding cost can always be charged
 * to the correct tenant and so vectors are always associated with a tenant at
 * upsert time.
 *
 * <p>Implementations are responsible for calling {@link
 * com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder} to meter embedding
 * spend against the tenant's monthly {@code aiBudgetUsd} cap.
 */
public interface EmbeddingService {

    /**
     * Embeds {@code text} and returns the dense vector.
     *
     * @param tenantId the tenant whose budget is charged — never {@code null}
     * @param text     the snippet to embed; callers should truncate to ≤ 8 000 chars
     * @return a {@code Mono} that emits the embedding vector
     */
    Mono<float[]> embed(UUID tenantId, String text);

    /** Short provider identifier, e.g. {@code "openai"} or {@code "mock"}. */
    String providerName();
}
