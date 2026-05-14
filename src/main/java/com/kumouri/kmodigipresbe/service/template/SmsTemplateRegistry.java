package com.kumouri.kmodigipresbe.service.template;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * In-memory SMS template registry. Phase 10e ships v1 with a single hardcoded
 * template ({@code on-the-way-default}) so the seeded on-the-way workflow rule
 * has something to send; full per-tenant template CRUD is a follow-up (the
 * {@code EmailTemplate} collection is the precedent — SMS will likely get a
 * similar {@code SmsTemplate} document when needed).
 *
 * <p>If a caller asks for a template name that is not registered, {@link #resolve}
 * returns the name itself so the dispatcher can still send a literal body — keeps
 * the SEND_SMS dispatcher path robust and lets ad-hoc rules send a fixed string
 * without registering it first.
 *
 * <p>TODO (Phase 10+ follow-up): replace this with a real
 * {@code SmsTemplateRepository} mirroring {@code EmailTemplateRepository}, and
 * teach the dispatcher to Mustache-render the body using the event payload as
 * variable scope (right now bodies are literal; placeholders like
 * {@code {{contactFirstName}}} appear verbatim).
 */
@Component
public class SmsTemplateRegistry {

    /**
     * Default short on-the-way SMS body. Kept under ~160 chars to fit a single
     * GSM-7 SMS segment and avoid Twilio multi-part billing. Placeholders are
     * documented (and rendered) once the v2 follow-up lands; until then this
     * ships as a literal string.
     */
    private static final String ON_THE_WAY_DEFAULT =
            "Hi {{contactFirstName}} — your {{tenantName}} technician is on the way "
                    + "and should arrive within the next 30 minutes.";

    private final Map<String, String> templates = Map.of(
            "on-the-way-default", ON_THE_WAY_DEFAULT);

    /**
     * Looks up an SMS template body by name. Returns the input string itself when
     * the name is not registered — see class-level Javadoc for the rationale.
     */
    public String resolve(String templateName) {
        if (templateName == null) return "";
        return templates.getOrDefault(templateName, templateName);
    }
}
