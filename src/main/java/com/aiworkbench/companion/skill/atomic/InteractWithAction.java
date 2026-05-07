package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 与方块交互（右键）—— 移动到目标方块旁 → 模拟玩家右键点击。
 * <p>
 * 使用主人的 ServerPlayer 实例执行交互，确保箱子/熔炉/门等方块正常响应。
 * 单次交互后完成（如门、按钮、拉杆）。
 * 对于需要持续交互的方块（熔炉冶炼），需要重复调用或配合其他操作。
 */
public class InteractWithAction implements AtomicAction {

    private static final double INTERACT_DISTANCE_SQ = 3.0 * 3.0;
    private static final int TIMEOUT_TICKS = 200;

    private final BlockPos targetPos;
    private int tickCounter;
    private boolean interacted;

    public InteractWithAction(BlockPos targetPos) {
        this.targetPos = targetPos;
        this.tickCounter = 0;
        this.interacted = false;
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        if (interacted) return true;

        ServerPlayer owner = entity.getOwner();
        if (owner == null) return true;

        // 必须在同一维度
        if (owner.level() != entity.level()) return true;

        // 看向目标
        Vec3 center = Vec3.atCenterOf(targetPos);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);

        double distSq = entity.distanceToSqr(center.x, center.y, center.z);

        if (distSq > INTERACT_DISTANCE_SQ) {
            // 走过去
            entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.8);
        } else {
            // 到达，让主人右键方块
            entity.getNavigation().stop();

            Level level = entity.level();
            BlockState state = level.getBlockState(targetPos);

            // 构建点击命中信息（从上方点击）
            BlockHitResult hitResult = new BlockHitResult(
                center, Direction.UP, targetPos, false
            );

            // 让主人执行右键交互
            state.use(level, owner, InteractionHand.MAIN_HAND, hitResult);

            // 也尝试使用主人手中的物品交互（如锄头耕地）
            owner.getItemInHand(InteractionHand.MAIN_HAND)
                .useOn(new net.minecraft.world.item.context.UseOnContext(
                    level, owner, InteractionHand.MAIN_HAND,
                    owner.getItemInHand(InteractionHand.MAIN_HAND), hitResult
                ));

            interacted = true;
            entity.swing(InteractionHand.MAIN_HAND);
            return true;
        }

        if (tickCounter > TIMEOUT_TICKS) {
            entity.getNavigation().stop();
            return true;
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        tickCounter = 0;
    }

    @Override
    public String getDescription() {
        return "与方块交互";
    }
}
