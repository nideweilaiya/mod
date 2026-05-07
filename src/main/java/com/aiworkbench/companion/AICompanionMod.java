package com.aiworkbench.companion;

import com.aiworkbench.companion.client.CompanionKeyHandler;
import com.aiworkbench.companion.command.CompanionCommands;
import com.aiworkbench.companion.entity.EntityInit;
import com.aiworkbench.companion.event.PlayerEventHandler;
import com.aiworkbench.companion.item.ItemInit;
import com.aiworkbench.companion.item.ModCreativeTab;
import com.aiworkbench.companion.manager.CompanionManager;
import com.aiworkbench.companion.manager.CharacterManager;
import com.aiworkbench.companion.network.CompanionTCPServer;
import com.aiworkbench.companion.network.BridgeClient;
import com.aiworkbench.companion.ai.AIManager;
import com.aiworkbench.companion.memory.CompanionMemoryManager;
import com.aiworkbench.companion.skill.PresetSkillRegistry;
import com.aiworkbench.companion.inventory.CompanionContainer;
import com.aiworkbench.companion.skill.SkillLibrary;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AICompanionMod.MODID)
public class AICompanionMod {
    public static final String MODID = "aicompanion";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger();

    public static MinecraftServer server;
    public static CompanionManager companionManager;
    public static CharacterManager characterManager;
    public static CompanionTCPServer tcpServer;
    public static BridgeClient bridgeClient;
    public static AIManager aiManager;
    public static SkillLibrary skillLibrary;
    public static CompanionMemoryManager memoryManager;

    // MenuType registration
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

    @SuppressWarnings("null")
    public static final java.util.function.Supplier<MenuType<CompanionContainer>> COMPANION_CONTAINER =
        MENU_TYPES.register("companion_container",
            () -> IForgeMenuType.create((containerId, inv, data) -> {
                // data 中有 companion 的 entityId（由 openMenu 自动写入）
                int entityId = data.readInt();
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return new CompanionContainer(containerId, inv, null);
                net.minecraft.world.entity.Entity entity = mc.level.getEntity(entityId);
                if (entity instanceof com.aiworkbench.companion.entity.AutomatonEntity companion) {
                    return new CompanionContainer(containerId, inv, companion);
                }
                return new CompanionContainer(containerId, inv, null);
            }));

    public AICompanionMod() {
        // Register entity types, items, creative tab, and menu types on the MOD bus
        EntityInit.register(FMLJavaModLoadingContext.get().getModEventBus());
        ItemInit.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModCreativeTab.register(FMLJavaModLoadingContext.get().getModEventBus());
        MENU_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PlayerEventHandler());

        printStartupBanner();
    }

    private void printStartupBanner() {
        LOGGER.info("");
        LOGGER.info("============================================================");
        LOGGER.info("              AI Companion Mod - Starting Up                 ");
        LOGGER.info("============================================================");
        LOGGER.info("  Mod ID:     {}", MODID);
        LOGGER.info("  Version:    {}", VERSION);
        LOGGER.info("  MC Version: 1.20.4");
        LOGGER.info("  Forge:      49.0.30");
        LOGGER.info("  Java:       {}", System.getProperty("java.version"));
        LOGGER.info("  Platform:   {}", FMLLoader.getDist().isClient() ? "CLIENT" : "SERVER");
        LOGGER.info("============================================================");
        LOGGER.info("");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Setup] Phase 1: Common Setup Starting");

        // Entity types are registered in the Mod constructor (CONSTRUCT phase).
        // Entity attributes are registered via EntityAttributeCreationEvent (see EntityAttributeEvents.java).
        // Both happen BEFORE this event.

        LOGGER.info("[Setup] Initializing CompanionManager...");
        companionManager = new CompanionManager();

        LOGGER.info("[Setup] Initializing CharacterManager...");
        characterManager = new CharacterManager();
        characterManager.createDefaultCharacter("default_companion");

        LOGGER.info("[Setup] TCP Server: Reserved for Phase 5");
        LOGGER.info("[Setup] Phase 1 Complete");
        LOGGER.info("[Setup] TCP Server will start when server begins");

        LOGGER.info("[Setup] Initializing AIManager...");
        aiManager = new AIManager();
        LOGGER.info("[Setup] AI Manager ready");

        LOGGER.info("[Setup] Initializing SkillLibrary...");
        skillLibrary = new SkillLibrary();
        PresetSkillRegistry.registerAll(skillLibrary);
        LOGGER.info("[Setup] SkillLibrary initialized with presets");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("[Client] Phase 2: Client Setup Starting");
        LOGGER.info("[Client] Registering key handler...");
        event.enqueueWork(() -> {
            CompanionKeyHandler.registerForgeEvents();
            LOGGER.info("[Client] Key handler registered on forge bus");
        });
        LOGGER.info("[Client] Phase 2 Complete");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Server] Server starting...");
        server = event.getServer();
        LOGGER.info("[Server] Server reference captured");

        // Load companion config
        CompanionConfig.load(server);
        LOGGER.info("[Server] Companion config loaded");

        // Initialize skill library with world directory for JSON persistence
        if (skillLibrary != null) {
            skillLibrary.setWorldDirectory(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile());
            LOGGER.info("[Server] SkillLibrary world directory set");
        }

        // Start TCP server for Python communication
        tcpServer = new CompanionTCPServer();
        tcpServer.start();
        LOGGER.info("[Server] TCP Server started on port 8765");

        // Start Bridge Client to Python bridge (port 8767)
        bridgeClient = new BridgeClient();
        bridgeClient.start();
        LOGGER.info("[Server] Bridge Client started, connecting to Python on port 8767");

        // Initialize Memory Manager
        memoryManager = new CompanionMemoryManager(
            server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        LOGGER.info("[Server] Memory Manager initialized");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[Server] Server stopping...");
        if (tcpServer != null) {
            tcpServer.stop();
            tcpServer = null;
        }
        LOGGER.info("[Server] TCP Server stopped");

        if (bridgeClient != null) {
            bridgeClient.close();
            bridgeClient = null;
            LOGGER.info("[Server] Bridge Client stopped");
        }

        // Save companion config
        if (server != null) {
            CompanionConfig.save(server);
            LOGGER.info("[Server] Companion config saved");
        }

        // Close memory manager
        if (memoryManager != null) {
            memoryManager.close();
            memoryManager = null;
            LOGGER.info("[Server] Memory Manager closed");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("[Commands] Registering companion commands");
        CompanionCommands.register(event.getDispatcher());
    }

    public static void logEntityDebug(String message) {
        LOGGER.info("[Entity] {}", message);
    }

    public static void logNetworkDebug(String message) {
        LOGGER.info("[Network] {}", message);
    }

    public static void logAIDebug(String message) {
        LOGGER.info("[AI] {}", message);
    }
}
