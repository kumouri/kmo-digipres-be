package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeSaveCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the {@code customFields} map on every save of a {@link CustomFieldHost}
 * against the per-tenant {@link FieldDefinition} catalog. Runs as a
 * {@link ReactiveBeforeSaveCallback} so it sees the entity after type conversion but
 * before the write hits Mongo, and so it can read the tenant from the Reactor Context.
 *
 * <p>Definitions for a tenant + entity type are fetched on every save. For Phase 2
 * this is intentionally uncached — correctness over latency. A cache (TTL + invalidation
 * on FieldDefinition writes) is a Phase 6+ optimization.
 */
@Component
@RequiredArgsConstructor
public class CustomFieldValidator implements ReactiveBeforeSaveCallback<CustomFieldHost> {

    private final FieldDefinitionRepository definitions;

    @Override
    @NonNull
    public Mono<CustomFieldHost> onBeforeSave(@NonNull CustomFieldHost host,
                                              @NonNull Document target,
                                              @NonNull String collection) {
        return TenantContextHolder.current()
                .flatMap(ctx -> validate(host, ctx.tenantId()))
                .thenReturn(host)
                .switchIfEmpty(Mono.just(host));
    }

    private Mono<Void> validate(CustomFieldHost host, UUID tenantId) {
        Map<String, Object> values = host.getCustomFields() == null
                ? Map.of()
                : host.getCustomFields();
        return definitions.findAllByTenantIdAndEntityType(tenantId, host.getEntityType())
                .collectList()
                .flatMap(defs -> {
                    for (FieldDefinition def : defs) {
                        Object value = values.get(def.getKey());
                        if (value == null) {
                            if (def.isRequired()) {
                                return Mono.error(new DigiPresBeException(
                                        "Required custom field '" + def.getKey() + "' is missing",
                                        1120, 400));
                            }
                            continue;
                        }
                        DigiPresBeException violation = checkValue(def, value);
                        if (violation != null) return Mono.error(violation);
                    }
                    return Mono.empty();
                });
    }

    private DigiPresBeException checkValue(FieldDefinition def, Object value) {
        return switch (def.getType()) {
            case TEXT -> value instanceof String
                    ? null
                    : typeError(def, "expected string");
            case NUMBER -> value instanceof Number
                    ? null
                    : typeError(def, "expected number");
            case BOOL -> value instanceof Boolean
                    ? null
                    : typeError(def, "expected boolean");
            case DATE -> isParseableDate(value)
                    ? null
                    : typeError(def, "expected ISO-8601 date string");
            case ENUM -> isAllowedEnumValue(def.getOptions(), value)
                    ? null
                    : typeError(def, "value not in allowed options " + def.getOptions());
            case LOOKUP -> isParseableUuid(value)
                    ? null
                    : typeError(def, "LOOKUP expects a UUID string");
        };
    }

    private static boolean isParseableDate(Object value) {
        if (!(value instanceof String s)) return false;
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isAllowedEnumValue(List<String> options, Object value) {
        return value instanceof String s && options != null && options.contains(s);
    }

    private static boolean isParseableUuid(Object value) {
        if (!(value instanceof String s)) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static DigiPresBeException typeError(FieldDefinition def, String detail) {
        return new DigiPresBeException(
                "Custom field '" + def.getKey() + "' (" + def.getType() + "): " + detail,
                1121, 400);
    }
}
