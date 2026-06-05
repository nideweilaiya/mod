package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.action.TreeHarvestCursor;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
/**
 * Breaks a target block and collects drops.
 */
public class BreakBlockAction implements IAction {

    private static final double BREAK_DISTANCE_SQ = 4.5 * 4.5;
    private static final float TICKS_PER_HARDNESS = 30f;
    private static final int MAX_TICKS = 600;
    private static final int MAX_OBSTACLE_ATTEMPTS = 20;

    private final AutomatonEntity entity;
    private final BlockPos target;
    private final Map<String, Object> variables;

    private int elapsedTicks;
    private float accumulatedProgress;
    private int mineTicks;

    @Nullable
    private BlockPos obstacleTarget;
    private float obstacleProgress;
    private int obstacleMineTicks;
    private int obstacleAttempts;
    @Nullable
    private Boolean requiresAxeCache;
    @Nullable
    private Boolean requiresPickaxeCache;

    public BreakBlockAction(AutomatonEntity entity, BlockPos target, Map<String, Object> variables) {
        this.entity = entity;
        this.target = target;
        this.variables = variables;
    }

    @Override
    public boolean canExecute(PerceptionData perception) {
        if (target == null || !isWithinBreakReach(target)) {
            return false;
        }
        BlockState state = entity.level().getBlockState(target);
        return !state.isAir() && state.getBlock().defaultDestroyTime() >= 0;
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        BlockPos effectiveTarget = obstacleTarget != null ? obstacleTarget : target;

        if (obstacleTarget == null && elapsedTicks % 10 == 0) {
            BlockHitResult hit = raycastToBlock(target);
            if (hit != null && !hit.getBlockPos().equals(target)) {
                BlockPos barrier = hit.getBlockPos();
                BlockState barrierState = entity.level().getBlockState(barrier);
                if (!barrierState.isAir()
                    && barrierState.getBlock().defaultDestroyTime() >= 0
                    && shouldClearBarrier(barrier, barrierState)) {
                    if (obstacleAttempts >= MAX_OBSTACLE_ATTEMPTS) {
                        AICompanionMod.LOGGER.info("[BreakBlock] Barrier attempts exhausted for {}", target);
                        return ActionResult.FAILURE;
                    }
                    obstacleTarget = barrier;
                    obstacleProgress = 0;
                    obstacleMineTicks = 0;
                    obstacleAttempts++;
                    accumulatedProgress = 0;
                    mineTicks = 0;
                    effectiveTarget = barrier;
                    AICompanionMod.LOGGER.info(
                        "[BreakBlock] Clearing barrier {} for target {} (attempt {})",
                        barrier,
                        target,
                        obstacleAttempts
                    );
                } else if (!barrierState.isAir() && barrierState.getBlock().defaultDestroyTime() >= 0) {
                    AICompanionMod.LOGGER.info(
                        "[BreakBlock] Strict LOS blocked by {} at {}, requesting reposition for target {}",
                        barrierState.getBlock().builtInRegistryHolder().key().location(),
                        barrier,
                        target
                    );
                    return requestReposition(barrier);
                }
            }
        }

        BlockState state = entity.level().getBlockState(effectiveTarget);
        if (state.isAir()) {
            if (obstacleTarget != null) {
                obstacleTarget = null;
                accumulatedProgress = 0;
                mineTicks = 0;
                BlockState originalState = entity.level().getBlockState(target);
                if (originalState.isAir()) {
                    return ActionResult.SUCCESS;
                }
                AICompanionMod.LOGGER.debug("[BreakBlock] Barrier cleared, returning to {}", target);
                return ActionResult.IN_PROGRESS;
            }
            TreeHarvestCursor cursor = currentCursor();
            if (cursor != null) {
                cursor.clearFailureState(target);
            }
            return ActionResult.SUCCESS;
        }

        if (state.getBlock().defaultDestroyTime() < 0) {
            if (obstacleTarget != null) {
                obstacleTarget = null;
                obstacleAttempts++;
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.FAILURE;
        }

        int maxTicks = obstacleTarget != null ? 100 : MAX_TICKS;
        if (elapsedTicks > maxTicks) {
            if (obstacleTarget != null) {
                obstacleTarget = null;
                obstacleAttempts++;
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.FAILURE;
        }

        if (!isWithinBreakReach(effectiveTarget)) {
            if (obstacleTarget != null) {
                obstacleTarget = null;
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.FAILURE;
        }

        ItemStack currentTool = entity.getEquippedTool();
        if (!checkToolConstraint(state, currentTool)) {
            return ActionResult.TOOL_MISSING;
        }

        entity.getLookControl().setLookAt(
            effectiveTarget.getX() + 0.5,
            effectiveTarget.getY() + 0.5,
            effectiveTarget.getZ() + 0.5
        );

        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) {
            hardness = 50;
        }
        float toolSpeed = entity.getEffectiveDigSpeed(state);
        if (obstacleTarget != null) {
            obstacleProgress += toolSpeed / (hardness * TICKS_PER_HARDNESS);
        } else {
            accumulatedProgress += toolSpeed / (hardness * TICKS_PER_HARDNESS);
        }

        entity.swing(InteractionHand.MAIN_HAND);

        int currentMineTicks = obstacleTarget != null ? ++obstacleMineTicks : ++mineTicks;
        if (entity.level() instanceof ServerLevel serverLevel && currentMineTicks % 4 == 0) {
            float currentProgress = obstacleTarget != null ? obstacleProgress : accumulatedProgress;
            int crackStage = Math.min((int) (currentProgress * 10), 9);
            serverLevel.destroyBlockProgress(entity.getId(), effectiveTarget, crackStage);
        }

        float targetProgress = obstacleTarget != null ? obstacleProgress : accumulatedProgress;
        if (targetProgress >= 1.0f) {
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlockProgress(entity.getId(), effectiveTarget, -1);
            }
            breakAndCollect(entity, effectiveTarget);
            if (obstacleTarget != null) {
                obstacleTarget = null;
                obstacleProgress = 0;
                obstacleMineTicks = 0;
                accumulatedProgress = 0;
                mineTicks = 0;
                AICompanionMod.LOGGER.debug("[BreakBlock] Barrier removed, returning to original target {}", target);
                return ActionResult.IN_PROGRESS;
            }
            TreeHarvestCursor cursor = currentCursor();
            if (cursor != null) {
                cursor.clearFailureState(target);
            }
            return ActionResult.SUCCESS;
        }

        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        BlockState state = entity.level().getBlockState(target);
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) {
            return Integer.MAX_VALUE;
        }
        float toolSpeed = entity.getEffectiveDigSpeed(state);
        if (toolSpeed <= 0) {
            toolSpeed = 1.0f;
        }
        return (int) Math.ceil(hardness * TICKS_PER_HARDNESS / toolSpeed);
    }

    @Nullable
    private BlockHitResult raycastToBlock(BlockPos targetPos) {
        Vec3 eye = entity.getEyePosition();
        Vec3 center = Vec3.atCenterOf(targetPos);
        Vec3 direction = center.subtract(eye).normalize();
        double distance = eye.distanceTo(center) + 1.0;
        Vec3 end = eye.add(direction.scale(distance));
        ClipContext context = new ClipContext(
            eye,
            end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            entity
        );
        BlockHitResult hit = entity.level().clip(context);
        return hit.getBlockPos().equals(targetPos) ? null : hit;
    }

    private static boolean isBarrier(BlockState state) {
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        return name.contains("leaves") || name.contains("leaf") || name.contains("vine")
            || name.contains("moss") || name.contains("snow")
            || name.contains("_plant") || name.contains("fern") || name.contains("bamboo")
            || name.contains("cobweb") || name.contains("scaffold")
            || name.contains("wool") || name.contains("carpet");
    }

    private boolean shouldClearBarrier(BlockPos barrier, BlockState state) {
        if (isBarrier(state)) {
            return true;
        }
        TreeHarvestCursor cursor = currentCursor();
        if (cursor == null || !cursor.isSameTreeLog(barrier)) {
            return false;
        }
        if (!cursor.isInActiveColumn(barrier)) {
            AICompanionMod.LOGGER.info(
                "[BreakBlock] LOS blocked by log in different column {}, forcing reposition instead of cross-column retarget",
                barrier
            );
            return false;
        }
        AICompanionMod.LOGGER.info("[BreakBlock] Retarget within active column via blocker {}", barrier);
        return true;
    }

    private ActionResult requestReposition(BlockPos barrier) {
        TreeHarvestCursor cursor = currentCursor();
        int attempts = cursor != null ? cursor.recordRepositionFailure(target) : 1;
        if (cursor != null && cursor.shouldAbandonTarget(target)) {
            return skipCurrentTarget(
                "[BreakBlock] Target %s marked unreachable after %d reposition attempts".formatted(target, attempts)
            );
        }

        BlockPos moveTarget = findRepositionCandidate(barrier);
        if (moveTarget == null) {
            if (canBreakGlassRetarget(barrier)) {
                clearRecoveryMarkers();
                variables.put("$retarget_block", barrier);
                variables.put("$retarget_original_target", target);
                AICompanionMod.LOGGER.info(
                    "[BreakBlock] No reposition path from scaffold, temporarily retargeting blocker {} before {}",
                    barrier,
                    target
                );
                return ActionResult.FAILURE;
            }
            if (cursor != null) {
                cursor.markUnreachable(target);
            }
            return skipCurrentTarget(
                "[BreakBlock] No reposition path for %s and blocker %s is not safely retargetable, skipping target"
                    .formatted(target, barrier)
            );
        }

        clearRecoveryMarkers();
        variables.put("$recoverable_failure", "los_blocked");
        variables.put("$reposition_target", target);
        variables.put("$reposition_blocker", barrier);
        variables.put("$reposition_move_target", moveTarget);
        AICompanionMod.LOGGER.info(
            "[BreakBlock] Reposition requested for {} via {} (attempt {}/{})",
            target,
            moveTarget,
            attempts,
            3
        );
        return ActionResult.FAILURE;
    }

    @Nullable
    private TreeHarvestCursor currentCursor() {
        if (variables == null) {
            return null;
        }
        Object cursor = variables.get("$tree_harvest_cursor");
        return cursor instanceof TreeHarvestCursor treeCursor ? treeCursor : null;
    }

    private boolean isWithinBreakReach(BlockPos pos) {
        return entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= BREAK_DISTANCE_SQ;
    }

    @Nullable
    private BlockPos findRepositionCandidate(BlockPos barrier) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(target.north());
        candidates.add(target.south());
        candidates.add(target.east());
        candidates.add(target.west());
        candidates.add(target.north().east());
        candidates.add(target.north().west());
        candidates.add(target.south().east());
        candidates.add(target.south().west());
        candidates.add(barrier.north());
        candidates.add(barrier.south());
        candidates.add(barrier.east());
        candidates.add(barrier.west());

        BlockPos currentPos = entity.blockPosition();
        candidates.removeIf(candidate -> !isStandable(candidate) || candidate.closerThan(currentPos, 1.2));

        candidates.sort(Comparator
            .comparingInt((BlockPos candidate) -> sameApproachSide(candidate, currentPos) ? 1 : 0)
            .thenComparingInt(candidate -> sameApproachSide(candidate, barrier) ? 1 : 0)
            .thenComparingDouble(candidate -> currentPos.distSqr(candidate))
        );

        for (BlockPos candidate : candidates) {
            Path path = entity.getNavigation().createPath(candidate, 1);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }

        for (BlockPos candidate : candidates) {
            if (entity.level().getBlockState(candidate).isAir()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canBreakGlassRetarget(BlockPos barrier) {
        TreeHarvestCursor cursor = currentCursor();
        if (cursor == null || !cursor.isSameTreeLog(barrier) || cursor.isInActiveColumn(barrier)) {
            return false;
        }
        return isWithinBreakReach(barrier);
    }

    private ActionResult skipCurrentTarget(String logMessage) {
        clearRecoveryMarkers();
        variables.put("$skip_current_target", target);
        AICompanionMod.LOGGER.warn(logMessage);
        return ActionResult.FAILURE;
    }

    private void clearRecoveryMarkers() {
        variables.remove("$recoverable_failure");
        variables.remove("$reposition_target");
        variables.remove("$reposition_blocker");
        variables.remove("$reposition_move_target");
        variables.remove("$retarget_block");
        variables.remove("$retarget_original_target");
    }

    private boolean isStandable(BlockPos pos) {
        return entity.level().getBlockState(pos).isAir()
            && entity.level().getBlockState(pos.above()).isAir()
            && entity.level().getBlockState(pos.below()).isSolid();
    }

    private boolean sameApproachSide(BlockPos candidate, BlockPos reference) {
        int candidateDx = Integer.compare(candidate.getX(), target.getX());
        int candidateDz = Integer.compare(candidate.getZ(), target.getZ());
        int referenceDx = Integer.compare(reference.getX(), target.getX());
        int referenceDz = Integer.compare(reference.getZ(), target.getZ());
        return candidateDx == referenceDx && candidateDz == referenceDz;
    }

    private boolean checkToolConstraint(BlockState state, ItemStack heldTool) {
        if (obstacleTarget != null) {
            return true;
        }
        if (requiresAxeCache == null || requiresPickaxeCache == null) {
            requiresAxeCache = state.is(BlockTags.MINEABLE_WITH_AXE);
            requiresPickaxeCache = state.is(BlockTags.MINEABLE_WITH_PICKAXE);
        }
        if (Boolean.TRUE.equals(requiresAxeCache)) {
            return heldTool.getItem() instanceof AxeItem;
        }
        if (Boolean.TRUE.equals(requiresPickaxeCache)) {
            return heldTool.getItem() instanceof PickaxeItem;
        }
        return true;
    }

    private void breakAndCollect(AutomatonEntity entity, BlockPos pos) {
        if (entity.level().isClientSide) {
            return;
        }

        Level level = entity.level();
        BlockState state = level.getBlockState(pos);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.destroyBlock(pos, true, entity);

        AICompanionMod.LOGGER.info(
            "[BreakBlock] Broke {} at {}, waiting for drops",
            state.getBlock().builtInRegistryHolder().key().location(),
            pos
        );
    }
}
