package org.liquidphase.redstone;

import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * 红石相渲染相关的宿主清单辅助类（历史遗留，保留给诊断/自检使用）。
 * <p>
 * <b>0.1.10 起红石外壳对“全部相容宿主”生成</b>（不再只限楼梯/台阶/墙/栅栏这 41 种）：
 * 任何 {@code PhaseCapable} 方块都能被写入 {@code phase=redstone_*}，只给 41 种生成外壳时，
 * 其余宿主（玻璃板、铁栏杆、梯子、树叶、锁链……这些<b>透明方块</b>最明显）在红石相下
 * 完全看不到红石。客户端也不再按这份清单登记渲染层，而是统一把“默认图层为 SOLID 的
 * 相容宿主”升级成 cutout（半透明宿主保持不变），见 {@code LiquidPhaseClient}。
 * <p>
 * {@code tools/redstone-hosts.txt} 仍保留，作为“外壳贴图最早覆盖的 41 个代表宿主”清单
 * 与生成器自检用。
 */
public final class RedstoneRenderBlocks {

    /** 早期外壳覆盖的 41 个代表宿主 id（文档/自检用）。 */
    private static final String[] HOST_IDS = {
        "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs",
        "acacia_stairs", "dark_oak_stairs", "cobblestone_stairs", "stone_stairs",
        "brick_stairs", "stone_brick_stairs", "sandstone_stairs", "quartz_stairs",
        "purpur_stairs",
        "oak_slab", "spruce_slab", "birch_slab", "jungle_slab",
        "acacia_slab", "dark_oak_slab", "stone_slab", "cobblestone_slab",
        "smooth_stone_slab", "stone_brick_slab", "brick_slab", "sandstone_slab",
        "quartz_slab", "purpur_slab",
        "cobblestone_wall", "cobbled_deepslate_wall", "mossy_cobblestone_wall",
        "stone_brick_wall", "brick_wall", "sandstone_wall", "prismarine_wall",
        "oak_fence", "spruce_fence", "birch_fence", "jungle_fence",
        "acacia_fence", "dark_oak_fence", "nether_brick_fence",
    };

    private RedstoneRenderBlocks() {
    }

    /** {@return 清单里实际存在的宿主方块（版本差异按注册表自然过滤）} */
    public static Iterable<Block> redstoneHosts() {
        java.util.List<Block> result = new java.util.ArrayList<>(HOST_IDS.length);
        for (String id : HOST_IDS) {
            for (Block block : BuiltInRegistries.BLOCK) {
                if (BuiltInRegistries.BLOCK.getKey(block).getPath().equals(id)) {
                    result.add(block);
                    break;
                }
            }
        }
        return result;
    }

    /** 未使用（保留给需要按需查询的调用方）。 */
    static Supplier<Iterable<Block>> supplier() {
        return RedstoneRenderBlocks::redstoneHosts;
    }
}
