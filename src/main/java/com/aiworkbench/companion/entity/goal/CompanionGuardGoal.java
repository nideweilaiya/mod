package com.aiworkbench.companion.entity.goal;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 完整战斗AI —— 守护玩家免受敌对生物伤害。
 *
 * 特性：
 * - 近战 + 弓箭双模式（背包有弓+箭自动切换远程）
 * - 自动装备最佳武器和护甲
 * - 战斗走位：绕圈攻击，不站桩
 * - 低血量自动撤退
 * - 优先攻击威胁主人的目标
 * - 采集模式遇敌自动切换战斗
 */
public class CompanionGuardGoal extends Goal {
    private final AutomatonEntity companion;
    private final double speed;
    private final float range;

    private LivingEntity target;
    private boolean inCombat;

    // 攻击距离
    private static final double MELEE_RANGE_SQ = 2.5 * 2.5;
    private static final double BOW_RANGE_SQ = 15.0 * 15.0;
    private static final double BOW_MIN_RANGE_SQ = 4.0 * 4.0; // 太近了不用弓

    // 走位
    private int strafeDir;
    private int strafeTimer;
    private static final int STRAFE_INTERVAL = 30; // 每1.5秒换个方向

    // 撤退
    private static final float RETREAT_HEALTH = 10f; // 低于10血撤退
    private int retreatTicks;

    // 远程攻击
    private int bowUseTicks;
    private static final int BOW_CHARGE_TIME = 20; // 1秒蓄力

    // 装备检查
    private boolean equipped;

    public CompanionGuardGoal(AutomatonEntity companion, double speed, float range) {
        this.companion = companion;
        this.speed = speed;
        this.range = range;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (companion.isSkillActive()) return false;
        if (!companion.isGuardModeEnabled()) return false;
        if (companion.getOwnerUUID() == null) return false;

        target = findTarget();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!companion.isGuardModeEnabled()) return false;
        if (companion.isSkillActive()) return false;
        if (target == null || !target.isAlive()) return false;
        return target.distanceToSqr(companion) < range * range * 2;
    }

    @Override
    public void start() {
        inCombat = true;
        equipped = false;
        strafeTimer = 0;
        retreatTicks = 0;
        bowUseTicks = 0;
        if (target != null) {
            companion.setGuardTarget(target);
            AICompanionMod.LOGGER.info("[GuardGoal] Engaging {}", target.getName().getString());
        }
    }

    @Override
    public void stop() {
        inCombat = false;
        String enemyName = target != null ? target.getName().getString() : "敌人";
        target = null;
        equipped = false;
        companion.setGuardTarget(null);
        companion.getNavigation().stop();
        companion.setGuardModeEnabled(false);
        // Restore the mode that was active before combat interrupted
        String previous = companion.getPreCombatMode();
        companion.setPreCombatMode("follow");
        switch (previous) {
            case "gather" -> {
                companion.setGatherModeEnabled(true);
                companion.showDialogue("§a威胁清除，继续采集", 40);
                AICompanionMod.LOGGER.info("[GuardGoal] Disengaged → restore gather");
            }
            case "farm" -> {
                companion.setFarmModeEnabled(true);
                companion.showDialogue("§a威胁清除，继续种植", 40);
                AICompanionMod.LOGGER.info("[GuardGoal] Disengaged → restore farm");
            }
            default -> {
                companion.returnToFollow();
                companion.showDialogue("§a威胁清除", 40);
                AICompanionMod.LOGGER.info("[GuardGoal] Disengaged → follow");
            }
        }
        companion.addRecentEvent("combat_end", "击败了" + enemyName);
        companion.triggerEventResponse("combat_end", java.util.Map.of("enemy", enemyName));
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) { inCombat = false; return; }

        // 进入战斗自动装备
        if (!equipped) { equipForCombat(); equipped = true; }

        // Check weapon durability
        ItemStack weapon = companion.getItemBySlot(EquipmentSlot.MAINHAND);
        if (weapon.getMaxDamage() > 0 && weapon.getMaxDamage() - weapon.getDamageValue() <= 5) {
            equipBestWeapon();
        }

        // 低血撤退
        if (companion.getHealth() < RETREAT_HEALTH) {
            retreat();
            return;
        }
        retreatTicks = 0;

        double distSq = companion.distanceToSqr(target);
        companion.getLookControl().setLookAt(target, 30f, companion.getMaxHeadYRot());

        // 判断用弓还是近战
        boolean hasBow = hasBowAndArrow();

        if (hasBow && distSq > BOW_MIN_RANGE_SQ && distSq < BOW_RANGE_SQ) {
            // 远程模式
            bowAttack(distSq);
        } else {
            // 近战模式
            meleeAttack(distSq);
        }
    }

    // ===== 近战 =====

    private void meleeAttack(double distSq) {
        if (distSq > MELEE_RANGE_SQ) {
            // 向目标移动 + 绕圈
            strafeToward();
            companion.getNavigation().moveTo(target, speed);
        } else {
            companion.getNavigation().stop();
            // 绕圈走位
            if (strafeTimer-- <= 0) {
                strafeDir = companion.getRandom().nextInt(3) - 1; // -1, 0, 1
                strafeTimer = STRAFE_INTERVAL;
            }
            if (strafeDir != 0) {
                Vec3 strafe = target.position().subtract(companion.position()).normalize();
                Vec3 side = new Vec3(-strafe.z, 0, strafe.x).scale(strafeDir * 0.5);
                companion.getMoveControl().strafe((float) side.x, (float) side.z);
            }
            // 攻击
            if (companion.isWithinAttackRange(target)) {
                companion.swing(InteractionHand.MAIN_HAND);
                companion.doHurtTarget(target);
            }
        }
        // 跳跃攻击高处目标
        if (target.getY() - companion.getY() > 0.5
                && companion.distanceToSqr(target.getX(), companion.getY(), target.getZ()) < 4.0
                && companion.onGround()) {
            companion.getJumpControl().jump();
        }
    }

    // ===== 远程 =====

    private void bowAttack(double distSq) {
        companion.getNavigation().stop();

        // 保持距离
        if (distSq < BOW_MIN_RANGE_SQ) {
            Vec3 away = companion.position().subtract(target.position()).normalize().scale(0.6);
            companion.getMoveControl().strafe((float) away.x, (float) away.z);
        }

        // 蓄力射箭
        if (bowUseTicks >= BOW_CHARGE_TIME) {
            performBowAttack();
            bowUseTicks = 0;
        } else {
            bowUseTicks++;
            // 蓄力时举弓动画
            if (bowUseTicks % 4 == 0) companion.animateSwing();
        }
    }

    private void performBowAttack() {
        ItemStack bow = findBow();
        if (bow.isEmpty()) return;

        Vec3 aim = target.getEyePosition().subtract(companion.getEyePosition()).normalize();
        net.minecraft.world.entity.projectile.AbstractArrow arrow =
            new net.minecraft.world.entity.projectile.Arrow(
                net.minecraft.world.entity.EntityType.ARROW, companion.level());
        arrow.setOwner(companion);
        arrow.setPos(companion.getEyePosition());
        arrow.shoot(aim.x, aim.y, aim.z, 1.6f, 1.0f);

        if (bowUseTicks >= BOW_CHARGE_TIME) {
            arrow.setCritArrow(true);
        }

        int powerLevel = EnchantmentHelper.getItemEnchantmentLevel(
            Enchantments.POWER_ARROWS, bow);
        if (powerLevel > 0) {
            arrow.setBaseDamage(arrow.getBaseDamage() + (double) powerLevel * 0.5D + 0.5D);
        }

        int punchLevel = EnchantmentHelper.getItemEnchantmentLevel(
            Enchantments.PUNCH_ARROWS, bow);
        if (punchLevel > 0) {
            arrow.setKnockback(punchLevel);
        }

        int flameLevel = EnchantmentHelper.getItemEnchantmentLevel(
            Enchantments.FLAMING_ARROWS, bow);
        if (flameLevel > 0) {
            arrow.setSecondsOnFire(100);
        }

        int infinityLevel = EnchantmentHelper.getItemEnchantmentLevel(
            Enchantments.INFINITY_ARROWS, bow);

        companion.level().addFreshEntity(arrow);
        companion.animateSwing();

        if (infinityLevel <= 0) {
            consumeArrow();
        }

        bow.hurtAndBreak(1, companion, e -> {});
    }

    // ===== 撤退 =====

    private void retreat() {
        retreatTicks++;
        Vec3 away = companion.position().subtract(target.position()).normalize();
        Vec3 retreatPos = companion.position().add(away.scale(5));
        companion.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, speed * 1.2);
        if (retreatTicks > 100 && companion.getHealth() > companion.getMaxHealth() * 0.5f) {
            retreatTicks = 0; // 血量恢复→重新战斗
        }
    }

    // ===== 装备 =====

    private void equipForCombat() {
        // 装备最佳武器（剑优先，其次斧头）
        equipBestWeapon();
        // 装备最佳护甲
        equipBestArmor();
    }

    private void equipBestWeapon() {
        ItemStack current = companion.getItemBySlot(EquipmentSlot.MAINHAND);
        // 已经有剑→不换
        if (current.getItem() instanceof SwordItem) return;

        int bestSlot = -1, bestDamage = 0;
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (s.isEmpty()) continue;
            float dmg = getWeaponDamage(s);
            if (dmg > bestDamage) { bestDamage = (int) dmg; bestSlot = i; }
        }
        if (bestSlot >= 0) {
            ItemStack weapon = companion.getItem(bestSlot);
            companion.setItem(bestSlot, current);
            companion.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        }
    }

    private float getWeaponDamage(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SwordItem s) return s.getDamage() + 1; // 剑基础伤害 + 1
        if (item instanceof AxeItem a) return a.getAttackDamage() - 1; // 斧头伤害稍低
        return 0;
    }

    private void equipBestArmor() {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack current = companion.getItemBySlot(slot);
            if (!current.isEmpty()) continue; // 已有护甲

            int bestSlot = -1, bestArmor = 0;
            for (int i = 0; i < companion.getInventorySize(); i++) {
                ItemStack s = companion.getItem(i);
                if (s.isEmpty() || !(s.getItem() instanceof ArmorItem ai)) continue;
                if (ai.getEquipmentSlot() != slot) continue;
                int armor = ai.getDefense();
                if (armor > bestArmor) { bestArmor = armor; bestSlot = i; }
            }
            if (bestSlot >= 0) {
                ItemStack armor = companion.getItem(bestSlot);
                companion.setItem(bestSlot, ItemStack.EMPTY);
                companion.setItemSlot(slot, armor);
            }
        }
    }

    // ===== 目标搜索 =====

    private LivingEntity findTarget() {
        Player owner = getOwner();
        if (owner == null) return null;

        Vec3 ownerPos = owner.position();
        LivingEntity closest = null;
        double closestScore = Double.MAX_VALUE;

        var entities = companion.level().getEntities(companion,
            companion.getBoundingBox().inflate(range, range / 2, range));

        for (var e : entities) {
            if (!(e instanceof Monster) || !(e instanceof LivingEntity mob)) continue;
            if (!mob.isAlive()) continue;
            // 不攻击铁傀儡等友善生物
            if (mob instanceof net.minecraft.world.entity.animal.IronGolem) continue;

            double distToOwner = mob.distanceToSqr(ownerPos);
            if (distToOwner > range * range) continue;

            // 评分：越近主人越优先 + 越强越优先
            double score = distToOwner - (mob.getMaxHealth() * 2);
            if (mob instanceof Creeper) score -= 100; // 爬行者最高优先级
            if (score < closestScore) {
                closestScore = score;
                closest = mob;
            }
        }
        return closest;
    }

    @Nullable
    private Player getOwner() {
        if (companion.getOwnerUUID() == null || companion.getServer() == null) return null;
        for (ServerLevel sl : companion.getServer().getAllLevels()) {
            Player p = sl.getPlayerByUUID(companion.getOwnerUUID());
            if (p != null) return p;
        }
        return null;
    }

    // ===== 工具方法 =====

    private boolean hasBowAndArrow() {
        return !findBow().isEmpty() && hasArrow();
    }

    private ItemStack findBow() {
        // 先检查主手
        ItemStack main = companion.getItemBySlot(EquipmentSlot.MAINHAND);
        if (main.getItem() instanceof BowItem) return main;
        // 检查背包
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (s.getItem() instanceof BowItem) return s;
        }
        return ItemStack.EMPTY;
    }

    private boolean hasArrow() {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            if (companion.getItem(i).getItem() == Items.ARROW) return true;
        }
        return false;
    }

    private void consumeArrow() {
        for (int i = 0; i < companion.getInventorySize(); i++) {
            ItemStack s = companion.getItem(i);
            if (s.getItem() == Items.ARROW && !s.isEmpty()) {
                s.shrink(1);
                if (s.isEmpty()) companion.setItem(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    private void strafeToward() {
        if (strafeTimer-- <= 0) {
            strafeDir = companion.getRandom().nextBoolean() ? 1 : -1;
            strafeTimer = STRAFE_INTERVAL / 2;
        }
    }

    public boolean isInCombat() { return inCombat; }
    public LivingEntity getTarget() { return target; }
}
