package com.kumouri.kmodigipresbe.config;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeConvertCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Assigns a random {@link UUID} to an entity's {@code @Id} field if it is null.
 *
 * <p>Spring Data Reactive Mongo's built-in id auto-generation only handles
 * {@code String}, {@code ObjectId}, and {@code BigInteger}. Every entity in this
 * codebase uses {@code @Id UUID id}, so saving one without setting the id first
 * fails at convert time with
 * {@code InvalidDataAccessApiUsageException: Cannot autogenerate id of type
 * java.util.UUID}. Service layers that always supply ids (e.g. via constructors)
 * never observed this; integration tests that save raw builders did.
 *
 * <p>Fires as a {@link ReactiveBeforeConvertCallback} so it runs alongside
 * {@code TenantStampingCallback} and operates on the entity instance directly,
 * before the Mongo converter sees it.
 */
@Component
public class UuidIdAutogenCallback implements ReactiveBeforeConvertCallback<Object> {

    private static final Field NO_FIELD = sentinel();
    private final ConcurrentMap<Class<?>, Field> idFieldCache = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public Mono<Object> onBeforeConvert(@NonNull Object entity, @NonNull String collection) {
        Field idField = idFieldCache.computeIfAbsent(entity.getClass(), UuidIdAutogenCallback::findUuidIdField);
        if (idField == NO_FIELD) {
            return Mono.just(entity);
        }
        try {
            if (idField.get(entity) == null) {
                idField.set(entity, UUID.randomUUID());
            }
        } catch (IllegalAccessException ex) {
            // setAccessible(true) at lookup; should not happen
            throw new IllegalStateException(ex);
        }
        return Mono.just(entity);
    }

    private static Field findUuidIdField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class) && f.getType() == UUID.class) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return NO_FIELD;
    }

    private static Field sentinel() {
        try {
            return UuidIdAutogenCallback.class.getDeclaredField("NO_FIELD");
        } catch (NoSuchFieldException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
