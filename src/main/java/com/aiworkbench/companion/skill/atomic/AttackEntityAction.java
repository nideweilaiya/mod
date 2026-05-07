package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * 攻击匹配条件的实体 —— 找到最近的敌对生物 → 移动过去 → 攻击至死亡或脱离。
 * <p>
 * 使用 Predicate 过滤目标（如"僵尸"、"所有敌对生物"）。
 */
public class AttackEntityAction implements AtomicAction {

    private static final double ATTACK_DISTANCE_SQ = 3.5 * 3.5;
    private static final int SEARCH_RADIUS = 12;
    private static final int TIMEOUT_TICKS = 600;
    private static final int RETARGET_INTERVAL = 40;

    private final Predicate<LivingEntity> filter;
    private LivingEntity target;
    private int tickCounter;
    private int retargetCooldown;
    private boolean searched;

    public AttackEntityAction(Predicate<LivingEntity> filter) {
        this.filter = filter;
        this.tickCounter = 0;
        this.retargetCooldown = 0;
        this.searched = false;
    }

    /**
     * 攻击所有敌对生物（实现 Enemy 接口的生物）。
     */
    public static AttackEntityAction allHostile() {
        return new AttackEntityAction(e -> e instanceof Enemy);
    }

    /**
     * 攻击指定实体类型的生物。
     *
     * @param entityClass 目标实体类（如 Zombie.class）
     */
    public static AttackEntityAction ofType(Class<? extends LivingEntity> entityClass) {
        return new AttackEntityAction(entityClass::isInstance);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        // 定期重新搜索目标
        retargetCooldown--;
        if (!searched || retargetCooldown <= 0) {
            target = findTarget(entity);
            searched = true;
            retargetCooldown = RETARGET_INTERVAL;
        }

        if (target == null) {
            return true; // 没有敌人，跳过
        }

        // 检查目标是否已死亡或消失
        if (!target.isAlive() || target.isRemoved()) {
            target = findTarget(entity);
            if (target == null) return true;
        }

        // 看向目标
        entity.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSq = entity.distanceToSqr(target);

        if (distSq > ATTACK_DISTANCE_SQ) {
            // 移向目标
            entity.getNavigation().moveTo(target, 1.0);
        } else {
            // 攻击范围内
            entity.getNavigation().stop();
            entity.doAttack(target);
        }

        // 超时保护
        if (tickCounter > TIMEOUT_TICKS) {
            entity.getNavigation().stop();
            return true;
        }

        return false;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        target = null;
        tickCounter = 0;
        searched = false;
    }

    private LivingEntity findTarget(AutomatonEntity entity) {
        AABB searchBox = entity.getBoundingBox().inflate(SEARCH_RADIUS);
        List<LivingEntity> candidates = entity.level().getEntitiesOfClass(
                LivingEntity.class, searchBox, e -> {
                    if (e == entity) return false;
                    if (!e.isAlive()) return false;
                    if (e == entity.getOwner()) return false;
                    return filter.test(e);
                });

        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (LivingEntity candidate : candidates) {
            double dist = entity.distanceToSqr(candidate);
            if (dist < closestDist) {
                closestDist = dist;
                closest = candidate;
            }
        }

        return closest;
    }

    @Override
    public String getDescription() {
        return "攻击目标";
    }
}
