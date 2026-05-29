# MC AI Companion — Forge Mod

Minecraft 1.20.4 Forge 模组，为游戏添加 AI 驱动的同伴实体。同伴具备感知、决策、执行三层架构，可自主采集、建造、跟随、守护。

## 架构

### 感知 → 评估 → 执行 三层决策循环

```
感知（PerceptionData：我看到什么？）
  → 评估（IDecisionMaker：我应该做什么？）
    → 执行（IAction 原语：我怎么做？）
      → 反馈 → 回到感知
```

### 新架构（`core/` 包 — P0-P5 已完成，P6 闭环验证中）

| 层 | 包 | 职责 |
|----|-----|------|
| 感知 | `core.perception` | 世界状态标准化为 PerceptionData |
| 评估 | `core.decision` | IDecisionMaker 接口，可插拔规则引擎/LLM |
| 执行 | `core.action` | 11 个动作原语（MoveTo, BreakBlock, PickupItem...） |

**关键设计**：
- 行为由 `config/capabilities/*.json` 驱动，新增行为无需写 Java 代码
- 决策源可热切换：RuleBasedDecisionMaker ↔ LLMDecisionMaker
- 模块间通过 EventBus 通信，零直接引用
- 所有自主行为必须经过授权检查（`authorizedCapabilities`）

### 旧架构（`ai/goal/` — 逐步迁移中）

每个行为一个 Goal 类（BTreeGatherGoal, CompanionChopGoal...），决策逻辑嵌入各 Goal，不可插拔。

### 已有能力

| 能力ID | 用途 | 原语序列 |
|--------|------|---------|
| `gather_logs` | 采集原木 | NavigateToInteract → EquipItem → BreakBlock → PickupItem |
| `gather_ores` | 采集矿石 | 同上 |

## LLM 三层档次

| 档次 | 决策方式 | LLM 角色 |
|------|---------|---------|
| 纯聊天 | RuleBasedDecisionMaker 内置规则 | LLM 只生成对话 |
| 可决策 | LLMDecisionMaker 通过 TCP Bridge 调用 | LLM 替代规则引擎 |
| 自主+记忆 | 在可决策基础上增加记忆读写 | LLM 自主制定计划 |

## 技术栈

- Minecraft 1.20.4 / Forge 49.2.7
- Java 17 / Gradle 8.1
- Ollama (qwen3.5) localhost:11434
- TCP Bridge port 8767 (Forge ↔ Python/LLM)

## 构建

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17"
./gradlew.bat clean reobfJar
cp build/libs/AICompanion.jar D:/.minecraft/1.20.4/versions/1204/mods/
```

## 后续方向

- **P6 闭环验证**：游戏内验证感知→评估→能力→执行全链路
- **旧代码迁移**：旧 Goal 类逐步 @Deprecated，迁移到能力 JSON 配置
- **LLM 记忆层**：长期记忆 + 自主计划制定
- **Web 面板对接**：ICompanionStatus → FastAPI → 浏览器面板
- **能力扩展**：钓鱼、建筑、战斗等新能力（纯 JSON 配置）
