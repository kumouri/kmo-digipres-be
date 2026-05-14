package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.service.files.AttachmentService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@RequestMapping("/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PresignRequest {
        private String subjectType;
        private UUID subjectId;
        private String contentType;
        private String suffix;
    }

    @GetMapping
    public Flux<Attachment> list(@RequestParam String subjectType, @RequestParam UUID subjectId) {
        return service.listFor(subjectType, subjectId);
    }

    @PostMapping("/presign")
    public Mono<FileStorageService.Presigned> presign(@RequestBody PresignRequest body) {
        return service.presignUpload(body.getSubjectType(), body.getSubjectId(),
                body.getContentType(), body.getSuffix());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Attachment> register(@RequestBody Attachment body) {
        return service.register(body);
    }

    @GetMapping("/download-url")
    public Mono<String> presignDownload(@RequestParam String storageRef) {
        return service.presignDownload(storageRef);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
