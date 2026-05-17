package com.kumouri.kmodigipresbe.integration.documenso;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure/stateless adapter that maps a raw Documenso webhook payload ({@link JsonNode})
 * to an internal {@link DocumensoEvent} (Phase F — F-D7).
 *
 * <h2>ASSUMPTION (F-D7 — documented known unknown)</h2>
 * The exact Documenso webhook payload format is a <strong>known unknown</strong>
 * deliberately not specified in the ultraplan. This class is the
 * <strong>single place where every payload-shape assumption lives</strong>.
 * Correcting against a real Documenso deployment requires changing only this class
 * (and the WireMock stub in the IT — the single coded-to contract).
 *
 * <p>Assumed wire format:
 * <pre>
 * {
 *   "id"      | "eventId"  : "&lt;event id string&gt;",
 *   "event"   | "type"     : "document.completed" | "document.signed" | &lt;other&gt;,
 *   "payload" : {
 *     "documentId"  : "&lt;documenso document id&gt;",
 *     "downloadUrl" : "&lt;signed PDF URL&gt;"   // optional
 *   }
 * }
 * </pre>
 * Field-name tolerance:
 * <ul>
 *   <li>Event id: {@code id} preferred; falls back to {@code eventId}</li>
 *   <li>Event type: {@code event} preferred; falls back to {@code type}</li>
 *   <li>Signed type values: both {@code document.completed} and
 *       {@code document.signed} map to {@link Type#DOCUMENT_SIGNED}; all other
 *       values map to {@link Type#OTHER} (ledgered + 200 no-op)</li>
 *   <li>{@code downloadUrl} is optional — if absent,
 *       {@link DocumensoClient#downloadSignedPdf} is used as the fallback</li>
 * </ul>
 *
 * <p>No other class in this codebase may parse or assume anything about the
 * Documenso webhook payload shape. The {@link DocumensoSignatureVerifier} owns the
 * digest scheme; this class owns the payload shape; the controller's
 * {@code @RequestHeader} annotation owns the header name.
 */
public final class DocumensoEventAdapter {

    private DocumensoEventAdapter() {
    }

    /**
     * Parses the raw Documenso webhook payload root node into a {@link DocumensoEvent}.
     *
     * @param root the parsed JSON root of the webhook body
     * @return a {@link DocumensoEvent}; never {@code null}
     */
    public static DocumensoEvent parse(JsonNode root) {
        // Event id — tolerate "id" and "eventId"
        String eventId = root.path("id").asText(null);
        if (eventId == null || eventId.isBlank()) {
            eventId = root.path("eventId").asText(null);
        }

        // Event type — tolerate "event" and "type"
        String rawType = root.path("event").asText(null);
        if (rawType == null || rawType.isBlank()) {
            rawType = root.path("type").asText(null);
        }
        Type type = mapType(rawType);

        // Payload sub-object
        JsonNode payload = root.path("payload");
        String documensoDocumentId = payload.path("documentId").asText(null);
        String downloadUrl = payload.path("downloadUrl").asText(null);
        if (downloadUrl != null && downloadUrl.isBlank()) {
            downloadUrl = null;
        }

        return new DocumensoEvent(eventId, type, documensoDocumentId, downloadUrl);
    }

    private static Type mapType(String raw) {
        if (raw == null) return Type.OTHER;
        return switch (raw.trim().toLowerCase()) {
            case "document.completed", "document.signed" -> Type.DOCUMENT_SIGNED;
            default -> Type.OTHER;
        };
    }

    /**
     * The mapped event type — a closed set so the webhook service can switch
     * exhaustively without knowing Documenso's full event vocabulary.
     */
    public enum Type {
        /**
         * A document was completed / signed — drives the signed-PDF store and
         * the SOW→WON+Project promotion (F-D9).
         */
        DOCUMENT_SIGNED,

        /**
         * Any other Documenso event. Ledgered and acknowledged 200 no-op;
         * no CRM side-effect. Mirrors the Stripe "ignored event still ledgered"
         * precedent.
         */
        OTHER
    }

    /**
     * Parsed representation of a single Documenso webhook delivery.
     *
     * @param eventId                  the Documenso event id (the dedup key);
     *                                 may be {@code null} or blank — checked by
     *                                 {@code DocumensoWebhookService} (3715)
     * @param type                     the mapped event type
     * @param documensoDocumentId      the Documenso document id from the payload;
     *                                 may be {@code null} for OTHER events
     * @param signedDocumentDownloadUrl optional signed-PDF URL from the payload;
     *                                 {@code null} triggers the
     *                                 {@link DocumensoClient#downloadSignedPdf} fallback
     */
    public record DocumensoEvent(
            String eventId,
            Type type,
            String documensoDocumentId,
            String signedDocumentDownloadUrl) {
    }
}
