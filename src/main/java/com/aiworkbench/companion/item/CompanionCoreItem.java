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
    // MC-047: stacksTo(1) 和 getMaxStackSize() 在 Gradle 编译依赖与 Forge 49.2.7 运行时
    // 之间存在签名差异。暂时接受默认 maxStackSize=64，不影响功能测试。

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

        // Check if player can recruit a new companion (满级门控)
        if (!manager.canRecruitNewCompanion(player.getUUID())) {
            int size = manager.squadSize(player.getUUID());
            if (size >= 3) {
                player.sendSystemMessage(Component.literal("§c已达最大同伴数量(3个)!"));
            } else {
                player.sendSystemMessage(Component.literal("§c需要当前同伴满级(Lv." + AutomatonEntity.MAX_LEVEL + ")才能招募新同伴!"));
            }
            return false;
        }

        // Spawn companion with GENERAL role (use /companion squad create <role> for specific roles)
        ServerLevel level = player.serverLevel();
        AutomatonEntity companion = AutomatonEntity.create(level, "default_companion", player);
        companion.setRole(com.aiworkbench.companion.manager.CompanionRole.GENERAL);
        boolean success = level.addFreshEntity(companion);

        if (!success) {
            player.sendSystemMessage(Component.literal("§cFailed to summon companion!"));
            return false;
        }

        // Register with manager
        manager.addCompanion(player.getUUID(), companion);

        // Spawn particles
        companion.playSpawnParticles();

        // Consume item
        stack.shrink(1);

        player.sendSystemMessage(Component.literal("§aYour companion has been summoned!"));
        AICompanionMod.LOGGER.info("Companion spawned for player {} via Companion Core",
            player.getName().getString());
        return true;
    }
}
