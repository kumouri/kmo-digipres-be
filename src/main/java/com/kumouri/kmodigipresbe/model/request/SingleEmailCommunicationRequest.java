package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import lombok.Builder;

@Builder(toBuilder = true)
public record SingleEmailCommunicationRequest(EmailContact from, EmailContact to, String subject, String body)
        implements EmailCommunicationRequest {
}
