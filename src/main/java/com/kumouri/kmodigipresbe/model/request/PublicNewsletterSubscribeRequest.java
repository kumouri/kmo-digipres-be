package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * Strict anonymous newsletter subscribe payload. Records ignore unknown JSON fields
 * by default, so smuggled {@code tenantId}, {@code ownerId}, {@code tags}, or
 * {@code subscriptionTopics} keys at the wrong nesting are dropped at the Jackson
 * boundary — tenant is derived from the path slug, topics is the authoritative
 * subscription-list field name.
 */
public record PublicNewsletterSubscribeRequest(
        @Email @NotBlank String email,
        String firstName,
        Set<String> topics) {
}
