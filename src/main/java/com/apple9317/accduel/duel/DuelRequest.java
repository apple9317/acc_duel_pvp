package com.apple9317.accduel.duel;

import java.util.UUID;

/**
 * 决斗请求（携带竞技类型）。
 */
public class DuelRequest {

    public final UUID requester;
    public final UUID target;
    public final String type;
    public final long expiresAt;

    public DuelRequest(UUID requester, UUID target, String type, long timeoutSeconds) {
        this.requester = requester;
        this.target = target;
        this.type = type;
        this.expiresAt = System.currentTimeMillis() + timeoutSeconds * 1000;
    }

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
