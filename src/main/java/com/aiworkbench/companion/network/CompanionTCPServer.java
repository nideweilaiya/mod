package com.aiworkbench.companion.network;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP Socket Server for Python Communication
 *
 * Uses JSON-over-TCP for simplicity
 * Protocol:
 * - Each message is a JSON object ending with newline
 * - MC -> Python: Events (companion_spawned, player_interact, position_update)
 * - Python -> MC: Commands (move, dialogue, animation, skin, remove, follow)
 */
public class CompanionTCPServer {
    private static final int PORT = 8765;

    private ServerSocket serverSocket;
    private boolean running = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger connectionIdCounter = new AtomicInteger(1);
    private final Gson gson = new GsonBuilder().create();
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Object broadcastLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> pendingDialogueHides = new ConcurrentHashMap<>();

    public CompanionTCPServer() {}

    public void start() {
        if (running) return;
        running = true;
        executor.submit(this::serverLoop);
        AICompanionMod.LOGGER.info("TCP Server started on port " + PORT);
    }

    public void stop() {
        if (!running) return;
        running = false;
        for (ClientHandler handler : clients.values()) {
            try { handler.close(); } catch (IOException ignored) {}
        }
        clients.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            AICompanionMod.LOGGER.warn("Error closing server: " + e.getMessage());
        }
        scheduler.shutdownNow();
        executor.shutdownNow();
        AICompanionMod.LOGGER.info("TCP Server stopped");
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            AICompanionMod.LOGGER.info("Waiting for Python client connection on port " + PORT);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setKeepAlive(true);
                    clientSocket.setTcpNoDelay(true);

                    int connId = connectionIdCounter.getAndIncrement();
                    AICompanionMod.LOGGER.info("Python client connected! Connection ID: " + connId);

                    ClientHandler handler = new ClientHandler(clientSocket, connId);
                    clients.put(connId, handler);
                    executor.submit(() -> handleClient(handler));
                } catch (IOException e) {
                    if (running) {
                        AICompanionMod.LOGGER.warn("Server accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("Failed to start TCP server: " + e.getMessage());
        }
    }

    private void handleClient(ClientHandler handler) {
        try {
            String line;
            BufferedReader in = handler.in;
            while (running && (line = in.readLine()) != null) {
                try {
                    handleCommand(line.trim());
                } catch (Exception e) {
                    AICompanionMod.LOGGER.warn("Failed to handle command: " + line);
                }
            }
        } catch (IOException e) {
            AICompanionMod.LOGGER.info("Client disconnected (Connection ID: " + handler.connectionId + ")");
        } finally {
            clients.remove(handler.connectionId);
            try { handler.close(); } catch (IOException ignored) {}
            AICompanionMod.LOGGER.info("Client removed. Active clients: " + clients.size());
        }
    }

    private void handleCommand(String line) {
        if (line.isEmpty() || !line.startsWith("{") || !line.endsWith("}")) return;

        CommandMessage msg;
        try {
            msg = gson.fromJson(line, CommandMessage.class);
        } catch (JsonSyntaxException e) {
            AICompanionMod.LOGGER.warn("Failed to parse JSON: " + line);
            return;
        }

        if (msg.type == null) return;
        AICompanionMod.LOGGER.info("TCP Received command: " + msg.type);

        switch (msg.type) {
            case "move": handleMove(msg); break;
            case "dialogue": handleDialogue(msg); break;
            case "animation": handleAnimation(msg); break;
            case "skin": handleSkin(msg); break;
            case "remove": handleRemove(msg); break;
            case "follow": handleFollow(msg); break;
            default: AICompanionMod.LOGGER.warn("Unknown command type: " + msg.type);
        }
    }

    private void handleMove(CommandMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        double x = msg.x != null ? msg.x : 0;
        double y = msg.y != null ? msg.y : 0;
        double z = msg.z != null ? msg.z : 0;
        float speed = msg.speed != null ? msg.speed.floatValue() : 1.2f;

        companion.setPos(x, y, z);
        AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " moved to (" + x + ", " + y + ", " + z + ")");
    }

    private void handleDialogue(CommandMessage msg) {
        if (msg.companion_id == null || msg.text == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        // Cancel any pending hide for this companion
        ScheduledFuture<?> existing = pendingDialogueHides.remove(msg.companion_id);
        if (existing != null) existing.cancel(false);

        // Escape HTML-like characters for safe display
        String displayText = msg.text
            .replace("&", "§")  // Support MC color codes
            .replace("<", "‹")
            .replace(">", "›");

        // Build component with §f (white) prefix for visibility
        Component textComponent = Component.literal("§f" + displayText);
        companion.setCustomName(textComponent);
        companion.setCustomNameVisible(true);

        // Determine display duration (default 3 seconds)
        int durationSecs = (msg.duration != null) ? msg.duration.intValue() : 3;

        // Schedule hiding the name
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            companion.setCustomNameVisible(false);
            companion.setCustomName(Component.empty());
            pendingDialogueHides.remove(msg.companion_id);
        }, durationSecs, TimeUnit.SECONDS);
        pendingDialogueHides.put(msg.companion_id, future);

        AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " dialogue displayed: " + displayText);
    }

    private void handleAnimation(CommandMessage msg) {
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
                AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " unknown animation: " + msg.animation);
                return;
        }
        AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " animation played: " + msg.animation);
    }

    private void handleSkin(CommandMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        if (msg.is_random != null && msg.is_random) {
            AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " random skin");
        } else if (msg.player_name != null && !msg.player_name.isEmpty()) {
            companion.setSkinFromPlayer(msg.player_name);
            AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " skin set from player: " + msg.player_name);
        } else if (msg.url != null && !msg.url.isEmpty()) {
            companion.setSkinFromUrl(msg.url);
            AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " skin set to: " + msg.url);
        }
    }

    private void handleRemove(CommandMessage msg) {
        if (msg.companion_id == null) return;
        try {
            UUID companionUUID = UUID.fromString(msg.companion_id);
            if (AICompanionMod.companionManager != null) {
                AICompanionMod.companionManager.removeCompanionById(companionUUID);
                AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " removed from world");
            }
        } catch (IllegalArgumentException e) {
            // Invalid UUID format, try by player name
            AICompanionMod.companionManager.removeCompanionByName(msg.companion_id);
            AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " removed by player name");
        }
    }

    private void handleFollow(CommandMessage msg) {
        if (msg.companion_id == null) return;
        AutomatonEntity companion = findCompanionById(msg.companion_id);
        if (companion == null) return;

        boolean followEnabled = (msg.enable != null) ? msg.enable : true;
        companion.setFollowEnabled(followEnabled);
        AICompanionMod.LOGGER.info("Companion " + msg.companion_id + " follow: " + (followEnabled ? "ENABLED" : "DISABLED"));
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

    // ==================== Send Events to Python ====================

    public void sendAll(String json) {
        synchronized (broadcastLock) {
            for (ClientHandler handler : clients.values()) {
                if (handler.out != null && !handler.out.checkError()) {
                    handler.out.println(json);
                }
            }
        }
    }

    public void onCompanionSpawned(String companionId, String ownerName, BlockPos pos, Level level) {
        String json = String.format(
            "{\"type\":\"companion_spawned\",\"companion_id\":\"%s\",\"owner_name\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
            escapeJson(companionId), escapeJson(ownerName), pos.getX(), pos.getY(), pos.getZ()
        );
        sendAll(json);
    }

    public void onCompanionRemoved(String companionId) {
        String json = String.format("{\"type\":\"companion_removed\",\"companion_id\":\"%s\"}", escapeJson(companionId));
        sendAll(json);
    }

    public void onPlayerInteract(String companionId, String playerName, String action) {
        String json = String.format(
            "{\"type\":\"player_interact\",\"companion_id\":\"%s\",\"player_name\":\"%s\",\"action\":\"%s\",\"timestamp\":%d}",
            escapeJson(companionId), escapeJson(playerName), escapeJson(action), System.currentTimeMillis()
        );
        sendAll(json);
    }

    public void onPositionUpdate(String companionId, BlockPos pos) {
        String json = String.format(
            "{\"type\":\"position_update\",\"companion_id\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
            escapeJson(companionId), pos.getX(), pos.getY(), pos.getZ()
        );
        sendAll(json);
    }

    public void onPlayerQuit(String playerName) {
        String json = String.format(
            "{\"type\":\"player_quit\",\"player_name\":\"%s\",\"timestamp\":%d}",
            escapeJson(playerName), System.currentTimeMillis()
        );
        sendAll(json);
    }

    public void onPlayerChat(String companionId, String playerName, String message) {
        String json = String.format(
            "{\"type\":\"player_chat\",\"companion_id\":\"%s\",\"player_name\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
            escapeJson(companionId), escapeJson(playerName), escapeJson(message), System.currentTimeMillis()
        );
        sendAll(json);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public boolean isConnected() { return !clients.isEmpty(); }
    public int getClientCount() { return clients.size(); }

    // ==================== Inner Classes ====================

    private class ClientHandler {
        final int connectionId;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        ClientHandler(Socket socket, int connectionId) throws IOException {
            this.connectionId = connectionId;
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void close() throws IOException {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private static class CommandMessage {
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
