package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 看向目标实体或位置，持续指定 tick 数后完成。
 * <p>
 * 用于展示同伴"注意"到某件事物，作为技能中的过渡步骤。
 */
public class LookAtAction implements AtomicAction {

    private final Vec3 targetPos;
    private final int durationTicks;
    private final boolean lookAtOwner;
    private int elapsedTicks;

    /**
     * @param targetPos 目标位置
     * @param durationTicks 持续 tick 数（20 tick = 1 秒）
     */
    public LookAtAction(Vec3 targetPos, int durationTicks) {
        this.targetPos = targetPos;
        this.durationTicks = Math.max(durationTicks, 10);
        this.lookAtOwner = false;
        this.elapsedTicks = 0;
    }

    private LookAtAction(int durationTicks) {
        this.targetPos = Vec3.ZERO;
        this.durationTicks = Math.max(durationTicks, 10);
        this.lookAtOwner = true;
        this.elapsedTicks = 0;
    }

    /**
     * 看向主人，持续指定时间。
     */
    public static LookAtAction lookAtOwner(int durationTicks) {
        return new LookAtAction(durationTicks);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        if (lookAtOwner) {
            ServerPlayer owner = entity.getOwner();
            if (owner != null) {
                entity.getLookControl().setLookAt(owner, 10.0F, 30.0F);
            }
        } else if (targetPos != Vec3.ZERO) {
            entity.getLookControl().setLookAt(targetPos.x, targetPos.y, targetPos.z);
        }
        elapsedTicks++;
        return elapsedTicks >= durationTicks;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        elapsedTicks = 0;
    }

    @Override
    public String getDescription() {
        return lookAtOwner ? "看向主人" : "注视";
    }
}
