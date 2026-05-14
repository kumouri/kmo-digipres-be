package com.kumouri.kmodigipresbe.service.communication;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Postmark send wire shape (token header, JSON body) and error
 * mapping against a {@link WireMockServer} standing in for Postmark. No Spring
 * context — bare WebClient pointed at WireMock's port.
 */
class PostmarkTransactionalEmailServiceTest {

    private WireMockServer wireMock;
    private IntegrationConnectionRepository connections;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        connections = mock(IntegrationConnectionRepository.class);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void send_useTenantToken_postsExpectedShape() {
        UUID tenant = UUID.randomUUID();
        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenant)
                .provider("postmark")
                .secrets(Map.of("apiToken", "tenant-token-abc"))
                .build();
        when(connections.findByTenantIdAndProvider(tenant, "postmark"))
                .thenReturn(Mono.just(conn));

        wireMock.stubFor(post(urlEqualTo("/"))
                .withHeader("X-Postmark-Server-Token", equalTo("tenant-token-abc"))
                .withRequestBody(equalToJson(
                        "{\"From\":\"sales@example.test\",\"To\":\"alice@example.test\","
                                + "\"Subject\":\"hi\",\"HtmlBody\":\"<p>hi</p>\","
                                + "\"Tag\":\"welcome\",\"Metadata\":{\"kmosf_contact_id\":\"c1\"}}",
                        true, true))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"MessageID\":\"mid-1\",\"To\":\"alice@example.test\","
                                + "\"SubmittedAt\":\"2026-05-14T20:00:00.000Z\","
                                + "\"ErrorCode\":0,\"Message\":\"OK\"}")));

        PostmarkTransactionalEmailService svc = service(wireMock.baseUrl(), "");

        TransactionalSendRequest req = new TransactionalSendRequest(
                List.of("alice@example.test"),
                "sales@example.test",
                "hi",
                "<p>hi</p>",
                null,
                "welcome",
                Map.of("kmosf_contact_id", "c1"));

        StepVerifier.create(svc.send(req)
                        .contextWrite(TenantContextHolder.write(ctx(tenant))))
                .assertNext(result -> {
                    assertThat(result.messageId()).isEqualTo("mid-1");
                    assertThat(result.recipient()).isEqualTo("alice@example.test");
                })
                .verifyComplete();
    }

    @Test
    void send_fallsBackToHouseToken_whenTenantHasNoConnection() {
        UUID tenant = UUID.randomUUID();
        when(connections.findByTenantIdAndProvider(tenant, "postmark"))
                .thenReturn(Mono.empty());

        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withHeader("X-Postmark-Server-Token", equalTo("house-token-xyz"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"MessageID\":\"mid-2\",\"To\":\"bob@example.test\","
                                + "\"SubmittedAt\":\"2026-05-14T20:01:00Z\"}")));

        PostmarkTransactionalEmailService svc = service(wireMock.baseUrl(), "house-token-xyz");

        StepVerifier.create(svc.send(new TransactionalSendRequest(
                        List.of("bob@example.test"), "sales@example.test",
                        "hello", null, "hello", null, null))
                        .contextWrite(TenantContextHolder.write(ctx(tenant))))
                .assertNext(r -> assertThat(r.messageId()).isEqualTo("mid-2"))
                .verifyComplete();
    }

    @Test
    void send_noTokenAnywhere_errors_1701() {
        UUID tenant = UUID.randomUUID();
        when(connections.findByTenantIdAndProvider(tenant, "postmark"))
                .thenReturn(Mono.empty());

        PostmarkTransactionalEmailService svc = service(wireMock.baseUrl(), "");

        StepVerifier.create(svc.send(new TransactionalSendRequest(
                        List.of("bob@example.test"), "sales@example.test",
                        "hello", null, "hello", null, null))
                        .contextWrite(TenantContextHolder.write(ctx(tenant))))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1701);
                    assertThat(((DigiPresBeException) err).getHttpStatusCode()).isEqualTo(412);
                })
                .verify();
    }

    @Test
    void send_postmarkReturnsNon200_maps_1700_502() {
        UUID tenant = UUID.randomUUID();
        when(connections.findByTenantIdAndProvider(tenant, "postmark"))
                .thenReturn(Mono.just(IntegrationConnection.builder()
                        .tenantId(tenant).provider("postmark")
                        .secrets(Map.of("apiToken", "t"))
                        .build()));

        wireMock.stubFor(post(urlEqualTo("/"))
                .willReturn(aResponse().withStatus(422).withBody("invalid recipient")));

        PostmarkTransactionalEmailService svc = service(wireMock.baseUrl(), "");

        StepVerifier.create(svc.send(new TransactionalSendRequest(
                        List.of("bob@example.test"), "sales@example.test",
                        "hello", null, "hello", null, null))
                        .contextWrite(TenantContextHolder.write(ctx(tenant))))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1700);
                    assertThat(((DigiPresBeException) err).getHttpStatusCode()).isEqualTo(502);
                })
                .verify();
    }

    private PostmarkTransactionalEmailService service(String baseUrl, String houseToken) {
        // The constructor wires the WebClient builder against the Postmark URL constant.
        // To point at WireMock we instantiate via the production constructor, then swap
        // the WebClient via ReflectionTestUtils.
        WebClient.Builder builder = WebClient.builder();
        PostmarkTransactionalEmailService svc =
                new PostmarkTransactionalEmailService(builder, connections, houseToken);
        ReflectionTestUtils.setField(svc, "http", WebClient.builder().baseUrl(baseUrl).build());
        return svc;
    }

    private static TenantContext ctx(UUID tenant) {
        return new TenantContext(tenant, UUID.randomUUID(), Set.of("STAFF"));
    }
}
