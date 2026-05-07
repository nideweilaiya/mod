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
import java.util.Random;
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
    private static final int INITIAL_RECONNECT_DELAY = 1;  // seconds
    private static final int MAX_RECONNECT_DELAY = 60;    // seconds
    private static final int MAX_RECONNECT_ATTEMPTS = 3;  // Reduced: no server = give up quickly

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
    private volatile long lastPongTime = 0;
    private ScheduledFuture<?> heartbeatFuture;
    private final Random random = new Random();

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
            lastPongTime = System.currentTimeMillis();
            AICompanionMod.LOGGER.info("[Bridge] Connected!");

            // Start reading messages in background
            executor.submit(this::readLoop);

            // Start heartbeat scheduler
            startHeartbeat();

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
        // Exponential backoff with jitter: min(INITIAL * 2^(attempt-1), MAX) + random jitter
        int delay = Math.min(INITIAL_RECONNECT_DELAY * (1 << (reconnectAttempts - 1)), MAX_RECONNECT_DELAY);
        int jitter = random.nextInt(Math.min(delay, 5)); // 0-4s jitter
        int actualDelay = delay + jitter;
        AICompanionMod.LOGGER.info("[Bridge] Reconnect attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " in " + actualDelay + "s (base=" + delay + ")");
        try {
            Thread.sleep(actualDelay * 1000L);
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void disconnect() {
        stopHeartbeat();
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        // Schedule heartbeat every 10 seconds
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (running && connected) {
                sendHeartbeat();
            }
        }, 10, 10, TimeUnit.SECONDS);
        AICompanionMod.LOGGER.info("[Bridge] Heartbeat scheduled");
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
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
     * Send a JSON message to Python via Gson serialization.
     */
    public void send(Map<String, Object> data) {
        if (connected && writer != null && running) {
            writer.println(gson.toJson(data));
        }
    }

    /**
     * Send companion spawned event
     */
    public void sendCompanionSpawned(String companionId, String ownerName, BlockPos pos) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "companion_spawned");
        data.put("companion_id", companionId);
        data.put("owner_name", ownerName);
        data.put("x", pos.getX());
        data.put("y", pos.getY());
        data.put("z", pos.getZ());
        send(data);
    }

    /**
     * Send companion removed event
     */
    public void sendCompanionRemoved(String companionId) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "companion_removed");
        data.put("companion_id", companionId);
        send(data);
    }

    /**
     * Send player interaction event
     */
    public void sendPlayerInteract(String companionId, String playerName, String action) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "player_interact");
        data.put("companion_id", companionId);
        data.put("player_name", playerName);
        data.put("action", action);
        data.put("timestamp", System.currentTimeMillis());
        send(data);
    }

    /**
     * Send position update
     */
    public void sendPositionUpdate(String companionId, BlockPos pos) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "position_update");
        data.put("companion_id", companionId);
        data.put("x", pos.getX());
        data.put("y", pos.getY());
        data.put("z", pos.getZ());
        send(data);
    }

    /**
     * Send heartbeat
     */
    public void sendHeartbeat() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "heartbeat");
        data.put("timestamp", System.currentTimeMillis());
        send(data);
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

        // pong is a simple timestamp update, safe from any thread
        if (msg.type.equals("pong")) {
            lastPongTime = System.currentTimeMillis();
            return;
        }

        // All entity operations MUST run on the Minecraft server thread
        if (AICompanionMod.server == null) return;
        AICompanionMod.server.execute(() -> {
            switch (msg.type) {
                case "move": handleMove(msg); break;
                case "dialogue": handleDialogue(msg); break;
                case "animation": handleAnimation(msg); break;
                case "skin": handleSkin(msg); break;
                case "remove": handleRemove(msg); break;
                case "follow": handleFollow(msg); break;
                default: AICompanionMod.LOGGER.warn("[Bridge] Unknown command: " + msg.type);
            }
        });
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
        if (AICompanionMod.server == null) return;

        // We're already on the server thread (called from server.execute() in handleMessage)
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        String displayText = msg.text
            .replace("&", "§")
            .replace("<", "‹")
            .replace(">", "›");

        Component textComponent = Component.literal("§f" + displayText);
        companion.setCustomName(textComponent);
        companion.setCustomNameVisible(true);

        int durationSecs = (msg.duration != null) ? msg.duration.intValue() : 3;
        int durationTicks = durationSecs * 20;

        // Use TickTask for thread-safe delayed hide on the server thread
        String companionId = msg.companion_id;
        AICompanionMod.server.tell(new net.minecraft.server.TickTask(
            AICompanionMod.server.getTickCount() + durationTicks,
            () -> {
                AutomatonEntity comp = findCompanionById(companionId);
                if (comp != null) {
                    comp.setCustomNameVisible(false);
                    comp.setCustomName(Component.empty());
                }
            }
        ));

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
