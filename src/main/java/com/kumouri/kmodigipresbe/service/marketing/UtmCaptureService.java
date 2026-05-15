package com.kumouri.kmodigipresbe.service.marketing;

import com.kumouri.kmodigipresbe.model.marketing.FirstTouch;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Writes {@link FirstTouch} onto a {@link Contact} exactly once (first-touch
 * attribution). If the contact already has a {@code firstTouch}, the call is a
 * no-op — subsequent submissions do not overwrite the originating channel.
 */
@Service
@RequiredArgsConstructor
public class UtmCaptureService {

    private static final String UTM_SOURCE = "utm_source";
    private static final String UTM_MEDIUM = "utm_medium";
    private static final String UTM_CAMPAIGN = "utm_campaign";
    private static final String UTM_TERM = "utm_term";
    private static final String UTM_CONTENT = "utm_content";

    private final ContactRepository contacts;

    /**
     * Applies {@code utmParams} as a FirstTouch on {@code contact} if no
     * FirstTouch is already recorded. Returns the (possibly updated) contact.
     */
    public Mono<Contact> capture(Contact contact, Map<String, String> utmParams,
                                 String landingPageSlug) {
        if (contact.getFirstTouch() != null) {
            return Mono.just(contact);
        }
        FirstTouch touch = FirstTouch.builder()
                .utmSource(utmParams.get(UTM_SOURCE))
                .utmMedium(utmParams.get(UTM_MEDIUM))
                .utmCampaign(utmParams.get(UTM_CAMPAIGN))
                .utmTerm(utmParams.get(UTM_TERM))
                .utmContent(utmParams.get(UTM_CONTENT))
                .capturedAt(Instant.now())
                .landingPageSlug(landingPageSlug)
                .build();
        contact.setFirstTouch(touch);
        return contacts.save(contact);
    }

    /** Extracts UTM query parameters from a raw query string map. */
    public static Map<String, String> extractUtmParams(
            org.springframework.util.MultiValueMap<String, String> queryParams) {
        if (queryParams == null) return Map.of();
        var result = new java.util.HashMap<String, String>();
        for (String key : new String[]{UTM_SOURCE, UTM_MEDIUM, UTM_CAMPAIGN, UTM_TERM, UTM_CONTENT}) {
            String val = queryParams.getFirst(key);
            if (val != null && !val.isBlank()) result.put(key, val);
        }
        return result;
    }
}
