package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.BootstrapTenantRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantBootstrapService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public Mono<Tenant> bootstrap(BootstrapTenantRequest req) {
        return tenants.findBySlug(req.tenantSlug())
                .flatMap(existing -> Mono.<Tenant>error(new DigiPresBeException(
                        "Tenant slug already exists", 1500, 409)))
                .switchIfEmpty(Mono.defer(() -> {
                    Tenant tenant = Tenant.builder()
                            .id(UUID.randomUUID())
                            .slug(req.tenantSlug())
                            .displayName(req.tenantDisplayName())
                            .status(Tenant.TenantStatus.ACTIVE)
                            .build();
                    return tenants.save(tenant);
                }))
                .flatMap(tenant -> {
                    User admin = User.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenant.getId())
                            .email(req.adminEmail().toLowerCase())
                            .passwordHash(encoder.encode(req.adminPassword()))
                            .displayName(req.adminDisplayName())
                            .roles(Set.of("STAFF", "ADMIN"))
                            .status(User.UserStatus.ACTIVE)
                            .build();
                    return users.save(admin).thenReturn(tenant);
                });
    }
}
