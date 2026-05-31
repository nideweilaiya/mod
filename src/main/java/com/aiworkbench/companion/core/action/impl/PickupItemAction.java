package com.aiworkbench.companion.core.action.impl;

import com.aiworkbench.companion.core.action.ActionResult;
import com.aiworkbench.companion.core.action.IAction;
import com.aiworkbench.companion.core.perception.PerceptionData;
import com.aiworkbench.companion.entity.AutomatonEntity;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * 鎷惧彇闄勮繎鎺夎惤鐗╃殑鍔ㄤ綔鍘熻銆?
 *
 * <p>鍙礋璐ｅ皢宸茬粡鍦ㄩ檮杩戠殑鐗╁搧鎷夊叆鑳屽寘銆備笉鎵ц瀵昏矾鈥斺€?
 * 璺濈杈冭繙鐨勭墿鍝侀渶瑕佸厛鐢?MoveToAction 璧板埌闄勮繎銆?/p>
 *
 * <p>寰皟绉诲姩锛氱墿鍝佸湪 2.5 鏍煎唴浣嗘湭鑷姩鎷惧彇鏃讹紝鍚戠墿鍝佹柟鍚戣蛋涓€姝ャ€?
 * 杩欐槸鏃犲璺殑鐩寸嚎瓒嬭繎锛屼笉鏇夸唬 MoveToAction銆?/p>
 *
 * <h3>缁勫悎绀轰緥</h3>
 * <pre>{@code
 *   // 瀹屾暣閲囬泦鍘熸湪锛?
 *   MoveToAction(nearTree) 鈫?BreakBlockAction(treePos) 鈫?PickupItemAction("log")
 *
 *   // 濡傛灉鎺夎惤鐗╂暎钀借緝杩滐紝鍏堣蛋鍒版帀钀界墿闄勮繎锛?
 *   MoveToAction(dropPos) 鈫?PickupItemAction("log")
 * }</pre>
 */
public class PickupItemAction implements IAction {

    private final AutomatonEntity entity;
    private final Predicate<ItemEntity> filter;

    private int elapsedTicks;
    private int emptyTicks; // 杩炵画鏃犵墿鍝佺殑 tick 鏁?

    /** 鑷姩鎷惧彇鎰熷簲鑼冨洿 */
    private static final double PICKUP_RANGE = 2.5;
    /** 寰皟瓒嬭繎鑼冨洿锛堢◢澶т簬鑷姩鎷惧彇鑼冨洿锛?*/
    private static final double APPROACH_RANGE_SQ = 3.0 * 3.0;
    /** 瑙嗕负鏃犵墿鍝佺殑鏈€闀挎寔缁椂闂?*/
    private static final int MAX_EMPTY_TICKS = 40;
    /** 鏈€澶ф墽琛屾椂闂?*/
    private static final int MAX_TICKS = 200;

    public PickupItemAction(AutomatonEntity entity, Predicate<ItemEntity> filter) {
        this.entity = entity;
        this.filter = filter;
    }

    /** 鎷惧彇鎵€鏈夌墿鍝侊紙鏃犺繃婊わ級 */
    public PickupItemAction(AutomatonEntity entity) {
        this(entity, e -> true);
    }

    /** 鎸夌墿鍝?ID 鍏抽敭瀛楄繃婊わ紙濡?"oak_log" 鍖归厤鎵€鏈夊師鏈級 */
    public static PickupItemAction byItemId(AutomatonEntity entity, String keyword) {
        return new PickupItemAction(entity, e -> {
            String id = e.getItem().getItem().builtInRegistryHolder()
                .key().location().getPath();
            return id.contains(keyword);
        });
    }

    // ==================== IAction 鎺ュ彛 ====================

    @Override
    public boolean canExecute(PerceptionData perception) {
        return !findMatchingItems().isEmpty();
    }

    @Override
    public ActionResult execute(PerceptionData perception) {
        elapsedTicks++;

        if (elapsedTicks > MAX_TICKS) {
            return ActionResult.FAILURE;
        }

        List<ItemEntity> items = findMatchingItems();

        if (items.isEmpty()) {
            emptyTicks++;
            // 杩炵画鏃犵墿鍝?鈫?纭宸插叏閮ㄦ嬀鍙?
            if (emptyTicks > MAX_EMPTY_TICKS) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.IN_PROGRESS;
        }

        emptyTicks = 0;

        // 鍚戞渶杩戠殑鐗╁搧寰皟瓒嬭繎
        ItemEntity nearest = items.get(0);
        double distSq = entity.distanceToSqr(nearest);

        if (distSq > APPROACH_RANGE_SQ) {
            // 澶繙锛岃秴鍑哄井璋冭寖鍥?鈫?闇€瑕?MoveToAction
            return ActionResult.FAILURE;
        }

        if (distSq > PICKUP_RANGE * PICKUP_RANGE) {
            // 鍦ㄥ井璋冭寖鍥村唴浣嗘湭鍒版嬀鍙栬寖鍥?鈫?鐩寸嚎瓒嬭繎
            Vec3 dir = nearest.position().subtract(entity.position()).normalize();
            Vec3 target = entity.position().add(dir.scale(0.5));
            entity.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
            return ActionResult.IN_PROGRESS;
        }

        // 鐜╁/浼欎即鍦?Minecraft 涓細鑷姩鎷惧彇 1.5 鏍煎唴鐨勭墿鍝?
        // 鍙鎸佺画闈犺繎锛岀墿鍝佷細琚嚜鐒跺惛鍏ヨ儗鍖?

        for (ItemEntity item : items) {
            if (!item.isAlive() || item.isRemoved()) continue;
            if (entity.distanceToSqr(item) > PICKUP_RANGE * PICKUP_RANGE) continue;
            if (entity.addItemToInventory(item.getItem())) {
                item.discard();
            }
        }

        return ActionResult.IN_PROGRESS;
    }

    @Override
    public int getCost() {
        return 1; // 鎷惧彇鏄灛鏃剁殑锛屼唬浠锋瀬灏?
    }

    // ==================== 鍐呴儴鏂规硶 ====================

    private List<ItemEntity> findMatchingItems() {
        AABB box = entity.getBoundingBox().inflate(Math.sqrt(APPROACH_RANGE_SQ));
        return entity.level().getEntitiesOfClass(ItemEntity.class, box, e ->
            e.isAlive() && !e.isRemoved() && filter.test(e)
        );
    }
}
