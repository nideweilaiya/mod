package com.aiworkbench.companion.task;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.core.perception.PerceptionData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 局部障碍规避规划器 — 在已规划的宏观路线上遇到意外障碍时计算最优绕路方向。
 *
 * <p>与 {@link Navigator} 的关系：Navigator 负责全局 A* 路径规划，
 * 本类负责局部障碍规避。两者协作：Navigator 画宏观路线，
 * ObstacleAvoidancePlanner 确保沿路线行进时不被突然出现的实体/方块挡住。</p>
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>从感知数据中提取当前位置 3 格内的障碍物</li>
 *   <li>在 8 个方向（含对角线）生成候选绕路位置（距离 1-2 格）</li>
 *   <li>过滤：被实体占用 / 被实心方块占用 / 不可站立（下方无固体）</li>
 *   <li>评分：优先选择朝目标方向偏差最小的候选</li>
 *   <li>返回最高分候选，无可用候选返回 null</li>
 * </ol>
 *
 * <h3>识别障碍类型</h3>
 * <ul>
 *   <li>实体障碍：羊、牛、村民等可碰撞实体</li>
 *   <li>方块障碍：凸起的实心方块</li>
 *   <li>悬崖/虚空：候选位置下方无固体方块</li>
 * </ul>
 */
public class ObstacleAvoidancePlanner {

    /** 障碍检测半径（格） */
    private static final int OBSTACLE_RADIUS = 3;
    /** 绕路搜索半径（格） */
    private static final int DETOUR_RADIUS = 2;

    /** 8 个方向偏移（含对角线） */
    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},   // 正交
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}   // 对角线
    };

    /**
     * 计算最优绕路位置。
     *
     * @param level 世界
     * @param currentPos 实体当前卡住的位置
     * @param targetPos 实体的最终目标（用于方向评分）
     * @param perception 当前感知快照（用于获取障碍物数据）
     * @return 最优绕路位置，无可用路径时返回 null
     */
    @Nullable
    public static BlockPos plan(Level level, BlockPos currentPos, BlockPos targetPos,
                                 PerceptionData perception) {
        // 1. 收集障碍位置集合
        Set<BlockPos> obstacles = collectObstacles(currentPos, perception);

        // 2. 生成候选绕路位置
        List<Candidate> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        for (int dist = 1; dist <= DETOUR_RADIUS; dist++) {
            for (int[] dir : DIRECTIONS) {
                candidate.set(
                    currentPos.getX() + dir[0] * dist,
                    currentPos.getY(),
                    currentPos.getZ() + dir[1] * dist
                );

                // 过滤：被障碍占用
                if (obstacles.contains(candidate)) continue;
                // 过滤：自身就是当前卡住的位置
                if (candidate.equals(currentPos)) continue;

                // 过滤：不可站立 (findWalkableGroundStatic 可返回 null，须防御)
                BlockPos groundCheck = Navigator.findWalkableGroundStatic(level, candidate, currentPos);
                if (groundCheck == null) continue;
                if (!groundCheck.equals(candidate)) {
                    candidate.set(groundCheck);
                }

                // 确保候选位置自身是空气（实体可以站在那里）
                if (!level.getBlockState(candidate).isAir()) continue;
                if (!level.getBlockState(candidate.above()).isAir()) continue;

                // 评分：朝目标方向偏差 + 距离
                double score = score(candidate, currentPos, targetPos, obstacles);
                candidates.add(new Candidate(candidate.immutable(), score));
            }
        }

        if (candidates.isEmpty()) return null;

        // 3. 选最高分
        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        BlockPos best = candidates.get(0).pos;
        AICompanionMod.LOGGER.debug("[ObstacleAvoidance] Detour from {} to {} ({} candidates, {} obstacles)",
            currentPos, best, candidates.size(), obstacles.size());
        return best;
    }

    // ==================== 内部方法 ====================

    /** 从感知数据中收集当前位置附近的障碍物坐标 */
    private static Set<BlockPos> collectObstacles(BlockPos currentPos, PerceptionData perception) {
        Set<BlockPos> obstacles = new HashSet<>();

        // 实体障碍
        if (perception != null && perception.nearbyEntities != null) {
            for (var entity : perception.nearbyEntities) {
                if (entity.pos() != null && entity.pos().distSqr(currentPos) < OBSTACLE_RADIUS * OBSTACLE_RADIUS) {
                    obstacles.add(entity.pos());
                }
            }
        }

        return obstacles;
    }

    /** 评分：越高越好。朝目标方向 + 远离障碍 */
    private static double score(BlockPos candidate, BlockPos currentPos,
                                 BlockPos targetPos, Set<BlockPos> obstacles) {
        double score = 0;

        // 主要权重：朝目标方向（dot product 近似）
        double dxToTarget = targetPos.getX() - currentPos.getX();
        double dzToTarget = targetPos.getZ() - currentPos.getZ();
        double dxCandidate = candidate.getX() - currentPos.getX();
        double dzCandidate = candidate.getZ() - currentPos.getZ();

        // 候选方向与目标方向的余弦相似度（越高越接近目标方向）
        double targetDist = Math.sqrt(dxToTarget * dxToTarget + dzToTarget * dzToTarget);
        double candidateDist = Math.sqrt(dxCandidate * dxCandidate + dzCandidate * dzCandidate);
        if (targetDist > 0.01 && candidateDist > 0.01) {
            double dotProduct = (dxToTarget * dxCandidate + dzToTarget * dzCandidate)
                / (targetDist * candidateDist);
            score += dotProduct * 50.0; // 方向对齐权重 ~50
        }

        // 次要权重：离目标更近（但是绕路，所以不应为了"更近"牺牲方向）
        double distToTarget = Math.sqrt(candidate.distSqr(targetPos));
        double currentDistToTarget = Math.sqrt(currentPos.distSqr(targetPos));
        if (distToTarget < currentDistToTarget) {
            score += 15.0; // 离目标更近的奖励
        }

        // 惩罚：候选位置附近仍有障碍物
        for (BlockPos obs : obstacles) {
            if (candidate.distSqr(obs) < 2.25) { // 1.5 格内
                score -= 10.0;
            }
        }

        // 小随机抖动：打破对称平局（如两个完全对称的候选方向）
        score += Math.random() * 2.0;

        return score;
    }

    /** 候选位置 + 评分 */
    private record Candidate(BlockPos pos, double score) {}
}
