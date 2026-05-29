package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 与目标实体交互的动作原语（右键）。
 *
 * <p>覆盖场景：村民交易、动物繁殖/剪毛/挤奶、骑乘、打开马/驴背包等。
 * 不覆盖攻击（攻击是 AttackEntityAction，未来实现）。</p>
 *
 * <h3>组合示例</h3>
 * <pre>{@code
 *   // 剪羊毛
 *   EquipItemAction("shears") → MoveToAction(nearSheep) → InteractEntityAction(sheepUUID)
 *
 *   // 村民交易
 *   MoveToAction(nearVillager) → InteractEntityAction(villagerUUID)
 * }</pre>
 */
public class InteractEntityAction implements IAction {

    private final AutomatonEntity entity;
    private final UUID targetUuid;

    private boolean interacted;

    /** 交互有效范围 */
    private static final double INTERACT_RANGE_SQ = 4.0 * 4.0;

    public InteractEntityAction(AutomatonEntity entity, UUID targetUuid) {
        this.entity = entity;
        this.targetUuid = targetUuid;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        Entity target = findEntity();
        if (target == null) return false;
        // 必须在交互范围内
        return entity.distanceToSqr(target) <= INTERACT_RANGE_SQ;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        if (interacted) return ActionResult.SUCCESS;
        interacted = true;

        Entity target = findEntity();
        if (target == null) return ActionResult.FAILURE;

        if (entity.distanceToSqr(target) > INTERACT_RANGE_SQ) {
            return ActionResult.FAILURE;
        }

        entity.swing(InteractionHand.MAIN_HAND);
        // MC 实体交互需要 Player 实例，伙伴通过靠近+注视触发系统级交互
        entity.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
        return ActionResult.SUCCESS;
    }

    @Override
    public int getCost() {
        return 1;
    }

    private Entity findEntity() {
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            return sl.getEntity(targetUuid);
        }
        return null;
    }
}
