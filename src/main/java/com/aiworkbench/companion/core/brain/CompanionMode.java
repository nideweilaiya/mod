package com.aiworkbench.companion.core.brain;

import java.util.Collections;
import java.util.Set;

/**
 * New-framework behavior modes.
 *
 * <p>These modes are the player-facing switches: follow, chop, mine, chat, and
 * autonomous. They replace the old mutually-exclusive Goal state as the new
 * architecture grows enough capability coverage.</p>
 */
public enum CompanionMode {
    FOLLOW("follow", true, Collections.emptySet()),
    CHOP("chop", false, Set.of("gather_logs")),
    MINE("mine", false, Set.of("gather_ores")),
    CHAT("chat", false, Collections.emptySet()),
    AUTONOMOUS("autonomous", true, Set.of("gather_logs", "gather_ores")),
    IDLE("idle", false, Collections.emptySet());

    private final String id;
    private final boolean followOnIdle;
    private final Set<String> authorizedCapabilities;

    CompanionMode(String id, boolean followOnIdle, Set<String> authorizedCapabilities) {
        this.id = id;
        this.followOnIdle = followOnIdle;
        this.authorizedCapabilities = authorizedCapabilities;
    }

    public String id() {
        return id;
    }

    public boolean followOnIdle() {
        return followOnIdle;
    }

    public Set<String> authorizedCapabilities() {
        return authorizedCapabilities;
    }

    public static CompanionMode fromId(String id) {
        if (id == null || id.isBlank()) return FOLLOW;
        return switch (id.toLowerCase()) {
            case "chop", "wood" -> CHOP;
            case "mine", "ore", "ores" -> MINE;
            case "chat" -> CHAT;
            case "auto", "autonomous" -> AUTONOMOUS;
            case "idle", "stop", "wait" -> IDLE;
            case "follow" -> FOLLOW;
            default -> FOLLOW;
        };
    }
}
