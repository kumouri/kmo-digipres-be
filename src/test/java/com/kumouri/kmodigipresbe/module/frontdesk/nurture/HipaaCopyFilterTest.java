package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 — unit test (no Docker; fast lane {@code *Test}) for the HIPAA copy filter. Proves the headline
 * correctness property in isolation: compliant copy passes through; copy confirming patient status or naming
 * a clinical term is replaced with the vetted safe generic template; null/blank is safe; the channel selects
 * the right safe template; and it never throws.
 */
class HipaaCopyFilterTest {

    private final HipaaCopyFilter filter = new HipaaCopyFilter(
            "Hi {firstName}, it's Bright Smiles — reply YES and we'll get you back on the schedule.",
            "Hi {firstName}, it's Bright Smiles. Reply and we'll find you a time.");

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
        String clean = "Hi Pat, it's been a while — reply YES and we'll get you back on the schedule.";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, clean, contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isFalse();
                    assertThat(r.body()).isEqualTo(clean);
                    assertThat(r.reason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void clinicalTerm_isReplacedWithSafeSms() {
        // "crown" is a clinical (dental procedure) term — naming care in a public/outbound message.
        String bad = "Hi Pat, time to come back in for your crown — want to schedule?";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.reason()).contains("phi").contains("crown");
                    // The safe SMS template, rendered for the contact — and free of the clinical term.
                    assertThat(r.body()).isEqualTo(
                            "Hi Pat, it's Bright Smiles — reply YES and we'll get you back on the schedule.");
                    assertThat(r.body().toLowerCase()).doesNotContain("crown");
                })
                .verifyComplete();
    }

    @Test
    void patientStatusConfirmation_isReplaced() {
        // "thank you for being our patient" confirms the recipient received care — itself a PHI disclosure.
        String bad = "Thank you for being our patient! Time for a check-up?";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, contact("Sam")))
                .assertNext(r -> assertThat(r.replaced()).isTrue())
                .verifyComplete();
    }

    @Test
    void procedureReference_isReplacedOnEmail_withSafeEmailBody() {
        // "your procedure" (patient-status) + "prescription" (clinical) → replaced; EMAIL selects the email body.
        String bad = "Hope your procedure went well — your prescription should be ready.";
        StepVerifier.create(filter.filter(NurtureChannel.EMAIL, bad, contact("Lee")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.body()).isEqualTo(
                            "Hi Lee, it's Bright Smiles. Reply and we'll find you a time.");
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
        String bad = "your root canal";
        StepVerifier.create(filter.filter(NurtureChannel.SMS, bad, null))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    // {firstName} renders to empty when contact is null — still a clean, sendable body.
                    assertThat(r.body()).isNotBlank();
                    assertThat(r.body().toLowerCase()).doesNotContain("root canal");
                })
                .verifyComplete();
    }

    @Test
    void defaultTemplatesUsed_whenBlankConfig() {
        HipaaCopyFilter defaulted = new HipaaCopyFilter("", "  ");
        StepVerifier.create(defaulted.filter(NurtureChannel.SMS, "your diagnosis", contact("Pat")))
                .assertNext(r -> {
                    assertThat(r.replaced()).isTrue();
                    assertThat(r.body()).isEqualTo(
                            HipaaCopyFilter.DEFAULT_SAFE_SMS.replace("{firstName}", "Pat"));
                })
                .verifyComplete();
    }
}
