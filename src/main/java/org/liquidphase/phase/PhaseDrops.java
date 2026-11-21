package org.liquidphase.phase;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 相的"弹出物品"口径（0.2.3 活塞路径）。
 * <p>
 * 活塞"挤掉/摧毁"复合方块（推动覆盖、破坏列表摧毁）时，相不随方块移动，改为在原格弹出：
 * <ul>
 *   <li>固相：黑曜石掉黑曜石；冰/霜冰**无物品**（原版冰没有物品形态，霜冰同样没有）；</li>
 *   <li>熔岩相：液体物品功能开启时掉熔岩物品（沿用桶物品 id），关闭时消失（与原版"关闭功能三
 *       时熔岩相只能靠桶收"的口径一致）；</li>
 *   <li>红石相：掉红石粉；</li>
 *   <li>元件相：掉对应元件物品。</li>
 * </ul>
 * 纯函数（不读世界），服务端自检直接对账；实际生成走 {@code Block.popResource}。
 */
public final class PhaseDrops {

    private PhaseDrops() {
    }

    /** {@return 该相/条目弹出时的物品；无物品返回 {@code EMPTY}} */
    public static ItemStack droppedItem(Phase phase, int extra) {
        if (PhaseExtra.isRedstone(extra)) {
            return new ItemStack(Items.REDSTONE);
        }
        if (PhaseExtra.isElement(extra)) {
            return org.liquidphase.element.ElementBlocks.itemFor(PhaseExtra.kind(extra));
        }
        if (phase == Phase.OBSIDIAN) {
            return new ItemStack(Items.OBSIDIAN);
        }
        if (phase == Phase.LAVA) {
            return org.liquidphase.LiquidPhaseConfig.get().liquidInteraction
                ? new ItemStack(Items.LAVA_BUCKET)
                : ItemStack.EMPTY;
        }
        // 冰/霜冰无物品（原版冰靠精准采集/融化，这里按"没有掉落物"处理）。
        return ItemStack.EMPTY;
    }
}
