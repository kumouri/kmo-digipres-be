package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.service.nurture.NurtureCopyFilter.FilterResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 — unit test (no Docker; fast lane {@code *Test}) for the Fair-Housing copy filter. Proves the
 * headline correctness property in isolation: compliant copy passes through; copy carrying any FHA
 * banned-term family is replaced with the vetted safe template; null/blank is safe; the channel selects
 * the right safe template; and it never throws.
 */
class FairHousingCopyFilterTest {

    private final FairHousingCopyFilter filter = new FairHousingCopyFilter(
            "Hi {firstName}, checking in from Gateway Realty — reply YES to chat.",
            "Hi {firstName}, checking in from Gateway Realty. Reply to set up a time.");

    private static Contact contact(String first) {
        return Contact.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .type(ContactType.PERSON)
                .firstName(first)
                .displayName(first)
                .build();
    }

    @Test
    void cleanSms_passesThroughUnchanged() {
        String clean = "Hi Pat, the market's moved since we last talked — want a quick value update?";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, clean, contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isFalse();
                    assertThat(r.body()).isEqualTo(clean);
                    assertThat(r.reason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void familialSteeringPhrase_isReplacedWithSafeSms() {
        // "perfect for families" is a classic FHA familial-status steering phrase.
        String bad = "This one is perfect for families, Pat — want to see it?";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.reason()).contains("fair-housing").contains("perfect for families");
                    // The safe SMS template, rendered for the contact — and free of the banned phrase.
                    assertThat(r.body()).isEqualTo(
                            "Hi Pat, checking in from Gateway Realty — reply YES to chat.");
                    assertThat(r.body().toLowerCase()).doesNotContain("perfect for families");
                })
                .verifyComplete();
    }

    @Test
    void safetyCodePhrase_isReplaced() {
        // "safe neighborhood" is the classic steering/safety code.
        String bad = "Great value in a safe neighborhood!";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, contact("Sam")))
                .assertNext(r -> assertThat(r.replaced()).isTrue())
                .verifyComplete();
    }

    @Test
    void protectedClassReference_isReplacedOnEmail_withSafeEmailBody() {
        // A religion reference → replaced; EMAIL channel selects the email safe body.
        String bad = "Close to the church and great for kids.";
        StepVerifier.create(filter.filter(NurtureChannel.EMAIL, bad, contact("Lee")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.body()).isEqualTo(
                            "Hi Lee, checking in from Gateway Realty. Reply to set up a time.");
                })
                .verifyComplete();
    }

    @Test
    void nullAndBlankBody_passThrough_neverThrow() {
        StepVerifier.create(filter.filter(NurtureChannel.SMS, null, contact("Pat")))
                .assertNext(r -> assertThat(r.replaced()).isFalse())
                .verifyComplete();
        StepVerifier.create(filter.filter(NurtureChannel.SMS, "   ", contact("Pat")))
                .assertNext(r -> assertThat(r.replaced()).isFalse())
                .verifyComplete();
    }

    @Test
    void nullContact_doesNotThrow_andSafeTemplateRenders() {
        String bad = "perfect for families";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, null))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    // {firstName} renders to empty when contact is null — still a clean, sendable body.
                    assertThat(r.body()).isNotBlank();
                    assertThat(r.body().toLowerCase()).doesNotContain("perfect for families");
                })
                .verifyComplete();
    }

    @Test
    void defaultTemplatesUsed_whenBlankConfig() {
        FairHousingCopyFilter defaulted = new FairHousingCopyFilter("", "  ");
        StepVerifier.create(defaulted.filter(NurtureChannel.SMS, "adults only", contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.body()).isEqualTo(
                            FairHousingCopyFilter.DEFAULT_SAFE_SMS.replace("{firstName}", "Pat"));
                })
                .verifyComplete();
    }
}
