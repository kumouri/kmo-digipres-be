package com.kumouri.kmodigipresbe.integration.imap;

import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.service.inbox.InboundEmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * IMAP inbound poll-source for the shared inbox (Phase H — H.3).
 *
 * <p>Each poll cycle opens a {@code jakarta.mail} IMAP {@link Store} from
 * {@link ImapProperties}, fetches UNSEEN messages from the configured folder,
 * and hands each one to the <strong>unchanged</strong>
 * {@link InboundEmailService#ingest} entrypoint for known-Contact →
 * {@code InboxThread} claim/create. The Store and Folder are opened and
 * closed per cycle in a {@code try/finally} block on
 * {@link Schedulers#boundedElastic()} so blocking IMAP I/O never touches
 * the Netty event loop (§9 rule).
 *
 * <h2>Default-OFF (§7 hard boundary)</h2>
 * This bean is only instantiated when
 * {@code kmosf.imap.inbound.enabled=true}. The {@code matchIfMissing=false}
 * default means <strong>no IMAP connect ever occurs in CI, tests, or the
 * default application context</strong>. The {@code ImapProperties.host}
 * defaults to a non-routable {@code .invalid} TLD so any accidental
 * non-gated connect fails fast. No host is hardcoded.
 *
 * <h2>Message-ID idempotency</h2>
 * Before calling {@code ingest}, the poller checks whether an
 * {@code InboxMessage} with the same RFC-822 {@code Message-ID} already
 * exists for this tenant via an <strong>explicit-boolean</strong> branch:
 * {@code .map(x -> true).defaultIfEmpty(false).flatMap(seen -> seen ? skip : ingest)}
 * — <strong>NEVER {@code switchIfEmpty(ingest)}</strong> (the §9 trap).
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Error code {@code 3910}: IMAP connection/auth failure — logged at WARN,
 *       swallowed per-cycle so one bad cycle does not kill the scheduler.
 *       NEVER surfaced to an HTTP client.</li>
 *   <li>Error code {@code 3911}: a single message is unparseable — logged,
 *       skipped, batch continues via {@code onErrorContinue}.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.imap.inbound", name = "enabled", matchIfMissing = false)
@RequiredArgsConstructor
public class ImapInboundPoller {

    private final ImapProperties imapProperties;
    private final InboxMessageRepository messages;
    private final InboundEmailService inboundEmailService;

    /**
     * Poll tick driven by Spring's task scheduler. Wraps the blocking IMAP I/O
     * on {@link Schedulers#boundedElastic()} so the Netty event loop is never
     * blocked. Any cycle-level failure is caught and logged (3910) — the
     * scheduler continues running on the next tick.
     */
    @Scheduled(
            fixedRateString = "${kmosf.imap.inbound.poll-interval-ms:60000}",
            initialDelayString = "${kmosf.imap.inbound.poll-interval-ms:60000}")
    public void tick() {
        pollOnce()
                .onErrorResume(err -> {
                    log.warn("[3910] IMAP poll cycle failed (host={}, folder={}): {}",
                            imapProperties.getHost(), imapProperties.getFolder(), err.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * Visible for testing — executes one full poll pass. Must be subscribed to
     * in a context that carries a valid {@link TenantContext}; the caller is
     * responsible for setting it via
     * {@link TenantContextHolder#write(TenantContext)}.
     */
    public Mono<Void> pollOnce() {
        return Mono.fromCallable(this::fetchUnseenMessages)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(this::processMessage)
                .onErrorContinue((err, msg) ->
                        log.warn("[3911] IMAP message unparseable, skipping: {}", err.toString()))
                .then();
    }

    // -------------------------------------------------------------------------
    // Blocking IMAP I/O — called on Schedulers.boundedElastic() only.
    // Opens Store + Folder per cycle; closes both in try/finally.
    // No long-lived connection bean — H-D7 lifecycle rule.
    // -------------------------------------------------------------------------

    private List<ParsedMessage> fetchUnseenMessages() {
        Session session = buildSession();
        Store store = null;
        Folder folder = null;
        try {
            store = session.getStore("imaps");
            store.connect(
                    imapProperties.getHost(),
                    imapProperties.getPort(),
                    imapProperties.getUsername(),
                    imapProperties.getPassword());

            folder = store.getFolder(imapProperties.getFolder());
            folder.open(Folder.READ_ONLY);

            Message[] unseen = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            List<ParsedMessage> result = new ArrayList<>(unseen.length);
            for (Message raw : unseen) {
                try {
                    result.add(parseMessage(raw));
                } catch (Exception e) {
                    log.warn("[3911] IMAP message unparseable during fetch, skipping: {}", e.toString());
                }
            }
            return result;
        } catch (MessagingException e) {
            throw new ImapPollException("IMAP connect/auth failed for host=" + imapProperties.getHost(), e);
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    private Session buildSession() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", imapProperties.getHost());
        props.put("mail.imaps.port", String.valueOf(imapProperties.getPort()));
        props.put("mail.imaps.ssl.enable", "true");
        return Session.getInstance(props);
    }

    private ParsedMessage parseMessage(Message raw) throws MessagingException, IOException {
        String messageId = null;
        if (raw instanceof MimeMessage mime) {
            messageId = mime.getMessageID();
        }
        if (messageId == null) {
            String[] headers = raw.getHeader("Message-ID");
            if (headers != null && headers.length > 0) {
                messageId = headers[0];
            }
        }

        String from = "";
        if (raw.getFrom() != null && raw.getFrom().length > 0) {
            from = ((InternetAddress) raw.getFrom()[0]).getAddress();
        }

        List<String> to = new ArrayList<>();
        if (raw.getAllRecipients() != null) {
            for (var addr : raw.getAllRecipients()) {
                if (addr instanceof InternetAddress ia) {
                    to.add(ia.getAddress());
                }
            }
        }

        String subject = raw.getSubject() == null ? "" : raw.getSubject();

        // Best-effort body extraction — plain-text only for the poller path;
        // htmlBody left null (InboundEmailService accepts null gracefully).
        String textBody = null;
        try {
            Object content = raw.getContent();
            if (content instanceof String s) {
                textBody = s;
            }
        } catch (Exception ignored) {
            // 3911 — unparseable body; continue with null textBody
        }

        Instant receivedAt = raw.getReceivedDate() != null
                ? raw.getReceivedDate().toInstant()
                : Instant.now();

        return new ParsedMessage(messageId, from, to, subject, textBody, receivedAt);
    }

    private static void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException ignored) { }
        }
    }

    private static void closeQuietly(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException ignored) { }
        }
    }

    // -------------------------------------------------------------------------
    // Reactive processing — runs on the boundedElastic thread (flatMap from
    // the fetchUnseenMessages Mono); tenant context is injected by the caller
    // (test) or absent for cross-tenant poller runs.
    // -------------------------------------------------------------------------

    private Mono<Void> processMessage(ParsedMessage parsed) {
        // Synthetic tenant context for the ingest call — the poller is
        // single-tenant-per-credential; tenantId is derived from ImapProperties
        // (or the caller in tests). For the production single-account mode the
        // tenant context must be set externally. This method just calls ingest
        // within whatever context is active.
        String rfcMessageId = parsed.messageId();

        if (rfcMessageId == null || rfcMessageId.isBlank()) {
            // No Message-ID — cannot deduplicate; ingest unconditionally.
            return ingestParsed(parsed);
        }

        // Explicit-boolean idempotency probe — NEVER switchIfEmpty(ingest).
        // The TenantScopedReactiveMongoRepository auto-filters by tenantId from
        // the active TenantContext; the caller must set the context before subscribe.
        return TenantContextHolder.required()
                .flatMap(ctx -> messages.findFirstByTenantIdAndMessageId(ctx.tenantId(), rfcMessageId)
                        .map(x -> true)
                        .defaultIfEmpty(false)
                        .flatMap(seen -> {
                            if (seen) {
                                log.debug("IMAP skip (already ingested): messageId={}", rfcMessageId);
                                return Mono.<Void>empty();
                            }
                            return ingestParsed(parsed);
                        }));
    }

    private Mono<Void> ingestParsed(ParsedMessage parsed) {
        InboundEmailService.InboundEmail email = new InboundEmailService.InboundEmail(
                /* contactId= */ null,          // InboundEmailService resolves from from-address
                parsed.messageId(),
                parsed.from(),
                parsed.to(),
                parsed.subject(),
                /* htmlBody= */ null,
                parsed.textBody(),
                parsed.receivedAt());
        return inboundEmailService.ingest(email).then();
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    record ParsedMessage(
            String messageId,
            String from,
            List<String> to,
            String subject,
            String textBody,
            Instant receivedAt) { }

    /** Wraps IMAP connection/auth failures (error code 3910). */
    static class ImapPollException extends RuntimeException {
        ImapPollException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
