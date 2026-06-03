package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;

/**
 * Minimal, read-only view of an assigned project's linked client (Phase J — J2).
 *
 * <p>Returned for {@code GET /me/contractor/projects/{id}/client} after the assigned-project
 * gate. Exposes ONLY the contact's name + primary email/phone and the company name — just
 * enough for a contractor to know who they are building for. Deliberately excludes every
 * other CRM field on {@code Contact}/{@code Company} (lead score, health score, tags,
 * owner, custom fields, the full email/phone/address lists, deliverability, timestamps) —
 * a contractor is not a CRM user.
 *
 * <p>All fields are nullable: a project may have no primary contact and/or no company, in
 * which case the corresponding fields are {@code null} (the view is still returned).
 */
public record ContractorClientView(
        String contactName,
        String contactEmail,
        String contactPhone,
        String companyName) {

    public static ContractorClientView from(Contact contact, Company company) {
        return new ContractorClientView(
                contact == null ? null : displayName(contact),
                contact == null ? null : primaryEmail(contact),
                contact == null ? null : primaryPhone(contact),
                company == null ? null : company.getName());
    }

    private static String displayName(Contact c) {
        if (c.getDisplayName() != null && !c.getDisplayName().isBlank()) {
            return c.getDisplayName();
        }
        String first = c.getFirstName() == null ? "" : c.getFirstName().trim();
        String last = c.getLastName() == null ? "" : c.getLastName().trim();
        String joined = (first + " " + last).trim();
        return joined.isBlank() ? null : joined;
    }

    private static String primaryEmail(Contact c) {
        if (c.getEmails() == null) {
            return null;
        }
        return c.getEmails().stream()
                .filter(e -> e != null && e.asString() != null)
                .map(EmailContact::asString)
                .findFirst()
                .orElse(null);
    }

    private static String primaryPhone(Contact c) {
        if (c.getPhones() == null) {
            return null;
        }
        return c.getPhones().stream()
                .filter(p -> p != null && p.number() != null)
                .map(PhoneNumber::number)
                .findFirst()
                .orElse(null);
    }
}
