package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Bootstraps two demo tenants on first run: {@code kmosf} and {@code nomomole}, each with
 * an admin user. Idempotent on tenant slug — re-running does nothing.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Override
    public void run(String... args) {
        seedTenant("kmosf", "KMO Solutions Foundry",
                "ceryce@kmosolutionsfoundry.com", "kmosf-dev-password",
                "Ceryce Armstrong")
                .then(seedTenant("nomomole", "No Mo Mole",
                        "rob@nomomole.example", "nmm-dev-password",
                        "Rob (NMM)"))
                .subscribe(
                        ignored -> {},
                        err -> log.error("DataSeeder failed", err));
    }

    private Mono<Void> seedTenant(String slug, String displayName,
                                  String adminEmail, String adminPassword,
                                  String adminDisplayName) {
        return tenants.findBySlug(slug)
                .doOnNext(t -> log.info("Tenant {} already seeded, skipping", slug))
                .switchIfEmpty(Mono.defer(() -> {
                    Tenant t = Tenant.builder()
                            .id(UUID.randomUUID())
                            .slug(slug)
                            .displayName(displayName)
                            .status(Tenant.TenantStatus.ACTIVE)
                            .build();
                    log.info("Seeding tenant {} ({})", slug, displayName);
                    return tenants.save(t).flatMap(saved -> {
                        User admin = User.builder()
                                .id(UUID.randomUUID())
                                .tenantId(saved.getId())
                                .email(adminEmail.toLowerCase())
                                .passwordHash(encoder.encode(adminPassword))
                                .displayName(adminDisplayName)
                                .roles(Set.of("STAFF", "ADMIN"))
                                .status(User.UserStatus.ACTIVE)
                                .build();
                        return users.save(admin).thenReturn(saved);
                    });
                }))
                .then();
    }
}
