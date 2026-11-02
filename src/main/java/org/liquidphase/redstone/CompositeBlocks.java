package org.liquidphase.redstone;

import net.minecraft.core.registries.BuiltInRegistries;
//? if 1.19.4 || 1.20.1 || 1.21.1 || 1.21.10 {
/*import net.minecraft.resources.ResourceLocation;
 *///?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.redstone.composites.CompositeFactories;

/**
 * 里程碑 2 §3.4：红石相组合方块注册（R1）。
 * 遍历相容宿主，按 CompositeFactories 的实例表创建组合方块并注册
 * （方块 + 物品；物品不进创造标签）。注册表顺序 = 宿主注册表顺序，两端一致。
 */
public final class CompositeBlocks {

    private CompositeBlocks() {
    }

    public static void register() {
        int registered = 0;
        for (Block host : BuiltInRegistries.BLOCK) {
            if (!(host instanceof PhaseCapable)) {
                continue;
            }
            String path = "redstone_" + BuiltInRegistries.BLOCK.getKey(host).getPath();
            Block composite;

            //? if 1.19.4 || 1.20.1 || 1.21.1 {
            /*ResourceLocation id = resource(path);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            composite = CompositeFactories.create(host, null);
            if (composite == null) {
                continue;
            }
            Registry.register(BuiltInRegistries.BLOCK, id, composite);
            Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(composite, new Item.Properties()));
             *///?} elif 1.21.10 {
            /*// 1.21.10：仍是 ResourceLocation，但 Properties 已要求 setId
            ResourceLocation id = resource(path);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            composite = CompositeFactories.create(host, ResourceKey.create(Registries.BLOCK, id));
            if (composite == null) {
                continue;
            }
            Registry.register(BuiltInRegistries.BLOCK, id, composite);
            Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(composite,
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
             *///?} else {
            Identifier id = resource(path);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            composite = CompositeFactories.create(host, ResourceKey.create(Registries.BLOCK, id));
            if (composite == null) {
                continue;
            }
            Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id), composite);
            Registry.register(BuiltInRegistries.ITEM, ResourceKey.create(Registries.ITEM, id),
                new BlockItem(composite, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
            //?}

            RedstoneBlocks.COMPOSITE_TO_HOST.put(composite, host);
            RedstoneBlocks.HOST_TO_COMPOSITE.put(host, composite);
            registered++;
        }
        LiquidPhase.LOGGER.info("[Liquid Phase] registered {} redstone-phase composite blocks (states: {})",
            registered, Block.BLOCK_STATE_REGISTRY.size());
    }

    //? if 1.19.4 || 1.20.1 {
    /*private static ResourceLocation resource(String path) {
        return new ResourceLocation(LiquidPhase.MOD_ID, path);
    }
     *///?} elif 1.21.1 || 1.21.10 {
    /*private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(LiquidPhase.MOD_ID, path);
    }
     *///?} else {
    private static Identifier resource(String path) {
        return Identifier.fromNamespaceAndPath(LiquidPhase.MOD_ID, path);
    }
    //?}
}
