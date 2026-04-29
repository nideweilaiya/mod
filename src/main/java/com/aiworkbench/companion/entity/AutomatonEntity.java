package com.aiworkbench.companion.entity;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.ai.PerceptionEngine;
import com.aiworkbench.companion.entity.goal.CompanionFollowGoal;
import com.aiworkbench.companion.entity.goal.CompanionWanderGoal;
import com.aiworkbench.companion.entity.goal.JumpGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import net.minecraft.core.NonNullList;

public class AutomatonEntity extends Monster {
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

    // ==================== Guard Mode (LivingEntity Combat) ====================
    private boolean guardModeEnabled = false;
    private net.minecraft.world.entity.LivingEntity guardTarget = null;
    private static final double ATTACK_REACH = 2.0D;  // Melee attack reach

    // ==================== Mine Mode (Task System) ====================
    private boolean mineModeEnabled = false;

    // ==================== Chop Mode (Task System) ====================
    private boolean chopModeEnabled = false;

    // ==================== Dialogue Display ====================
    private String dialogueText = "";
    private int dialogueEndTick = 0;
    private static final int DEFAULT_DIALOGUE_DURATION_TICKS = 100; // ~5 seconds
    private long lastSituationWarningTick = 0;  // Prevent spam: only warn every ~30 seconds

    // ==================== Follow Goal Reference ====================
    private CompanionFollowGoal followGoal;

    // ==================== Inventory ====================
    private static final int INVENTORY_SIZE = 27;  // 3 rows x 9 columns
    private NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // ==================== Constructor ====================

    public AutomatonEntity(EntityType<? extends AutomatonEntity> type, Level level) {
        super(type, level);
        // Note: stepHeight is controlled by PathfinderMob.getStepHeight()
        // Default step height for PathfinderMob is 0.6 blocks
        // JumpGoal handles terrain-aware jumping instead
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
    }

    // ==================== Attribute Supplier ====================

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 60.0D)      // 30 hearts - much tankier
            .add(Attributes.MOVEMENT_SPEED, 0.3D)  // Match player sprint speed
            .add(Attributes.ARMOR_TOUGHNESS, 4.0D) // Some explosion protection
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.5D) // Partial knockback resistance
            .add(Attributes.FOLLOW_RANGE, 16.0D)
            .add(Attributes.ATTACK_DAMAGE, 2.0D)
            .add(Attributes.ARMOR, 4.0D);          // 4 armor points
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

        // 2: Guard goal - attack hostile mobs threatening owner (only when guardModeEnabled=true)
        this.goalSelector.addGoal(2, new com.aiworkbench.companion.entity.goal.CompanionGuardGoal(this, 1.0, 10.0F));

        // 3: Mine goal - mine nearby stone/ore blocks (only when mineModeEnabled=true)
        this.goalSelector.addGoal(3, new com.aiworkbench.companion.entity.goal.CompanionMineGoal(this, 0.8, 6.0F));

        // 4: Chop goal - chop nearby trees/logs (only when chopModeEnabled=true)
        this.goalSelector.addGoal(4, new com.aiworkbench.companion.entity.goal.CompanionChopGoal(this, 0.8, 8.0F));

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

        AICompanionMod.LOGGER.info("[AutomatonEntity] Goals registered: Float, Jump, Guard, Mine, Chop, FollowOwner, Wander, LookAt, RandomLook");
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

    @Override
    public void tick() {
        super.tick();
        tickCount++;
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

        // Autonomous AI decision every 60 ticks (~3 seconds) - check surroundings
        if (tickCount % 60 == 0) {
            evaluateSituation();
        }

        // Dialogue auto-hide
        if (!dialogueText.isEmpty() && tickCount > dialogueEndTick) {
            this.setCustomNameVisible(false);
            this.setCustomName(net.minecraft.network.chat.Component.literal(" "));
            dialogueText = "";
        }
    }

    /**
     * Scan for nearby item entities and pick them up into inventory
     */
    private void pickupNearbyItems() {
        if (this.level().isClientSide) return;

        double pickupRadius = 2.5;
        net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(
            this.getX() - pickupRadius, this.getY() - 0.5, this.getZ() - pickupRadius,
            this.getX() + pickupRadius, this.getY() + 1.5, this.getZ() + pickupRadius
        );

        java.util.List<net.minecraft.world.entity.item.ItemEntity> nearbyItems =
            this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds);

        int picked = 0;
        for (net.minecraft.world.entity.item.ItemEntity item : nearbyItems) {
            // Only pick up items that are on the ground long enough (not freshly spawned)
            if (item.isPickable() && item.isAlive() && item.tickCount > 10) {
                ItemStack stack = item.getItem();
                if (addItemToInventory(stack)) {
                    item.discard();
                    picked++;
                }
                // If inventory full, stop trying
                if (!hasInventorySpace()) break;
            }
        }

        if (picked > 0) {
            AICompanionMod.LOGGER.info("[AutomatonEntity] Picked up " + picked + " item stacks");
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

    /**
     * Autonomous situation evaluation - reacts to environment without LLM
     * Runs every 60 ticks (~3 seconds) with cooldown to avoid spam
     */
    private void evaluateSituation() {
        if (this.level().isClientSide) return;

        // Cooldown: don't react more than once every 30 seconds (600 ticks)
        if (tickCount - lastSituationWarningTick < 600) return;

        PerceptionEngine.PerceptionData perception = PerceptionEngine.gatherPerception(this);
        if (perception == null) return;

        String warning = null;

        // Critical dangers - immediate warning
        if (perception.dangerLava) {
            warning = "主人，小心岩浆！";
        } else if (perception.dangerFall) {
            warning = "注意脚下，别摔下去了！";
        } else if (perception.dangerHostile) {
            warning = "有敌对生物在旁边！";
        } else if (perception.dangerSuffocation) {
            warning = "主人，这里会窒息！";
        } else if (perception.dangerLowHealth && perception.health > 0) {
            warning = "主人血量低了，注意安全！";
        }

        // Resource discovery - comment on nearby ores
        if (warning == null && !perception.resources.isEmpty()) {
            String topResource = perception.resources.get(0);
            if (topResource.contains("diamond") || topResource.contains("emerald")) {
                warning = "主人，发现钻石了！";
            } else if (topResource.contains("gold")) {
                warning = "这里有金矿！";
            } else if (topResource.contains("iron")) {
                warning = "主人，前面有铁矿！";
            } else if (topResource.contains("coal")) {
                warning = "发现煤矿了！";
            } else if (topResource.contains("oak_log") || topResource.contains("spruce_log")) {
                warning = "前面有树！";
            }
        }

        // Log the evaluation
        if (warning != null) {
            AICompanionMod.LOGGER.info("[AutomatonEntity] Situation reaction: " + warning);
            showDialogue(warning, 80);  // Show for 4 seconds
            lastSituationWarningTick = tickCount;
        }
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

            // Send position update
            AICompanionMod.tcpServer.onPositionUpdate(
                this.getUUID().toString(),
                this.blockPosition()
            );

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
            entity.setPos(owner.getX() + 2, owner.getY(), owner.getZ() + 2);
            // Set display name based on owner
            entity.setCustomName(net.minecraft.network.chat.Component.literal(owner.getName().getString() + "'s Companion"));
        }
        // Set to max health on spawn
        entity.setHealth(entity.getMaxHealth());
        return entity;
    }

    // ==================== NBT Serialization ====================

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("CharacterId")) {
            this.characterId = tag.getString("CharacterId");
        }
        if (tag.hasUUID("OwnerUUID")) {
            this.ownerUUID = tag.getUUID("OwnerUUID");
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
    }

    // ==================== Getters and Setters ====================

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) { this.ownerUUID = uuid; }
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
        Player player = getServer().getLevel(level().dimension()).getPlayerByUUID(ownerUUID);
        return player instanceof ServerPlayer ? (ServerPlayer) player : null;
    }

    /**
     * Enable or disable the follow goal
     */
    public void setFollowEnabled(boolean enabled) {
        if (this.followGoal != null) {
            this.followGoal.setEnabled(enabled);
            if (!enabled) {
                this.getNavigation().stop();
            }
        }
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

    // ==================== Guard Mode (LivingEntity Combat) ====================

    /**
     * Check if guard mode is enabled
     */
    public boolean isGuardModeEnabled() {
        return guardModeEnabled;
    }

    /**
     * Enable or disable guard mode
     */
    public void setGuardModeEnabled(boolean enabled) {
        this.guardModeEnabled = enabled;
        if (!enabled) {
            this.guardTarget = null;
        }
        AICompanionMod.LOGGER.info("[AutomatonEntity] Guard mode: " + (enabled ? "ENABLED" : "DISABLED"));
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
        double distSq = this.distanceToSqr(target.getX(), target.getY(), target.getZ());
        return distSq <= ATTACK_REACH * ATTACK_REACH;
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

    // ==================== Mine Mode ====================

    /**
     * Check if mine mode is enabled
     */
    public boolean isMineModeEnabled() {
        return mineModeEnabled;
    }

    /**
     * Enable or disable mine mode
     */
    public void setMineModeEnabled(boolean enabled) {
        this.mineModeEnabled = enabled;
        AICompanionMod.LOGGER.info("[AutomatonEntity] Mine mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    // ==================== Chop Mode ====================

    /**
     * Check if chop mode is enabled
     */
    public boolean isChopModeEnabled() {
        return chopModeEnabled;
    }

    /**
     * Enable or disable chop mode
     */
    public void setChopModeEnabled(boolean enabled) {
        this.chopModeEnabled = enabled;
        AICompanionMod.LOGGER.info("[AutomatonEntity] Chop mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

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
                // Check if items are the same type (using builtInRegistryHolder comparison)
                if (slot.getItem() == item.getItem() && slot.getDamageValue() == item.getDamageValue()) {
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
        if (guardModeEnabled) return "§c守护中";
        if (mineModeEnabled) return "§b挖掘中";
        if (chopModeEnabled) return "§6砍伐中";
        return "§a跟随中";
    }

    /**
     * Show dialogue text floating above the companion's head
     * @param text the dialogue text (supports § color codes)
     * @param durationTicks how long to show (in ticks, default ~5 seconds)
     */
    public void showDialogue(String text, int durationTicks) {
        if (text == null || text.isEmpty()) return;
        // Escape HTML-like chars
        text = text.replace("&", "§").replace("<", "‹").replace(">", "›");
        this.dialogueText = text;
        this.dialogueEndTick = this.tickCount + durationTicks;
        this.setCustomName(net.minecraft.network.chat.Component.literal("§f" + text));
        this.setCustomNameVisible(true);
        AICompanionMod.LOGGER.info("[AutomatonEntity] Dialogue: " + text);
    }

    /**
     * Show dialogue with default duration (~5 seconds)
     */
    public void showDialogue(String text) {
        showDialogue(text, DEFAULT_DIALOGUE_DURATION_TICKS);
    }
}
