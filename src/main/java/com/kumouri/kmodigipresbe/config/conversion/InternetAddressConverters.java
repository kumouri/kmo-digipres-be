package com.kumouri.kmodigipresbe.config.conversion;

import com.kumouri.kmodigipresbe.util.EmailUtil;
import jakarta.mail.internet.InternetAddress;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Mongo conversion for {@link InternetAddress} fields.
 *
 * <h2>Why this exists</h2>
 * {@code EmailContact} is a record holding a single {@code email: InternetAddress}.
 * Spring Data MongoDB's POJO codec did not reliably round-trip the nested
 * {@code InternetAddress} sub-document: loaded Contacts came back with
 * {@code emails[i].email == null}, breaking downstream code that read
 * {@code EmailContact.asString()}. Phase 9d's {@code SequenceEngine.loadRecipient}
 * surfaced this with "contact has no email — exiting sequence" warnings on
 * contacts that visibly had emails on creation.
 *
 * <h2>Fix</h2>
 * Persist {@code InternetAddress} as a flat String (the address itself). On
 * read, parse the String back through {@link EmailUtil#fromString}, which
 * applies the same validation used at write time elsewhere in the codebase.
 *
 * <p>Stored shape after this PR: {@code emails: [{email: "alice@example.test"}]}
 * (was nested {@code {address, personal}} sub-doc before, with the round-trip
 * bug). Any query referencing the old path needs an update — see
 * {@code ContactRepository.findByTenantAndEmailAddress}.
 *
 * <p>Wired into the converter chain by {@code MongoConfig.customConversions()}.
 */
public final class InternetAddressConverters {

    private InternetAddressConverters() {
    }

    @WritingConverter
    public enum InternetAddressToString implements Converter<InternetAddress, String> {
        INSTANCE;

        @Override
        public String convert(InternetAddress source) {
            return source == null ? null : source.getAddress();
        }
    }

    @ReadingConverter
    public enum StringToInternetAddress implements Converter<String, InternetAddress> {
        INSTANCE;

        @Override
        public InternetAddress convert(String source) {
            return (source == null || source.isBlank()) ? null : EmailUtil.fromString(source);
        }
    }
}
