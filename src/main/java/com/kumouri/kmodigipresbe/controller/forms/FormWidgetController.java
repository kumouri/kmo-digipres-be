package com.kumouri.kmodigipresbe.controller.forms;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.forms.FormSubmission;
import com.kumouri.kmodigipresbe.service.forms.FormSubmissionService;
import com.kumouri.kmodigipresbe.service.marketing.UtmCaptureService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Anonymous public endpoint for form widget submissions.
 *
 * <p>The path token establishes tenant identity (via HMAC-signed {@code widgetType="form"}).
 * The form id is in the request body. UTM parameters passed as query parameters on the
 * submission URL are captured for first-touch attribution.
 */
@Slf4j
@RestController
@RequestMapping("/public/widget/form")
@RequiredArgsConstructor
public class FormWidgetController {

    static final String WIDGET_TYPE = "form";

    private final PublicWidgetTokenService tokens;
    private final FormSubmissionService submissionService;

    public record FormWidgetRequest(
            @NotNull UUID formId,
            @NotNull Map<String, String> fields,
            String landingPageSlug) {
    }

    @PostMapping("/{token}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FormSubmission> submit(
            @PathVariable String token,
            @Valid @RequestBody FormWidgetRequest body,
            @RequestParam MultiValueMap<String, String> queryParams) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> handleSubmission(claims, body, queryParams));
    }

    private Mono<FormSubmission> handleSubmission(PublicWidgetToken claims,
                                                   FormWidgetRequest body,
                                                   MultiValueMap<String, String> queryParams) {
        if (!WIDGET_TYPE.equals(claims.widgetType())) {
            return Mono.error(new DigiPresBeException(
                    "Widget token type mismatch (expected '" + WIDGET_TYPE
                            + "', got '" + claims.widgetType() + "')",
                    2911, 401));
        }
        Map<String, String> utmParams = UtmCaptureService.extractUtmParams(queryParams);
        return submissionService.submit(
                claims.tenantId(),
                body.formId(),
                body.fields(),
                utmParams,
                body.landingPageSlug());
    }
}
