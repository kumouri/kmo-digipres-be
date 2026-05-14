package com.kumouri.kmodigipresbe.model.request;

/**
 * Common shape of an outbound communication. The recipient/sender types differ per channel
 * (email vs SMS vs ...) so they live on the channel-specific subtypes rather than here.
 */
public interface CommunicationRequest {
    String subject();
    String body();
}
