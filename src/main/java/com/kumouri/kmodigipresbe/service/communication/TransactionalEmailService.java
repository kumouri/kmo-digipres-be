package com.kumouri.kmodigipresbe.service.communication;

import reactor.core.publisher.Mono;

/**
 * Transactional / templated outbound email. Distinct from the existing
 * {@code EmailService} (Jakarta Mail → ProtonMail SMTP, used for the human-from-
 * Ceryce path) because ProtonMail does not surface per-message engagement
 * webhooks (open / click / bounce) and rate-limits its SMTP relay.
 *
 * <p>Implementations: {@link PostmarkTransactionalEmailService} (the Phase 9c
 * default). Adding SendGrid or another provider is a one-class change —
 * {@code TransactionalEmailService} is the single seam, and downstream features
 * (sequences in 9d, reporting v2 in 9e, mention notifications in 9g) only know
 * about this interface.
 *
 * <p>Per-tenant API keys live on
 * {@code IntegrationConnection(provider="postmark").secrets.apiToken}; tenants
 * that have not configured their own fall back to the KMOSF house token from
 * {@code kmosf.email.postmark.house-token}.
 */
public interface TransactionalEmailService {

    Mono<TransactionalSendResult> send(TransactionalSendRequest request);
}
