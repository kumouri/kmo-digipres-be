package com.kumouri.kmodigipresbe.model.contact;

import com.kumouri.kmodigipresbe.util.EmailUtil;
import jakarta.mail.internet.InternetAddress;
import lombok.Builder;

/**
 * A single email-channel binding. Used in two places:
 * <ul>
 *   <li>Embedded as one of {@link Contact#emails} on a CRM Contact document.</li>
 *   <li>Inline on outbound communication requests (e.g. SingleEmailCommunicationRequest).</li>
 * </ul>
 * <p>This is intentionally NOT a {@code @Document} — it's a value type.
 */
@Builder(toBuilder = true)
public record EmailContact(InternetAddress email) {
    public EmailContact(String email) {
        this(EmailUtil.fromString(email));
    }

    public String asString() {
        return email == null ? null : email.getAddress();
    }
}
