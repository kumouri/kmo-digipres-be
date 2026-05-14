package com.kumouri.kmodigipresbe.service.communication;

import java.time.Instant;

/**
 * Result of a transactional send. {@code messageId} is the provider's id; we
 * persist it so engagement webhooks (open/click/bounce) can be threaded back
 * to the originating send.
 */
public record TransactionalSendResult(
        String messageId,
        String recipient,
        Instant submittedAt) {
}
