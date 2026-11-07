// 手写维护的实例工厂：只登记跨六个版本都存在的基础宿主方块（stairs / slabs / walls / fences）。
// 其余宿主的批量生成（tools/gen_redstone_composites.py）仍在完善中，待其稳定后再切换为生成物。
package org.liquidphase.redstone.composites;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 宿主方块实例 → 红石相组合方块（§3.4 R1）。
 * 组合方块的属性 = 宿主属性拷贝（战利品表随宿主），因此破坏后掉落与原版一致。
 */
public final class CompositeFactories {

    private CompositeFactories() {
    }

    private static BlockBehaviour.Properties properties(Block host, ResourceKey<Block> id) {
        //? if 1.19.4 || 1.20.1 {
        /*return BlockBehaviour.Properties.copy(host).dropsLike(host);
         *///?} elif 1.21.1 {
        /*return BlockBehaviour.Properties.ofFullCopy(host).dropsLike(host);
         *///?} else {
        // 1.21.11+/26.3：Properties 必须带注册键，否则构造时 effectiveDrops 抛 "Block id not set"；
        // 战利品表显式指向宿主（默认会查 liquidphase:blocks/redstone_xxx，那个表不存在 → 破坏不掉落）。
        return BlockBehaviour.Properties.ofFullCopy(host)
            .setId(id)
            .overrideLootTable(host.getLootTable());
        //?}
    }

    private static final Function<Block, Function<ResourceKey<Block>, Block>> REGISTRY = buildRegistry();

    private static Function<Block, Function<ResourceKey<Block>, Block>> buildRegistry() {
        Map<Block, Function<ResourceKey<Block>, Block>> map = new HashMap<>();

        // 楼梯：StairBlock(基础方块默认状态, 属性)
        map.put(Blocks.OAK_STAIRS, id -> new WireStairBlock(Blocks.OAK_PLANKS.defaultBlockState(), properties(Blocks.OAK_STAIRS, id)));
        map.put(Blocks.SPRUCE_STAIRS, id -> new WireStairBlock(Blocks.SPRUCE_PLANKS.defaultBlockState(), properties(Blocks.SPRUCE_STAIRS, id)));
        map.put(Blocks.BIRCH_STAIRS, id -> new WireStairBlock(Blocks.BIRCH_PLANKS.defaultBlockState(), properties(Blocks.BIRCH_STAIRS, id)));
        map.put(Blocks.JUNGLE_STAIRS, id -> new WireStairBlock(Blocks.JUNGLE_PLANKS.defaultBlockState(), properties(Blocks.JUNGLE_STAIRS, id)));
        map.put(Blocks.ACACIA_STAIRS, id -> new WireStairBlock(Blocks.ACACIA_PLANKS.defaultBlockState(), properties(Blocks.ACACIA_STAIRS, id)));
        map.put(Blocks.DARK_OAK_STAIRS, id -> new WireStairBlock(Blocks.DARK_OAK_PLANKS.defaultBlockState(), properties(Blocks.DARK_OAK_STAIRS, id)));
        map.put(Blocks.COBBLESTONE_STAIRS, id -> new WireStairBlock(Blocks.COBBLESTONE.defaultBlockState(), properties(Blocks.COBBLESTONE_STAIRS, id)));
        map.put(Blocks.STONE_STAIRS, id -> new WireStairBlock(Blocks.STONE.defaultBlockState(), properties(Blocks.STONE_STAIRS, id)));
        map.put(Blocks.BRICK_STAIRS, id -> new WireStairBlock(Blocks.BRICKS.defaultBlockState(), properties(Blocks.BRICK_STAIRS, id)));
        map.put(Blocks.STONE_BRICK_STAIRS, id -> new WireStairBlock(Blocks.STONE_BRICKS.defaultBlockState(), properties(Blocks.STONE_BRICK_STAIRS, id)));
        map.put(Blocks.SANDSTONE_STAIRS, id -> new WireStairBlock(Blocks.SANDSTONE.defaultBlockState(), properties(Blocks.SANDSTONE_STAIRS, id)));
        map.put(Blocks.QUARTZ_STAIRS, id -> new WireStairBlock(Blocks.QUARTZ_BLOCK.defaultBlockState(), properties(Blocks.QUARTZ_STAIRS, id)));
        map.put(Blocks.PURPUR_STAIRS, id -> new WireStairBlock(Blocks.PURPUR_BLOCK.defaultBlockState(), properties(Blocks.PURPUR_STAIRS, id)));

        // 台阶：SlabBlock(属性)
        map.put(Blocks.OAK_SLAB, id -> new WireSlabBlock(properties(Blocks.OAK_SLAB, id)));
        map.put(Blocks.SPRUCE_SLAB, id -> new WireSlabBlock(properties(Blocks.SPRUCE_SLAB, id)));
        map.put(Blocks.BIRCH_SLAB, id -> new WireSlabBlock(properties(Blocks.BIRCH_SLAB, id)));
        map.put(Blocks.JUNGLE_SLAB, id -> new WireSlabBlock(properties(Blocks.JUNGLE_SLAB, id)));
        map.put(Blocks.ACACIA_SLAB, id -> new WireSlabBlock(properties(Blocks.ACACIA_SLAB, id)));
        map.put(Blocks.DARK_OAK_SLAB, id -> new WireSlabBlock(properties(Blocks.DARK_OAK_SLAB, id)));
        map.put(Blocks.STONE_SLAB, id -> new WireSlabBlock(properties(Blocks.STONE_SLAB, id)));
        map.put(Blocks.COBBLESTONE_SLAB, id -> new WireSlabBlock(properties(Blocks.COBBLESTONE_SLAB, id)));
        map.put(Blocks.SMOOTH_STONE_SLAB, id -> new WireSlabBlock(properties(Blocks.SMOOTH_STONE_SLAB, id)));
        map.put(Blocks.STONE_BRICK_SLAB, id -> new WireSlabBlock(properties(Blocks.STONE_BRICK_SLAB, id)));
        map.put(Blocks.BRICK_SLAB, id -> new WireSlabBlock(properties(Blocks.BRICK_SLAB, id)));
        map.put(Blocks.SANDSTONE_SLAB, id -> new WireSlabBlock(properties(Blocks.SANDSTONE_SLAB, id)));
        map.put(Blocks.QUARTZ_SLAB, id -> new WireSlabBlock(properties(Blocks.QUARTZ_SLAB, id)));
        map.put(Blocks.PURPUR_SLAB, id -> new WireSlabBlock(properties(Blocks.PURPUR_SLAB, id)));

        // 墙：WallBlock(属性)
        map.put(Blocks.COBBLESTONE_WALL, id -> new WireWallBlock(properties(Blocks.COBBLESTONE_WALL, id)));
        map.put(Blocks.COBBLED_DEEPSLATE_WALL, id -> new WireWallBlock(properties(Blocks.COBBLED_DEEPSLATE_WALL, id)));
        map.put(Blocks.MOSSY_COBBLESTONE_WALL, id -> new WireWallBlock(properties(Blocks.MOSSY_COBBLESTONE_WALL, id)));
        map.put(Blocks.STONE_BRICK_WALL, id -> new WireWallBlock(properties(Blocks.STONE_BRICK_WALL, id)));
        map.put(Blocks.BRICK_WALL, id -> new WireWallBlock(properties(Blocks.BRICK_WALL, id)));
        map.put(Blocks.SANDSTONE_WALL, id -> new WireWallBlock(properties(Blocks.SANDSTONE_WALL, id)));
        map.put(Blocks.PRISMARINE_WALL, id -> new WireWallBlock(properties(Blocks.PRISMARINE_WALL, id)));

        // 栅栏：FenceBlock(属性)
        map.put(Blocks.OAK_FENCE, id -> new WireFenceBlock(properties(Blocks.OAK_FENCE, id)));
        map.put(Blocks.SPRUCE_FENCE, id -> new WireFenceBlock(properties(Blocks.SPRUCE_FENCE, id)));
        map.put(Blocks.BIRCH_FENCE, id -> new WireFenceBlock(properties(Blocks.BIRCH_FENCE, id)));
        map.put(Blocks.JUNGLE_FENCE, id -> new WireFenceBlock(properties(Blocks.JUNGLE_FENCE, id)));
        map.put(Blocks.ACACIA_FENCE, id -> new WireFenceBlock(properties(Blocks.ACACIA_FENCE, id)));
        map.put(Blocks.DARK_OAK_FENCE, id -> new WireFenceBlock(properties(Blocks.DARK_OAK_FENCE, id)));
        map.put(Blocks.NETHER_BRICK_FENCE, id -> new WireFenceBlock(properties(Blocks.NETHER_BRICK_FENCE, id)));

        return map::get;
    }

    /** {@return 组合方块实例；host 未登记时返回 null} */
    public static Block create(Block host, ResourceKey<Block> id) {
        Function<ResourceKey<Block>, Block> factory = REGISTRY.apply(host);
        return factory == null ? null : factory.apply(id);
    }
}
