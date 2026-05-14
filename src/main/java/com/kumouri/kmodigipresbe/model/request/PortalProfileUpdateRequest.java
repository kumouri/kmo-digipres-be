package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.contact.PostalAddress;

import java.util.List;

/**
 * Strict portal profile PUT payload. Records ignore unknown JSON fields by default, so
 * smuggled {@code tenantId}, {@code ownerId}, {@code id}, {@code companyId},
 * {@code tags}, {@code customFields}, {@code subscriptionTopics}, or {@code emails} keys
 * are silently dropped at the Jackson boundary.
 *
 * <p>Emails are excluded deliberately — changing the linked email is a verification flow
 * (not yet built) and not a simple profile edit.
 */
public record PortalProfileUpdateRequest(
        String firstName,
        String lastName,
        String displayName,
        List<PhoneNumber> phones,
        List<PostalAddress> addresses) {
}
