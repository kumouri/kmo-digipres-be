package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.model.request.CommunicationRequest;
import reactor.core.publisher.Mono;

public interface ContactService<T extends CommunicationRequest> {
    Mono<Boolean> initiateContact(T contact);
}
