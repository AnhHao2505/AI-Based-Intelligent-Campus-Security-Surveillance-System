package com.fa26se040.icss.dto.audit;

import com.fa26se040.icss.entity.User;
import lombok.Getter;

@Getter
public class AuditActor {
    private final String actorType; // "USER" or "SYSTEM"
    private final User user;
    private final String actorSource;

    private AuditActor(String actorType, User user, String actorSource) {
        this.actorType = actorType;
        this.user = user;
        this.actorSource = actorSource;
    }

    public static AuditActor user(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User actor cannot be null");
        }
        return new AuditActor("USER", user, null);
    }

    public static AuditActor system(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("System actor source cannot be blank");
        }
        return new AuditActor("SYSTEM", null, source);
    }
}
