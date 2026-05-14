package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 10a stub: handler returns an empty Mono. 10c wires {@code DispatchBoardService}
 * with technician grouping + geo-aware ordering within each tech's day.
 */
@RestController
@RequestMapping("/home-services/dispatch")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class DispatchBoardController {

    private final TenantModuleRegistry modules;

    @GetMapping
    public Mono<Object> board(@RequestParam LocalDate date,
                              @RequestParam(required = false) UUID technicianId) {
        return guard().then(Mono.empty());
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
