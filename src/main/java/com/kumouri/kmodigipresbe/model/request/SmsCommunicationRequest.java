package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import lombok.Builder;

@Builder(toBuilder = true)
public record SmsCommunicationRequest(PhoneContact to, String body)
        implements CommunicationRequest {

    @Override
    public String subject() {
        // SMS has no subject; expose body as the summary value for callers that
        // route on subject() (none today, but the interface requires it).
        return body;
    }
}
