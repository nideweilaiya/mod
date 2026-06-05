package com.aiworkbench.companion.core.brain;

/**
 * How much control the local LLM is allowed to have.
 *
 * <p>Tier 1 is chat only. Tier 2 may return behavior decisions. Tier 3 is the
 * same as tier 2, but decisions are made with long-term memory context.</p>
 */
public enum LLMControlTier {
    CHAT_ONLY(1),
    DECISION(2),
    MEMORY_DECISION(3);

    private final int level;

    LLMControlTier(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean allowsLLMDecision() {
        return this == DECISION || this == MEMORY_DECISION;
    }

    public boolean usesMemory() {
        return this == MEMORY_DECISION;
    }
}
