package com.aiworkbench.companion.skill;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 技能执行引擎 —— 驱动技能的行为序列。
 * <p>
 * 工作流程：{@link #startSkill(Skill, AutomatonEntity)} → 每 tick 调用
 * {@link #tick(AutomatonEntity)} → 完成或 {@link #cancelSkill(AutomatonEntity)}。
 * <p>
 * 技能激活时，同伴的 Goal 系统被暂停（通过实体标志），
 * 引擎直接控制同伴行为。技能完成后 Goal 自动恢复。
 * thread-safety: 仅在服务器主线程访问。
 */
public class SkillEngine {

    @Nullable
    private Skill currentSkill;

    @Nullable
    private SkillAction currentAction;

    private boolean active;

    /** 当前技能的显示名称 */
    private String currentSkillName = "";

    /** 当前原子操作的描述文本 */
    private String currentStepDescription = "";

    /** 是否已完成原子操作 tick（防止重复推进） */
    private boolean stepCompleted;

    /** 技能开始执行的 tick 计数 */
    private int skillStartTick;

    /** 最近一次技能执行的反馈 */
    @Nullable
    private FeedbackCollector.SkillFeedback lastFeedback;

    /** 最近一次技能执行的 LLM 验证结果（异步写入，volatile 保证可见性） */
    @Nullable
    private volatile SkillVerifier.VerificationResult lastVerification;

    // ================ 公开方法 ================

    /**
     * 开始执行一个技能。
     * @return true 如果技能成功启动
     */
    public boolean startSkill(Skill skill, AutomatonEntity entity) {
        if (active) {
            AICompanionMod.LOGGER.warn("[SkillEngine] Cannot start skill '{}' - engine already busy with '{}'",
                    skill.getName(), currentSkillName);
            return false;
        }
        if (skill.getAction() == null || skill.getAction().getTotalSteps() == 0) {
            AICompanionMod.LOGGER.warn("[SkillEngine] Skill '{}' has no actions", skill.getName());
            return false;
        }

        this.currentSkill = skill;
        this.currentAction = skill.getAction();
        this.currentAction.start();
        this.active = true;
        this.currentSkillName = skill.getName();
        this.stepCompleted = false;
        this.skillStartTick = entity.getTickCounter();
        updateStepDescription();

        // 标记实体，暂停 Goal 系统
        entity.setSkillActive(true);

        AICompanionMod.LOGGER.info("[SkillEngine] Started skill '{}' ({} steps)",
                skill.getName(), currentAction.getTotalSteps());
        return true;
    }

    /**
     * 每 tick 驱动技能执行。由 AutomatonEntity.tick() 调用。
     * 引擎处于非活跃状态时，此方法不做任何事。
     */
    public void tick(AutomatonEntity entity) {
        if (!active || currentAction == null) {
            return;
        }

        // 检查是否已完成所有步骤
        if (currentAction.isDone()) {
            completeSkill(entity);
            return;
        }

        AtomicAction step = currentAction.getCurrentAction();
        if (step == null) {
            // 当前步骤无效，尝试推进
            currentAction.advance();
            if (currentAction.isDone()) {
                completeSkill(entity);
            }
            return;
        }

        // 前置检查
        if (!step.canStart(entity)) {
            // 无法执行此步骤，跳到下一步
            entity.showDialogue("§7跳过: " + step.getDescription(), 40);
            currentAction.advance();
            updateStepDescription();
            return;
        }

        // 执行当前步骤
        boolean done = step.tick(entity);
        if (done) {
            currentAction.advance();
            updateStepDescription();

            // 检查是否全部完成
            if (currentAction.isDone()) {
                completeSkill(entity);
            }
        }
    }

    /**
     * 取消当前技能。
     */
    public void cancelSkill(AutomatonEntity entity) {
        if (!active) return;

        // 停止当前原子操作
        if (currentAction != null) {
            AtomicAction step = currentAction.getCurrentAction();
            if (step != null) {
                step.stop(entity);
            }
        }

        // 收集反馈（在 cleanup 前，需要 currentSkill 仍可用）
        Skill skill = currentSkill;
        if (skill != null) {
            FeedbackCollector.SkillFeedback feedback = FeedbackCollector.collect(skill, entity, FeedbackCollector.FailureReason.INTERRUPTED);
            feedback.executionTicks = entity.getTickCounter() - skillStartTick;
            this.lastFeedback = feedback;
            AICompanionMod.LOGGER.info("[SkillEngine] Feedback for '{}' (cancelled): ticks={}",
                    currentSkillName, feedback.executionTicks);
        }

        cleanup(entity);
        entity.showDialogue("§7技能已取消", 40);
        AICompanionMod.LOGGER.info("[SkillEngine] Cancelled skill '{}'", currentSkillName);
    }

    /** 是否有技能正在执行 */
    public boolean isActive() {
        return active;
    }

    /** 获取当前技能名称 */
    public String getCurrentSkillName() {
        return currentSkillName;
    }

    /** 获取当前步骤描述 */
    public String getCurrentStepDescription() {
        return currentStepDescription;
    }

    /** 获取当前技能对象 */
    @Nullable
    public Skill getCurrentSkill() {
        return currentSkill;
    }

    /** 获取最近一次技能执行的反馈 */
    @Nullable
    public FeedbackCollector.SkillFeedback getLastFeedback() {
        return lastFeedback;
    }

    /** 获取最近一次技能执行的 LLM 验证结果 */
    @Nullable
    public SkillVerifier.VerificationResult getLastVerification() {
        return lastVerification;
    }

    // ================ 内部方法 ================

    private void completeSkill(AutomatonEntity entity) {
        String skillName = currentSkillName;

        // 收集环境反馈（在 cleanup 前，需要 currentSkill 仍可用）
        Skill skill = currentSkill;
        if (skill != null) {
            FeedbackCollector.SkillFeedback feedback = FeedbackCollector.collect(skill, entity);
            feedback.executionTicks = entity.getTickCounter() - skillStartTick;
            this.lastFeedback = feedback;
            AICompanionMod.LOGGER.info("[SkillEngine] Feedback for '{}': success={}, ticks={}, blocksBroken={}, itemsCollected={}",
                    skillName, feedback.isSuccess(), feedback.executionTicks,
                    feedback.brokenBlocks.size(), feedback.collectedItems.size());
        }

        //entity.showDialogue("§a技能完成: " + skillName, 60);
        AICompanionMod.LOGGER.info("[SkillEngine] Completed skill '{}'", skillName);

        // 通知命令层：技能完成，触发后续询问
        var owner = entity.getOwner();
        if (owner != null) {
            com.aiworkbench.companion.command.CompanionAICommands.onSkillCompleted(owner, entity, skillName);
        }

        cleanup(entity);

        // 异步自我验证已禁用（每次完成都调Ollama造成无限循环）
    }

    private void cleanup(AutomatonEntity entity) {
        this.active = false;
        this.currentSkill = null;
        this.currentAction = null;
        this.currentSkillName = "";
        this.currentStepDescription = "";
        this.stepCompleted = false;
        entity.setSkillActive(false);
    }

    private void updateStepDescription() {
        if (currentAction != null) {
            AtomicAction step = currentAction.getCurrentAction();
            this.currentStepDescription = (step != null) ? step.getDescription() : "";
        } else {
            this.currentStepDescription = "";
        }
    }
}
