package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.DispatchBoardResponseDTO;
import com.kumouri.kmodigipresbe.module.homeservices.service.DispatchBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/home-services/dispatch")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class DispatchBoardController {

    private final DispatchBoardService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Mono<DispatchBoardResponseDTO> board(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID technicianId) {
        return guard().then(service.findForDate(date, technicianId));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
