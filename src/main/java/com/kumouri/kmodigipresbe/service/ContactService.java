package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.model.request.CommunicationRequest;

public interface ContactService<t extends CommunicationRequest> {
    boolean initiateContact(t contact);
}
