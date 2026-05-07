package com.aiworkbench.companion.skill.atomic;

import com.aiworkbench.companion.AICompanionMod;
import com.aiworkbench.companion.entity.AutomatonEntity;
import com.aiworkbench.companion.skill.AtomicAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 熔炉冶炼 —— 自动完成：靠近熔炉 → 放入原料 + 燃料 → 等待冶炼 → 取出成品。
 * <p>
 * 适用于任何熔炉配方（烧铁、烧金、烧矿等）。
 * 可以指定熔炉位置，或自动搜索最近的熔炉。
 */
public class SmeltItemsAction implements AtomicAction {

    private static final double FURNACE_DISTANCE_SQ = 3.0 * 3.0;
    private static final int MAX_WAIT_TICKS = 600; // 30秒超时
    private static final int FURNACE_SEARCH_RADIUS = 10;

    private BlockPos furnacePos;
    private final Item inputItem;
    private final Item fuelItem;
    private final int totalCraftCount;
    private final boolean autoFind;

    private int tickCounter;
    private int remainingCount;
    private int waitTicks;
    private Phase phase;
    private boolean searched;

    private enum Phase {
        APPROACH,
        LOAD,
        WAIT,
        COLLECT,
        DONE
    }

    /**
     * @param furnacePos 熔炉的位置（传入 null 则自动搜索）
     * @param inputItem  原料物品（如 Items.IRON_ORE）
     * @param fuelItem   燃料物品（如 Items.COAL）
     * @param count      冶炼次数
     */
    public SmeltItemsAction(BlockPos furnacePos, Item inputItem, Item fuelItem, int count) {
        this.furnacePos = furnacePos;
        this.inputItem = inputItem;
        this.fuelItem = fuelItem;
        this.totalCraftCount = Math.max(count, 1);
        this.remainingCount = this.totalCraftCount;
        this.autoFind = (furnacePos == null);
        this.tickCounter = 0;
        this.waitTicks = 0;
        this.searched = false;
        this.phase = Phase.APPROACH;
    }

    /**
     * 自动搜索最近的熔炉并冶炼铁锭。
     */
    public static SmeltItemsAction ironIngot() {
        return new SmeltItemsAction(null, Items.IRON_ORE, Items.COAL, 1);
    }

    /**
     * 在指定熔炉冶炼铁锭。
     */
    public static SmeltItemsAction ironIngotAt(BlockPos furnacePos) {
        return new SmeltItemsAction(furnacePos, Items.IRON_ORE, Items.COAL, 1);
    }

    @Override
    public boolean canStart(AutomatonEntity entity) {
        return true;
    }

    @Override
    public boolean tick(AutomatonEntity entity) {
        tickCounter++;

        switch (phase) {
            case APPROACH -> {
                // 自动搜索熔炉
                if (autoFind && !searched) {
                    furnacePos = findFurnace(entity);
                    searched = true;
                    if (furnacePos == null) {
                        AICompanionMod.LOGGER.info("[Smelt] No furnace found nearby");
                        phase = Phase.DONE;
                        return true;
                    }
                }
                if (furnacePos == null) {
                    phase = Phase.DONE;
                    return true;
                }

                Vec3 center = Vec3.atCenterOf(furnacePos);
                entity.getLookControl().setLookAt(center.x, center.y, center.z);

                double distSq = entity.distanceToSqr(center.x, center.y, center.z);
                if (distSq > FURNACE_DISTANCE_SQ) {
                    entity.getNavigation().moveTo(
                        furnacePos.getX(), furnacePos.getY(), furnacePos.getZ(), 0.8);
                } else {
                    entity.getNavigation().stop();
                    phase = Phase.LOAD;
                }
            }
            case LOAD -> {
                if (loadFurnace(entity)) {
                    phase = Phase.WAIT;
                    waitTicks = 0;
                    AICompanionMod.LOGGER.info("[Smelt] Furnace loaded, waiting for smelting");
                } else {
                    // 没有足够材料
                    AICompanionMod.LOGGER.info("[Smelt] Not enough materials");
                    phase = Phase.DONE;
                    return true;
                }
            }
            case WAIT -> {
                waitTicks++;

                Level level = entity.level();
                BlockEntity be = level.getBlockEntity(furnacePos);
                if (!(be instanceof FurnaceBlockEntity furnace)) {
                    phase = Phase.DONE;
                    return true;
                }

                // 检查输出槽是否有物品（表示一次冶炼完成）
                ItemStack output = furnace.getItem(2);
                if (!output.isEmpty()) {
                    phase = Phase.COLLECT;
                    waitTicks = 0;
                }

                // 检查熔炉是否还在燃烧（燃料槽为空且无输出说明熄灭了）
                ItemStack fuelSlot = furnace.getItem(1);
                if ((fuelSlot.isEmpty() || fuelSlot.getCount() <= 0) && output.isEmpty()) {
                    if (hasFuel(entity)) {
                        // 还有燃料，重新加载
                        phase = Phase.LOAD;
                    }
                }

                // 超时保护
                if (waitTicks > MAX_WAIT_TICKS) {
                    AICompanionMod.LOGGER.info("[Smelt] Wait timeout");
                    phase = Phase.DONE;
                    return true;
                }
            }
            case COLLECT -> {
                if (collectOutput(entity)) {
                    remainingCount--;
                    if (remainingCount <= 0) {
                        phase = Phase.DONE;
                        return true;
                    }
                    // 继续冶炼下一个
                    phase = Phase.LOAD;
                } else {
                    phase = Phase.DONE;
                    return true;
                }
            }
            case DONE -> {
                return true;
            }
        }

        // 总超时
        if (tickCounter > MAX_WAIT_TICKS + totalCraftCount * 100) {
            phase = Phase.DONE;
            return true;
        }

        return false;
    }

    /**
     * 将原料和燃料放入熔炉。
     */
    private boolean loadFurnace(AutomatonEntity entity) {
        Level level = entity.level();
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (!(be instanceof FurnaceBlockEntity furnace)) return false;

        // 检查输入槽是否为空或已有同类型物品
        ItemStack inputSlot = furnace.getItem(0);
        if (inputSlot.isEmpty()) {
            // 从背包找原料放入输入槽
            ItemStack inputStack = findItemInInventory(entity, inputItem);
            if (inputStack.isEmpty() || inputStack.getCount() <= 0) return false;

            ItemStack toPlace = inputStack.split(1);
            furnace.setItem(0, toPlace);
        } else if (inputSlot.getItem() != inputItem) {
            // 输入槽被其他物品占用
            return false;
        }

        // 检查燃料槽
        ItemStack fuelSlot = furnace.getItem(1);
        if (fuelSlot.isEmpty()) {
            ItemStack fuelStack = findItemInInventory(entity, fuelItem);
            if (fuelStack.isEmpty() || fuelStack.getCount() <= 0) {
                // 尝试找其他燃料（煤炭/木炭/木板等）
                fuelStack = findAnyFuel(entity);
            }
            if (fuelStack.isEmpty()) return false;

            ItemStack toPlace = fuelStack.split(1);
            furnace.setItem(1, toPlace);
        }

        return true;
    }

    /**
     * 从熔炉取出成品。
     */
    private boolean collectOutput(AutomatonEntity entity) {
        Level level = entity.level();
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (!(be instanceof FurnaceBlockEntity furnace)) return false;

        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) return false;

        // 取到背包
        if (entity.addItemToInventory(output.copy())) {
            output.setCount(0);
            furnace.setItem(2, ItemStack.EMPTY);
            return true;
        }

        return false;
    }

    /**
     * 在背包中查找指定物品。
     */
    private ItemStack findItemInInventory(AutomatonEntity entity, Item item) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 检查背包中是否有燃料。
     */
    private boolean hasFuel(AutomatonEntity entity) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (!stack.isEmpty() && isFuel(stack)) return true;
        }
        return false;
    }

    /**
     * 在背包中找任何可用的燃料。
     */
    private ItemStack findAnyFuel(AutomatonEntity entity) {
        for (int i = 0; i < entity.getInventorySize(); i++) {
            ItemStack stack = entity.getItem(i);
            if (!stack.isEmpty() && isFuel(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 判断是否为熔炉燃料。
     * 使用 Minecraft 内置的燃料注册表判断。
     */
    private boolean isFuel(ItemStack stack) {
        return stack.getBurnTime(RecipeType.SMELTING) > 0;
    }

    /**
     * 在附近搜索熔炉方块。
     */
    private BlockPos findFurnace(AutomatonEntity entity) {
        BlockPos origin = entity.blockPosition();
        for (int dx = -FURNACE_SEARCH_RADIUS; dx <= FURNACE_SEARCH_RADIUS; dx++) {
            for (int dy = -FURNACE_SEARCH_RADIUS / 2; dy <= FURNACE_SEARCH_RADIUS / 2; dy++) {
                for (int dz = -FURNACE_SEARCH_RADIUS; dz <= FURNACE_SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (entity.level().getBlockState(pos).is(Blocks.FURNACE)) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void stop(AutomatonEntity entity) {
        entity.getNavigation().stop();
        phase = Phase.DONE;
    }

    @Override
    public String getDescription() {
        return "熔炉冶炼";
    }
}
