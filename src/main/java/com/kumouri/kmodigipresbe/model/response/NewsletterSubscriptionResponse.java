package com.kumouri.kmodigipresbe.model.response;

import java.util.Set;

/**
 * Minimal anonymous-friendly response to a newsletter subscribe call. Deliberately
 * tighter than {@code PublicContactController}'s return shape — that endpoint
 * returns a full {@code Contact} document and leaks {@code ownerId} and
 * {@code customFields} to anonymous callers. We do not replicate that.
 *
 * <p>{@code status} is either {@code "subscribed"} (new contact) or
 * {@code "already_subscribed"} (existing contact had topics merged).
 */
public record NewsletterSubscriptionResponse(
        String email,
        Set<String> topics,
        String status) {
}
