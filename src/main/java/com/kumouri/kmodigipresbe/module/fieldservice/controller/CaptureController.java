package com.kumouri.kmodigipresbe.module.fieldservice.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.model.Capture;
import com.kumouri.kmodigipresbe.module.fieldservice.service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/captures")
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService service;
    private final TenantModuleRegistry modules;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Capture> register(@RequestBody Capture body) {
        return guard().then(service.register(body));
    }

    @GetMapping
    public Flux<Capture> listForWorkOrder(@RequestParam UUID workOrderId) {
        return guard().thenMany(service.listForWorkOrder(workOrderId));
    }

    @GetMapping("/download-url")
    public Mono<String> presignDownload(@RequestParam String storageRef) {
        return guard().then(service.presignDownload(storageRef));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FieldServiceAutoConfiguration.MODULE_KEY);
    }
}
