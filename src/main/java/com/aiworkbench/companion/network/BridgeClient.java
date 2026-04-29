package com.aiworkbench.companion.network;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP Client for connecting to Python Bridge Server
 * Handles:
 * - Sending events to Python (companion_spawned, position_update, etc.)
 * - Receiving commands from Python (move, dialogue, animation, etc.)
 *
 * Note: Uses simple blocking TCP + JSON, same protocol as CompanionTCPServer
 */
public class BridgeClient implements AutoCloseable {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8767; // Different from CompanionTCPServer
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final String host;
    private final int port;
    private final Gson gson = new GsonBuilder().create();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private volatile boolean connected = false;
    private volatile boolean running = false;
    private int reconnectAttempts = 0;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> pendingDialogueHides = new ConcurrentHashMap<>();

    public BridgeClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public BridgeClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Start the bridge client connection
     */
    public void start() {
        if (running) return;
        running = true;
        connect();
    }

    /**
     * Stop the bridge client
     */
    @Override
    public void close() {
        running = false;
        connected = false;
        disconnect();
        scheduler.shutdownNow();
        executor.shutdownNow();
        AICompanionMod.LOGGER.info("[Bridge] Client stopped");
    }

    private void connect() {
        if (!running) return;

        try {
            AICompanionMod.LOGGER.info("[Bridge] Connecting to " + host + ":" + port);

            socket = new Socket(host, port);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            connected = true;
            reconnectAttempts = 0;
            AICompanionMod.LOGGER.info("[Bridge] Connected!");

            // Start reading messages in background
            executor.submit(this::readLoop);

        } catch (IOException e) {
            AICompanionMod.LOGGER.warn("[Bridge] Failed to connect: " + e.getMessage());
            connected = false;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                AICompanionMod.LOGGER.warn("[Bridge] Max reconnect attempts reached, giving up");
            }
            return;
        }
        reconnectAttempts++;
        AICompanionMod.LOGGER.info("[Bridge] Scheduling reconnect attempt " + reconnectAttempts + " in " + RECONNECT_DELAY_SECONDS + "s");
        try {
            Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void disconnect() {
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    private void readLoop() {
        String line;
        try {
            while (running && connected && (line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (IOException e) {
            if (running) {
                AICompanionMod.LOGGER.warn("[Bridge] Read error: " + e.getMessage());
                connected = false;
                scheduleReconnect();
            }
        }
    }

    /**
     * Send a JSON message to Python
     */
    public void send(String json) {
        if (connected && writer != null && running) {
            writer.println(json);
        }
    }

    /**
     * Send companion spawned event
     */
    public void sendCompanionSpawned(String companionId, String ownerName, BlockPos pos) {
        String json = String.format(
            "{\"type\":\"companion_spawned\",\"companion_id\":\"%s\",\"owner_name\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
            companionId, ownerName, pos.getX(), pos.getY(), pos.getZ()
        );
        send(json);
    }

    /**
     * Send companion removed event
     */
    public void sendCompanionRemoved(String companionId) {
        String json = String.format(
            "{\"type\":\"companion_removed\",\"companion_id\":\"%s\"}",
            companionId
        );
        send(json);
    }

    /**
     * Send player interaction event
     */
    public void sendPlayerInteract(String companionId, String playerName, String action) {
        String json = String.format(
            "{\"type\":\"player_interact\",\"companion_id\":\"%s\",\"player_name\":\"%s\",\"action\":\"%s\",\"timestamp\":%d}",
            companionId, playerName, action, System.currentTimeMillis()
        );
        send(json);
    }

    /**
     * Send position update
     */
    public void sendPositionUpdate(String companionId, BlockPos pos) {
        String json = String.format(
            "{\"type\":\"position_update\",\"companion_id\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
            companionId, pos.getX(), pos.getY(), pos.getZ()
        );
        send(json);
    }

    /**
     * Send heartbeat
     */
    public void sendHeartbeat() {
        String json = String.format(
            "{\"type\":\"heartbeat\",\"timestamp\":%d}",
            System.currentTimeMillis()
        );
        send(json);
    }

    /**
     * Handle incoming message from Python
     */
    private void handleMessage(String text) {
        if (text == null || text.isEmpty()) return;

        BridgeMessage msg;
        try {
            msg = gson.fromJson(text, BridgeMessage.class);
        } catch (JsonSyntaxException e) {
            AICompanionMod.LOGGER.warn("[Bridge] Failed to parse message: " + text);
            return;
        }

        if (msg.type == null) return;

        AICompanionMod.LOGGER.debug("[Bridge] Received: " + msg.type);

        switch (msg.type) {
            case "move": handleMove(msg); break;
            case "dialogue": handleDialogue(msg); break;
            case "animation": handleAnimation(msg); break;
            case "skin": handleSkin(msg); break;
            case "remove": handleRemove(msg); break;
            case "follow": handleFollow(msg); break;
            case "pong": /* heartbeat response, ignore */ break;
            default: AICompanionMod.LOGGER.warn("[Bridge] Unknown command: " + msg.type);
        }
    }

    private void handleMove(BridgeMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        double x = msg.x != null ? msg.x : 0;
        double y = msg.y != null ? msg.y : 0;
        double z = msg.z != null ? msg.z : 0;

        companion.setPos(x, y, z);
        AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " moved to (" + x + ", " + y + ", " + z + ")");
    }

    private void handleDialogue(BridgeMessage msg) {
        if (msg.companion_id == null || msg.text == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        // Cancel any pending hide for this companion
        ScheduledFuture<?> existing = pendingDialogueHides.remove(msg.companion_id);
        if (existing != null) existing.cancel(false);

        String displayText = msg.text
            .replace("&", "§")
            .replace("<", "‹")
            .replace(">", "›");

        Component textComponent = Component.literal("§f" + displayText);
        companion.setCustomName(textComponent);
        companion.setCustomNameVisible(true);

        int durationSecs = (msg.duration != null) ? msg.duration.intValue() : 3;

        // Schedule entity modification on main server thread for thread safety
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (AICompanionMod.server != null) {
                AICompanionMod.server.execute(() -> {
                    companion.setCustomNameVisible(false);
                    companion.setCustomName(Component.empty());
                    pendingDialogueHides.remove(msg.companion_id);
                });
            }
        }, durationSecs, TimeUnit.SECONDS);
        pendingDialogueHides.put(msg.companion_id, future);

        AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " dialogue displayed: " + displayText);
    }

    private void handleAnimation(BridgeMessage msg) {
        if (msg.companion_id == null || msg.animation == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        switch (msg.animation.toLowerCase()) {
            case "attack":
            case "swing":
                companion.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                break;
            case "hurt":
                companion.animateHurt(0.0f);
                break;
            case "jump":
                companion.getJumpControl().jump();
                break;
            default:
                AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " unknown animation: " + msg.animation);
                return;
        }
        AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " animation played: " + msg.animation);
    }

    private void handleSkin(BridgeMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        if (msg.is_random != null && msg.is_random) {
            AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " random skin");
        } else if (msg.player_name != null && !msg.player_name.isEmpty()) {
            companion.setSkinFromPlayer(msg.player_name);
            AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " skin set from player: " + msg.player_name);
        } else if (msg.url != null && !msg.url.isEmpty()) {
            companion.setSkinFromUrl(msg.url);
            AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " skin set to: " + msg.url);
        }
    }

    private void handleRemove(BridgeMessage msg) {
        if (msg.companion_id == null) return;
        try {
            UUID companionUUID = UUID.fromString(msg.companion_id);
            if (AICompanionMod.companionManager != null) {
                AICompanionMod.companionManager.removeCompanionById(companionUUID);
                AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " removed from world");
            }
        } catch (IllegalArgumentException e) {
            // Fallback to name-based removal with null check
            if (AICompanionMod.companionManager != null) {
                AICompanionMod.companionManager.removeCompanionByName(msg.companion_id);
                AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " removed by player name");
            }
        }
    }

    private void handleFollow(BridgeMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        boolean followEnabled = (msg.enable != null) ? msg.enable : true;
        companion.setFollowEnabled(followEnabled);
        AICompanionMod.LOGGER.info("[Bridge] Companion " + msg.companion_id + " follow: " + (followEnabled ? "ENABLED" : "DISABLED"));
    }

    private AutomatonEntity findCompanionById(String companionId) {
        if (AICompanionMod.companionManager == null || companionId == null) return null;
        try {
            UUID companionUUID = UUID.fromString(companionId);
            return AICompanionMod.companionManager.getCompanionById(companionUUID);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    // ==================== Message Classes ====================

    private static class BridgeMessage {
        String type;
        String companion_id;
        Double x, y, z, speed;
        String text;
        Double duration;
        Boolean typewriter;
        String animation;
        String url;
        String player_name;
        Boolean is_random;
        Boolean enable;
    }
}
