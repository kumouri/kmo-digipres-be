package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.service.storage.S3FileStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the single core {@link FileStorageService} bean used by every
 * subsystem that needs binary uploads — field-service captures (Phase 3), quote
 * PDFs (Phase 7), generic attachments (Phase 7).
 *
 * <p>Implementation is always {@link S3FileStorageService}. If the bucket isn't
 * configured, the bean still loads and presign calls fail with
 * {@code DigiPresBeException 1310/503}, which is the right behavior — the
 * surrounding subsystem stays up, only the file-touching paths return a clear
 * error.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfig {

    @Bean
    public FileStorageService fileStorageService(FileStorageProperties props) {
        return S3FileStorageService.create(props);
    }
}
