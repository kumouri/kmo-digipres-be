package com.kumouri.kmodigipresbe.tenancy.permissions;

import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.extension.FieldDefinition;
import com.kumouri.kmodigipresbe.extension.FieldDefinitionRepository;
import com.kumouri.kmodigipresbe.repository.FieldPermissionPolicyRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.beans.PropertyDescriptor;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reactive serialization-time field-permission enforcement.
 *
 * <p>Phase 9h ships the redactor + admin endpoints + the {@code visibilityRoles}
 * slot on {@link FieldDefinition}. WebFlux's Jackson encoder doesn't expose a
 * per-request filter hook that can yield to Reactor for policy/role lookup, so
 * the enforcement model is <em>opt-in</em>: controllers that want field
 * redaction call {@link #redact} and return the resulting Map instead of the
 * entity. {@code DealController} shows the pattern; the rest migrate when
 * field-permission policies grow beyond a smoke-test.
 *
 * <h2>Rule resolution</h2>
 * For every property of the entity:
 * <ol>
 *   <li>If a {@link FieldPermissionPolicy} rule names {@code (entityType,
 *       field)} and {@code allowedRoles} is non-empty: keep the property only
 *       if the current user's roles intersect {@code allowedRoles}.</li>
 *   <li>For custom fields under {@code customFields}: consult
 *       {@link FieldDefinition#getVisibilityRoles()} too.</li>
 *   <li>Else: include the property unchanged.</li>
 * </ol>
 *
 * <p>The redacted map omits restricted properties entirely — the client sees
 * them as <em>absent</em>, not as null. Matches the §5 Phase 9 acceptance
 * criterion 8 phrasing.
 */
@Service
@RequiredArgsConstructor
public class FieldPermissionRedactor {

    private final FieldPermissionPolicyRepository policies;
    private final FieldDefinitionRepository fieldDefinitions;

    public <T> Mono<Map<String, Object>> redact(T entity, String entityType) {
        if (entity == null) return Mono.empty();
        return TenantContextHolder.required()
                .flatMap(ctx -> loadRules(ctx)
                        .flatMap(rulesByField -> loadCustomFieldVis(ctx, entityType)
                                .map(customVis -> doRedact(entity, entityType, ctx.roles(),
                                        rulesByField, customVis))));
    }

    private Mono<Map<String, List<String>>> loadRules(TenantContext ctx) {
        return policies.findByTenantId(ctx.tenantId())
                .map(p -> rulesByEntityField(p, null))
                .defaultIfEmpty(Map.of());
    }

    private static Map<String, List<String>> rulesByEntityField(FieldPermissionPolicy policy, String filterEntityType) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (policy == null || policy.getRules() == null) return out;
        for (FieldPermissionRule r : policy.getRules()) {
            if (filterEntityType != null && !filterEntityType.equals(r.getEntityType())) continue;
            out.put(r.getEntityType() + "." + r.getField(),
                    r.getAllowedRoles() == null ? List.of() : r.getAllowedRoles());
        }
        return out;
    }

    private Mono<Map<String, List<String>>> loadCustomFieldVis(TenantContext ctx, String entityType) {
        return fieldDefinitions.findAllByTenantIdAndEntityType(ctx.tenantId(), entityType)
                .collectList()
                .map(defs -> {
                    Map<String, List<String>> out = new LinkedHashMap<>();
                    for (FieldDefinition d : defs) {
                        List<String> vr = d.getVisibilityRoles();
                        if (vr != null && !vr.isEmpty()) {
                            out.put(d.getKey(), vr);
                        }
                    }
                    return out;
                });
    }

    private static Map<String, Object> doRedact(Object entity,
                                                String entityType,
                                                Set<String> userRoles,
                                                Map<String, List<String>> rulesByField,
                                                Map<String, List<String>> customFieldVis) {
        Set<String> roles = userRoles == null ? Set.of() : userRoles;
        BeanWrapper bw = new BeanWrapperImpl(entity);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (PropertyDescriptor pd : bw.getPropertyDescriptors()) {
            String name = pd.getName();
            if ("class".equals(name)) continue;
            if (pd.getReadMethod() == null) continue;
            String ruleKey = entityType + "." + name;
            List<String> allowed = rulesByField.get(ruleKey);
            if (allowed != null && !allowed.isEmpty() && !hasAnyRole(roles, allowed)) {
                continue; // restricted top-level field — omit
            }
            Object value = bw.getPropertyValue(name);
            if ("customFields".equals(name) && entity instanceof CustomFieldHost) {
                value = redactCustomFields(value, customFieldVis, roles);
            }
            out.put(name, value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactCustomFields(Object raw,
                                                          Map<String, List<String>> customFieldVis,
                                                          Set<String> roles) {
        if (!(raw instanceof Map<?, ?> in) || in.isEmpty()) return Map.of();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String key = String.valueOf(e.getKey());
            List<String> allowed = customFieldVis.get(key);
            if (allowed != null && !allowed.isEmpty() && !hasAnyRole(roles, allowed)) continue;
            out.put(key, e.getValue());
        }
        return out;
    }

    private static boolean hasAnyRole(Set<String> userRoles, List<String> allowedRoles) {
        Set<String> intersect = new HashSet<>(userRoles);
        intersect.retainAll(allowedRoles);
        return !intersect.isEmpty();
    }
}
