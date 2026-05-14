package com.kumouri.kmodigipresbe.service.communication;

import java.util.List;
import java.util.Map;

/**
 * Payload for one transactional send. {@code metadata} is round-tripped to the
 * provider so engagement webhooks can correlate back to the originating send —
 * Phase 9c uses this to thread {@code contactId} through to the
 * {@code EmailEngagement} write.
 */
public record TransactionalSendRequest(
        List<String> to,
        String from,
        String subject,
        String htmlBody,
        String textBody,
        String tag,
        Map<String, String> metadata) {
}
