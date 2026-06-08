package com.kumouri.kmodigipresbe.module.frontdesk.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.service.AppointmentService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * FrontDesk IQ (FD-1) — authenticated staff CRUD for {@link Appointment} (the front-desk console surface).
 *
 * <p>Gating mirrors the {@code ListingController}/{@code NoShowRiskController} precedent:
 * {@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)} (absent from the OpenAPI spec when the
 * module is off) + per-tenant module membership via {@link TenantModuleRegistry#requireEnabled} (the shared
 * 1130/1132 module-gate codes) + {@link RoleGuard#requireRole "STAFF"} (1800). Tenant is resolved from the
 * request context, so an appointment is always scoped to the caller's tenant ({@code 4276} on a
 * missing/not-owned appointment; {@code 4277} on an invalid payload).
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code /api/v1/frontdesk/appointments...}.
 */
@RestController
@RequestMapping("/frontdesk/appointments")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointments;
    private final TenantModuleRegistry modules;

    @PostMapping
    public Mono<Appointment> create(@RequestBody Appointment body) {
        return guard().then(appointments.create(body));
    }

    @GetMapping
    public Flux<Appointment> list() {
        return guard().thenMany(appointments.list());
    }

    @GetMapping("/{id}")
    public Mono<Appointment> get(@PathVariable UUID id) {
        return guard().then(appointments.get(id));
    }

    @PutMapping("/{id}")
    public Mono<Appointment> update(@PathVariable UUID id, @RequestBody Appointment body) {
        return guard().then(appointments.update(id, body));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
