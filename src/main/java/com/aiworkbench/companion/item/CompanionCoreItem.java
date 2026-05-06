package com.aiworkbench.companion.item;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.entity.EntityInit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Companion Core - A craftable item that spawns a companion when used.
 * Right-click on ground or in air to spawn.
 * If the player already has a companion, shows a warning message.
 */
public class CompanionCoreItem extends Item {

    public CompanionCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        boolean success = spawnCompanion((ServerPlayer) player, stack);
        return success ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        spawnCompanion((ServerPlayer) player, stack);
        return InteractionResult.CONSUME;
    }

    /**
     * Attempt to spawn a companion for the player.
     * @return true if the item should be consumed, false otherwise
     */
    private boolean spawnCompanion(ServerPlayer player, ItemStack stack) {
        var manager = AICompanionMod.companionManager;
        if (manager == null) {
            player.sendSystemMessage(Component.literal("§cCompanion system not initialized"));
            return false;
        }

        // Check if player already has a companion
        if (manager.hasCompanion(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cYou already have a companion!"));
            return false;
        }

        // Spawn companion
        ServerLevel level = player.serverLevel();
        AutomatonEntity companion = AutomatonEntity.create(level, "default_companion", player);
        boolean success = level.addFreshEntity(companion);

        if (!success) {
            player.sendSystemMessage(Component.literal("§cFailed to summon companion!"));
            return false;
        }

        // Register with manager
        manager.addCompanion(player.getUUID(), companion);

        // Consume item
        stack.shrink(1);

        player.sendSystemMessage(Component.literal("§aYour companion has been summoned!"));
        AICompanionMod.LOGGER.info("Companion spawned for player {} via Companion Core",
            player.getName().getString());
        return true;
    }
}
