# MC AI Companion — End-to-End Test Script v1.0

> Run these tests after deploying AICompanion.jar to Minecraft 1.20.4

## Prerequisites
- Minecraft 1.20.4 with Forge 49.2.7
- AICompanion.jar in mods/ folder
- Ollama running on localhost:11434 with qwen3.5 model
- Python panel (optional, on port 8000)

---

## 1. Companion Lifecycle

| # | Test | Steps | Expected |
|---|------|-------|----------|
| L1 | Spawn | /companion create | Companion spawns near player, FOLLOW mode |
| L2 | Name tag | Look at companion | Shows level, mode, HP |
| L3 | Hide/Show | /companion hide → /companion hide | Toggle visibility |
| L4 | Death | Kill companion | Drops items, respawns after 30s with preserved level |
| L5 | Remove | /companion remove | Companion despawns cleanly |

## 2. Movement & Following

| # | Test | Steps | Expected |
|---|------|-------|----------|
| M1 | Follow | Walk away from companion | Follows at comfortable distance |
| M2 | Sprint follow | Sprint away >10 blocks | Companion sprints to catch up |
| M3 | Teleport away | /tp far away (>32 blocks) | Auto-recall after 5 sec |
| M4 | Cross dimension | Enter Nether | Auto-recall cross-dimension |
| M5 | Stop | /companion stop | Companion freezes in place |
| M6 | Come | /companion come | Companion walks to player |

## 3. Mode Switching

| # | Test | Steps | Expected |
|---|------|-------|----------|
| S1 | Guard mode | /companion guard near hostile | Attacks nearby hostiles |
| S2 | Guard timeout | Kill all enemies, wait 10s | Auto-returns to FOLLOW |
| S3 | Gather mode | /companion gather near trees/ores | Walks to and mines resources |
| S4 | Gather barrier | Place dirt in front of ore | Mines dirt first, then ore |
| S5 | Farm mode | /companion farm near crops | Harvests mature crops |
| S6 | F key cycle | Press F key repeatedly | Cycles: FOLLOW→GUARD→GATHER→FARM→PATROL→FOLLOW |
| S7 | Auto-defend | Get attacked by hostile mob | Companion defends and switches back |

## 4. Building

| # | Test | Steps | Expected |
|---|------|-------|----------|
| B1 | Build hut | /companion skill learn buildShelter → wait | Walks to each position, places blocks 1-by-1 |
| B2 | No materials | Build with empty inventory | Reports missing materials, does not start |
| B3 | No support | Build over air | Reports no support under feet |
| B4 | Build pace | Count blocks per second | ~2 blocks/sec (0.5s per block cooldown) |

## 5. Attributes & Leveling

| # | Test | Steps | Expected |
|---|------|-------|----------|
| A1 | Level up | Mine 20+ ores to gain XP | Level up notification, +3 attribute points |
| A2 | Allocate stats | /companion stat vit 5 | HP increases by 5 |
| A3 | Stat cap | /companion stat spd 25 | Rejects: exceeds max (20) |
| A4 | Sprint speed | Allocate speed points, sprint | Noticeably faster but not teleporting |

## 6. Inventory

| # | Test | Steps | Expected |
|---|------|-------|----------|
| I1 | Open GUI | B key or shift+right-click | Opens Container GUI |
| I2 | Transfer items | Move items between panels | Items transfer correctly |
| I3 | Shift-click | Shift+click between inventories | Instant transfer |
| I4 | Equip weapon | Place sword in equip slot | Companion wields sword |
| I5 | NBT stacking | Give 2 enchanted swords | Two separate slots, not stacked |

## 7. Skills & AI

| # | Test | Steps | Expected |
|---|------|-------|----------|
| K1 | Skill list | /companion skill list | Shows all available skills |
| K2 | Execute skill | /companion skill learn mineIronOre | Mines nearest iron ore |
| K3 | Cancel skill | /companion skill cancel mid-execution | Skill stops, returns to previous mode |
| K4 | LLM chat | /companion chat dig iron | LLM responds with [SKILL:gather] |
| K5 | Auto eat | Let hunger drop below 18 | Eats food from inventory |

