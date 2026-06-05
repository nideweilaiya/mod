package com.aiworkbench.companion.ai;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AI brain instances for each companion
 */
public class AIManager {
    private final Map<UUID, CompanionAI> aiInstances = new ConcurrentHashMap<>();

    /**
     * Get or create AI for a companion
     */
    public CompanionAI getAI(AutomatonEntity companion) {
        UUID id = companion.getUUID();
        return aiInstances.computeIfAbsent(id, uuid -> {
            String ownerName = companion.getOwnerUUID() != null ?
                getPlayerName(companion.getOwnerUUID()) : "Player";
            AICompanionMod.LOGGER.info("Creating AI brain for companion " + id);
            return new CompanionAI(id.toString(), ownerName);
        });
    }

    /**
     * Remove AI for a companion
     */
    public void removeAI(UUID companionId) {
        CompanionAI ai = aiInstances.remove(companionId);
        if (ai != null) {
            ai.shutdown();
            AICompanionMod.LOGGER.info("Removed AI brain for companion " + companionId);
        }
    }

    /**
     * Check if companion has AI
     */
    public boolean hasAI(UUID companionId) {
        return aiInstances.containsKey(companionId);
    }

    private String getPlayerName(UUID playerId) {
        if (AICompanionMod.server == null) return "Player";
        var player = AICompanionMod.server.getPlayerList().getPlayer(playerId);
        return player != null ? player.getName().getString() : "Player";
    }

    /**
     * Shutdown all AI instances
     */
    public void shutdownAll() {
        for (CompanionAI ai : aiInstances.values()) {
            ai.shutdown();
        }
        aiInstances.clear();
        AICompanionMod.LOGGER.info("All AI brains shut down");
    }
}
