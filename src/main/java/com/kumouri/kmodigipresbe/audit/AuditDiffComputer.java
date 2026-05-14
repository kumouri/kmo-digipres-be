package com.kumouri.kmodigipresbe.audit;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Computes property-level diffs between a prior and current entity using Spring's
 * {@link BeanWrapper}. Pure / stateless / null-safe.
 *
 * <p>Server-managed and noise properties ({@code version}, {@code createdAt},
 * {@code updatedAt}, audit metadata getters) are skipped so the diff list reflects
 * domain changes only. The skip list is conservative — properties that genuinely
 * matter to history (e.g. {@code stageChangedAt}) are included; those that mechanically
 * change on every save and add no audit value (the {@code @Version} counter, audit
 * helper methods) are not.
 */
public final class AuditDiffComputer {

    private static final Set<String> IGNORED_PROPERTIES = Set.of(
            "class",
            "version",
            "createdAt",
            "updatedAt",
            "auditEntityType");

    private AuditDiffComputer() {
    }

    public static List<FieldDiff> diff(Object prior, Object current) {
        if (prior == null || current == null) {
            return List.of();
        }
        BeanWrapper p = new BeanWrapperImpl(prior);
        BeanWrapper c = new BeanWrapperImpl(current);
        List<FieldDiff> out = new ArrayList<>();
        for (PropertyDescriptor pd : p.getPropertyDescriptors()) {
            String name = pd.getName();
            if (IGNORED_PROPERTIES.contains(name)) continue;
            if (pd.getReadMethod() == null) continue;
            Object before = readSafe(p, name);
            Object after = readSafe(c, name);
            if (!Objects.equals(before, after)) {
                out.add(new FieldDiff(name, before, after));
            }
        }
        return out;
    }

    private static Object readSafe(BeanWrapper bw, String property) {
        try {
            return bw.getPropertyValue(property);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
