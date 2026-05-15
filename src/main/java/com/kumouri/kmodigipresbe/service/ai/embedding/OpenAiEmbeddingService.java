package com.kumouri.kmodigipresbe.service.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Embeddings API client using model {@code text-embedding-3-small}
 * (1536 dimensions, cosine similarity).
 *
 * <p>API key resolution order: per-tenant {@link IntegrationConnection} with
 * {@code provider="openai"} → {@code kmosf.ai.openai.house-key} config property.
 * Error code 2900 when no key is available; 2901 for upstream non-200.
 *
 * <p>Embedding calls are metered via {@link AiUsageRecorder} against the same
 * {@code aiBudgetUsd} cap as text generation — embeddings do have real cost.
 * Pricing estimate: $0.02 per million tokens (text-embedding-3-small).
 */
@Slf4j
@Service
public class OpenAiEmbeddingService implements EmbeddingService {

    public static final String PROVIDER = "openai";
    static final String MODEL = "text-embedding-3-small";

    /** $0.02 / 1M tokens. */
    private static final BigDecimal EMBED_INPUT_PER_MILLION = new BigDecimal("0.02");

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;

    public OpenAiEmbeddingService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.openai.base-url:https://api.openai.com/v1/embeddings}") String baseUrl,
            @Value("${kmosf.ai.openai.house-key:}") String houseKey) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public Mono<float[]> embed(UUID tenantId, String text) {
        return resolveKey(tenantId)
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> callApi(key, text))
                .flatMap(result -> usageRecorder.record(result.tokens(), 0L,
                                estimateUsd(result.tokens()))
                        .thenReturn(result.vector()));
    }

    private Mono<String> resolveKey(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .map(IntegrationConnection::getSecrets)
                .mapNotNull(secrets -> secrets == null ? null : secrets.get("apiKey"))
                .filter(k -> k != null && !k.isBlank())
                .switchIfEmpty(Mono.defer(() -> houseKey.isBlank()
                        ? Mono.error(new DigiPresBeException(
                                "No OpenAI API key for embeddings — configure IntegrationConnection" +
                                " provider=openai or set kmosf.ai.openai.house-key", 2900, 412))
                        : Mono.just(houseKey)));
    }

    private Mono<EmbedResult> callApi(String apiKey, String text) {
        Map<String, Object> body = Map.of("model", MODEL, "input", text);
        return Mono.fromCallable(() -> body)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(b -> http.post()
                        .uri("")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(b)
                        .retrieve()
                        .onStatus(status -> !status.is2xxSuccessful(),
                                response -> response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(err -> Mono.error(new DigiPresBeException(
                                                "OpenAI embeddings call failed: "
                                                        + response.statusCode() + " " + err,
                                                2901, 502))))
                        .bodyToMono(JsonNode.class)
                        .map(OpenAiEmbeddingService::parseResponse));
    }

    private static EmbedResult parseResponse(JsonNode node) {
        JsonNode dataNode = node.path("data").path(0).path("embedding");
        List<Float> vals = new ArrayList<>();
        for (JsonNode v : dataNode) {
            vals.add((float) v.asDouble());
        }
        float[] vector = new float[vals.size()];
        for (int i = 0; i < vals.size(); i++) {
            vector[i] = vals.get(i);
        }
        long tokens = node.path("usage").path("total_tokens").asLong(0L);
        return new EmbedResult(vector, tokens);
    }

    private BigDecimal estimateUsd(long tokens) {
        return EMBED_INPUT_PER_MILLION
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private record EmbedResult(float[] vector, long tokens) {}
}
