package com.kumouri.kmodigipresbe.model.marketing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Embedded on {@link com.kumouri.kmodigipresbe.model.contact.Contact}. Records the first
 * marketing touch point for a contact — set once on first form submission or landing-page
 * lead capture; never overwritten thereafter (first-touch attribution model).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FirstTouch {

    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmTerm;
    private String utmContent;

    private Instant capturedAt;

    /** Slug of the landing page the contact first arrived on; null if direct form embed. */
    private String landingPageSlug;
}
