package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.contact.PostalAddress;

import java.util.List;

/**
 * Strict portal profile read shape. Deliberately omits tenant-internal and staff-only
 * fields ({@code ownerId}, {@code companyId}, {@code tags}, {@code customFields},
 * {@code subscriptionTopics}, {@code tenantId}, {@code version}, {@code createdAt},
 * {@code updatedAt}) so the portal client can't observe data it shouldn't.
 */
public record PortalProfileResponse(
        String id,
        String firstName,
        String lastName,
        String displayName,
        List<String> emails,
        List<PhoneNumber> phones,
        List<PostalAddress> addresses) {
}
