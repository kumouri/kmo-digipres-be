package com.kumouri.kmodigipresbe.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Verifies that the SMTP password is configured. In the {@code prod} profile a missing
 * password fails fast — outbound email is core functionality and the alternative is a
 * confusing 500 from {@code POST /api/communication/singleEmail}. In any other profile
 * a missing password is a WARN: dev work should still boot.
 */
public class SmtpRequiredEnvironmentPostProcessor
        implements EnvironmentPostProcessor, ApplicationListener<ContextRefreshedEvent> {

    private static final DeferredLog LOG = new DeferredLog();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String password = env.getProperty("kmosf.mail.smtp.password");
        boolean missing = password == null || password.isBlank();
        if (!missing) return;
        if (isProd(env)) {
            throw new IllegalStateException(
                    "KMOSF_MAIL_SMTP_PASSWORD must be set in the prod profile. " +
                    "Set the env var or run with a non-prod profile.");
        }
        LOG.warn("kmosf.mail.smtp.password is not set — outbound email will fail at send time. " +
                 "Set KMOSF_MAIL_SMTP_PASSWORD to silence this.");
        app.addListeners(this);
    }

    private boolean isProd(ConfigurableEnvironment env) {
        for (String p : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        Log realLog = org.apache.commons.logging.LogFactory.getLog(getClass());
        LOG.replayTo(realLog);
    }
}
