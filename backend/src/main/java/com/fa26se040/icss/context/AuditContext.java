package com.fa26se040.icss.context;

import com.fa26se040.icss.dto.audit.AuditActor;

import java.util.UUID;

public final class AuditContext {

    private static final ThreadLocal<UUID> CORRELATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<AuditActor> CURRENT_ACTOR = new ThreadLocal<>();

    private AuditContext() {}

    public static UUID getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void setCorrelationId(UUID correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    public static void clearCorrelationId() {
        CORRELATION_ID.remove();
    }

    public static AuditActor getCurrentActor() {
        return CURRENT_ACTOR.get();
    }

    public static void setCurrentActor(AuditActor actor) {
        CURRENT_ACTOR.set(actor);
    }

    public static void clearCurrentActor() {
        CURRENT_ACTOR.remove();
    }

    public static void clear() {
        CORRELATION_ID.remove();
        CURRENT_ACTOR.remove();
    }

    public static <T> T runAsSystem(String source, java.util.function.Supplier<T> task) {
        UUID prevCorrelationId = getCorrelationId();
        AuditActor prevActor = getCurrentActor();
        try {
            setCorrelationId(UUID.randomUUID());
            setCurrentActor(AuditActor.system(source));
            return task.get();
        } finally {
            if (prevCorrelationId != null) {
                setCorrelationId(prevCorrelationId);
            } else {
                clearCorrelationId();
            }
            if (prevActor != null) {
                setCurrentActor(prevActor);
            } else {
                clearCurrentActor();
            }
        }
    }

    public static void runAsSystem(String source, Runnable task) {
        runAsSystem(source, () -> {
            task.run();
            return null;
        });
    }
}
