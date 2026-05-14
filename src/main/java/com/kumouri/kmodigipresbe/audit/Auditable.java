package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;

import java.util.UUID;

/**
 * Marker for entities whose CREATE / UPDATE / DELETE lifecycle is recorded in the
 * {@link AuditEvent} log. The {@link AuditingCallback} subscribes to saves of every
 * {@code Auditable}, loads the prior state, computes per-property diffs via Spring's
 * {@code BeanWrapper}, and writes a corresponding event in the same Reactor chain.
 *
 * <p>{@code Auditable} extends {@link TenantScoped} so audit events inherit the same
 * tenant-isolation guarantees as the entities they describe.
 *
 * <p>Implementors typically just add this interface to their entity class — no
 * additional methods or state are required. Override {@link #getAuditEntityType()}
 * only when a class rename would otherwise break audit-log queries that filter by
 * {@code entityType}.
 *
 * <p>DELETE is not captured automatically: Spring Data MongoDB has no reactive
 * delete callback, so services that delete an Auditable entity must explicitly
 * invoke {@link AuditEventWriter#auditDelete(Auditable)} before the repository call
 * to participate in delete-auditing.
 */
public interface Auditable extends TenantScoped {

    /**
     * Document id. Every Mongo entity in the codebase declares {@code @Id UUID id}
     * with a Lombok-generated getter, so implementors satisfy this contract for
     * free. Declared here so {@link AuditingCallback} and {@link AuditEventWriter}
     * can read the id without reflection.
     */
    UUID getId();

    /**
     * Stable identifier for the entity <em>type</em> stored in
     * {@link AuditEvent#getEntityType()}. Defaults to the implementing class's
     * simple name.
     */
    default String getAuditEntityType() {
        return getClass().getSimpleName();
    }
}
