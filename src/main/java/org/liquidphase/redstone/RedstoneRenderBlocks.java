package org.liquidphase.redstone;

import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * 红石相渲染资源覆盖到的宿主清单（41 个：楼梯/台阶/墙/栅栏）。
 * <p>
 * 与 {@code tools/gen_redstone_assets.py} 的宿主清单保持一致：脚本按这个顺序
 * 生成 blockstate，客户端按同一清单登记 cutout 图层。
 * <p>
 * 红石相不是独立方块，所以这里只需要宿主列表（不再有组合方块注册）。
 */
public final class RedstoneRenderBlocks {

    /** 与生成器一致的 41 个宿主 id。 */
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
