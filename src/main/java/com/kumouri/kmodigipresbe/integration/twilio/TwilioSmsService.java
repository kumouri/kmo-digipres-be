package com.kumouri.kmodigipresbe.integration.twilio;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionService;
import com.kumouri.kmodigipresbe.model.request.CommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.service.ContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Twilio SMS impl of {@link ContactService}. Closes the Phase 1 promise of a
 * second channel — the {@code ContactService<T extends CommunicationRequest>}
 * generic interface has been waiting for an SMS sibling to {@code EmailService}
 * since the very first commit.
 *
 * <p>Credentials come from the tenant's {@link IntegrationConnection}
 * (provider {@code "twilio"}, secrets {@code accountSid}, {@code authToken},
 * {@code fromNumber}). The Twilio API call is a single form-POST to
 * {@code /2010-04-01/Accounts/{Sid}/Messages.json}; cheap to build inline
 * with {@code WebClient}.
 */
@Service
@RequiredArgsConstructor
public class TwilioSmsService implements ContactService<CommunicationRequest> {

    public static final String PROVIDER = "twilio";
    private static final String API = "https://api.twilio.com";

    private final IntegrationConnectionService connections;
    private final WebClient.Builder webClientBuilder;

    @Override
    public Mono<Boolean> initiateContact(CommunicationRequest request) {
        if (!(request instanceof SmsCommunicationRequest sms)) {
            return Mono.just(false);
        }
        return sendSms(sms);
    }

    public Mono<Boolean> sendSms(SmsCommunicationRequest req) {
        return connections.requireByProvider(PROVIDER).flatMap(conn -> {
            String sid = secret(conn, "accountSid");
            String token = secret(conn, "authToken");
            String from = secret(conn, "fromNumber");
            if (req.to() == null || req.to().e164() == null || req.to().e164().isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "SMS recipient is required", 2530, 400));
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("From", from);
            form.add("To", req.to().e164());
            form.add("Body", req.body() == null ? "" : req.body());
            String basic = "Basic " + Base64.getEncoder().encodeToString(
                    (sid + ":" + token).getBytes(StandardCharsets.UTF_8));
            WebClient client = webClientBuilder.baseUrl(API).build();
            return client.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", sid)
                    .header("Authorization", basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .toBodilessEntity()
                    .thenReturn(true)
                    .onErrorMap(err -> new DigiPresBeException(
                            "Twilio send failed: " + err.getMessage(), 2531, 502));
        });
    }

    private String secret(IntegrationConnection conn, String key) {
        String v = conn.getSecrets() == null ? null : conn.getSecrets().get(key);
        if (v == null || v.isBlank()) {
            throw new DigiPresBeException(
                    "Twilio connection is missing '" + key + "'", 2532, 412);
        }
        return v;
    }
}
