# MC AI Companion Mod — 采集系统与状态机架构审查

## 1. 当前架构总览

### 1.1 状态机 (AutomatonEntity:126-132)
```java
public enum CompanionState {
    IDLE, FOLLOW, GUARD, GATHER, FARM, PATROL, SURVIVAL
}
// 7 个互斥状态，同一时刻只能有一个
private CompanionState currentState = CompanionState.FOLLOW;
```

### 1.2 Goal 优先级注册 (AutomatonEntity:482-518)
```
Priority 0: FloatGoal      — 防止溺水
Priority 1: JumpGoal       — 地形跳跃
Priority 2: GuardGoal      — 战斗 (仅 GUARD 状态激活)
Priority 3: BTreeGatherGoal — 采集 (仅 GATHER 状态激活)
Priority 4: FarmGoal       — 种植 (仅 FARM 状态激活)
Priority 5: FollowGoal     — 跟随 (始终可激活)
Priority 6: WanderGoal     — 闲逛
Priority 7-8: LookGoal     — 注视
Priority 9: SurvivalGoal   — 生存
```

### 1.3 模式切换 (V键循环)
```
FOLLOW → GUARD → GATHER → FARM → FOLLOW → ...
```
只能循环切换，无法直接跳转到指定模式。

### 1.4 采集扫描 (BTreeGatherGoal.scan())
```
扫描范围: 19×11×19 ≈ 4000 方块
过滤条件: 方块类型 + 暴露面检查 + 路径可达验证
选择策略: 优先级分最高 + 距离最近
采集方式: BreakBlockAction 直接挖掘
```

---

## 2. 已确认的结构性问题

### 缺陷 A: 状态机是"封建制" — 互斥导致能力丢失
- FOLLOW 模式下同伴不会战斗（GuardGoal 不激活）
- GATHER 模式下同伴战斗优先级降低（GuardGoal 优先级 2 > BTreeGatherGoal 优先级 3，理论上会抢占，但实际测试中切换延迟明显）
- 无法同时 GATHER + FOLLOW（采集中途主人走远了不会跟）
- 实际上应该是多层并发：Always(战斗感知) + Active(采集/跟随) + Background(生存)

### 缺陷 B: 无法挖掘地下矿物
- scan() 只扫描直接暴露的方块（isExposed 检查）
- 对嵌入式矿脉：需要挖开周围石头 → 到达矿石 → 挖掘矿石
- 当前架构完全不支持"先挖石头开路，再采集目标矿"的复合任务
- 缺少垂直挖掘能力（不能往下挖）

### 缺陷 C: 无多目标执行规划
- 每次扫描只选一个"最优"目标
- 对于一片森林/矿脉，没有路径优化（TSP 问题）
- 采集完成后冷却 60 tick，然后重新扫描 → 可能选择远处的新目标而忽略近处
- 没有"区域清空"概念

### 缺陷 D: 模式切换混乱
- V 键只能线性循环，无法直达目标模式
- 右键快捷菜单的模式按钮也是 toggle 而非 set
- 玩家通过 /companion gather 设了采集，但按 V 键会切换到守护
- GATHER 和 GUARD 的切换逻辑分散在多个方法中，增加维护成本

### 缺陷 E: 技能库与采集系统脱节
- BTreeGatherGoal 完全绕过了 SkillEngine/SkillLibrary
- 预设技能（mineIronOre 等）成了死代码
- planTask() → findSkillByDescription() 匹配总是失败
- 向量技能库 (VectorSkillLibrary) 从未被采集流程使用
- 两个系统各自为政，没有统一的"任务执行"层

### 缺陷 F: 寻路能力不足
- 依赖原版 PathNavigation（只能走平坦地形）
- 不会搭方块、不会破坏障碍物、不会游泳绕路
- Navigator.checkStuck() 只是位置检测，不分析卡住原因
- 对高处/低处/水中的目标无能为力

---

## 3. 建议的重构方向

### 方案: 分层架构 — 感知→决策→执行 三层分离

```
感知层 (PerceptionEngine)
  ├── 方块扫描 (已实现)
  ├── 实体检测 (已实现)  
  └── 地形分析 (需新增: 可达性图、危险区域)

决策层 (新: TaskPlanner)
  ├── 任务队列 (已实现: TaskQueue)
  ├── 任务分解 (已实现: GoalDecomposer)
  ├── 路径规划 (需增强: 多目标排序)
  └── 中断管理 (需增强: 危险中断+恢复)

执行层 (新: ActionExecutor)
  ├── 原子动作 (已实现: AtomicAction 14种)
  ├── 寻路执行 (需增强: 破障、搭路)
  └── 技能引擎 (已实现: SkillEngine)
```

### 状态机改为能力标记 (Capability Flags)
```
当前: 互斥枚举 FOLLOW|GUARD|GATHER|FARM
建议: 独立能力标记
  - 跟随: on/off
  - 战斗: on/off (始终 on, 自动触发)
  - 采集: on/off
  - 种植: on/off
  - 生存: on/off (始终 on, 条件触发)
```

---

## 4. 审查用代码路径

审查以下文件（均在 D:\AI_Workbench\integrations\minecraft\forge-mod\src\main\java\com\aiworkbench\companion\）：

| 文件 | 行数 | 审查重点 |
|------|------|----------|
| entity/AutomatonEntity.java | 3283 | CompanionState 状态机 (L126-132), registerGoals (L479-518), toggleFollowMode (L2105-2125), transitionTo (L2431-2441) |
| entity/goal/BTreeGatherGoal.java | 311 | scan() 扫描+过滤+可达性 (L242-330), mineTarget 采集逻辑 (L167-197) |
| task/Navigator.java | 83 | navigateTo 寻路 (L44-55), checkStuck 卡住检测 (L61-69) |
| skill/atomic/BreakBlockAction.java | 243 | performBreak 挖掘逻辑 (L105-164), setTaskTarget 目标锁定 (L59-65) |
| event/PlayerEventHandler.java:246 | — | onPlayerLoggedOut (登出处理) |

GitHub: https://github.com/nideweilaiya/mod
