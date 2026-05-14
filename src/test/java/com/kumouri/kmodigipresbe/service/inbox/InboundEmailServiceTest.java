package com.kumouri.kmodigipresbe.service.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for InboundEmailService's subject-normalisation helper —
 * the rest of the service is exercised by the controller-level IT.
 */
class InboundEmailServiceTest {

    @Test
    void normaliseSubject_stripsReAndFwdPrefixes() {
        assertThat(InboundEmailService.normaliseSubject("Re: Hello"))
                .isEqualTo("hello");
        assertThat(InboundEmailService.normaliseSubject("Fwd: question"))
                .isEqualTo("question");
        assertThat(InboundEmailService.normaliseSubject("FW: status"))
                .isEqualTo("status");
    }

    @Test
    void normaliseSubject_stripsRepeatedPrefixes() {
        assertThat(InboundEmailService.normaliseSubject("Re: Re: Fwd: Re: deep thread"))
                .isEqualTo("deep thread");
    }

    @Test
    void normaliseSubject_caseInsensitivePrefix() {
        assertThat(InboundEmailService.normaliseSubject("RE: Already lower"))
                .isEqualTo("already lower");
    }

    @Test
    void normaliseSubject_handlesNullAndBlank() {
        assertThat(InboundEmailService.normaliseSubject(null)).isEmpty();
        assertThat(InboundEmailService.normaliseSubject("   ")).isEmpty();
    }
}
