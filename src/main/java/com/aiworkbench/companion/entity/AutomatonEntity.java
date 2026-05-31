package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.OllamaClient;
import com.aiworkbench.companion.ai.DialogueStack;
import com.aiworkbench.companion.ai.DialogueStack.PendingMessage;
import com.aiworkbench.companion.ai.DialogueStack.MessagePriority;
import com.aiworkbench.companion.ai.PerceptionEngine;
import com.aiworkbench.companion.ai.TaskQueue;
import com.aiworkbench.companion.manager.CompanionManager;
import com.aiworkbench.companion.manager.CompanionRole;
import com.aiworkbench.companion.personality.CompanionPersonality;
import com.aiworkbench.companion.entity.goal.AutoUpgrader;
import com.aiworkbench.companion.entity.goal.CompanionFollowGoal;
import com.aiworkbench.companion.skill.AutoCurriculum;
import com.aiworkbench.companion.skill.AutoCurriculum.CurriculumProposal;
import com.aiworkbench.companion.skill.Skill;
import com.aiworkbench.companion.skill.SkillEngine;
import com.aiworkbench.companion.entity.goal.CompanionWanderGoal;
import com.aiworkbench.companion.entity.goal.JumpGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.core.NonNullList;
import com.aiworkbench.companion.inventory.CompanionContainer;

public class AutomatonEntity extends PathfinderMob implements net.minecraft.world.MenuProvider {
    // ==================== Data Accessors ====================

    private static final EntityDataAccessor<String> DATA_CUSTOM_NAME =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_IS_ESSENTIAL =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_CHARACTER_ID =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.STRING);
    // Skin type: 0=default, 1=url, 2=player_name
    private static final EntityDataAccessor<Integer> DATA_SKIN_TYPE =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_SKIN_VALUE =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.STRING);

    // Working mode string for HUD sync (auto-synced to clients via EntityDataAccessor)
    private static final EntityDataAccessor<String> DATA_WORKING_MODE =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.STRING);

    // Owner UUID for client-side sync (auto-synced via EntityDataAccessor)
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    // Level & XP data accessors (auto-synced to clients)
    private static final EntityDataAccessor<Integer> DATA_LEVEL =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_XP =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_XP_TO_NEXT =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AVAILABLE_POINTS =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STRENGTH_POINTS =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_VITALITY_POINTS =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SPEED_POINTS =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DEFENSE_POINTS =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ACTION_TEXT =
        SynchedEntityData.defineId(AutomatonEntity.class, EntityDataSerializers.STRING);
    private int strengthPoints = 0;   // 攻击力
    private int vitalityPoints = 0;   // 生命力
    private int speedPoints = 0;      // 移动速度
    private int defensePoints = 0;    // 护甲
    private static final int POINTS_PER_LEVEL = 3;
    private static final int MAX_SPEED_POINTS = 20;
    private static final int MAX_ATTACK_POINTS = 20;
    private static final int MAX_VITALITY_POINTS = 20;
    private static final int MAX_DEFENSE_POINTS = 20;

    /** Clean base movement speed. Set by applyStatAllocation. */
    private double baseMoveSpeed = 0.1;

    // ==================== Fields ====================

    private UUID ownerUUID;
    private String characterId = "";
    private boolean isEssential = true;
    private float followDistance = 2.5f;
    private float homeRadius = 10.0f;
    private BlockPos homePos;
    private int tickCount = 0;
    // Skin: 0=default, 1=url, 2=player_name
    private int skinType = 0;
    private String skinValue = "";

    // ==================== Unified State Machine ====================
    /** @deprecated v3.0: 使用 CapabilityScheduler 替代互斥枚举 */
    @Deprecated
    public enum CompanionState {
        IDLE, FOLLOW, GUARD, GATHER, FARM, PATROL, SURVIVAL
    }
    /** @deprecated v3.0: 委托给 scheduler */
    @Deprecated
    private CompanionState currentState = CompanionState.FOLLOW;
    private CompanionState preCombatState = CompanionState.FOLLOW;

    // v3.0: 新能力系统（位掩码 + 三层调度）
    private final CapabilityScheduler scheduler = new CapabilityScheduler();

    // ==================== Guard Mode (LivingEntity Combat) ====================
    private net.minecraft.world.entity.LivingEntity guardTarget = null;
    private static final double ATTACK_REACH = 3.0D;  // Melee attack reach (increased for better hitting)

    // ==================== Auto-Defend (Always Active) ====================
    @Nullable
    private net.minecraft.world.entity.LivingEntity autoDefendTarget = null;
    private int autoDefendTicks = 0;

    // ==================== Gather Mode (智能统一采集) ====================
    private String gatherFilter = "all"; // "all", "ores", "wood"
    private final java.util.Set<String> gatherPriorityResources = new java.util.HashSet<>();
    private String gatherTargetBlock = ""; // 指定采集目标方块ID，如 "minecraft:iron_ore"

    // ==================== Farm Mode (作物种植) ====================

    // ==================== 感知缓存（避免重复O(n³)扫描）====================
    private PerceptionEngine.PerceptionData cachedPerception;
    private long lastPerceptionTick;

    private PerceptionEngine.PerceptionData getOrGatherPerception() {
        long now = level().getGameTime();
        if (cachedPerception != null && now - lastPerceptionTick < 30) { // 1.5秒缓存
            return cachedPerception;
        }
        cachedPerception = PerceptionEngine.gatherPerception(this);
        lastPerceptionTick = now;
        return cachedPerception;
    }

    // ==================== Dialogue Display ====================
    private String dialogueText = "";
    private int dialogueEndTick = 0;
    private static final int DEFAULT_DIALOGUE_DURATION_TICKS = 100; // ~5 seconds
    private long lastSituationWarningTick = 0;
    private String lastDangerType = null; // 同类型危险30秒内不重复提醒
    // ==================== Active Decision State Machine ====================
    private String currentQuestion = null;      // Current active question (null = no question)
    private int questionEndTick = 0;            // Tick when question should disappear
    private int questionCooldown = 0;           // Cooldown ticks before next question (prevents spam)
    private static final int QUESTION_DURATION_TICKS = 60;    // 3 seconds for questions
    private static final int QUESTION_COOLDOWN_TICKS = 200;   // 10 seconds cooldown between questions
    private static final int MIN_TICKS_BETWEEN_QUESTIONS = 200; // 10 seconds global cooldown

    // Last question type shown (to avoid repeating same question)
    private String lastQuestionType = null;
    private int ticksSinceLastQuestion = 0;

    // ==================== Follow Goal Reference ====================
    private CompanionFollowGoal followGoal;

    // ==================== Visibility (Hide Command) ====================
    private boolean hidden = false;

    // ==================== Mode Toggle Cooldown (F Key) ====================
    private int modeToggleCooldown = 0;
    private static final int MODE_TOGGLE_COOLDOWN_TICKS = 20; // 1 second debounce

    // ==================== Stop Command ====================
    private boolean movementStopped = false;
    private int stopToggleCooldown = 0;
    private static final int STOP_COOLDOWN_TICKS = 40;

    // ==================== Patrol Mode ====================
    private BlockPos patrolCenter = null;
    private static final float PATROL_RADIUS = 8.0f;

    // ==================== Auto-Pickup Settings ====================
    private boolean autoPickupEnabled = true;
    private double pickupRadius = 5.0;
    private boolean pickupOnlyValuable = false;

    // ==================== Chat Message Toggle ====================
    private boolean chatMsgEnabled = true;

    // ==================== Manual Equip Cooldown ====================
    private long lastManualEquipTick = 0; // 玩家手动装备的时间戳，防止autoEquip立即覆盖
    private boolean suppressAutoEquip = false; // 背包打开期间暂停自动装备
    private long autoUpgradeLastCheckTick = 0;

    // ==================== Inventory ====================
    private static final int INVENTORY_SIZE = 27;  // 3 rows x 9 columns
    private NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // ==================== Health & Regen ====================
    private int lastHurtTime = -200; // Tick when last damaged (negative = full health at start)
    private boolean respawnPending = false; // Prevent double-respawn scheduling

    // ==================== Hunger System ====================
    private static final int MAX_HUNGER = 20;
    private static final float MAX_SATURATION = 20.0f;
    private int hungerLevel = MAX_HUNGER;
    private float saturationLevel = 5.0f;
    private float exhaustionLevel = 0.0f;
    private int hungerTickCounter = 0;
    private int eatingTicks = 0; // 进食冷却，模拟玩家1.6秒进食时间

    // ==================== AI Spontaneous Dialogue ====================
    private int aiSpontaneousCooldown = 0; // ticks until next AI idle speech
    private static final int AI_SPONTANEOUS_MIN = 2400; // 2 minutes minimum
    private static final int AI_SPONTANEOUS_MAX = 4800; // 4 minutes maximum

    // ==================== Recent Events ====================
    private final java.util.LinkedList<RecentEvent> recentEvents = new java.util.LinkedList<>();
    private static final int MAX_RECENT_EVENTS = 8;
    private long lastEventDialogueTick = 0;
    private static final long EVENT_DIALOGUE_COOLDOWN_TICKS = 200; // 10 seconds
    private boolean wasDaytime = true; // Track day→night transition

    // Navigation timeout (prevent infinite stuck)
    private BlockPos lastNavCheckPos = BlockPos.ZERO;
    private int navStuckTicks = 0;

    // ==================== Skill Engine ====================
    private final SkillEngine skillEngine = new SkillEngine();
    private volatile boolean skillActive = false;
    // v2.4.1: 采集活跃标志（BTreeGatherGoal 设置），抑制对话打断
    volatile boolean activelyGathering = false;

    // ==================== AutoCurriculum / Autonomous Mode ====================
    private CompanionRole companionRole = CompanionRole.GENERAL;
    private CompanionPersonality personality = new CompanionPersonality();
    private boolean autonomousMode = false;
    private int curriculumTickCounter = 0;            // 课程评估计数器
    private static final int CURRICULUM_EVAL_INTERVAL = 600; // 每30秒评估一次（600 ticks）
    private CurriculumProposal pendingProposal = null; // 当前待处理的课程提议（手动模式，兼容旧逻辑）
    private final TaskQueue taskQueue = new TaskQueue(); // 任务队列（自主模式）
    private final DialogueStack dialogueStack = new DialogueStack(); // 对话消息栈

    // ==================== 新框架（P0-P5 闭环） ====================
    private boolean useNewFramework = false; // 标志位：true=新框架接管 tick
    private String newFrameworkMode = null; // null=跟随, "chop"=砍树, "mine"=挖矿
    private final Set<String> authorizedCapabilities = new HashSet<>(); // 授权的能力ID集合
    private final Map<BlockPos, Integer> failedTargets = new java.util.LinkedHashMap<>(); // 失败目标→失败次数，防止死循环
    private BlockPos lastAttemptedTarget; // 最近尝试采集的目标位置
    private com.aiworkbench.companion.core.brain.CoreBrain coreBrain;
    private com.aiworkbench.companion.core.action.ActionExecutor actionExecutor;
    private com.aiworkbench.companion.core.decision.RuleBasedDecisionMaker ruleDecisionMaker;
    private int perceptionCooldown; // 感知降频
    private int decisionCooldown;   // 决策冷却（防抖动）

    // ==================== Valuable Items (Pickup Filter) ====================

    private static final java.util.Set<net.minecraft.world.item.Item> VALUABLE_ITEMS = java.util.Set.of(
        net.minecraft.world.item.Items.DIAMOND,
        net.minecraft.world.item.Items.EMERALD,
        net.minecraft.world.item.Items.GOLD_INGOT,
        net.minecraft.world.item.Items.IRON_INGOT,
        net.minecraft.world.item.Items.NETHERITE_SCRAP,
        net.minecraft.world.item.Items.NETHERITE_INGOT,
        net.minecraft.world.item.Items.ANCIENT_DEBRIS,
        net.minecraft.world.item.Items.DIAMOND_BLOCK,
        net.minecraft.world.item.Items.EMERALD_BLOCK,
        net.minecraft.world.item.Items.GOLD_BLOCK,
        net.minecraft.world.item.Items.IRON_BLOCK,
        net.minecraft.world.item.Items.NETHERITE_BLOCK,
        net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,
        net.minecraft.world.item.Items.GOLDEN_APPLE,
        net.minecraft.world.item.Items.ENDER_PEARL,
        net.minecraft.world.item.Items.ENDER_EYE,
        net.minecraft.world.item.Items.BLAZE_ROD,
        net.minecraft.world.item.Items.WITHER_SKELETON_SKULL,
        net.minecraft.world.item.Items.TRIDENT,
        net.minecraft.world.item.Items.TOTEM_OF_UNDYING,
        net.minecraft.world.item.Items.ELYTRA,
        net.minecraft.world.item.Items.NETHER_STAR,
        net.minecraft.world.item.Items.HEART_OF_THE_SEA,
        net.minecraft.world.item.Items.ECHO_SHARD,
        net.minecraft.world.item.Items.AMETHYST_SHARD,
        net.minecraft.world.item.Items.SPYGLASS,
        net.minecraft.world.item.Items.RECOVERY_COMPASS,
        net.minecraft.world.item.Items.MUSIC_DISC_5,
        net.minecraft.world.item.Items.MUSIC_DISC_OTHERSIDE,
        net.minecraft.world.item.Items.MUSIC_DISC_PIGSTEP
    );

    private static boolean isValuableItem(net.minecraft.world.item.Item item) {
        return VALUABLE_ITEMS.contains(item);
    }

    /**
     * 拾取优先级：5=贵重, 4=工具武器, 3=食物, 2=建材, 1=其他, 0=垃圾
     */
    private static int pickupPriority(net.minecraft.world.entity.item.ItemEntity item) {
        net.minecraft.world.item.Item i = item.getItem().getItem();
        if (VALUABLE_ITEMS.contains(i)) return 5;
        if (i instanceof net.minecraft.world.item.SwordItem
            || i instanceof net.minecraft.world.item.PickaxeItem
            || i instanceof net.minecraft.world.item.AxeItem
            || i instanceof net.minecraft.world.item.ShovelItem
            || i instanceof net.minecraft.world.item.HoeItem
            || i instanceof net.minecraft.world.item.ArmorItem
            || i instanceof net.minecraft.world.item.BowItem
            || i instanceof net.minecraft.world.item.CrossbowItem
            || i instanceof net.minecraft.world.item.TridentItem
            || i instanceof net.minecraft.world.item.ShieldItem) return 4;
        if (i.getFoodProperties(item.getItem(), null) != null) return 3;
        if (i instanceof net.minecraft.world.item.BlockItem) return 2;
        return 1;
    }

    // ==================== Auto-Recall Fields ====================
    private static final double RECALL_DISTANCE_SQ = 1024.0; // 32^2 blocks
    private static final int RECALL_WARNING_TICKS = 100; // 5 seconds (5 * 20)
    private int recallWarningTicks = 0;
    private boolean recallWarningActive = false;

    // ==================== Level & XP Fields ====================
    private int level = 1;
    private int xp = 0;
    private int xpToNext = 130; // XP needed for level 2 (50 + 1*80)
    public static final int MAX_LEVEL = 100;

    // ==================== Constructor ====================

    public AutomatonEntity(EntityType<? extends AutomatonEntity> type, Level level) {
        super(type, level);
        // Note: stepHeight is controlled by PathfinderMob.getStepHeight()
        // Default step height for PathfinderMob is 0.6 blocks
        // JumpGoal handles terrain-aware jumping instead
    }

    // ==================== Sound Effects ====================

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        // Play spawn sound (server broadcasts to all nearby players)
        if (!this.level().isClientSide) {
            this.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }
    }

    public void playHealSound() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 1.0f, 1.2f);
            serverLevel.sendParticles(ParticleTypes.HEART,
                this.getX(), this.getY() + 1.2, this.getZ(),
                8, 0.4, 0.3, 0.4, 0.1);
        }
    }

    public void playTeleportSound() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f);
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                this.getX(), this.getY() + 0.5, this.getZ(),
                30, 0.5, 0.5, 0.5, 0.3);
            serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL,
                this.getX(), this.getY() + 0.5, this.getZ(),
                20, 0.5, 0.5, 0.5, 0.3);
        }
    }

    public void playModeSwitchSound() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.playSound(net.minecraft.sounds.SoundEvents.ITEM_PICKUP, 1.0f, 1.5f);
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                this.getX(), this.getY() + 1.0, this.getZ(),
                10, 0.5, 0.4, 0.5, 0.2);
        }
    }

    /**
     * Spawn particles at this entity's position (server-side only).
     */
    private void spawnParticlesAtSelf(SimpleParticleType type, int count, double spread) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(type,
                this.getX(), this.getY() + 0.8, this.getZ(),
                count, spread, spread, spread, 0.1);
        }
    }

    /**
     * Spawn particles for companion spawn/revival event.
     */
    public void playSpawnParticles() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                this.getX(), this.getY() + 0.5, this.getZ(),
                30, 0.6, 0.6, 0.6, 0.2);
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                this.getX(), this.getY() + 0.5, this.getZ(),
                20, 0.5, 0.5, 0.5, 0.3);
            serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                this.getX(), this.getY() + 0.8, this.getZ(),
                15, 0.4, 0.4, 0.4, 0.5);
        }
    }

    /**
     * Spawn particles for item pickup event.
     */
    public void playPickupParticles() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.WAX_ON,
                this.getX(), this.getY() + 0.8, this.getZ(),
                5, 0.3, 0.3, 0.3, 0.05);
        }
    }

    /**
     * Swing arm with proper client broadcast.
     * Use this instead of raw swing() for non-attack actions
     * (mining, building, item use, etc.) to ensure animation visibility.
     */
    public void animateSwing() {
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        this.level().broadcastEntityEvent(this, (byte) 4);
    }

    /**
     * Place a block with swing + break/place particles.
     * Call AFTER level.setBlock(), passing the placed position.
     */
    public void animateBlockPlace(net.minecraft.core.BlockPos pos) {
        animateSwing();
        var state = this.level().getBlockState(pos);
        this.level().levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));
    }

    // ==================== Entity Data ====================

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CUSTOM_NAME, "");
        this.entityData.define(DATA_IS_ESSENTIAL, true);
        this.entityData.define(DATA_CHARACTER_ID, "");
        this.entityData.define(DATA_SKIN_TYPE, 0);
        this.entityData.define(DATA_SKIN_VALUE, "");
        this.entityData.define(DATA_WORKING_MODE, "follow");
        this.entityData.define(DATA_OWNER_UUID, Optional.empty());
        this.entityData.define(DATA_LEVEL, 1);
        this.entityData.define(DATA_XP, 0);
        this.entityData.define(DATA_XP_TO_NEXT, 130); // 50 + 1*80
        this.entityData.define(DATA_AVAILABLE_POINTS, 0);
        this.entityData.define(DATA_STRENGTH_POINTS, 0);
        this.entityData.define(DATA_VITALITY_POINTS, 0);
        this.entityData.define(DATA_SPEED_POINTS, 0);
        this.entityData.define(DATA_DEFENSE_POINTS, 0);
        this.entityData.define(DATA_ACTION_TEXT, "");
    }

    // ==================== Attribute Supplier ====================

    public static AttributeSupplier.Builder createAttributes() {
        // Base stats match a fresh player — attributes improve via level-up points
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)           // 10 hearts (same as player)
            .add(Attributes.MOVEMENT_SPEED, 0.1D)        // Same as player walk speed
            .add(Attributes.ARMOR, 0.0D)                 // No base armor (from equipment only)
            .add(Attributes.ARMOR_TOUGHNESS, 0.0D)       // No base toughness
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)  // No base knockback resistance
            .add(Attributes.FOLLOW_RANGE, 16.0D)
            .add(Attributes.ATTACK_DAMAGE, 1.0D);        // Same as player base damage
    }

    // ==================== Goal Selector (Phase 2: Task System) ====================

    @Override
    protected void registerGoals() {
        AICompanionMod.LOGGER.info("[AutomatonEntity] registerGoals() called");

        // Priority: lower = higher priority (0 = highest)
        // Task goals (Guard, Mine, Chop) take priority over Follow/Wander

        // 0: Float goal (always available - prevents drowning)
        this.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(this));

        // 1: Jump goal - terrain-aware jumping (highest priority after Float)
        this.goalSelector.addGoal(1, new JumpGoal(this));

        // 2: Guard goal - attack hostile mobs threatening owner (only when isGuardModeEnabled()=true)
        this.goalSelector.addGoal(2, new com.aiworkbench.companion.entity.goal.CompanionGuardGoal(this, 3.0, 10.0F));

        // 3: Gather goal - smart resource gathering (ores + logs, when gatherModeEnabled)
        this.goalSelector.addGoal(3, new com.aiworkbench.companion.entity.goal.BTreeGatherGoal(this));

        // 4: Farm goal - crop harvesting + replanting (when farmModeEnabled)
        this.goalSelector.addGoal(4, new com.aiworkbench.companion.entity.goal.CompanionFarmGoal(this, 2.0));

        // 5: Follow owner when too far away
        // minDistance=2 blocks (stop), maxDistance=16 blocks (follow)
        this.followGoal = new CompanionFollowGoal(this, 2.0F, 16.0F);
        this.goalSelector.addGoal(5, this.followGoal);

        // 6: Wander around when idle (and not near owner)
        this.goalSelector.addGoal(6, new CompanionWanderGoal(this, 0.5, 5.0F));

        // 7: Look at nearby entities (players, other mobs)
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));

        // 8: Random look around when idle
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // 9: Survival — nighttime lighting + shelter
        this.goalSelector.addGoal(9, new com.aiworkbench.companion.entity.goal.CompanionSurvivalGoal(this));

        // 10: Fishing — auto-fish near water
        this.goalSelector.addGoal(10, new com.aiworkbench.companion.entity.goal.CompanionFishingGoal(this));

        // 11: Trading — auto-trade with villagers
        this.goalSelector.addGoal(11, new com.aiworkbench.companion.entity.goal.CompanionTradeGoal(this));

        AICompanionMod.LOGGER.info("[AutomatonEntity] Goals registered: Float, Jump, Guard, Gather, Farm, Follow, Wander, Look, Random, Survival, Fishing, Trade");
    }

    // ==================== Entity Behavior ====================

    @Override
    public boolean isPersistenceRequired() {
        return isEssential;
    }

    @Override
    public boolean removeWhenFarAway(double dist) {
        return !isEssential;
    }

    /**
     * Track damage time for passive regen cooldown.
     */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        this.lastHurtTime = this.tickCount;
        // 被生物攻击时自动切换到守护模式并反击（记住被打前的模式）
        if (!this.level().isClientSide && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
            && attacker != getOwner() && !(attacker instanceof AutomatonEntity)) {
            if (currentState != CompanionState.GUARD) {
                savePreCombatState();
                transitionTo(CompanionState.GUARD);
                AICompanionMod.LOGGER.info("[AutomatonEntity] Hit by {} - auto-switching to guard mode", attacker.getName().getString());
            }
        }
        return super.hurt(source, amount);
    }

    @Override
    protected int calculateFallDamage(float fallDistance, float damageMultiplier) {
        if (this.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) return 0;
        float adjusted = fallDistance;
        var jumpBoost = this.getEffect(net.minecraft.world.effect.MobEffects.JUMP);
        if (jumpBoost != null) {
            adjusted -= (jumpBoost.getAmplifier() + 1);
        }
        int damage = net.minecraft.util.Mth.floor((adjusted - 3.0F) * damageMultiplier);
        return Math.max(0, damage);
    }

    // ==================== Animation Pipeline ====================

    /**
     * Drive the swing animation timer every tick.
     * PathfinderMob does NOT call updateSwingTime() — without this,
     * swing() calls have no visible effect on clients.
     */
    @Override
    public void aiStep() {
        this.updateSwingTime();
        // 新框架仍需 super.aiStep() 驱动导航/移动控制/跳跃控制
        // 旧 Goal 已在 enableNewFramework() 中移除，不会冲突
        if (useNewFramework) {
            tickCoreFramework();
        }
        super.aiStep();
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == 4) {
            this.swinging = true;
            this.swingTime = 0;
        } else {
            super.handleEntityEvent(id);
        }
    }

    // ==================== Death & Respawn ====================

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (!this.level().isClientSide) {
            this.playSound(net.minecraft.sounds.SoundEvents.PLAYER_DEATH, 1.0f, 0.8f);
        }
        if (!this.level().isClientSide && !respawnPending) {
            respawnPending = true;

            // dropInventoryItems(); // keepInventory

            String deathCharId = characterId;
            int deathLevel = level;
            int deathXp = xp;
            int deathSkinType = this.entityData.get(DATA_SKIN_TYPE);
            String deathSkinValue = this.entityData.get(DATA_SKIN_VALUE);
            int deathStr = strengthPoints, deathVit = vitalityPoints;
            int deathSpd = speedPoints, deathDef = defensePoints;
            int deathAvail = this.entityData.get(DATA_AVAILABLE_POINTS);
            int deathHunger = hungerLevel;
            float deathSaturation = saturationLevel;

            String customName = this.getCustomName() != null ? this.getCustomName().getString() : "Companion";
            ServerPlayer owner = getOwner();

            if (AICompanionMod.aiManager != null) {
                AICompanionMod.aiManager.removeAI(this.getUUID());
            }

            if (AICompanionMod.companionManager != null && ownerUUID != null) {
                AICompanionMod.companionManager.removeCompanion(ownerUUID);
            }

            if (owner != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7c" + customName + " died! Respawning in 30 seconds..."));
            }

            scheduleRespawn(deathCharId, deathLevel, deathXp, deathSkinType, deathSkinValue,
                deathStr, deathVit, deathSpd, deathDef, deathAvail,
                deathHunger, deathSaturation);
        }
        super.die(source);
    }

    /**
     * Drop all inventory items as ItemEntities on the ground.
     */
    private void dropInventoryItems() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.inventory.get(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
                this.inventory.set(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Schedule a respawn task 30 seconds (600 ticks) later, preserving level/XP/skin.
     */
    private void scheduleRespawn(String charId, int savedLevel, int savedXp, int savedSkinType,
            String savedSkinValue, int savedStr, int savedVit, int savedSpd, int savedDef, int savedAvail,
            int savedHunger, float savedSaturation) {
        if (this.level().isClientSide || ownerUUID == null) return;

        UUID ownerUuid = ownerUUID;
        net.minecraft.server.MinecraftServer server = this.level().getServer();
        if (server == null) return;

        server.tell(new net.minecraft.server.TickTask(
            server.getTickCount() + 600,
            () -> {
                // 防止复活竞态：如果玩家在此期间重新登录并已有同伴，跳过
                if (AICompanionMod.companionManager != null
                        && AICompanionMod.companionManager.hasCompanion(ownerUuid)) {
                    AICompanionMod.LOGGER.info("Player {} already has a companion, skipping scheduled respawn", ownerUuid);
                    return;
                }

                // Find the player across all dimensions
                ServerPlayer player = null;
                ServerLevel targetLevel = null;
                for (ServerLevel sl : AICompanionMod.server.getAllLevels()) {
                    ServerPlayer p = (ServerPlayer) sl.getPlayerByUUID(ownerUuid);
                    if (p != null) {
                        player = p;
                        targetLevel = sl;
                        break;
                    }
                }
                if (player == null || targetLevel == null) return;

                // Create new companion at player's position
                AutomatonEntity newCompanion = AutomatonEntity.create(targetLevel, charId, player);
                targetLevel.addFreshEntity(newCompanion);

                // Restore skin
                if (savedSkinType == 1) {
                    newCompanion.setSkinFromUrl(savedSkinValue);
                } else if (savedSkinType == 2) {
                    newCompanion.setSkinFromPlayer(savedSkinValue);
                }

                // Restore level & XP
                if (savedLevel > 1) {
                    newCompanion.setLevel(savedLevel);
                }
                if (savedXp > 0) {
                    newCompanion.grantXp(savedXp);
                }
                // Restore stat allocation
                newCompanion.strengthPoints = savedStr;
                newCompanion.vitalityPoints = savedVit;
                newCompanion.speedPoints = savedSpd;
                newCompanion.defensePoints = savedDef;
                newCompanion.entityData.set(DATA_AVAILABLE_POINTS, savedAvail);
                newCompanion.entityData.set(DATA_STRENGTH_POINTS, savedStr);
                newCompanion.entityData.set(DATA_VITALITY_POINTS, savedVit);
                newCompanion.entityData.set(DATA_SPEED_POINTS, savedSpd);
                newCompanion.entityData.set(DATA_DEFENSE_POINTS, savedDef);
                newCompanion.applyStatAllocation();

                newCompanion.hungerLevel = savedHunger;
                newCompanion.saturationLevel = savedSaturation;

                newCompanion.showDialogue("\u00a7a我回来了！", 80);
                newCompanion.playSpawnParticles();

                // Re-register with manager
                if (AICompanionMod.companionManager != null) {
                    AICompanionMod.companionManager.addCompanion(ownerUuid, newCompanion);
                }

                // 保存新同伴UUID到玩家NBT（防止下次登录产生重复同伴）
                net.minecraft.nbt.CompoundTag nbt = player.getPersistentData();
                nbt.putUUID("aicompanion.companion_uuid", newCompanion.getUUID());
                nbt.putDouble("aicompanion.companion_x", newCompanion.getX());
                nbt.putDouble("aicompanion.companion_y", newCompanion.getY());
                nbt.putDouble("aicompanion.companion_z", newCompanion.getZ());
                nbt.putString("aicompanion.companion_dim", newCompanion.level().dimension().location().toString());
                // 清理旧格式残留
                nbt.remove("aicompanion.companion_uuid_mostMost");
                nbt.remove("aicompanion.companion_uuid_mostLeast");

                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a7a你的同伴已复活！等级: " + savedLevel));
            }
        ));
    }

    @Override
    public void tick() {
        super.tick();
        tickCount++;

        // If hidden, only tick essential systems (no movement, no perception push)
        if (hidden) {
            // Dialogue auto-hide still works when hidden
            if (!dialogueText.isEmpty() && tickCount > dialogueEndTick) {
                dialogueText = "";
                updatePersistentName();
            }
            // Auto-recall still works when hidden
            if (tickCount % 20 == 0) {
                checkAutoRecall();
            }
            return;
        }

        // If movement stopped, skip movement-related ticks but keep essential systems
        if (movementStopped) {
            // Decrement cooldown
            if (stopToggleCooldown > 0) stopToggleCooldown--;
            // Dialogue auto-hide still works
            if (!dialogueText.isEmpty() && tickCount > dialogueEndTick) {
                dialogueText = "";
                updatePersistentName();
            }
            // Auto-recall still works when movement stopped
            if (tickCount % 20 == 0) {
                checkAutoRecall();
            }
            return;
        }

        // Decrement cooldowns
        if (modeToggleCooldown > 0) modeToggleCooldown--;
        scheduler.tickCooldown(); // v3.0: 调度器防抖冷却
        if (stopToggleCooldown > 0) stopToggleCooldown--;

        // Patrol behavior - wander around patrol center
        tickPatrol();

        // Eating cooldown countdown (every tick)
        if (eatingTicks > 0) eatingTicks--;

        // Hunger system tick (server-side only)
        if (!this.level().isClientSide && tickCount % 20 == 0) {
            tickHunger();
            // Auto-smelt: check if we can smelt ores (every 10s)
            if (tickCount % 200 == 0) {
                AutoUpgrader.trySmeltIfNeeded(this);
            }
            // Auto-sprint: match owner's sprint or combat speed boost
            if (tickCount % 10 == 0) {
                updateSprintState();
            }
            // Tool acquisition every 5 seconds
            if (tickCount % 100 == 0) {
                tryAcquireTool();
            }
            // Needs check every 30 seconds
            if (tickCount % 600 == 0) {
                checkAndReportNeeds();
            }
        }

        // Skill Engine - takes priority over goal system when active
        if (skillEngine.isActive()) {
            skillEngine.tick(this);
        }

        // Auto-defend: track and attack whoever hurt the owner recently
        if (autoDefendTicks > 0) {
            autoDefendTicks--;
            if (autoDefendTarget != null && autoDefendTarget.isAlive()) {
                // Navigate toward target
                this.getNavigation().moveTo(autoDefendTarget, 1.2);
                this.getLookControl().setLookAt(autoDefendTarget, 10.0F, 30.0F);
                // Attack if in range
                if (this.distanceToSqr(autoDefendTarget) <= ATTACK_REACH * ATTACK_REACH) {
                    this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    this.doHurtTarget(autoDefendTarget);
                }
            } else {
                autoDefendTarget = null;
                autoDefendTicks = 0;
                this.getNavigation().stop();
            }
        }

        // Auto-recall check every second
        if (tickCount % 20 == 0) {
            checkAutoRecall();
            checkNavigationTimeout();
        }

        if (tickCount % 100 == 0) {
            Vec3 pos = this.position();
            AICompanionMod.LOGGER.debug("[AutomatonEntity] Tick {} - Position: ({}, {}, {})",
                tickCount, pos.x, pos.y, pos.z);
        }

        // Push perception data every ~200ms (every 4 ticks)
        if (tickCount % 4 == 0) {
            pushPerception();
        }

        // Auto item pickup every 10 ticks (~0.5 seconds)
        if (tickCount % 10 == 0) {
            pickupNearbyItems();
        }

        // Auto-equip check every 40 ticks (2 seconds) — 确保模式切换后装备正确
        if (tickCount % 40 == 0) {
            autoEquip();
        }

        // Memory summarizer tick every 1200 ticks (60 seconds)
        if (tickCount % 1200 == 0 && AICompanionMod.memoryManager != null) {
            AICompanionMod.memoryManager.tick();
        }

        // Autonomous AI decision every 60 ticks (~3 seconds) - check surroundings
        if (tickCount % 60 == 0) {
            evaluateSituation();
            // Nightfall detection for LLM contextual dialogue
            boolean isDayNow = this.level().isDay();
            if (wasDaytime && !isDayNow) {
                addRecentEvent("nightfall", "夜幕降临！");
                triggerEventDialogue("nightfall", null);
            }
            wasDaytime = isDayNow;
        }

        // AI spontaneous dialogue every 2-4 minutes (server-side only)
        if (!this.level().isClientSide && tickCount % 20 == 0) {
            tickAiSpontaneous();
        }

        // Update AI context every 5 seconds (server-side only)
        if (!this.level().isClientSide && tickCount % 100 == 0) {
            pushAiContext();
        }

        // Dialogue auto-hide — show persistent status
        if (!dialogueText.isEmpty() && tickCount > dialogueEndTick) {
            dialogueText = "";
            updatePersistentName();
        }

        // Refresh persistent name every 5 seconds
        if (dialogueText.isEmpty() && tickCount % 100 == 0) {
            updatePersistentName();
        }

        // 驱动任务队列 — 每 tick 推进当前任务、处理超时、提升等待优先级
        if (!useNewFramework && !this.level().isClientSide && isAlive() && getOwner() != null) {
            taskQueue.tick(this);
            // 每 5 秒清理过期消息
            if (tickCount % 100 == 0) {
                dialogueStack.expireStale();
            }
        }

        // AutoCurriculum evaluation — 仅在非战斗、非技能执行、非采集活跃时评估（服务端）
        if (!useNewFramework && !this.level().isClientSide && !isSkillActive() && isAlive() && getOwner() != null
            && !isActivelyMining()) {
            curriculumTickCounter++;
            if (curriculumTickCounter >= CURRICULUM_EVAL_INTERVAL) {
                curriculumTickCounter = 0;
                evaluateCurriculum();
            }
            // LLM 采集战略 — 仅当 gatherPriorityResources 为空时首次调用（按需触发）
            // 之后不再重复调用，避免每30秒消耗 LLM 资源
            if (isGatherModeEnabled() && curriculumTickCounter == CURRICULUM_EVAL_INTERVAL / 2
                && !isActivelyMining() && gatherPriorityResources.isEmpty()) {
                evaluateGatherStrategyAsync();
            }
        }
    }

    /**
     * LLM 采集战略层：异步发送感知数据给 Ollama，获取优先采集目标。     * 在采集模式下每30秒运行一次，不阻塞游戏主线程。     */
    private void evaluateGatherStrategyAsync() {
        if (AICompanionMod.LLM_DISABLED) return;
        ServerPlayer owner = getOwner();
        if (owner == null) return;

        // 使用缓存的感知数据生成资源摘要
        PerceptionEngine.PerceptionData perception = getOrGatherPerception();
        java.util.List<String> resources = perception != null ?
            com.aiworkbench.companion.entity.goal.CompanionGatherGoal.getResourceSummary(this, 8) :
            java.util.Collections.emptyList();
        if (resources.isEmpty()) return;

        // 构建提示词——包含资源、背包、上次建议的反馈
        StringBuilder ctx = new StringBuilder();
        ctx.append("你是Minecraft中的AI同伴。当前在采集模式下，请分析环境并给出优先级建议。\n\n");
        ctx.append("## 附近资源\n");
        int count = 0;
        for (String r : resources) {
            if (count >= 15) break;
            String name = r.contains("@") ? r.substring(0, r.indexOf('@')) : r;
            ctx.append("- ").append(name);
            count++;
            if (count < Math.min(resources.size(), 15)) ctx.append("\n");
        }
        ctx.append("\n## 状态\n");
        ctx.append("- 位置: ").append(blockPosition().toShortString()).append("\n");
        ctx.append("- 背包: ").append(getUsedInventorySlots()).append("/").append(getInventorySize()).append("\n");
        ctx.append("- 当前优先: ").append(String.join(",", gatherPriorityResources)).append("\n");

        // 上次LLM建议的执行反馈
        ctx.append("\n请分析以上数据，回复JSON: {\"priority\":[\"资源1\",\"资源2\"],\"reason\":\"简短理由\"}\n");
        ctx.append("资源名只用英文：iron/coal/gold/diamond/copper/redstone/lapis/emerald/log\n");
        ctx.append("最多5个，不要重复，不要包含stone/dirt/cobblestone。reason用中文，15字以内。");

        String model = com.aiworkbench.companion.CompanionConfig.getModel(owner.getUUID());
        String promptStr = ctx.toString();

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                java.util.LinkedHashMap<String, Object> opts = new java.util.LinkedHashMap<>();
                opts.put("temperature", OllamaClient.TEMP_PRECISE);
                opts.put("num_predict", 100);
                String content = com.aiworkbench.companion.ai.OllamaClient.chat(
                    model, null, promptStr, opts, 15000, 2);
                if (content == null || content.isEmpty()) return;

                java.util.Map<String, Object> parsed = com.aiworkbench.companion.ai.OllamaClient.extractJson(content);
                if (parsed == null) return;

                @SuppressWarnings("unchecked")
                java.util.List<String> priorities = (java.util.List<String>) parsed.get("priority");
                if (priorities != null && !priorities.isEmpty()) {
                    java.util.List<String> filtered = new java.util.ArrayList<>();
                    for (String p : priorities) {
                        String t = p.trim().toLowerCase();
                        if (t.isEmpty() || t.equals("stone") || t.equals("dirt")
                            || t.equals("cobblestone") || t.equals("gravel") || t.equals("sand"))
                            continue;
                        filtered.add(t);
                    }
                    if (!filtered.isEmpty()) {
                        String joined = String.join(",", filtered);
                        if (getServer() != null) {
                            getServer().execute(() -> {
                                setGatherPriority(joined);
                                showDialogue("\u00a7bLLM: " + joined, 60);
                                AICompanionMod.LOGGER.info("[LLMStrategy] {}", joined);
                            });
                        }
                    }
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.warn("[LLMStrategy] Error: {}", e.getMessage());
            }
        });
    }

    public int getUsedInventorySlots() {
        int used = 0;
        for (int i = 0; i < getInventorySize(); i++) {
            if (!getItem(i).isEmpty()) used++;
        }
        return used;
    }

    /**
     * Scan for nearby item entities and pick them up into inventory.
     * Controlled by autoPickupEnabled, pickupRadius, and pickupOnlyValuable.
     */
    private void pickupNearbyItems() {
        if (this.level().isClientSide) return;
        if (!autoPickupEnabled || !isAlive()) return;

        double radius = pickupRadius;
        net.minecraft.world.phys.AABB pickupBounds = new net.minecraft.world.phys.AABB(
            this.getX() - radius, this.getY() - 1.0, this.getZ() - radius,
            this.getX() + radius, this.getY() + 2.0, this.getZ() + radius
        );

        // When not in follow mode, actively walk toward nearby items
        if (!isFollowModeActive() && this.navigation.isDone()) {
            double scanRadius = 10.0;
            net.minecraft.world.phys.AABB scanBounds = new net.minecraft.world.phys.AABB(
                this.getX() - scanRadius, this.getY() - 2.0, this.getZ() - scanRadius,
                this.getX() + scanRadius, this.getY() + 3.0, this.getZ() + scanRadius
            );
            net.minecraft.world.entity.item.ItemEntity nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (net.minecraft.world.entity.item.ItemEntity item :
                    this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, scanBounds)) {
                if (item.isPickable() && item.isAlive() && item.tickCount > 10) {
                    double d = this.distanceToSqr(item);
                    if (d < nearestDistSq) {
                        nearestDistSq = d;
                        nearest = item;
                    }
                }
            }
            // Walk toward nearest item if beyond immediate pickup range
            if (nearest != null && nearestDistSq > pickupRadius * pickupRadius) {
                this.navigation.moveTo(nearest, 1.0);
            }
        }

        // Pick up items in range, sorted by priority (valuable first)
        int picked = 0;
        java.util.List<net.minecraft.world.entity.item.ItemEntity> candidates = new java.util.ArrayList<>(
            this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, pickupBounds));
        // Sort by priority: valuable > tools > food > materials > rest
        candidates.sort((a, b) -> Integer.compare(pickupPriority(b), pickupPriority(a)));
        for (net.minecraft.world.entity.item.ItemEntity item : candidates) {
            if (item.isPickable() && item.isAlive() && item.tickCount > 10) {
                // 贵重物品过滤
                if (pickupOnlyValuable && !isValuableItem(item.getItem().getItem())) continue;
                ItemStack stack = item.getItem();
                if (addItemToInventory(stack)) {
                    item.discard();
                    picked++;
                }
                if (!hasInventorySpace()) break;
            }
        }

        if (picked > 0) {
            AICompanionMod.LOGGER.info("[AutomatonEntity] Picked up " + picked + " item stacks");
            animateSwing();
            playPickupParticles();
            autoEquip();
        }
    }

    // ==================== Auto-Equip System ====================

    /**
     * Scan inventory and auto-equip the best weapons and armor.
     * Compares attack damage for weapons, armor value for armor pieces.
     */
    /**
     * 模式感知自动装备：根据当前模式优先装备合适的工具和防具。
     * - 挖掘模式 — 最好的镮子
     * - 砍伐模式 — 最好的斧头
     * - 守护模式 — 最好的剑 + 全身护甲+ 全身护甲
     * - 跟随模式 — 卸下主手装备（外观）
     */
    /** 通知实体：玩家手动操作了装备槽，autoEquip短时间内不覆盖*/
    public void notifyManualEquip() {
        this.lastManualEquipTick = this.level().getGameTime();
    }

    /** 背包GUI打开/关闭时调用，暂停/恢复自动装备 */
    public void setSuppressAutoEquip(boolean suppress) {
        this.suppressAutoEquip = suppress;
        if (suppress) {
            AICompanionMod.LOGGER.debug("[AutomatonEntity] Auto-equip suppressed (backpack open)");
        } else {
            AICompanionMod.LOGGER.debug("[AutomatonEntity] Auto-equip resumed (backpack closed)");
        }
    }

    public long getAutoUpgradeLastCheckTick() { return autoUpgradeLastCheckTick; }
    public void setAutoUpgradeLastCheckTick(long tick) { this.autoUpgradeLastCheckTick = tick; }

    /** Auto-craft needed tools from available materials */
    public void tryAcquireTool() {
        if (!isAlive() || this.level().isClientSide) return;
        ItemStack mainhand = getItemBySlot(EquipmentSlot.MAINHAND);
        boolean needsTool = false;
        if (isGatherModeEnabled()) {
            if (!(mainhand.getItem() instanceof net.minecraft.world.item.PickaxeItem)
                && !(mainhand.getItem() instanceof net.minecraft.world.item.AxeItem))
                needsTool = true;
            else if (isToolLowDurability(mainhand))
                needsTool = true;
        }
        if (isGuardModeEnabled()) {
            if (!(mainhand.getItem() instanceof net.minecraft.world.item.SwordItem)
                && !(mainhand.getItem() instanceof net.minecraft.world.item.AxeItem))
                needsTool = true;
            else if (isToolLowDurability(mainhand))
                needsTool = true;
        }
        if (!needsTool) return;
        if (isGatherModeEnabled() && isToolLowDurability(mainhand)) {
            Class<?> toolClass = mainhand.getItem() instanceof net.minecraft.world.item.PickaxeItem
                ? net.minecraft.world.item.PickaxeItem.class
                : net.minecraft.world.item.AxeItem.class;
            if (trySwapToBetterTool(toolClass)) return;
        }
        if (isGuardModeEnabled() && isToolLowDurability(mainhand)) {
            Class<?> toolClass = mainhand.getItem() instanceof net.minecraft.world.item.SwordItem
                ? net.minecraft.world.item.SwordItem.class
                : net.minecraft.world.item.AxeItem.class;
            if (trySwapToBetterTool(toolClass)) return;
        }
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            net.minecraft.world.item.Item it = this.inventory.get(i).getItem();
            if ((it instanceof net.minecraft.world.item.PickaxeItem
                || it instanceof net.minecraft.world.item.AxeItem
                || it instanceof net.minecraft.world.item.SwordItem)
                && !isToolLowDurability(this.inventory.get(i))) {
                ItemStack tool = this.inventory.get(i).copy();
                this.inventory.set(i, mainhand.copy());
                setItemSlot(EquipmentSlot.MAINHAND, tool);
                animateSwing();
                return;
            }
        }
        if (tryCraftPickaxe(Items.WOODEN_PICKAXE, "木镐") || tryCraftPickaxe(Items.STONE_PICKAXE, "石镐")) return;
        int logs = countLogs(), planks = countPlanksItems(), sticks = countItems("stick");
        if (logs == 0 && planks < 3) notifyOwner("\u00a7e缺原木来合成木镐");
        else if (sticks < 2) notifyOwner("\u00a7e缺木棍（需" + (2-sticks) + "根）");
        else notifyOwner("\u00a7e缺木板（需" + (3-planks) + "块）");
    }

    public boolean isToolLowDurability(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getMaxDamage() <= 0) return false;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        return remaining <= 5;
    }

    public boolean trySwapToBetterTool(Class<?> toolClass) {
        ItemStack current = getItemBySlot(EquipmentSlot.MAINHAND);
        int bestSlot = -1;
        int bestDurability = isToolLowDurability(current) ? 0 : (current.getMaxDamage() - current.getDamageValue());

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.inventory.get(i);
            if (stack.isEmpty() || !toolClass.isInstance(stack.getItem())) continue;
            int durability = stack.getMaxDamage() - stack.getDamageValue();
            if (durability > bestDurability && !isToolLowDurability(stack)) {
                bestDurability = durability;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            ItemStack newTool = this.inventory.get(bestSlot).copy();
            this.inventory.set(bestSlot, current.copy());
            setItemSlot(EquipmentSlot.MAINHAND, newTool);
            animateSwing();
            return true;
        }
        return false;
    }
    private boolean tryCraftPickaxe(net.minecraft.world.item.Item target, String name) {
        int planks = countPlanksItems(), sticks = countItems("stick");
        boolean hasCT = countItem(Items.CRAFTING_TABLE) > 0;
        if (target == Items.WOODEN_PICKAXE) {
            // Ensure enough planks: 3 for pickaxe + optionally 4 for crafting table
            int need = hasCT ? 3 : 7;
            while (planks < need && countLogs() > 0) { consumeLogs(1); addPlanks(4); planks += 4; }
            if (planks < need) return false;
            if (sticks < 2) { if (planks >= 2) { consumePlanksItems(2); addSticks(4); sticks += 4; planks -= 2; } else return false; }
            // After sticks consumption, make CT if needed
            if (!hasCT) {
                while (planks < 4 && countLogs() > 0) { consumeLogs(1); addPlanks(4); planks += 4; }
                if (planks >= 4) { consumePlanksItems(4); addItemToInventory(new ItemStack(Items.CRAFTING_TABLE)); planks -= 4; hasCT = true; }
                else return false;
            }
            // Final check: need 3 planks + 2 sticks for pickaxe
            if (planks < 3 || sticks < 2) return false;
            consumePlanksItems(3); consumeStickCount(2);
            addItemToInventory(new ItemStack(Items.WOODEN_PICKAXE));
            notifyOwner("\u00a7a合成了" + name + "！"); animateSwing();
            return true;
        }
        if (target == Items.STONE_PICKAXE) {
            if (!hasCT || countItem(Items.COBBLESTONE) < 3 || sticks < 2) return false;
            consumeFromInventory(Items.COBBLESTONE, 3); consumeStickCount(2);
            addItemToInventory(new ItemStack(Items.STONE_PICKAXE));
            notifyOwner("\u00a7a合成了" + name + "！"); animateSwing();
            return true;
        }
        return false;
    }

    private int countLogs() { int c = 0; for (int i = 0; i < INVENTORY_SIZE; i++) { String n = this.inventory.get(i).getItem().builtInRegistryHolder().key().location().getPath(); if (n.contains("_log") || n.contains("_stem")) c += this.inventory.get(i).getCount(); } return c; }
    private int countPlanksItems() { int c = 0; for (int i = 0; i < INVENTORY_SIZE; i++) { String n = this.inventory.get(i).getItem().builtInRegistryHolder().key().location().getPath(); if (n.contains("_planks")) c += this.inventory.get(i).getCount(); } return c; }
    private int countItems(String type) { if ("stick".equals(type)) return countItem(Items.STICK); if ("cobblestone".equals(type)) return countItem(Items.COBBLESTONE); return 0; }
    private int countItem(net.minecraft.world.item.Item item) { int c = 0; for (int i = 0; i < INVENTORY_SIZE; i++) { if (this.inventory.get(i).getItem() == item) c += this.inventory.get(i).getCount(); } return c; }
    private void consumeLogs(int count) { int r = count; for (int i = 0; i < INVENTORY_SIZE && r > 0; i++) { String n = this.inventory.get(i).getItem().builtInRegistryHolder().key().location().getPath(); if (n.contains("_log") || n.contains("_stem")) { int t = Math.min(r, this.inventory.get(i).getCount()); this.inventory.get(i).shrink(t); r -= t; if (this.inventory.get(i).isEmpty()) this.inventory.set(i, ItemStack.EMPTY); } } }
    private void consumePlanksItems(int count) { int r = count; for (int i = 0; i < INVENTORY_SIZE && r > 0; i++) { String n = this.inventory.get(i).getItem().builtInRegistryHolder().key().location().getPath(); if (n.contains("_planks")) { int t = Math.min(r, this.inventory.get(i).getCount()); this.inventory.get(i).shrink(t); r -= t; if (this.inventory.get(i).isEmpty()) this.inventory.set(i, ItemStack.EMPTY); } } }
    private void consumeStickCount(int count) { consumeFromInventory(Items.STICK, count); }
    private void consumeFromInventory(net.minecraft.world.item.Item item, int count) { int r = count; for (int i = 0; i < INVENTORY_SIZE && r > 0; i++) { if (this.inventory.get(i).getItem() == item) { int t = Math.min(r, this.inventory.get(i).getCount()); this.inventory.get(i).shrink(t); r -= t; if (this.inventory.get(i).isEmpty()) this.inventory.set(i, ItemStack.EMPTY); } } }
    private void addPlanks(int count) { addItemToInventory(new ItemStack(Items.OAK_PLANKS, count)); }
    private void addSticks(int count) { addItemToInventory(new ItemStack(Items.STICK, count)); }

    /** Check what the companion needs and notify the owner */
    private void checkAndReportNeeds() {
        if (!isAlive() || this.level().isClientSide) return;

        if (isGatherModeEnabled()) {
            boolean hasTool = false;
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                net.minecraft.world.item.Item it = this.inventory.get(i).getItem();
                if (it instanceof net.minecraft.world.item.PickaxeItem || it instanceof net.minecraft.world.item.AxeItem) {
                    hasTool = true; break;
                }
            }
            if (!hasTool) notifyOwner("\u00a7e我需要镐子或斧头才能采集资源");
        }
        if (isGuardModeEnabled()) {
            if (getItemBySlot(EquipmentSlot.MAINHAND).isEmpty())
                notifyOwner("\u00a7e我没有武器，战斗能力有限");
        }
    }

    /** Auto-sprint: match owner's sprint speed or use sprint when far behind */
    private void updateSprintState() {
        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr == null) return;
        double baseSpeed = baseMoveSpeed;
        ServerPlayer owner = getOwner();

        if (owner != null && isFollowModeActive()) {
            var ownerSpeedAttr = owner.getAttribute(Attributes.MOVEMENT_SPEED);
            if (ownerSpeedAttr != null) {
                speedAttr.setBaseValue(ownerSpeedAttr.getValue());
                this.setSprinting(owner.isSprinting());
                return;
            }
        }

        if (hungerLevel <= 6) { this.setSprinting(false); speedAttr.setBaseValue(baseSpeed); return; } // 饥饿≤6不能疾跑（原版规则）
        boolean shouldSprint = false;
        // 追随：距离>6格疾跑追赶
        if (owner != null && isFollowModeActive() && this.distanceToSqr(owner) > 6.25) {
            shouldSprint = true;
        }
        // 战斗/采集/种植模式中自动疾跑（只在导航移动时，避免站桩跑步粒子）
        if (isGuardModeEnabled() || isGatherModeEnabled() || isFarmModeEnabled()) {
            shouldSprint = !this.getNavigation().isDone();
        }
        this.setSprinting(shouldSprint);
        speedAttr.setBaseValue(shouldSprint ? baseSpeed * 1.3 : baseSpeed);
    }

    private void tickHunger() {
        float exhaustion = 0.0f;
        if (isGuardModeEnabled() || autoDefendTarget != null) {
            exhaustion += 0.1f;
        } else if (isGatherModeEnabled() || isFarmModeEnabled()) {
            exhaustion += 0.05f;
        } else if (isFollowModeActive()) {
            exhaustion += 0.01f;
        }
        if (this.isSprinting()) {
            exhaustion += 0.1f;
        }

        addExhaustion(exhaustion);

        if (hungerLevel >= 18 && this.getHealth() < this.getMaxHealth() && tickCount - lastHurtTime > 100) {
            hungerTickCounter++;
            int regenInterval = saturationLevel > 0 ? 80 : 160;
            if (hungerTickCounter >= regenInterval) {
                this.heal(1.0f);
                hungerTickCounter = 0;
                addExhaustion(saturationLevel > 0 ? 3.0f : 0.0f);
            }
        } else {
            hungerTickCounter = 0;
        }

        if (hungerLevel <= 0 && tickCount % 40 == 0 && this.getHealth() > 1.0f) {
            this.hurt(this.level().damageSources().starve(), 1.0f);
        }

        if (hungerLevel < 18) {
            tryAutoEat();
        }

        if (hungerLevel <= 0 && tickCount % 200 == 0) {
            showDialogue("\u00a7c好饿...没有食物了！", 60);
        } else if (hungerLevel < 6 && tickCount % 200 == 0) {
            showDialogue("\u00a7c好饿...需要食物！", 60);
        }
    }

    public void addExhaustion(float amount) {
        exhaustionLevel += amount;
        while (exhaustionLevel >= 4.0f) {
            exhaustionLevel -= 4.0f;
            if (saturationLevel > 0) {
                saturationLevel = Math.max(0, saturationLevel - 1.0f);
            } else {
                hungerLevel = Math.max(0, hungerLevel - 1);
            }
        }
    }

    private void tryAutoEat() {
        if (eatingTicks > 0) return;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.inventory.get(i);
            if (stack.isEmpty()) continue;
            var foodProps = stack.getItem().getFoodProperties(stack, null);
            if (foodProps != null) {
                int nutrition = foodProps.getNutrition();
                float saturation = foodProps.getSaturationModifier() * nutrition;

                hungerLevel = Math.min(MAX_HUNGER, hungerLevel + nutrition);
                saturationLevel = Math.min(hungerLevel, saturationLevel + saturation);

                stack.shrink(1);
                if (stack.isEmpty()) this.inventory.set(i, ItemStack.EMPTY);
                eatingTicks = 32; // 1.6秒进食冷却，模拟玩家进食时间
                this.level().broadcastEntityEvent(this, (byte) 9);
                this.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 0.8f, 1.0f);
                AICompanionMod.LOGGER.info("[AutomatonEntity] Ate {} hunger={}/{} saturation={:.1f}",
                    stack.getItem(), hungerLevel, MAX_HUNGER, saturationLevel);
                return;
            }
        }
    }

    private void autoEquip() {
        if (this.level().isClientSide) return;

        // 背包打开期间暂停自动装备，防止覆盖玩家的手动操作
        if (suppressAutoEquip) return;

        // 玩家10秒内手动操作了装备，尊重玩家选择，不自动覆盖
        if (this.level().getGameTime() - lastManualEquipTick < 200) return;

        if (isGatherModeEnabled()) {
            // 采集模式：GatherGoal内部自动切换工具，autoEquip不干涉
            return;
        } else if (isGuardModeEnabled()) {
            ItemStack current = getItemBySlot(EquipmentSlot.MAINHAND);
            // 守护模式下：弓+箭是合法远程配置，不要替换成剑
            boolean hasBow = current.getItem() instanceof net.minecraft.world.item.BowItem && hasArrow();
            if (!hasBow && (current.isEmpty() || !(current.getItem() instanceof net.minecraft.world.item.SwordItem
                || current.getItem() instanceof net.minecraft.world.item.AxeItem))) {
                equipBestTool(net.minecraft.world.item.SwordItem.class);
            }
            equipBestArmor();
        } else {
            // 跟随模式：不再强制卸下武器，尊重玩家选择
            // 只自动装备护甲
            equipBestArmor();
        }
    }

    /** 在背包中找到最好的指定类型工具并装备到主手 */
    private void equipBestTool(Class<?> toolClass) {
        int bestSlot = -1;
        float bestSpeed = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.inventory.get(i);
            if (stack.isEmpty()) continue;
            if (toolClass.isInstance(stack.getItem())) {
                float speed = stack.getDestroySpeed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }
        if (bestSlot >= 0) {
            ItemStack current = getItemBySlot(EquipmentSlot.MAINHAND);
            ItemStack newTool = this.inventory.get(bestSlot).copy();
            this.inventory.set(bestSlot, current.copy());
            setItemSlot(EquipmentSlot.MAINHAND, newTool);
        }
    }

    /** 从背包中装备最好的护甲 */
    private void equipBestArmor() {
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : armorSlots) {
            int bestIdx = -1;
            double bestArmor = -1;
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                ItemStack stack = this.inventory.get(i);
                if (stack.isEmpty()) continue;
                if (Mob.getEquipmentSlotForItem(stack) != slot) continue;
                double armorValue = 0;
                for (var entry : stack.getAttributeModifiers(slot).entries()) {
                    if (entry.getKey().equals(Attributes.ARMOR)) {
                        armorValue += entry.getValue().getAmount();
                    }
                }
                if (armorValue > bestArmor) {
                    bestArmor = armorValue;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                ItemStack current = getItemBySlot(slot);
                ItemStack newArmor = this.inventory.get(bestIdx).copy();
                this.inventory.set(bestIdx, current.copy());
                setItemSlot(slot, newArmor);
                animateSwing();
            }
        }
    }

    private boolean hasArrow() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (this.inventory.get(i).getItem() == net.minecraft.world.item.Items.ARROW) return true;
        }
        return false;
    }

    /**
     * Patrol behavior - wander around patrol center when patrol mode is enabled
     */
    private void tickPatrol() {
        if (!isPatrolModeEnabled() || patrolCenter == null) return;
        if (isFollowModeActive() || isGuardModeEnabled() || isGatherModeEnabled()) return;
        if (movementStopped || hidden) return;

        // Check if we've reached our current patrol target
        if (this.navigation.isDone()) {
            // Pick a new random position within patrol radius
            float randX = (this.random.nextFloat() - 0.5f) * PATROL_RADIUS * 2;
            float randZ = (this.random.nextFloat() - 0.5f) * PATROL_RADIUS * 2;
            int targetX = (int)(patrolCenter.getX() + randX);
            int targetY = patrolCenter.getY();
            int targetZ = (int)(patrolCenter.getZ() + randZ);

            // Move to new patrol point
            this.navigation.moveTo(targetX, targetY, targetZ, 0.8);
        }
    }

    /**
     * Check if inventory has at least one empty slot
     */
    private boolean hasInventorySpace() {
        for (ItemStack slot : inventory) {
            if (slot.isEmpty()) return true;
        }
        return false;
    }

    // ==================== AI Spontaneous Dialogue ====================

    /**
     * Tick the AI spontaneous dialogue cooldown and trigger when ready.
     * Only runs every second (called every 20 ticks).
     */
    private void tickAiSpontaneous() {
        if (this.level().isClientSide) return;
        if (AICompanionMod.aiManager == null) return;

        // Don't speak if already showing dialogue
        if (!dialogueText.isEmpty() && tickCount < dialogueEndTick) return;

        // Don't speak if in combat (auto-defend or guard active)
        if (autoDefendTarget != null || isGuardModeEnabled()) return;

        // Don't speak if owner is too far (distance > 20 blocks)
        ServerPlayer owner = getOwner();
        if (owner == null || this.distanceToSqr(owner) > 400.0) return;

        // Cooldown management
        if (aiSpontaneousCooldown > 0) {
            aiSpontaneousCooldown--;
            return;
        }

        // Trigger AI spontaneous dialogue
        var ai = AICompanionMod.aiManager.getAI(this);
        // Reset cooldown before async call to prevent overlapping triggers
        aiSpontaneousCooldown = AI_SPONTANEOUS_MIN + this.random.nextInt(AI_SPONTANEOUS_MAX - AI_SPONTANEOUS_MIN);
        ai.generateSpontaneousAction().thenAccept(response -> {
            if (response != null && !response.isEmpty() && !response.equals("...")) {
                // Must run on server thread
                net.minecraft.server.MinecraftServer srv = this.level().getServer();
                if (srv != null) {
                    srv.execute(() -> {
                        if (this.isAlive()) {
                            showDialogue("\u00a7d" + response, 80);
                        }
                    });
                }
            }
        });
    }

    // ==================== Recent Event Methods ====================

    public void addRecentEvent(String type, String description) {
        addRecentEvent(type, description, null);
    }

    public void addRecentEvent(String type, String description,
                               java.util.Map<String, Object> data) {
        recentEvents.addLast(new RecentEvent(type, description,
            this.level().getGameTime(), data));
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.removeFirst();
        }
    }

    public java.util.List<RecentEvent> getRecentEvents() {
        return new java.util.ArrayList<>(recentEvents);
    }

    public void triggerEventResponse(String eventType, java.util.Map<String, Object> ctx) {
        triggerEventDialogue(eventType, ctx);
    }

    private void triggerEventDialogue(String eventType, java.util.Map<String, Object> ctx) {
        if (AICompanionMod.aiManager == null) return;
        if (this.level().getGameTime() - lastEventDialogueTick < EVENT_DIALOGUE_COOLDOWN_TICKS) return;
        lastEventDialogueTick = this.level().getGameTime();
        var ai = AICompanionMod.aiManager.getAI(this);
        if (ai == null) return;
        ai.generateEventResponse(eventType, ctx).thenAccept(response -> {
            if (response != null && !response.isEmpty() && !response.equals("...")) {
                net.minecraft.server.MinecraftServer srv = this.level().getServer();
                if (srv != null) {
                    srv.execute(() -> {
                        if (this.isAlive()) {
                            showDialogue("\u00a7d" + response, 80);
                        }
                    });
                }
            }
        });
    }

    /**
     * Push current world context to the companion's AI brain.
     * Runs every 5 seconds.
     */
    private void pushAiContext() {
        if (AICompanionMod.aiManager == null) return;

        try {
            var ai = AICompanionMod.aiManager.getAI(this);

            java.util.Map<String, Object> context = new java.util.HashMap<>();
            context.put("level", this.entityData.get(DATA_LEVEL));
            context.put("mode", this.entityData.get(DATA_WORKING_MODE));
            context.put("owner_health", getOwner() != null ? (int) getOwner().getHealth() : 20);

            // Time of day
            if (this.level().isDay()) {
                context.put("time_of_day", "白天");
            } else {
                context.put("time_of_day", "夜晚");
            }

            // Biome
            var biome = this.level().getBiome(this.blockPosition());
            String biomeName = biome.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("未知");
            context.put("biome", biomeName);

            // === Push perception data for LLM context ===
            PerceptionEngine.PerceptionData perception = getOrGatherPerception();
            if (perception != null) {
                context.put("nearby_resources", perception.resources != null
                    ? new java.util.ArrayList<>(perception.resources) : java.util.Collections.emptyList());
                context.put("has_hostile", perception.dangerHostile);
                context.put("health_ratio", perception.health / this.getMaxHealth());
            }

            // === Push recent events (last 5 minutes) ===
            if (!recentEvents.isEmpty()) {
                java.util.List<String> eventDescs = new java.util.ArrayList<>();
                for (RecentEvent e : recentEvents) {
                    if (this.level().getGameTime() - e.gameTime < 6000) {
                        eventDescs.add(e.description);
                    }
                }
                if (!eventDescs.isEmpty()) {
                    context.put("recent_event_descriptions", eventDescs);
                }
            }

            ai.updateContext(context);
        } catch (Exception e) {
            // Silently fail - context update is non-critical
        }
    }

    /**
     * Autonomous situation evaluation - reacts to environment without LLM
     * Runs every 60 ticks (~3 seconds) with cooldown to avoid spam
     *
     * State machine behavior:
     * - Dangers: immediate warning (bypass question system)
     * - Resources: ask owner questions ("要采矿吗？") with 3s display, 10s cooldown
     * - Owner health: check owner's health, warn if low
     */
    private void evaluateSituation() {
        if (this.level().isClientSide) return;

        // Tick the question cooldown
        if (questionCooldown > 0) {
            questionCooldown--;
        }
        if (ticksSinceLastQuestion < Integer.MAX_VALUE) {
            ticksSinceLastQuestion++;
        }

        // Auto-clear expired question (3 second display time)
        if (currentQuestion != null && tickCount > questionEndTick) {
            clearActiveQuestion();
        }

        // Cooldown: don't react more than once every 30 seconds (600 ticks) for warnings
        if (tickCount - lastSituationWarningTick < 600) return;

        // 共享感知缓存：多个系统共用一次扫描
        PerceptionEngine.PerceptionData perception = getOrGatherPerception();
        if (perception == null) return;

        // ==================== 危险警告（不取消技能，同伴自行判断）====================
        if (perception.dangerLava) {
            showWarning("lava", "主人，小心岩浆！");
            return;
        } else if (perception.dangerFall) {
            showWarning("fall", "注意脚下，别摔下去了！");
            return;
        } else if (perception.dangerHostile) {
            showWarning("hostile", "有敌对生物在旁边！");
            return;
        } else if (perception.dangerSuffocation) {
            showWarning("suffocation", "主人，这里会窒息！");
            return;
        }

        // ==================== OWNER HEALTH CHECK ====================
        ServerPlayer owner = getOwner();
        if (owner != null && owner.getHealth() <= 10.0f && !perception.dangerLowHealth) {
            // Only remind if owner health is low (5 hearts) and we haven't already warned recently
            if (ticksSinceLastQuestion >= MIN_TICKS_BETWEEN_QUESTIONS) {
                askQuestion("owner_health_low", "主人血低了！");
                return;
            }
        }

        // ==================== RESOURCE QUESTIONS (Active Decision) ====================
        // Skip if we already have an active question or on cooldown
        if (currentQuestion != null || questionCooldown > 0) return;

        if (!perception.resources.isEmpty()) {
            String topResource = perception.resources.get(0);

            // Determine question type based on resource
            String questionType = null;
            String questionText = null;

            if (topResource.contains("diamond") || topResource.contains("emerald") ||
                topResource.contains("gold") || topResource.contains("iron") ||
                topResource.contains("coal") || topResource.contains("lapis") ||
                topResource.contains("redstone") || topResource.contains("copper")) {
                // Ore detected - ask about mining
                questionType = "ore";
                questionText = "要采矿吗？";
            } else if (topResource.contains("log") || topResource.contains("oak_log") ||
                       topResource.contains("spruce_log") || topResource.contains("birch_log") ||
                       topResource.contains("jungle_log") || topResource.contains("dark_oak_log") ||
                       topResource.contains("acacia_log")) {
                // Tree/log detected - ask about chopping
                questionType = "tree";
                questionText = "要砍树吗？";
            }

            // Ask question if we found a valid type and it's different from last question
            if (questionType != null && questionText != null) {
                if (!questionType.equals(lastQuestionType)) {
                    askQuestion(questionType, questionText);
                    return;
                }
            }
        }
    }

    /**
     * Show a warning message (bypasses question system, immediate display)
     */
    private void showWarning(String dangerType, String message) {
        // 同类型危险30秒内不重复提醒        if (dangerType.equals(lastDangerType) && tickCount - lastSituationWarningTick < 200) return;
        lastDangerType = dangerType;
        AICompanionMod.LOGGER.info("[AutomatonEntity] Warning: " + message);
        showDialogue(message, 80);
        lastSituationWarningTick = tickCount;
    }

    /**
     * Ask an active decision question (state machine)
     * @param questionType unique identifier for this question type
     * @param questionText the question text to display
     */
    private void askQuestion(String questionType, String questionText) {
        if (questionCooldown > 0) return;

        this.currentQuestion = questionType;
        this.questionEndTick = this.tickCount + QUESTION_DURATION_TICKS;
        this.questionCooldown = QUESTION_COOLDOWN_TICKS;
        this.lastQuestionType = questionType;
        this.ticksSinceLastQuestion = 0;

        AICompanionMod.LOGGER.info("[AutomatonEntity] Question: " + questionText + " (type: " + questionType + ")");
        showDialogue(questionText, QUESTION_DURATION_TICKS);
        lastSituationWarningTick = tickCount;
    }

    /**
     * Clear the active question state
     */
    private void clearActiveQuestion() {
        this.currentQuestion = null;
        // Don't reset questionCooldown - let it run its course
    }

    /**
     * Push perception data to Python bridge if connected
     */
    private void pushPerception() {
        if (AICompanionMod.tcpServer == null || !AICompanionMod.tcpServer.isConnected()) {
            return;
        }

        try {
            // Gather perception data
            PerceptionEngine.PerceptionData data = PerceptionEngine.gatherPerception(this);

            // Send position update via TCPServer
            AICompanionMod.tcpServer.onPositionUpdate(
                this.getUUID().toString(),
                this.blockPosition()
            );

            // Also send position update via BridgeClient
            if (AICompanionMod.bridgeClient != null && AICompanionMod.bridgeClient.isConnected()) {
                AICompanionMod.bridgeClient.sendPositionUpdate(
                    this.getUUID().toString(),
                    this.blockPosition()
                );
            }

            // Send danger alerts if critical
            if ("critical".equals(data.urgency)) {
                String alertMsg;
                if (data.dangerLava) {
                    alertMsg = "DANGER: Lava nearby!";
                } else if (data.dangerFall) {
                    alertMsg = "DANGER: About to fall!";
                } else if (data.dangerLowHealth) {
                    alertMsg = "DANGER: Low health!";
                } else {
                    alertMsg = "DANGER: Hostile nearby!";
                }
                AICompanionMod.LOGGER.warn("[AutomatonEntity] " + alertMsg + " for " + this.getName().getString());
            }
        } catch (Exception e) {
            // Don't log every tick error
        }
    }

    // ==================== Factory Method ====================

    public static AutomatonEntity create(ServerLevel level, String characterId, @Nullable Player owner) {
        AutomatonEntity entity = new AutomatonEntity(EntityInit.AUTOMATON.get(), level);
        entity.characterId = characterId;
        if (owner != null) {
            entity.ownerUUID = owner.getUUID();
            entity.entityData.set(DATA_OWNER_UUID, Optional.of(owner.getUUID()));
            entity.setPos(owner.getX() + 2, owner.getY(), owner.getZ() + 2);
            entity.setCustomName(net.minecraft.network.chat.Component.literal(owner.getName().getString() + "'s Companion"));
        }
        // 出生自带基础工具
        entity.giveStarterEquipment();
        return entity;
    }

    /**
     * 给予同伴出生基础工具：石剑(采矿)、石斧(砍树)、石剑(战斗)。     */
    private void giveStarterEquipment() {
        this.inventory.set(0, new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        this.inventory.set(1, new ItemStack(net.minecraft.world.item.Items.STONE_AXE));
        this.inventory.set(2, new ItemStack(net.minecraft.world.item.Items.STONE_SWORD));
        // Auto-equip the best tool
        autoEquip();
    }

    // ==================== NBT Serialization ====================

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("CharacterId")) {
            this.characterId = tag.getString("CharacterId");
        }
        if (tag.contains("OwnerUUID")) {
            this.ownerUUID = tag.getUUID("OwnerUUID");
            this.entityData.set(DATA_OWNER_UUID, Optional.of(this.ownerUUID));
        }
        if (tag.contains("IsEssential")) {
            this.isEssential = tag.getBoolean("IsEssential");
        }
        if (tag.contains("FollowDistance")) {
            this.followDistance = tag.getFloat("FollowDistance");
        }
        if (tag.contains("HomeRadius")) {
            this.homeRadius = tag.getFloat("HomeRadius");
        }
        if (tag.contains("SkinType")) {
            this.skinType = tag.getInt("SkinType");
            this.entityData.set(DATA_SKIN_TYPE, this.skinType);
        }
        if (tag.contains("SkinValue")) {
            this.skinValue = tag.getString("SkinValue");
            this.entityData.set(DATA_SKIN_VALUE, this.skinValue);
        }
        if (tag.contains("HomePos")) {
            CompoundTag homeTag = tag.getCompound("HomePos");
            this.homePos = new BlockPos(
                homeTag.getInt("X"),
                homeTag.getInt("Y"),
                homeTag.getInt("Z")
            );
        }
        // Load inventory
        if (tag.contains("Inventory")) {
            ListTag inventoryTag = tag.getList("Inventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < inventoryTag.size(); i++) {
                CompoundTag itemTag = inventoryTag.getCompound(i);
                int slot = itemTag.getInt("Slot");
                if (slot >= 0 && slot < INVENTORY_SIZE) {
                    this.inventory.set(slot, ItemStack.of(itemTag));
                }
            }
        }
        // Load level & XP
        if (tag.contains("CompanionLevel")) {
            this.level = Math.min(tag.getInt("CompanionLevel"), MAX_LEVEL);
            this.xp = tag.contains("CompanionXP") ? tag.getInt("CompanionXP") : 0;
            this.xpToNext = 50 + level * 80;

            this.entityData.set(DATA_LEVEL, level);
            this.entityData.set(DATA_XP, xp);
            this.entityData.set(DATA_XP_TO_NEXT, xpToNext);
        }
        // Load stat allocation
        if (tag.contains("AvailablePoints")) {
            this.entityData.set(DATA_AVAILABLE_POINTS, tag.getInt("AvailablePoints"));
        }
        if (tag.contains("StrengthPoints")) strengthPoints = tag.getInt("StrengthPoints");
        if (tag.contains("VitalityPoints")) vitalityPoints = tag.getInt("VitalityPoints");
        if (tag.contains("SpeedPoints")) speedPoints = tag.getInt("SpeedPoints");
        if (tag.contains("DefensePoints")) defensePoints = tag.getInt("DefensePoints");

        // Sync loaded stats to client
        this.entityData.set(DATA_STRENGTH_POINTS, strengthPoints);
        this.entityData.set(DATA_VITALITY_POINTS, vitalityPoints);
        this.entityData.set(DATA_SPEED_POINTS, speedPoints);
        this.entityData.set(DATA_DEFENSE_POINTS, defensePoints);

        // Apply loaded stats (after all attributes are registered)
        applyStatAllocation();

        // Load working mode
        if (tag.contains("WorkingMode")) {
            String mode = tag.getString("WorkingMode");
            this.entityData.set(DATA_WORKING_MODE, mode);
            currentState = switch (mode) {
                case "guard" -> CompanionState.GUARD;
                case "gather" -> CompanionState.GATHER;
                case "farm" -> CompanionState.FARM;
                case "patrol" -> CompanionState.PATROL;
                default -> CompanionState.FOLLOW;
            };
        }

        // v3.0: 优先从位掩码恢复能力（新格式）
        if (tag.contains("Capabilities")) {
            scheduler.loadFromNbt(tag.getInt("Capabilities"));
        }
        // Load explicit mode flags (backward compat with old format)
        if (tag.contains("GuardMode") && tag.getBoolean("GuardMode")) scheduler.setActive(CapabilityFlags.GUARD);
        if (tag.contains("GatherMode") && tag.getBoolean("GatherMode")) scheduler.setActive(CapabilityFlags.GATHER);
        if (tag.contains("PatrolMode") && tag.getBoolean("PatrolMode")) scheduler.setActive(CapabilityFlags.PATROL);
        if (tag.contains("FollowModeActive") && tag.getBoolean("FollowModeActive")) scheduler.setActive(CapabilityFlags.FOLLOW);
        syncLegacyState();
        if (tag.contains("AutonomousMode")) autonomousMode = tag.getBoolean("AutonomousMode");
        if (tag.contains("CompanionRole")) companionRole = CompanionRole.valueOf(tag.getString("CompanionRole"));
        if (tag.contains("personality")) personality = CompanionPersonality.fromNBT(tag.getCompound("personality"));

        // Load persistent config state
        if (tag.contains("Hidden")) hidden = tag.getBoolean("Hidden");
        if (tag.contains("MovementStopped")) movementStopped = tag.getBoolean("MovementStopped");
        if (tag.contains("AutoPickupEnabled")) autoPickupEnabled = tag.getBoolean("AutoPickupEnabled");
        if (tag.contains("PickupRadius")) pickupRadius = tag.getDouble("PickupRadius");
        if (tag.contains("PickupOnlyValuable")) pickupOnlyValuable = tag.getBoolean("PickupOnlyValuable");
        if (tag.contains("ChatMsgEnabled")) chatMsgEnabled = tag.getBoolean("ChatMsgEnabled");

        // Load hunger system
        if (tag.contains("HungerLevel")) hungerLevel = tag.getInt("HungerLevel");
        if (tag.contains("SaturationLevel")) saturationLevel = tag.getFloat("SaturationLevel");
        if (tag.contains("ExhaustionLevel")) exhaustionLevel = tag.getFloat("ExhaustionLevel");

        // Load patrol center
        if (tag.contains("PatrolCenter")) {
            CompoundTag patrolTag = tag.getCompound("PatrolCenter");
            patrolCenter = new BlockPos(
                patrolTag.getInt("PX"),
                patrolTag.getInt("PY"),
                patrolTag.getInt("PZ")
            );
        }

        // Apply hidden state
        if (hidden) {
            this.setInvisible(true);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putString("CharacterId", characterId);
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
        }
        tag.putBoolean("IsEssential", isEssential);
        tag.putFloat("FollowDistance", followDistance);
        tag.putFloat("HomeRadius", homeRadius);
        tag.putInt("SkinType", this.entityData.get(DATA_SKIN_TYPE));
        tag.putString("SkinValue", this.entityData.get(DATA_SKIN_VALUE));

        if (homePos != null) {
            CompoundTag homeTag = new CompoundTag();
            homeTag.putInt("X", homePos.getX());
            homeTag.putInt("Y", homePos.getY());
            homeTag.putInt("Z", homePos.getZ());
            tag.put("HomePos", homeTag);
        }
        // Save working mode
        tag.putString("WorkingMode", this.entityData.get(DATA_WORKING_MODE));

        // v3.0: 保存能力位掩码 (向后兼容：同时保留旧 boolean 标记)
        tag.putInt("Capabilities", scheduler.getForNbt());
        tag.putBoolean("GuardMode", isGuardModeEnabled());
        tag.putBoolean("GatherMode", isGatherModeEnabled());
        tag.putBoolean("FarmMode", isFarmModeEnabled());
        tag.putBoolean("PatrolMode", isPatrolModeEnabled());
        tag.putBoolean("FollowModeActive", isFollowModeActive());
        tag.putBoolean("AutonomousMode", autonomousMode);
        tag.putString("CompanionRole", companionRole.name());
        tag.put("personality", personality.toNBT());

        // Save persistent config state
        tag.putBoolean("Hidden", hidden);
        tag.putBoolean("MovementStopped", movementStopped);
        tag.putBoolean("AutoPickupEnabled", autoPickupEnabled);
        tag.putDouble("PickupRadius", pickupRadius);
        tag.putBoolean("PickupOnlyValuable", pickupOnlyValuable);
        tag.putBoolean("ChatMsgEnabled", chatMsgEnabled);

        // Save hunger system
        tag.putInt("HungerLevel", hungerLevel);
        tag.putFloat("SaturationLevel", saturationLevel);
        tag.putFloat("ExhaustionLevel", exhaustionLevel);

        // Save patrol center
        if (patrolCenter != null) {
            CompoundTag patrolTag = new CompoundTag();
            patrolTag.putInt("PX", patrolCenter.getX());
            patrolTag.putInt("PY", patrolCenter.getY());
            patrolTag.putInt("PZ", patrolCenter.getZ());
            tag.put("PatrolCenter", patrolTag);
        }

        // Save inventory
        ListTag inventoryTag = new ListTag();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.inventory.get(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                stack.save(itemTag);
                itemTag.putInt("Slot", i);
                inventoryTag.add(itemTag);
            }
        }
        tag.put("Inventory", inventoryTag);

        // Save level & XP
        tag.putInt("CompanionLevel", level);
        tag.putInt("CompanionXP", xp);
        tag.putInt("XpToNext", xpToNext);

        // Save stat allocation
        tag.putInt("AvailablePoints", this.entityData.get(DATA_AVAILABLE_POINTS));
        tag.putInt("StrengthPoints", strengthPoints);
        tag.putInt("VitalityPoints", vitalityPoints);
        tag.putInt("SpeedPoints", speedPoints);
        tag.putInt("DefensePoints", defensePoints);
    }

    // ==================== Getters and Setters ====================

    public UUID getOwnerUUID() {
        // 优先读本地字段；客户端侧回退到同步数据（ownerUUID从未被客户端设置过）
        if (ownerUUID != null) return ownerUUID;
        return this.entityData.get(DATA_OWNER_UUID).orElse(null);
    }
    public void setOwnerUUID(UUID uuid) {
        this.ownerUUID = uuid;
        if (uuid != null) {
            this.entityData.set(DATA_OWNER_UUID, Optional.of(uuid));
        } else {
            this.entityData.set(DATA_OWNER_UUID, Optional.empty());
        }
    }
    /**
     * Get the owner UUID from the data accessor (auto-synced to client).
     * Used by CompanionClientState to find this player's companion.
     */
    public Optional<UUID> getDataOwnerUUID() {
        return this.entityData.get(DATA_OWNER_UUID);
    }
    public String getCharacterId() { return characterId; }
    public void setCharacterId(String id) { this.characterId = id; }
    public boolean isEssential() { return isEssential; }
    public void setEssential(boolean essential) { this.isEssential = essential; }
    public float getFollowDistance() { return followDistance; }
    public void setFollowDistance(float distance) { this.followDistance = distance; }
    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos pos) { this.homePos = pos; }

    /**
     * Get the owner player (ServerPlayer) if available
     */
    @Nullable
    public ServerPlayer getOwner() {
        if (ownerUUID == null || getServer() == null) {
            return null;
        }
        // Search all dimensions - supports cross-dimension tracking
        for (ServerLevel sl : getServer().getAllLevels()) {
            Player player = sl.getPlayerByUUID(ownerUUID);
            if (player instanceof ServerPlayer sp) return sp;
        }
        return null;
    }

    /**
     * Enable or disable following through the new framework.
     */
    public void setFollowEnabled(boolean enabled) {
        if (enabled) {
            scheduler.jumpToFollow();
            syncLegacyState();
            enableNewFramework("follow");
        } else {
            setMode("idle");
            this.getNavigation().stop();
        }
    }

    // ==================== Follow Mode Toggle (F Key) ====================

    public void setPreCombatState(CompanionState state) { this.preCombatState = state; }
    @Deprecated public String getPreCombatMode() { return preCombatState.name().toLowerCase(); }
    @Deprecated public void setPreCombatMode(String mode) {
        preCombatState = switch (mode) {
            case "gather" -> CompanionState.GATHER;
            case "farm" -> CompanionState.FARM;
            default -> CompanionState.FOLLOW;
        };
    }

    /**
     * Toggle between follow mode and task mode (F key)
     * - If in follow mode: enable the first available task (guard -> mine -> chop)
     * - If in task mode: disable all tasks and return to follow mode
     */
    public boolean toggleFollowMode() {
        if (!scheduler.canToggle()) return scheduler.isActive(CapabilityFlags.FOLLOW);
        scheduler.markToggled(MODE_TOGGLE_COOLDOWN_TICKS);

        int next = scheduler.cycleActive();
        syncLegacyState();
        if (next == CapabilityFlags.FOLLOW) {
            enableNewFramework("follow");
        }
        animateSwing();
        showDialogue(CapabilityFlags.toChineseName(next) + "模式", 60);
        return next == CapabilityFlags.FOLLOW;
    }

    /**
     * Cancel current task and return to follow mode (ESC key)
     * Disables all task modes and sets isFollowModeActive() to true
     */
    public void returnToFollow() {
        scheduler.jumpToFollow();
        syncLegacyState();
        enableNewFramework("follow");
        showDialogue("返回跟随模式", 60);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Returned to follow mode");
    }

    // ==================== Hide/Show (Companion Visibility) ====================

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        this.setInvisible(hidden);
        showDialogue(hidden ? "已隐藏" : "已显示", 40);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Hidden: {}", hidden);
    }

    public void toggleHidden() {
        setHidden(!this.hidden);
    }

    // ==================== Stop Movement ====================

    public boolean isMovementStopped() {
        return this.movementStopped;
    }

    public void stopAllMovement() {
        this.movementStopped = true;
        this.setDeltaMovement(0, 0, 0);
        this.navigation.stop();
        this.getMoveControl().setWantedPosition(this.getX(), this.getY(), this.getZ(), 0);
        showDialogue("已停止移动", 40);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Movement stopped");
    }

    public void resumeMovement() {
        this.movementStopped = false;
        showDialogue("恢复移动", 40);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Movement resumed");
    }

    public void toggleMovementStopped() {
        if (stopToggleCooldown > 0) {
            return;  // Ignore toggle during cooldown
        }
        stopToggleCooldown = STOP_COOLDOWN_TICKS;
        if (this.movementStopped) {
            resumeMovement();
        } else {
            stopAllMovement();
        }
    }

    public BlockPos getPatrolCenter() {
        return this.patrolCenter;
    }

    public void teleportToOwner() {
        ServerPlayer owner = getOwner();
        if (owner != null) {
            playTeleportSound();
            if (!owner.level().dimension().equals(this.level().dimension())) {
                // Cross-dimension teleport
                ServerLevel targetLevel = (ServerLevel) owner.level();
                this.teleportTo(targetLevel, owner.getX(), owner.getY(), owner.getZ(),
                    java.util.Set.of(), owner.getYRot(), owner.getXRot());
            } else {
                this.teleportTo(owner.getX(), owner.getY(), owner.getZ());
            }
            setDeltaMovement(0, 0, 0);
            fallDistance = 0;
            AICompanionMod.LOGGER.info("[AutomatonEntity] Teleported to owner at {}, {}, {}",
                owner.getX(), owner.getY(), owner.getZ());
        }
    }

    public void teleportDown() {
        ServerPlayer owner = getOwner();
        if (owner != null) {
            playTeleportSound();
            // 从主人脚下一格向下扫描，找安全位置（避免窒息）
            double targetY = findSafeYBelow(owner.level(), owner.blockPosition());
            if (!owner.level().dimension().equals(this.level().dimension())) {
                ServerLevel targetLevel = (ServerLevel) owner.level();
                this.teleportTo(targetLevel, owner.getX(), targetY, owner.getZ(),
                    java.util.Set.of(), owner.getYRot(), owner.getXRot());
            } else {
                this.teleportTo(owner.getX(), targetY, owner.getZ());
            }
            setDeltaMovement(0, 0, 0);
            fallDistance = 0;
            showDialogue("下来了！", 40);
            AICompanionMod.LOGGER.info("[AutomatonEntity] Teleported down to {}", targetY);
        }
    }

    /**
     * 从给定位置向下扫描，找到第一个安全落脚点（脚下两格都是空气或可穿过）。
     * 最多向下扫描10格，找不到则返回原位-1。     */
    private double findSafeYBelow(Level level, BlockPos ownerPos) {
        for (int dy = 1; dy <= 10; dy++) {
            BlockPos footPos = ownerPos.below(dy);
            BlockPos headPos = footPos.above();
            if (level.getBlockState(footPos).isAir() && level.getBlockState(headPos).isAir()) {
                return footPos.getY() + 0.1;
            }
        }
        return ownerPos.getY() - 1; // fallback
    }

    // ==================== Skin Management ====================

    public static final int SKIN_TYPE_DEFAULT = 0;
    public static final int SKIN_TYPE_URL = 1;
    public static final int SKIN_TYPE_PLAYER = 2;

    public int getSkinType() { return this.entityData.get(DATA_SKIN_TYPE); }
    public String getSkinValue() { return this.entityData.get(DATA_SKIN_VALUE); }

    /**
     * Set default skin (Steve slim)
     */
    public void setDefaultSkin() {
        this.skinType = SKIN_TYPE_DEFAULT;
        this.skinValue = "";
        this.entityData.set(DATA_SKIN_TYPE, SKIN_TYPE_DEFAULT);
        this.entityData.set(DATA_SKIN_VALUE, "");
    }

    /**
     * Set skin from a URL
     */
    public void setSkinFromUrl(String url) {
        this.skinType = SKIN_TYPE_URL;
        this.skinValue = url;
        this.entityData.set(DATA_SKIN_TYPE, SKIN_TYPE_URL);
        this.entityData.set(DATA_SKIN_VALUE, url);
    }

    /**
     * Set skin from a Minecraft player's username
     */
    public void setSkinFromPlayer(String playerName) {
        this.skinType = SKIN_TYPE_PLAYER;
        this.skinValue = playerName;
        this.entityData.set(DATA_SKIN_TYPE, SKIN_TYPE_PLAYER);
        this.entityData.set(DATA_SKIN_VALUE, playerName);
    }

    /**
     * Get unique skin ID for this entity's current skin configuration
     */
    public String getSkinId() {
        return getUUID().toString() + "_" + skinType + "_" + skinValue;
    }

    // ==================== Inventory Management ====================

    /**
     * Get the inventory item at the specified slot
     */
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return ItemStack.EMPTY;
        }
        return inventory.get(slot);
    }

    /**
     * Set the inventory item at the specified slot
     */
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return;
        }
        this.inventory.set(slot, stack);
    }

    /**
     * Get the full inventory
     */
    public NonNullList<ItemStack> getInventory() {
        return inventory;
    }

    /**
     * Get inventory size
     */
    public int getInventorySize() {
        return INVENTORY_SIZE;
    }

    /**
     * Check if inventory has any items
     */
    public boolean hasItems() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clear all items in inventory
     */
    public void clearInventory() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            this.inventory.set(i, ItemStack.EMPTY);
        }
    }

    // ==================== Equipment Helpers ====================

    /**
     * Get the currently equipped main hand item (weapon/tool)
     */
    public ItemStack getEquippedTool() {
        return this.getItemBySlot(EquipmentSlot.MAINHAND);
    }

    /**
     * Get the dig speed of the currently equipped tool against a block state.
     * Returns 1.0f if no tool is held (matches hand speed).
     */
    public float getToolDigSpeed(BlockState state) {
        ItemStack tool = getEquippedTool();
        if (tool.isEmpty()) return 1.0f;
        return Math.max(tool.getDestroySpeed(state), 1.0f);
    }

    /**
     * 获取有效挖掘速度——完全复刻原版玩家公式。
     * 含效率附魔、急迫/挖掘疲劳效果，与玩家挖掘行为一致。
     */
    public float getEffectiveDigSpeed(BlockState state) {
        ItemStack tool = getEquippedTool();
        float speed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);

        // 效率附魔（仅在基础速度>1且工具类型正确时生效）
        if (speed > 1.0f && tool.isCorrectToolForDrops(state)) {
            int efficiency = net.minecraft.world.item.enchantment.EnchantmentHelper.getBlockEfficiency(this);
            if (efficiency > 0) {
                speed += efficiency * efficiency + 1;
            }
        }

        // 急迫效果（每级+20%）
        if (hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED)) {
            int amp = getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED).getAmplifier();
            speed *= 1.0f + (amp + 1) * 0.2f;
        }

        // 挖掘疲劳（每级-0.3）
        if (hasEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)) {
            int amp = getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN).getAmplifier();
            speed *= (float) Math.pow(0.3, amp + 1);
        }

        return Math.max(speed, 1.0f);
    }

    /**
     * Get the fortune level on the currently held tool
     */
    public int getFortuneLevel() {
        ItemStack tool = getEquippedTool();
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, tool);
    }

    // ==================== Auto-Defend ====================

    /**
     * Set an auto-defend target (e.g. when owner is attacked).
     * Companion will attack this target for the given duration regardless of current mode.
     */
    public void setAutoDefendTarget(@Nullable net.minecraft.world.entity.LivingEntity target, int ticks) {
        this.autoDefendTarget = target;
        this.autoDefendTicks = ticks;
    }

    // ==================== Guard Mode (LivingEntity Combat) ====================

    // ==================== Unified State Machine ====================

    public CompanionState getState() { return currentState; }
    public CompanionState getPreCombatState() { return preCombatState; }

    /** 保存当前状态为战斗前状态（供 GuardGoal 战斗结束后恢复） */
    public void savePreCombatState() {
        if (scheduler.isActive(CapabilityFlags.GATHER) || scheduler.isActive(CapabilityFlags.FARM)
                || scheduler.isActive(CapabilityFlags.PATROL)) {
            scheduler.interruptForDanger("combat");
        }
    }

    /** 战斗结束恢复战前状态 */
    public void restorePreCombatState() {
        scheduler.resumeFromDanger();
        syncLegacyState();
    }

    /** 统一的模式切换入口。自动处理互斥和音效。 */
    /** @deprecated v3.0: 使用 scheduler.setActive() 替代 */
    @Deprecated
    public void transitionTo(CompanionState newState) {
        // 映射到新能力系统
        int cap = switch (newState) {
            case GUARD -> CapabilityFlags.GUARD;
            case GATHER -> CapabilityFlags.GATHER;
            case FARM -> CapabilityFlags.FARM;
            case PATROL -> CapabilityFlags.PATROL;
            default -> CapabilityFlags.FOLLOW;
        };
        scheduler.setActive(cap);
        syncLegacyState();
        if (!scheduler.isActive(CapabilityFlags.GUARD)) {
            guardTarget = null;
        }
        this.entityData.set(DATA_WORKING_MODE, scheduler.getActiveName().toLowerCase());
    }

    // v3.0: 委托给 CapabilityScheduler，保持旧方法签名兼容
    public boolean isGuardModeEnabled()   { return scheduler.isActive(CapabilityFlags.GUARD); }
    public boolean isGatherModeEnabled()  { return scheduler.isActive(CapabilityFlags.GATHER); }

    /** 同伴是否正在执行采集动作（导航中或挖掘中），用于抑制对话打断 */
    public boolean isActivelyMining() {
        return activelyGathering;
    }

    public void setActivelyGathering(boolean active) {
        this.activelyGathering = active;
    }
    public boolean isFarmModeEnabled()    { return scheduler.isActive(CapabilityFlags.FARM); }
    public boolean isFollowModeActive()   { return scheduler.isActive(CapabilityFlags.FOLLOW); }
    public boolean isPatrolModeEnabled()  { return scheduler.isActive(CapabilityFlags.PATROL); }

    /** v3.0: 暴露调度器给新 Goal 使用 */
    public CapabilityScheduler getScheduler() { return scheduler; }

    // v3.0: 委托给 CapabilityScheduler
    public void setGuardModeEnabled(boolean enabled) {
        if (enabled) scheduler.setActive(CapabilityFlags.GUARD);
        else if (scheduler.isActive(CapabilityFlags.GUARD)) scheduler.setActive(CapabilityFlags.FOLLOW);
        syncLegacyState();
    }

    public void setGatherModeEnabled(boolean enabled) {
        if (enabled) {
            scheduler.setActive(CapabilityFlags.GATHER);
            String mode = switch (gatherFilter) {
                case "ores" -> "mine";
                case "wood" -> "chop";
                default -> "autonomous";
            };
            enableNewFramework(mode);
        } else if (scheduler.isActive(CapabilityFlags.GATHER)) {
            scheduler.setActive(CapabilityFlags.FOLLOW);
            enableNewFramework("follow");
        }
        syncLegacyState();
    }

    public void setFarmModeEnabled(boolean enabled) {
        if (enabled) scheduler.setActive(CapabilityFlags.FARM);
        else if (scheduler.isActive(CapabilityFlags.FARM)) scheduler.setActive(CapabilityFlags.FOLLOW);
        syncLegacyState();
    }

    /** 同步旧 currentState 到新 scheduler（过渡期兼容） */
    private void syncLegacyState() {
        int active = scheduler.getActiveLayer();
        currentState = switch (active) {
            case CapabilityFlags.GUARD -> CompanionState.GUARD;
            case CapabilityFlags.GATHER -> CompanionState.GATHER;
            case CapabilityFlags.FARM -> CompanionState.FARM;
            case CapabilityFlags.PATROL -> CompanionState.PATROL;
            default -> CompanionState.FOLLOW;
        };
        // 同步到EntityData，确保HUD和客户端显示正确
        this.entityData.set(DATA_WORKING_MODE, scheduler.getActiveName().toLowerCase());
    }

    public void setPatrolModeEnabled(boolean enabled) {
        if (enabled) {
            this.patrolCenter = this.getOnPos();
            transitionTo(CompanionState.PATROL);
            showDialogue("巡逻模式", 60);
        } else if (currentState == CompanionState.PATROL) {
            transitionTo(CompanionState.FOLLOW);
            showDialogue("退出巡逻", 40);
        }
    }

    /**
     * Set the current guard target
     */
    public void setGuardTarget(@Nullable net.minecraft.world.entity.LivingEntity target) {
        this.guardTarget = target;
    }

    /**
     * Get the current guard target
     */
    @Nullable
    public net.minecraft.world.entity.LivingEntity getGuardTarget() {
        return guardTarget;
    }

    /**
     * Check if a target is within melee attack range
     * Uses default ATTACK_REACH constant since ATTACK_RANGE attribute may not exist
     */
    public boolean isWithinAttackRange(net.minecraft.world.entity.LivingEntity target) {
        // Use horizontal distance + vertical tolerance for melee
        double horizontalDistSq = this.distanceToSqr(target.getX(), this.getY(), target.getZ());
        double verticalDist = Math.abs(this.getY() - target.getY());
        // Can attack if horizontally close enough and vertically within 2 blocks
        return horizontalDistSq <= ATTACK_REACH * ATTACK_REACH && verticalDist <= 2.0;
    }

    /**
     * Swing hand to perform attack animation
     */
    public void swingAttackHand() {
        // Use the swing method inherited from LivingEntity with InteractionHand
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /**
     * Perform a melee attack on a target (uses Minecraft's built-in attack system)
     */
    public void doAttack(net.minecraft.world.entity.LivingEntity target) {
        if (this.isWithinAttackRange(target)) {
            this.swingAttackHand();
            this.doHurtTarget(target);
        }
    }

    // ==================== Gather Mode (智能统一采集) ====================

    /** "all", "ores", "wood" */
    public String getGatherFilter() { return gatherFilter; }

    public void setGatherFilter(String filter) {
        this.gatherFilter = filter;
        if (isGatherModeEnabled()) {
            String mode = switch (gatherFilter) {
                case "ores" -> "mine";
                case "wood" -> "chop";
                default -> "autonomous";
            };
            enableNewFramework(mode);
        }
    }

    /** LLM-set priority resources (substrings like "iron", "diamond") */
    public java.util.Set<String> getGatherPriorityResources() { return gatherPriorityResources; }

    public void setGatherPriority(String resources) {
        gatherPriorityResources.clear();
        for (String r : resources.split("[,\\s]+")) {
            if (!r.isEmpty()) gatherPriorityResources.add(r.trim().toLowerCase());
        }
    }

    /** 指定采集目标方块 (如 "minecraft:iron_ore")，空字符串表示不限 */
    public String getGatherTargetBlock() { return gatherTargetBlock; }

    public void setGatherTargetBlock(String blockId) { this.gatherTargetBlock = blockId; }

    // ==================== Item Collection ====================

    /**
     * Add an item to the companion's inventory.
     * @return true if item was added, false if inventory is full
     */
    public boolean addItemToInventory(ItemStack item) {
        if (item.isEmpty()) {
            return true;
        }

        // Try to stack with existing items first
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack slot = this.inventory.get(i);
            if (!slot.isEmpty() && slot.getCount() < slot.getMaxStackSize()) {
                // Check if items are stackable (same item, damage, and NBT)
                if (ItemStack.isSameItemSameTags(slot, item)) {
                    int spaceLeft = slot.getMaxStackSize() - slot.getCount();
                    int toAdd = Math.min(spaceLeft, item.getCount());
                    slot.grow(toAdd);
                    item.shrink(toAdd);
                    if (item.isEmpty()) {
                        return true;
                    }
                }
            }
        }

        // Find empty slot
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (this.inventory.get(i).isEmpty()) {
                this.inventory.set(i, item);
                return true;
            }
        }

        // Inventory full
        return false;
    }

    /**
     * Get current mode status as string for display
     */
    public String getCurrentModeString() {
        return switch (currentState) {
            case GUARD -> "§c守护";
            case GATHER -> "§6采集";
            case FARM -> "§a种植";
            case PATROL -> "§b巡逻";
            default -> "§a跟随";
        };
    }

    /**
     * Get the working mode string for HUD sync.
     * Returns a short English string: "follow", "guard", "mine", "chop"
     */
    public String getWorkingMode() {
        return this.entityData.get(DATA_WORKING_MODE);
    }

    // ==================== MenuProvider 接口 ====================

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return getCustomName() != null
            ? getCustomName()
            : net.minecraft.network.chat.Component.translatable("container.companion.inventory");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
            net.minecraft.world.entity.player.Inventory playerInv, net.minecraft.world.entity.player.Player player) {
        return new CompanionContainer(containerId, playerInv, this.getId());
    }

    /**
     * Get the mode data string based on current state (for mode transitions).
     */
    private String getModeDataString() {
        if (isGuardModeEnabled()) return "guard";
        if (isGatherModeEnabled()) return "gather";
        if (isFarmModeEnabled()) return "farm";
        if (isFollowModeActive()) return "follow";
        return "follow";
    }

    // ==================== Level & XP System ====================

    public int getTickCounter() { return tickCount; }

    public int getLevel() { return this.entityData.get(DATA_LEVEL); }
    public int getXp() { return this.entityData.get(DATA_XP); }
    public int getXpToNext() { return xpToNext; }

    /**
     * Grant XP to the companion. Triggers level-up when enough XP is accumulated.
     */
    public void grantXp(int amount) {
        if (this.level().isClientSide) return;
        if (level >= MAX_LEVEL) return;

        xp += amount;
        AICompanionMod.LOGGER.info("[Level] Companion {} gained {} XP (total: {}/{})",
            this.getUUID().toString().substring(0, 8), amount, xp, xpToNext);

        // Check for level-up
        while (xp >= xpToNext && level < MAX_LEVEL) {
            xp -= xpToNext;
            level++;
            xpToNext = 50 + level * 80; // Level N→N+1: 50 + N*80
            onLevelUp();
        }

        // Sync to client
        this.entityData.set(DATA_LEVEL, level);
        this.entityData.set(DATA_XP, xp);
        this.entityData.set(DATA_XP_TO_NEXT, xpToNext);

        // 满级时触发LLM介绍对话，提示可招募新同伴
        if (level >= MAX_LEVEL) {
            triggerMaxLevelRecruitDialogue();
        }
    }

    /** 是否满级 */
    public boolean isMaxLevel() { return level >= MAX_LEVEL; }

    public CompanionRole getRole() { return companionRole; }
    public void setRole(CompanionRole role) { this.companionRole = role; }
    public CompanionPersonality getPersonality() { return personality; }
    public void setPersonality(CompanionPersonality p) { this.personality = p; }

    /** 满级时触发LLM对话，介绍招募新同伴的可能 */
    private void triggerMaxLevelRecruitDialogue() {
        int squadSize = AICompanionMod.companionManager.squadSize(getOwnerUUID());
        if (squadSize >= CompanionManager.MAX_COMPANIONS) return; // 已满3个

        String roleHint = switch (companionRole) {
            case MINER -> "我是矿工，也许你需要一个守卫来保护我们？";
            case GUARD -> "我是守卫，要不要招募一个矿工帮忙采集资源？";
            case FARMER -> "我是农民，也许你需要一个探索者去开地图？";
            case BUILDER -> "我是建筑师，要不要找个矿工帮忙采集建材？";
            default -> "你可以招募不同角色的同伴组成小队！";
        };
        showDialogue("§d✨ 我已满级！" + roleHint + " §7[/companion squad create <角色>]", 200);

        // 异步触发LLM生成更有趣的介绍对话
        if (AICompanionMod.LLM_DISABLED) return;
        ServerPlayer owner = getOwner();
        if (owner != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String model = com.aiworkbench.companion.CompanionConfig.getModel(owner.getUUID());
                    String prompt = "你是Minecraft中的" + companionRole.chineseName + "同伴，你刚达到满级Lv." + MAX_LEVEL +
                        "。请用一句话（15字以内）通知主人可以招募新同伴了。你有" + squadSize + "个同伴，最多" + CompanionManager.MAX_COMPANIONS + "个。";
                    java.util.LinkedHashMap<String, Object> opts = new java.util.LinkedHashMap<>();
                    opts.put("temperature", OllamaClient.TEMP_CREATIVE);
                    opts.put("num_predict", 40);
                    String response = com.aiworkbench.companion.ai.OllamaClient.chat(model, null, prompt, opts, 10000, 1);
                    if (response != null && !response.isEmpty()) {
                        final String msg = response.trim();
                        if (getServer() != null) {
                            getServer().execute(() -> showDialogue("§d💬 " + msg, 120));
                        }
                    }
                } catch (Exception e) {
            AICompanionMod.LOGGER.warn("[AutomatonEntity] Action failed: {}", e.getMessage());}
            });
        }
    }

    /**
     * 根据方块类型计算采矿XP奖励。     * 稀有矿石和深层变种给予更多XP。     */
    public void grantMiningXp(net.minecraft.world.level.block.state.BlockState state) {
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        int xp;
        if (name.contains("diamond")) {
            xp = 25;
            addRecentEvent("rare_resource", "发现了钻石！");
            triggerEventDialogue("rare_resource", java.util.Map.of("resource", "钻石"));
        } else if (name.contains("emerald")) {
            xp = 25;
            addRecentEvent("rare_resource", "发现了绿宝石");
            triggerEventDialogue("rare_resource", java.util.Map.of("resource", "绿宝石"));
        } else if (name.contains("ancient_debris")) {
            xp = 50;
            addRecentEvent("rare_resource", "发现了远古残骸！");
            triggerEventDialogue("rare_resource", java.util.Map.of("resource", "远古残骸"));
        } else if (name.contains("netherite")) {
            xp = 40;
        } else if (name.contains("gold") || name.contains("lapis")) {
            xp = 15;
        } else if (name.contains("redstone")) {
            xp = 12;
        } else if (name.contains("deepslate")) {
            xp = 18;
        } else {
            xp = 10;
        }
        grantXp(xp);
    }

    /**
     * 砍伐树木获得XP（每根原木固定值）。
     */
    public void grantChoppingXp() {
        grantXp(10);
    }

    /**
     * Trigger level-up effects and grant stat points.
     * Stats are no longer auto-applied; player allocates points manually.
     */
    private void onLevelUp() {
        // Grant attribute points (3 points per level, cumulative)
        this.entityData.set(DATA_AVAILABLE_POINTS, (level - 1) * POINTS_PER_LEVEL);

        // Play sound and particles
        playLevelUpEffect();

        // Notify owner
        String msg = "\u00a76\u00a7l✨升级！同伴达到Lv." + level + "！+" + POINTS_PER_LEVEL + "属性点";
        showDialogue(msg, 100);
        // Record event + trigger LLM contextual level-up dialogue
        addRecentEvent("level_up", "升到了Lv." + level);
        triggerEventDialogue("level_up", java.util.Map.of("level", level));

        ServerPlayer owner = getOwner();
        if (owner != null) {
            String name = this.getCustomName() != null ? this.getCustomName().getString() : "同伴";
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a76\u00a7l✨" + name + " 升级到Lv." + level
                + "！获得" + POINTS_PER_LEVEL + " 属性点"));
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a77使用 \u00a7f/companion stats add <属性> <点数> \u00a77分配属性点"));
        }

        AICompanionMod.LOGGER.info("[Level] Companion leveled up to {}! Available points: {}",
            level, this.entityData.get(DATA_AVAILABLE_POINTS));
    }

    /**
     * 应用属性点分配，重新计算所有属性基值。     * 在玩家分配点数后调用。     */
    private void applyStatAllocation() {
        // Base stats match a fresh player
        double baseHealth = 20.0;
        double baseAttack = 1.0;
        double baseSpeed = 0.1;
        double baseArmor = 0.0;

        double newHealth = baseHealth + vitalityPoints * 1.0;
        double newAttack = baseAttack + strengthPoints * 0.2;
        double newSpeed = baseSpeed + speedPoints * 0.003;
        this.baseMoveSpeed = newSpeed;
        double newArmor = baseArmor + defensePoints * 0.3;

        var healthAttr = this.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) healthAttr.setBaseValue(newHealth);

        var atkAttr = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null) atkAttr.setBaseValue(newAttack);

        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) speedAttr.setBaseValue(newSpeed);

        var armorAttr = this.getAttribute(Attributes.ARMOR);
        if (armorAttr != null) armorAttr.setBaseValue(newArmor);

        AICompanionMod.LOGGER.info("[Stats] Applied: HP={}, ATK={}, SPD={}, ARMOR={} | str={}, vit={}, spd={}, def={}",
            (int)newHealth, String.format("%.1f", newAttack), String.format("%.2f", newSpeed),
            String.format("%.1f", newArmor), strengthPoints, vitalityPoints, speedPoints, defensePoints);
    }

    /**
     * 尝试分配属性点。成功返回true，点数不足或达上限返回false。     */
    public boolean allocateStat(String stat, int points) {
        if (points <= 0) return false;
        int available = this.entityData.get(DATA_AVAILABLE_POINTS);
        if (available < points) return false;

        boolean allocated = false;
        switch (stat) {
            case "vitality", "vit", "体力" -> {
                if (vitalityPoints + points > MAX_VITALITY_POINTS) return false;
                vitalityPoints += points;
                allocated = true;
            }
            case "strength", "str", "力量" -> {
                if (strengthPoints + points > MAX_ATTACK_POINTS) return false;
                strengthPoints += points;
                allocated = true;
            }
            case "speed", "spd", "速度" -> {
                if (speedPoints + points > MAX_SPEED_POINTS) return false;
                speedPoints += points;
                allocated = true;
            }
            case "defense", "def", "防御" -> {
                if (defensePoints + points > MAX_DEFENSE_POINTS) return false;
                defensePoints += points;
                allocated = true;
            }
            default -> { return false; }
        }

        if (allocated) {
            this.entityData.set(DATA_AVAILABLE_POINTS, available - points);
            this.entityData.set(DATA_STRENGTH_POINTS, strengthPoints);
            this.entityData.set(DATA_VITALITY_POINTS, vitalityPoints);
            this.entityData.set(DATA_SPEED_POINTS, speedPoints);
            this.entityData.set(DATA_DEFENSE_POINTS, defensePoints);
            applyStatAllocation();
        }
        return allocated;
    }

    public int getAvailablePoints() { return this.entityData.get(DATA_AVAILABLE_POINTS); }
    public int getStrengthPoints() { return this.entityData.get(DATA_STRENGTH_POINTS); }
    public int getVitalityPoints() { return this.entityData.get(DATA_VITALITY_POINTS); }
    public int getSpeedPoints() { return this.entityData.get(DATA_SPEED_POINTS); }
    public int getDefensePoints() { return this.entityData.get(DATA_DEFENSE_POINTS); }
    public String getActionText() { return this.entityData.get(DATA_ACTION_TEXT); }
    public void setActionText(String text) { this.entityData.set(DATA_ACTION_TEXT, text); }

    public void resetAllStats() {
        strengthPoints = 0;
        vitalityPoints = 0;
        speedPoints = 0;
        defensePoints = 0;
        int totalLevels = (level - 1);
        this.entityData.set(DATA_AVAILABLE_POINTS, totalLevels * POINTS_PER_LEVEL);
        this.entityData.set(DATA_STRENGTH_POINTS, 0);
        this.entityData.set(DATA_VITALITY_POINTS, 0);
        this.entityData.set(DATA_SPEED_POINTS, 0);
        this.entityData.set(DATA_DEFENSE_POINTS, 0);
        applyStatAllocation();
    }

    /**
     * Play level-up particle and sound effects.
     */
    private void playLevelUpEffect() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                this.getX(), this.getY() + 1.0, this.getZ(),
                30, 0.5, 0.5, 0.5, 0.5);
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                this.getX(), this.getY() + 1.0, this.getZ(),
                20, 0.5, 0.5, 0.5, 0.3);
            serverLevel.sendParticles(ParticleTypes.FIREWORK,
                this.getX(), this.getY() + 1.5, this.getZ(),
                15, 0.6, 0.4, 0.6, 0.1);
        }
        this.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    /**
     * Set companion level directly (admin command). Also adjusts stats.
     */
    public void setLevel(int newLevel) {
        if (this.level().isClientSide) return;
        this.level = Math.min(Math.max(newLevel, 1), MAX_LEVEL);
        this.xp = 0;
        this.xpToNext = 50 + level * 80;

        // Grant accumulated points (player allocates manually)
        int totalPoints = (level - 1) * POINTS_PER_LEVEL;
        this.entityData.set(DATA_AVAILABLE_POINTS, totalPoints);
        this.entityData.set(DATA_LEVEL, level);
        this.entityData.set(DATA_XP, 0);
        this.entityData.set(DATA_XP_TO_NEXT, xpToNext);

        // Reset stats: give all points back as unallocated
        strengthPoints = 0;
        vitalityPoints = 0;
        speedPoints = 0;
        defensePoints = 0;
        this.entityData.set(DATA_STRENGTH_POINTS, 0);
        this.entityData.set(DATA_VITALITY_POINTS, 0);
        this.entityData.set(DATA_SPEED_POINTS, 0);
        this.entityData.set(DATA_DEFENSE_POINTS, 0);
        applyStatAllocation();

        AICompanionMod.LOGGER.info("[Level] Companion level set to {} (admin), {} points available", level, totalPoints);
    }

    // ==================== Auto-Pickup Getters/Setters ====================

    public boolean isAutoPickupEnabled() { return autoPickupEnabled; }
    public void setAutoPickupEnabled(boolean enabled) { this.autoPickupEnabled = enabled; }
    public double getPickupRadius() { return pickupRadius; }
    public void setPickupRadius(double radius) { this.pickupRadius = Math.max(1.0, Math.min(16.0, radius)); }
    public boolean isPickupOnlyValuable() { return pickupOnlyValuable; }
    public void setPickupOnlyValuable(boolean valuable) { this.pickupOnlyValuable = valuable; }
    public boolean isChatMsgEnabled() { return chatMsgEnabled; }
    public void setChatMsgEnabled(boolean enabled) { this.chatMsgEnabled = enabled; }

    // ==================== Hunger System Getters/Setters ====================
    public int getHungerLevel() { return hungerLevel; }
    public float getSaturationLevel() { return saturationLevel; }
    public void setHungerLevel(int level) { this.hungerLevel = Math.max(0, Math.min(MAX_HUNGER, level)); }
    public void setSaturationLevel(float level) { this.saturationLevel = Math.max(0, Math.min(MAX_SATURATION, level)); }

    // ==================== Auto-Recall System ====================

    /**
     * Check if the companion should auto-recall to the owner.
     * - Cross-dimension: teleport after 5 second warning
     * - Same-dimension distance > 32 blocks: teleport after 5 second warning
     * Resets warning when owner comes back within range.
     */
    private void checkAutoRecall() {
        if (this.level().isClientSide) return;

        // 任务模式中不自动召回（让同伴完成采矿/砍树/防守）
        if (!isFollowModeActive()) return;

        ServerPlayer owner = getOwner();
        if (owner == null) return;

        // Cross-dimension: teleport to owner's dimension immediately with warning
        if (!owner.level().dimension().equals(this.level().dimension())) {
            if (!recallWarningActive) {
                recallWarningActive = true;
                recallWarningTicks = 0;
                showDialogue("\u00a7d主人穿越了维度！", 80);
            } else {
                recallWarningTicks += 20;
                if (recallWarningTicks >= RECALL_WARNING_TICKS) {
                    doCrossDimensionRecall(owner);
                    recallWarningActive = false;
                    recallWarningTicks = 0;
                }
            }
            return;
        }

        // Same-dimension distance check
        double distSq = this.distanceToSqr(owner);

        if (distSq > RECALL_DISTANCE_SQ) {
            if (!recallWarningActive) {
                recallWarningActive = true;
                recallWarningTicks = 0;
                showDialogue("\u00a7e主人太远了！5秒后传送...", 80);
            } else {
                recallWarningTicks += 20;
                if (recallWarningTicks >= RECALL_WARNING_TICKS) {
                    playTeleportSound();
                    this.teleportTo(owner.getX(), owner.getY(), owner.getZ());
                    this.setDeltaMovement(0, 0, 0);
                    this.fallDistance = 0;
                    showDialogue("\u00a7a传送完成！", 60);
                    recallWarningActive = false;
                    recallWarningTicks = 0;
                }
            }
        } else {
            if (recallWarningActive) {
                recallWarningActive = false;
                recallWarningTicks = 0;
                showDialogue("\u00a7a已跟上主人！", 40);
            }
        }
    }

    /**
     * Cross-dimension recall: teleport companion to owner's current dimension.
     */
    private void doCrossDimensionRecall(ServerPlayer owner) {
        ServerLevel targetLevel = (ServerLevel) owner.level();
        playTeleportSound();
        this.teleportTo(targetLevel, owner.getX(), owner.getY(), owner.getZ(),
            java.util.Set.of(), owner.getYRot(), owner.getXRot());
        this.setDeltaMovement(0, 0, 0);
        this.fallDistance = 0;
        showDialogue("\u00a7d穿越维度完成！", 60);

        // Update CompanionManager registration
        if (AICompanionMod.companionManager != null) {
            AICompanionMod.companionManager.addCompanion(ownerUUID, this);
        }
    }

    /**
     * Force-stop navigation if stuck in place for 5+ seconds.
     * Vanilla navigation has a bug: timeout=0 is ignored when speed=0,
     * causing entities to get stuck forever against walls.
     */
    private void checkNavigationTimeout() {
        if (this.navigation.isDone()) {
            navStuckTicks = 0;
            return;
        }
        if (this.blockPosition().distSqr(lastNavCheckPos) < 2.0) {
            if (++navStuckTicks > 100) {
                this.navigation.stop();
                navStuckTicks = 0;
                AICompanionMod.LOGGER.debug("[AutomatonEntity] Navigation timed out — force stopped");
            }
        } else {
            navStuckTicks = 0;
            lastNavCheckPos = this.blockPosition();
        }
    }

    // ==================== Skill Engine ====================

    public SkillEngine getSkillEngine() {
        return skillEngine;
    }

    // ==================== TaskPlanner ====================
    /** 将高层任务描述分解为 SkillEngine 可执行的 Skill。桥接 AutoCurriculum ↔ SkillEngine。 */
    public Skill planTask(String taskDescription) {
        if (AICompanionMod.skillLibrary == null) return null;
        Skill preset = AICompanionMod.skillLibrary.findSkillByDescription(taskDescription, getOwnerUUID());
        if (preset != null) return preset;
        // Fallback: 尝试用 gather 模式替代采集类任务
        String lower = taskDescription.toLowerCase();
        if (lower.contains("挖") || lower.contains("矿") || lower.contains("mine") || lower.contains("砍") || lower.contains("chop")) {
            if (!isGatherModeEnabled()) setGatherModeEnabled(true);
        }
        return null;
    }

    public boolean isSkillActive() {
        return skillActive;
    }

    public void setSkillActive(boolean active) {
        this.skillActive = active;
    }

    // ==================== AutoCurriculum / Autonomous Mode ====================

    /**
     * 评估环境并生成课程提议。     * 自主模式下直接执行，手动模式下显示提议等待玩家确认。     */
    private void evaluateCurriculum() {
        if (autonomousMode) {
            java.util.List<CurriculumProposal> proposals = AutoCurriculum.proposeQueue(this);
            if (proposals.isEmpty()) return;
            int enqueued = 0;
            for (CurriculumProposal p : proposals) {
                if (taskQueue.enqueue(p)) enqueued++;
            }
            if (enqueued > 0) {
                AICompanionMod.LOGGER.info("[AutoCurriculum] Enqueued {} proposals", enqueued);
            }
        } else {
            CurriculumProposal proposal = AutoCurriculum.proposeNextTask(this);
            if (proposal == null) return;
            String category = proposal.suggestedSkill != null ? proposal.suggestedSkill : "general";
            MessagePriority mp = proposal.isHighPriority() ? MessagePriority.HIGH : MessagePriority.NORMAL;
            dialogueStack.push(new PendingMessage(
                "curriculum_" + System.currentTimeMillis(),
                proposal.taskDescription,
                category, mp, proposal.suggestedSkill,
                extractKeywords(proposal.taskDescription)
            ));
            String msg = "§e" + "💡 " + proposal.taskDescription + " §7[/companion confirm]";
            showDialogue(msg, 120);
            this.pendingProposal = proposal;
        }
    }

    /**
     * 执行课程提议 —— 根据建议的技能名查找并启动技能。     */
    private void executeCurriculumProposal(CurriculumProposal proposal) {
        if (proposal.hasSkill()) {
            // "gather" is a special action that enables continuous gathering mode
            if ("gather".equals(proposal.suggestedSkill)) {
                setGatherModeEnabled(true);
                showDialogue("\u00a7a🔧" + proposal.taskDescription, 60);
                AICompanionMod.LOGGER.info("[AutoCurriculum] Enabled gather mode: {}", proposal.taskDescription);
                return;
            }
            Skill skill = AICompanionMod.skillLibrary.getPreset(proposal.suggestedSkill);
            if (skill != null) {
                this.skillEngine.startSkill(skill, this);
                showDialogue("\u00a7a🔧 " + proposal.taskDescription, 60);
            } else {
                // Fallback: if skill not registered, try enabling gather mode for resource proposals
                String skillName = proposal.suggestedSkill;
                if (skillName != null && (skillName.startsWith("mine") || skillName.contains("Wood") || skillName.contains("collect"))) {
                    setGatherModeEnabled(true);
                    showDialogue("\u00a7a🔧" + proposal.taskDescription, 60);
                }
            }
        }
    }

    /**
     * 玩家确认当前待处理的课程提议。     */
    public void confirmProposal() {
        if (pendingProposal != null) {
            executeCurriculumProposal(pendingProposal);
            pendingProposal = null;
        }
    }

    /**
     * 获取当前待处理的课程提议（用于外部查询）。     */
    public CurriculumProposal getPendingProposal() {
        return pendingProposal;
    }

    /** 获取对话消息栈 */
    public DialogueStack getDialogueStack() { return dialogueStack; }
    /** 获取任务队列 */
    public TaskQueue getTaskQueue() { return taskQueue; }
    /** 从文本中提取匹配关键词 */
    private static String[] extractKeywords(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        java.util.List<String> k = new java.util.ArrayList<>();
        String l = text.toLowerCase();
        if (l.contains("矿") || l.contains("ore")) k.add("矿");
        if (l.contains("树") || l.contains("木") || l.contains("wood")) k.add("树");
        if (l.contains("战") || l.contains("敌") || l.contains("mob")) k.add("战");
        if (l.contains("建") || l.contains("build")) k.add("建");
        if (l.contains("回") || l.contains("跟") || l.contains("follow")) k.add("回");
        return k.toArray(new String[0]);
    }

    /**
     * 获取当前待处理提议或队列状态的文本描述（用于GUI显示）。
     */
    public String getPendingProposalText() {
        if (!taskQueue.isEmpty()) {
            List<String> descs = taskQueue.getQueueDescriptions();
            return String.join("\n", descs);
        }
        return pendingProposal != null ? pendingProposal.taskDescription : null;
    }

    /**
     * 开启/关闭自主模式     */
    public void setAutonomousMode(boolean enabled) {
        this.autonomousMode = enabled;
        if (enabled) {
            if (!useNewFramework) {
                enableNewFramework("autonomous");
            } else {
                setMode("autonomous");
            }
        } else if (useNewFramework && "autonomous".equals(newFrameworkMode)) {
            setMode("follow");
        }
        if (enabled) {
            showDialogue("\u00a7a自主模式已开启 - 我将自行决策", 60);
        } else {
            showDialogue("\u00a77自主模式已关闭 - 等待你的指令", 60);
        }
    }

    /**
     * 是否处于自主模式     */
    public boolean isAutonomousMode() {
        return autonomousMode;
    }

    /**
     * Show dialogue text floating above the companion's head
     * @param text the dialogue text (supports § color codes)
     * @param durationTicks how long to show (in ticks, default ~5 seconds)
     */
    public void showDialogue(String text, int durationTicks) {
        if (text == null || text.isEmpty()) return;
        // Escape HTML-like chars
        text = text.replace("&", "\u00a7").replace("<", "&lt;").replace(">", "&gt;");
        this.dialogueText = text;
        this.dialogueEndTick = this.tickCount + durationTicks;
        this.setCustomName(net.minecraft.network.chat.Component.literal("\u00a7f" + text));
        this.setCustomNameVisible(true);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Dialogue: " + text);
    }

    /**
     * Show dialogue with default duration (~5 seconds)
     */
    public void showDialogue(String text) {
        showDialogue(text, DEFAULT_DIALOGUE_DURATION_TICKS);
    }

    /** Send a chat message to the owner player */
    public void notifyOwner(String msg) {
        if (!chatMsgEnabled) return;
        ServerPlayer owner = getOwner();
        if (owner != null) {
            String name = this.getCustomName() != null ? this.getCustomName().getString() : "同伴";
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a77[\u00a7b" + name + "\u00a77] \u00a7f" + msg));
        }
    }

    /** Update the persistent name tag showing mode/level when no dialogue is active */
    private void updatePersistentName() {
        if (!dialogueText.isEmpty()) return;
        String mode = getModeDataString();
        String modeIcon = switch (mode) {
            case "guard" -> "\u00a7c🛡";
            case "gather" -> "\u00a76⛏";
            case "farm" -> "\u00a7a🌾";
            default -> "\u00a7b👤";
        };
        int lv = this.entityData.get(DATA_LEVEL);
        int hp = (int) Math.ceil(this.getHealth());
        int maxHp = (int) Math.ceil(this.getMaxHealth());
        String hpColor = hp > maxHp * 0.6 ? "\u00a7a" : hp > maxHp * 0.3 ? "\u00a7e" : "\u00a7c";
        this.setCustomName(net.minecraft.network.chat.Component.literal(
            "\u00a77Lv." + lv + " " + modeIcon + " " + hpColor + "\u2764" + hp + "/" + maxHp + " \u00a7f" + getModeDisplayName()));
        this.setCustomNameVisible(true);
    }

    private String getModeDisplayName() {
        if (isGuardModeEnabled()) return "守护";
        if (isGatherModeEnabled()) return "采集";
        if (isFarmModeEnabled()) return "种植";
        if (isFollowModeActive()) return "跟随";
        return "待命";
    }

    // ==================== RecentEvent Inner Class ====================

    public static class RecentEvent {
        public final String type;
        public final String description;
        public final long gameTime;
        public final java.util.Map<String, Object> data;

        public RecentEvent(String type, String description, long gameTime, java.util.Map<String, Object> data) {
            this.type = type;
            this.description = description;
            this.gameTime = gameTime;
            this.data = data != null ? new java.util.LinkedHashMap<>(data) : new java.util.LinkedHashMap<>();
        }
    }

    // ==================== Resistance Damage Reduction ====================

    @Override
    protected void actuallyHurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        var resistance = this.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
        if (resistance != null) {
            int level = resistance.getAmplifier() + 1;
            float reduction = Math.min(0.8f, level * 0.2f);
            amount *= (1.0f - reduction);
        }
        super.actuallyHurt(source, amount);
    }

    // ==================== 新框架 tick 驱动（P0-P5 闭环） ====================

    /** 启用新框架 */
    public void enableNewFramework() {
        enableNewFramework(null);
    }

    /** 启用新框架并设置模式 */
    public void enableNewFramework(String mode) {
        if (actionExecutor == null) {
            actionExecutor = new com.aiworkbench.companion.core.action.ActionExecutor(this);
        }
        if (coreBrain == null) {
            coreBrain = new com.aiworkbench.companion.core.brain.CoreBrain();
        }
        if (ruleDecisionMaker == null) {
            ruleDecisionMaker = new com.aiworkbench.companion.core.decision.RuleBasedDecisionMaker();
        }
        this.goalSelector.removeAllGoals(it -> true);
        if (!this.onGround()) {
            BlockPos ground = com.aiworkbench.companion.task.Navigator.findWalkableGroundStatic(
                this.level(), this.blockPosition(), this.blockPosition());
            if (ground != null) {
                this.setPos(this.getX(), ground.getY(), this.getZ());
            }
        }
        this.getNavigation().stop();
        this.decisionCooldown = 0;
        this.perceptionCooldown = 0;
        authorizedCapabilities.clear();
        setMode(mode != null ? mode : "follow");
        useNewFramework = true;
        AICompanionMod.LOGGER.info("[NewFramework] Enabled for {} mode={} caps={}",
            this.getUUID(), newFrameworkMode, authorizedCapabilities);
    }

    /** 切换模式 */
    public void setMode(String mode) {
        if (coreBrain == null) {
            coreBrain = new com.aiworkbench.companion.core.brain.CoreBrain();
        }
        coreBrain.setMode(mode);
        this.newFrameworkMode = coreBrain.modeId();
        authorizedCapabilities.clear();
        authorizedCapabilities.addAll(coreBrain.authorizedCapabilities());
        if (actionExecutor != null) {
            actionExecutor.abort();
        }
    }

    public String getMode() { return newFrameworkMode; }

    /** 禁用新框架，回退旧 Goal 系统 */
    public void disableNewFramework() {
        useNewFramework = false;
        if (actionExecutor != null) actionExecutor.abort();
        AICompanionMod.LOGGER.info("[NewFramework] Disabled for {}", this.getUUID());
    }

    public boolean isNewFrameworkActive() { return useNewFramework; }

    /** 检查目标方块是否仍未破坏 */
    private boolean isStillThere(BlockPos pos) {
        return !this.level().getBlockState(pos).isAir();
    }

    /**
     * 每 tick 运行新框架：感知 → 决策 → 执行。
     * @return true 表示新框架处理了本 tick（旧 goalSelector 应跳过）
     */
    private boolean tickCoreFramework() {
        if (!useNewFramework) return false;
        boolean followMode = "follow".equals(newFrameworkMode);

        // 降频感知：每 20 tick（1 秒）重新扫描
        perceptionCooldown--;
        com.aiworkbench.companion.core.perception.PerceptionData perception = null;
        if (perceptionCooldown <= 0) {
            perception = PerceptionEngine.gatherStructured(this);
            perceptionCooldown = 20;
            // 感知刷新时清理已不存在的失败目标
            failedTargets.keySet().removeIf(pos -> !isStillThere(pos));
        }

        // 有活跃动作 → 驱动执行（不重新决策）
        if (actionExecutor.isActive() && followMode) {
            actionExecutor.abort();
        } else if (actionExecutor.isActive()) {
            // MC-043: 感知降频期间 perception 可能为 null，强制采集
            if (perception == null) {
                perception = PerceptionEngine.gatherStructured(this);
                perceptionCooldown = 20;
            }
            var result = actionExecutor.tick(perception);
            if (result != null) {
                AICompanionMod.LOGGER.debug("[NewFramework] Action {}: {}",
                    result.outcome(), result.detail() != null ? result.detail() : "");
                // 记录失败目标，防止死循环
                if (lastAttemptedTarget != null) {
                    if (result.outcome() == com.aiworkbench.companion.core.action.ActionExecutor.ActionTickResult.Outcome.FAILED) {
                        failedTargets.merge(lastAttemptedTarget, 1, Integer::sum);
                        decisionCooldown = 60;
                    } else if (lastAttemptedTarget.closerThan(this.blockPosition(), 2.0)
                               && isStillThere(lastAttemptedTarget)) {
                        // 序列"完成"但目标还在原地 → 能力什么都没做
                        failedTargets.merge(lastAttemptedTarget, 1, Integer::sum);
                        decisionCooldown = 60;
                    } else {
                        failedTargets.remove(lastAttemptedTarget); // 成功 → 清除失败记录
                    }
                }
            }
            return true;
        }

        // 无活跃动作 + 冷却中 → 等待（MC-044: 冷却在外层控制，DecisionMaker 为纯函数）
        if (decisionCooldown > 0) {
            if (followMode) {
                decisionCooldown = 0;
            } else {
            decisionCooldown--;
            return true;
            }
        }

        // 无活跃动作 + 冷却结束 → 决策下一步
        decisionCooldown = followMode ? 2 : 20; // follow needs fresh owner positions
        if (perception == null) {
            perception = PerceptionEngine.gatherStructured(this);
            perceptionCooldown = 20;
        }
        if (coreBrain == null) {
            coreBrain = new com.aiworkbench.companion.core.brain.CoreBrain();
            coreBrain.setMode(newFrameworkMode);
        }
        var nearest = this.level().getNearestPlayer(this, 64);
        BlockPos followTarget = nearest != null ? nearest.blockPosition() : null;
        var decision = coreBrain.decide(perception, followTarget);

        if ("idle".equals(decision.actionId())) return true;
        // 提取目标位置（用于失败跟踪和死循环防护）
        BlockPos decisionTarget = decision.params().get("$found_block.pos") instanceof BlockPos bp ? bp : null;

        // 失败目标检查：同一目标失败≥3次 → 临时屏蔽，等感知刷新
        if (decisionTarget != null) {
            int fails = failedTargets.getOrDefault(decisionTarget, 0);
            if (fails >= 3) {
                AICompanionMod.LOGGER.debug("[NewFramework] Target {} failed {} times, skipping", decisionTarget, fails);
                decisionCooldown = 60;
                return true;
            }
        }

        // 授权检查：EXECUTE_CAPABILITY 需在授权列表中
        String capId = (String) decision.params().get("capability_id");
        if (capId != null && !authorizedCapabilities.contains(capId)) {
            // 未授权能力 → 直接跟随玩家，不产生自引用偏移
            var nearestAllowedFallback = this.level().getNearestPlayer(this, 64);
            if (nearestAllowedFallback != null) {
                decision = new com.aiworkbench.companion.core.decision.ActionDecision("MoveTo",
                    java.util.Map.of("target", nearestAllowedFallback.blockPosition(), "speed", 1.0),
                    "unauthorized → follow player", com.aiworkbench.companion.core.decision.DecisionSource.RULE_ENGINE);
            } else {
                return true; // 找不到玩家，跳过本次决策
            }
        }
        lastAttemptedTarget = decisionTarget;
        AICompanionMod.LOGGER.info("[NewFramework] Decision: {} → {} {}",
            decision.actionId(), decision.params(), decision.reasoning());
        actionExecutor.dispatch(decision);
        return true;
    }
}
