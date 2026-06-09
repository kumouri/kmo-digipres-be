package com.kumouri.kmodigipresbe.integration.documenso;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure/stateless adapter that maps a raw Documenso webhook payload ({@link JsonNode})
 * to an internal {@link DocumensoEvent} (Phase F — F-D7).
 *
 * <h2>Real Documenso payload shape (corrected against the live product)</h2>
 * This class is the <strong>single place where every payload-shape assumption
 * lives</strong>. Correcting against a real Documenso deployment requires changing
 * only this class (and the WireMock stub in the IT — the single coded-to contract).
 *
 * <p>Real Documenso wire format:
 * <pre>
 * {
 *   "id" | "eventId"   : "&lt;event id&gt;",
 *   "event" | "type"   : "DOCUMENT_COMPLETED" | &lt;other uppercase enum&gt;,
 *   "payload" : {
 *     "id"          : &lt;documenso document id — INTEGER&gt;,
 *     "downloadUrl" : "&lt;signed PDF URL&gt;"   // optional, rarely present
 *   }
 * }
 * </pre>
 * Field tolerance (real first, legacy placeholder forms still accepted):
 * <ul>
 *   <li>Event id: {@code id} preferred; falls back to {@code eventId}.</li>
 *   <li>Event type: {@code event} preferred; falls back to {@code type}. The real
 *       completed value is the uppercase enum {@code DOCUMENT_COMPLETED}; the old
 *       lowercase dotted {@code document.completed}/{@code document.signed} are still
 *       accepted. All map to {@link Type#DOCUMENT_SIGNED}; everything else →
 *       {@link Type#OTHER} (ledgered + 200 no-op).</li>
 *   <li>Document id: the real location is {@code payload.id} (an <strong>integer</strong>);
 *       the old {@code payload.documentId} is still accepted as a fallback. The id is
 *       carried as its canonical string form ({@code JsonNode.asText} on the integer
 *       node) — the same string stored in {@code Contract.documensoDocumentId} at
 *       send-time, so create/webhook/DB all agree on the integer's string form.</li>
 *   <li>{@code downloadUrl} is optional — if absent,
 *       {@link DocumensoClient#downloadSignedPdf} is used as the fallback.</li>
 * </ul>
 *
 * <p>No other class in this codebase may parse or assume anything about the
 * Documenso webhook payload shape. The {@link DocumensoSignatureVerifier} owns the
 * webhook-secret verification scheme; this class owns the payload shape; the
 * controller's {@code @RequestHeader} annotation owns the header name.
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

        // Payload sub-object. Real Documenso puts the document id at payload.id
        // (an INTEGER); the old placeholder used payload.documentId. Read id first,
        // fall back to documentId. JsonNode.asText renders the integer node as its
        // canonical string form (e.g. 42 -> "42") — the same string stored in
        // Contract.documensoDocumentId at send-time, so the webhook correlation key
        // matches.
        JsonNode payload = root.path("payload");
        String documensoDocumentId = nonBlankText(payload.path("id"));
        if (documensoDocumentId == null) {
            documensoDocumentId = nonBlankText(payload.path("documentId"));
        }
        String downloadUrl = payload.path("downloadUrl").asText(null);
        if (downloadUrl != null && downloadUrl.isBlank()) {
            downloadUrl = null;
        }

        return new DocumensoEvent(eventId, type, documensoDocumentId, downloadUrl);
    }

    /**
     * Returns the node's text value (numeric nodes render as their canonical string
     * form), or {@code null} if the node is missing/null/blank.
     */
    private static String nonBlankText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String t = node.asText(null);
        return (t == null || t.isBlank()) ? null : t;
    }

    private static Type mapType(String raw) {
        if (raw == null) return Type.OTHER;
        return switch (raw.trim().toUpperCase()) {
            // Real Documenso completed-event enum:
            case "DOCUMENT_COMPLETED",
                 // Legacy placeholder dotted forms (still accepted):
                 "DOCUMENT.COMPLETED", "DOCUMENT.SIGNED" -> Type.DOCUMENT_SIGNED;
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
