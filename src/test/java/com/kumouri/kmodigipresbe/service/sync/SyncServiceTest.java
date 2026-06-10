package com.kumouri.kmodigipresbe.service.sync;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-07 — unit coverage for the sync push writable-field allowlist
 * ({@link SyncService#filterWritable}). Pure (no Mongo / Docker).
 */
class SyncServiceTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void filterWritable_dropsTenantIdAndServerManagedFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", UUID.randomUUID());   // the cross-tenant write-out attack
        fields.put("_id", UUID.randomUUID());
        fields.put("id", UUID.randomUUID());
        fields.put("version", 99L);
        fields.put("createdAt", "2020-01-01T00:00:00Z");
        fields.put("updatedAt", "2020-01-01T00:00:00Z");
        fields.put("firstName", "Legit");            // the one writable field

        Map<String, Object> out = SyncService.filterWritable("contacts", fields, ID);

        assertThat(out).containsOnlyKeys("firstName");
        assertThat(out).doesNotContainKeys(
                "tenantId", "_id", "id", "version", "createdAt", "updatedAt");
    }

    @Test
    void filterWritable_keepsAllowlistedContactFields() {
        Map<String, Object> fields = Map.of(
                "firstName", "A", "lastName", "B", "displayName", "C",
                "companyId", UUID.randomUUID(), "tags", java.util.List.of("vip"),
                "ownerId", UUID.randomUUID());

        Map<String, Object> out = SyncService.filterWritable("contacts", fields, ID);

        assertThat(out).containsOnlyKeys(
                "firstName", "lastName", "displayName", "companyId", "tags", "ownerId");
    }

    @Test
    void filterWritable_dropsArbitraryUnknownField() {
        Map<String, Object> fields = Map.of(
                "firstName", "Keep", "definitelyNotAField", "drop", "status", "drop-too");

        // "status" is writable on work_orders/activities but NOT on contacts — the
        // allowlist is per-collection, so it is dropped here.
        Map<String, Object> out = SyncService.filterWritable("contacts", fields, ID);

        assertThat(out).containsOnlyKeys("firstName");
    }

    @Test
    void filterWritable_workOrderStatusIsWritable() {
        Map<String, Object> fields = Map.of(
                "status", "COMPLETED", "notes", "done", "tenantId", UUID.randomUUID());

        Map<String, Object> out = SyncService.filterWritable("work_orders", fields, ID);

        assertThat(out).containsOnlyKeys("status", "notes");
    }

    @Test
    void filterWritable_unknownCollectionDropsEverything_failSafe() {
        Map<String, Object> fields = Map.of("firstName", "X", "anything", "Y");

        Map<String, Object> out = SyncService.filterWritable("not_a_collection", fields, ID);

        assertThat(out).isEmpty();
    }
}
