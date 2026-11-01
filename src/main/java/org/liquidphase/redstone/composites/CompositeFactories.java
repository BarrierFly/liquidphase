// 由 tools/gen_redstone_composites.py 生成——不要手改，重跑生成器。
package org.liquidphase.redstone.composites;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.block.WeatheringCopper;

/**
 * 宿主方块实例 → 红石相组合方块（§3.4 R1）。构造参数原文取自 vanilla Blocks.java，
 * Properties 参数统一替换为 properties(host)（= 原版属性拷贝，战利品表随宿主）。
 */
public final class CompositeFactories {

    private CompositeFactories() {
    }

    private static BlockBehaviour.Properties properties(Block host, net.minecraft.resources.ResourceKey<Block> id) {
        //? if 1.19.4 || 1.20.1 {
        /*return BlockBehaviour.Properties.copy(host);
         *///?} else if 1.21.1 || 1.21.10 {
        /*return BlockBehaviour.Properties.ofFullCopy(host);
         *///?} else {
        // 1.21.11+/26.3：Properties 必须带注册键，否则构造时 effectiveDrops 抛 "Block id not set"
        return BlockBehaviour.Properties.ofFullCopy(host).setId(id);
        //?}
    }

    private static final Function<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> REGISTRY = buildRegistry();

    private static Function<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> buildRegistry() {
        Map<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> map = new HashMap<>();
        //? if 1.21.1 {
                    map.put(Blocks.TUFF_STAIRS, (id) -> new WireStairBlock(Blocks.TUFF.defaultBlockState(), properties(Blocks.TUFF_STAIRS, id)));
        //? }
        //? if 1.21.1 {
                    map.put(Blocks.POLISHED_TUFF_STAIRS, (id) -> new WireStairBlock(Blocks.POLISHED_TUFF.defaultBlockState(), properties(Blocks.POLISHED_TUFF_STAIRS, id)));
        //? }
        //? if 1.21.1 {
                    map.put(Blocks.TUFF_BRICK_STAIRS, (id) -> new WireStairBlock(Blocks.TUFF_BRICKS.defaultBlockState(), properties(Blocks.TUFF_BRICK_STAIRS, id)));
        //? }
        return map::get;
    }

    /** {@return 组合方块实例；host 不在表内时返回 null} */
    public static Block create(Block host, net.minecraft.resources.ResourceKey<Block> id) {
        Function<net.minecraft.resources.ResourceKey<Block>, Block> factory = REGISTRY.apply(host);
        return factory == null ? null : factory.apply(id);
    }
}