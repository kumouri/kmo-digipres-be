package com.kumouri.kmodigipresbe.model.contact;

import com.kumouri.kmodigipresbe.util.EmailUtil;
import jakarta.mail.internet.InternetAddress;
import lombok.Builder;

@Builder(toBuilder = true)
public record EmailContact(InternetAddress email) implements Contact {
    public EmailContact(String email) {
        this(EmailUtil.fromString(email));
    }
}
