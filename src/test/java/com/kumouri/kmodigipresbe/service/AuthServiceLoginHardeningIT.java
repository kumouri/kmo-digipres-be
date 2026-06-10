package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-06 (login de-enumeration + lockout/throttle) and BE-13
 * ({@code /auth/me} must not leak {@code passwordHash}). Each test uses a distinct
 * {@code X-Forwarded-For} so the per-IP {@code LoginRateLimitFilter} (10/min) does not
 * cross-contaminate, and distinct emails / counts so the per-account lockout (5 failures)
 * only fires where intended.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
// This class exercises the per-IP throttle directly (repeatedFailuresFromOneIpAreThrottled),
// so it needs the production-realistic cap of 10 — NOT the global test relaxation
// (kmosf.login-rate-limit.max-requests=100000 in application-test.properties, which keeps the
// 20+ shared-loopback-IP login ITs from tripping it). Every test here uses a DISTINCT
// X-Forwarded-For, so a cap of 10 scoped to this class never cross-contaminates its own tests.
@TestPropertySource(properties = "kmosf.login-rate-limit.max-requests=10")
class AuthServiceLoginHardeningIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("authhard-it-" + tenantId)
                .displayName("Auth Hardening IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        // Distinct active accounts per test so the in-memory per-account lockout (a singleton
        // bean NOT reset by @BeforeEach) never bleeds between tests.
        seedActive("active@auth.test");   // de-enumeration probe (1 failure only)
        seedActive("login-ok@auth.test"); // valid-login test
        seedActive("me@auth.test");       // /auth/me test
        seedActive("lockme@auth.test");   // dedicated lockout target

        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("disabled@auth.test")
                .passwordHash(encoder.encode("correct-horse-battery"))
                .roles(Set.of("STAFF")).status(User.UserStatus.DISABLED)
                .portal(User.Portal.STAFF).build()).block();
    }

    private void seedActive(String email) {
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email(email)
                .passwordHash(encoder.encode("correct-horse-battery"))
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.STAFF).build()).block();
    }

    /** BE-06: inactive vs nonexistent vs wrong-password all return the SAME 401 + 1020. */
    @Test
    void inactiveNonexistentAndWrongPasswordReturnIdenticalError() {
        Map wrongPw = loginExpectError("active@auth.test", "WRONG", "ip-a-1");
        Map inactive = loginExpectError("disabled@auth.test", "correct-horse-battery", "ip-a-2");
        Map nonexistent = loginExpectError("ghost@auth.test", "whatever", "ip-a-3");

        // Same HTTP status (401), same errorCode (1020), same detail message.
        assertThat(wrongPw.get("status")).isEqualTo(401);
        assertThat(inactive.get("status")).isEqualTo(401);
        assertThat(nonexistent.get("status")).isEqualTo(401);

        assertThat(wrongPw.get("errorCode")).isEqualTo(1020);
        assertThat(inactive.get("errorCode")).isEqualTo(1020);
        assertThat(nonexistent.get("errorCode")).isEqualTo(1020);

        assertThat(inactive.get("detail")).isEqualTo(wrongPw.get("detail"));
        assertThat(nonexistent.get("detail")).isEqualTo(wrongPw.get("detail"));
    }

    /** BE-06: valid credentials still succeed (the hardening must not break real login). */
    @Test
    void validCredentialsStillLogIn() {
        Map body = web.post().uri("/auth/login")
                .header("X-Forwarded-For", "ip-ok")
                .bodyValue(Map.of("email", "login-ok@auth.test", "password", "correct-horse-battery"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body.get("token")).isNotNull();
    }

    /** BE-06: repeated failures from one IP eventually get a 429 (per-IP throttle). */
    @Test
    void repeatedFailuresFromOneIpAreThrottled() {
        // The per-IP window allows 10/min; the 11th from the same IP is 429.
        boolean got429 = false;
        for (int i = 0; i < 15; i++) {
            int status = web.post().uri("/auth/login")
                    .header("X-Forwarded-For", "ip-flood")
                    // distinct emails so the per-ACCOUNT lockout doesn't mask the per-IP cap
                    .bodyValue(Map.of("email", "spray" + i + "@auth.test", "password", "x"))
                    .exchange().returnResult(Void.class).getStatus().value();
            if (status == 429) {
                got429 = true;
                break;
            }
        }
        assertThat(got429).as("per-IP throttle eventually returns 429").isTrue();
    }

    /** BE-06: hammering ONE account locks it (per-account lockout). */
    @Test
    void repeatedFailuresAgainstOneAccountLockIt() {
        // 5 failures from rotating IPs (so the per-IP cap is not what trips) lock the account.
        for (int i = 0; i < LockConstants.ACCOUNT_FAILURES; i++) {
            web.post().uri("/auth/login")
                    .header("X-Forwarded-For", "lockip-" + i)
                    .bodyValue(Map.of("email", "lockme@auth.test", "password", "WRONG"))
                    .exchange().expectStatus().isUnauthorized();
        }
        // Even the CORRECT password now returns the generic 401 (locked, no oracle).
        Map locked = web.post().uri("/auth/login")
                .header("X-Forwarded-For", "lockip-final")
                .bodyValue(Map.of("email", "lockme@auth.test", "password", "correct-horse-battery"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(locked.get("errorCode")).isEqualTo(1020);
    }

    /** BE-13: /auth/me must not serialize the bcrypt passwordHash. */
    @Test
    void authMeDoesNotLeakPasswordHash() {
        String token = (String) web.post().uri("/auth/login")
                .header("X-Forwarded-For", "ip-me")
                .bodyValue(Map.of("email", "me@auth.test", "password", "correct-horse-battery"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("token");

        Map me = web.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(me).doesNotContainKey("passwordHash");
        assertThat(me.get("email")).isEqualTo("me@auth.test");
    }

    private Map loginExpectError(String email, String password, String xff) {
        return web.post().uri("/auth/login")
                .header("X-Forwarded-For", xff)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange().expectStatus().isUnauthorized()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    /** Mirror of {@code LoginAttemptTracker.MAX_FAILURES} (package-private there). */
    private static final class LockConstants {
        static final int ACCOUNT_FAILURES = 5;
    }
}
