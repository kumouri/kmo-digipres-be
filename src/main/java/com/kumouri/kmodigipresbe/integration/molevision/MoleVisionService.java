package com.kumouri.kmodigipresbe.integration.molevision;

import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.ai.vision.VisionClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Classifies a homeowner's pest photo (mole / vole / gopher / none / unsure + confidence) via the
 * Anthropic Messages <strong>vision</strong> API (Phase 2 — NMM "is this a mole?" photo triage,
 * Feature B). A <strong>thin mole-specific caller</strong> over the reusable
 * {@link AiVisionService} transport: it owns the mole {@link #SYSTEM_PROMPT} / {@link #USER_TEXT}
 * and the {@code kmosf.mole-triage.vision-model} model choice, then maps the generic
 * {@link VisionClassification} back into the mole {@link MoleClassification} at the adapter
 * boundary ({@link MoleClassificationCategory#fromWire(String)}).
 *
 * <p>All of the previous transport behavior (per-tenant API key + house-key fallback, the budget
 * gate before / spend record after, the off-loop base64 encode, the {@code image}-block-first
 * request shape, defensive parsing, and the {@code 1200/1201/1202/1203} error codes) now lives in
 * {@link AiVisionService} and is preserved byte-for-byte — this class is a delegate.
 *
 * <p>The strict system prompt asks the model to return ONLY JSON
 * {@code {classification: "mole"|"vole"|"gopher"|"none"|"unsure", confidence: 0.0-1.0,
 * rationale: string}}. Parsing stays <strong>defensive</strong>: a blank/fenced/prose-wrapped or
 * non-200/parse-failure answer degrades to {@link MoleClassification#unsure()} (via
 * {@code VisionClassification.unsure()} whose null label maps to {@code UNSURE}) rather than
 * throwing — AI is triage, not truth (plan §8), and the photo is always stored as an
 * {@code Attachment} so Rob can verify.
 */
@Slf4j
@Service
public class MoleVisionService {

    private static final String SYSTEM_PROMPT =
            "You are a pest-identification assistant for a mole-removal business. You are shown ONE "
            + "photo a homeowner took of their lawn or yard. Decide whether it shows a mole mound, "
            + "a vole sign, a gopher mound, or no pest sign at all. A mole mound is a conical "
            + "volcano-shaped hill of loose soil with no visible entry hole; a gopher mound is "
            + "fan- or crescent-shaped with a visible plugged hole off to one side; voles leave "
            + "narrow surface runways and small holes rather than mounds. Respond with ONLY a "
            + "single minified JSON object and nothing else — no prose, no markdown, no code "
            + "fences. The object MUST have exactly these keys: \"classification\" (one of "
            + "\"mole\", \"vole\", \"gopher\", \"none\", or \"unsure\"), \"confidence\" (a number "
            + "from 0.0 to 1.0 for how confident you are), and \"rationale\" (a short one-sentence "
            + "explanation). Use \"unsure\" with a low confidence when the photo is unclear, "
            + "out of focus, or does not clearly show any of these. Do not guess wildly; this is a "
            + "triage hint a human will confirm.";

    private static final String USER_TEXT =
            "Classify this homeowner photo. Return ONLY the JSON object described in the system "
            + "prompt.";

    private final AiVisionService aiVisionService;
    private final String visionModel;

    public MoleVisionService(
            AiVisionService aiVisionService,
            @Value("${kmosf.mole-triage.vision-model:claude-sonnet-4-6}") String visionModel) {
        this.aiVisionService = aiVisionService;
        this.visionModel = visionModel;
    }

    /** True iff {@code mediaType} is an Anthropic-vision-supported image type. */
    public static boolean isSupportedMediaType(String mediaType) {
        return AiVisionService.isSupportedMediaType(mediaType);
    }

    /**
     * Classifies the image bytes via {@link AiVisionService}, mapping the generic
     * {@link VisionClassification} into a mole {@link MoleClassification}. Blank-image,
     * defensive-parse, and upstream-1202 behaviors are all preserved by {@link AiVisionService}
     * (a {@code null} label maps to {@link MoleClassificationCategory#UNSURE}).
     */
    public Mono<MoleClassification> classify(byte[] imageBytes, String mediaType) {
        return aiVisionService.classify(imageBytes, mediaType, visionModel, SYSTEM_PROMPT, USER_TEXT)
                .map(vc -> new MoleClassification(
                        MoleClassificationCategory.fromWire(vc.label()),
                        vc.confidence(),
                        vc.rationale()));
    }
}
