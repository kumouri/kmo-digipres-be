package com.kumouri.kmodigipresbe.controller.sync;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.sync.SyncChange;
import com.kumouri.kmodigipresbe.model.sync.SyncMutation;
import com.kumouri.kmodigipresbe.model.sync.SyncPushResult;
import com.kumouri.kmodigipresbe.service.sync.SyncService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

/**
 * Mobile delta-sync endpoints. Requires authentication (STAFF or higher).
 *
 * <ul>
 *   <li>{@code GET  /sync/{collection}?since=<ISO-8601>} — pull changed docs + tombstones</li>
 *   <li>{@code POST /sync/{collection}} — push client mutations (body: JSON array)</li>
 * </ul>
 *
 * Allowed collections: {@code contacts}, {@code work_orders}, {@code activities}.
 * Unsupported collections return 400 + errorCode 1500.
 */
@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService sync;

    @GetMapping("/{collection}")
    public Flux<SyncChange> pull(
            @PathVariable String collection,
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since) {
        Instant sinceInstant;
        try {
            sinceInstant = Instant.parse(since);
        } catch (Exception ex) {
            return Flux.error(new DigiPresBeException(
                    "Invalid 'since' cursor — expected ISO-8601 instant (e.g., 2025-01-01T00:00:00Z)", 1501, 400));
        }
        return TenantContextHolder.required()
                .flatMapMany(ctx -> {
                    if (ctx.userId() == null) {
                        return Flux.error(new DigiPresBeException(
                                "Sync pull requires an authenticated user (not a system token)", 1501, 400));
                    }
                    return sync.pull(ctx.tenantId(), ctx.userId(), collection, sinceInstant);
                });
    }

    @PostMapping("/{collection}")
    public Flux<SyncPushResult> push(
            @PathVariable String collection,
            @RequestBody List<SyncMutation> mutations) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> sync.push(ctx.tenantId(), collection, mutations));
    }
}
