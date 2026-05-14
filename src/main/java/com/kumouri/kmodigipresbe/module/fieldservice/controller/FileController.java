package com.kumouri.kmodigipresbe.module.fieldservice.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.service.CaptureService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/files")
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
@RequiredArgsConstructor
public class FileController {

    private final CaptureService captures;
    private final TenantModuleRegistry modules;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PresignRequest {
        private UUID workOrderId;
        private String contentType;
        private String suffix;
    }

    @PostMapping("/presign")
    public Mono<FileStorageService.Presigned> presign(@RequestBody PresignRequest body) {
        return modules.requireEnabled(FieldServiceAutoConfiguration.MODULE_KEY)
                .then(captures.presignUpload(body.getWorkOrderId(), body.getContentType(), body.getSuffix()));
    }
}
