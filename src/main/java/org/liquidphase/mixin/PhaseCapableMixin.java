package org.liquidphase.mixin;

import net.minecraft.world.level.block.*;
import org.liquidphase.phase.PhaseCapable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给全部"直接实现 SimpleWaterloggedBlock 的类"注入 {@link PhaseCapable} 标记
 * （鸭子模式空壳类；子类经继承获得标记）。
 * <p>
 * 目标清单必须与各版本的直接实现类集合一致（guardian 逐版本核对，
 * 再生成方法见 AGENTS.md）：
 * 1.19.4=33、1.20.1=34、1.21.1=37、1.21.10=1.21.11=39、26.3=39。
 * <p>
 * 版本差异：DecoratedPotBlock 1.20.1+；Barrier/HeavyCore/WaterloggedTransparent 1.21.1+；
 * CopperGolemStatue/DriedGhast/Shelf 1.21.10+；多面体家族 1.21.1 及以前是
 * GlowLichenBlock+SculkVeinBlock，1.21.10+ 换成 MultifaceBlock；
 * 石钟乳 26.3 改名 SpeleothemBlock。
 */
@Mixin({
        AmethystClusterBlock.class,
        BarrierBlock.class,
        BaseCoralPlantTypeBlock.class,
        BaseRailBlock.class,
        BigDripleafBlock.class,
        BigDripleafStemBlock.class,
        CampfireBlock.class,
        CandleBlock.class,
        ChainBlock.class,
        ChestBlock.class,
        ConduitBlock.class,
        //? if 1.21.10 || 1.21.11 || 26.3 {
        CopperGolemStatueBlock.class,
        //?}
        CrossCollisionBlock.class,
        //? if 1.20.1 || 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        DecoratedPotBlock.class,
        //?}
        //? if 1.21.10 || 1.21.11 || 26.3 {
        DriedGhastBlock.class,
        //?}
        EnderChestBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        HeavyCoreBlock.class,
        //?}
        HangingRootsBlock.class,
        // 1.21.10+ 多面体家族统一成 MultifaceBlock（glow_lichen / sculk_vein / resin_clump 都直接或间继承它）。
        // 注意：原始源码必须对 1.21.11 合法——这里的 live 分支就是 1.21.11 要用的 MultifaceBlock。
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*GlowLichenBlock.class,
        SculkVeinBlock.class,
        *///?} else {
        MultifaceBlock.class,
        //?}
        LadderBlock.class,
        LanternBlock.class,
        LeavesBlock.class,
        LightBlock.class,
        LightningRodBlock.class,
        MangrovePropaguleBlock.class,
        MangroveRootsBlock.class,
        //? if 1.19.4 || 1.20.1 || 1.21.1 || 1.21.10 || 1.21.11 {
        PointedDripstoneBlock.class,
        //?} else {
        /*SpeleothemBlock.class,
         *///?}
        ScaffoldingBlock.class,
        SculkSensorBlock.class,
        SculkShriekerBlock.class,
        SeaPickleBlock.class,
        //? if 1.21.10 || 1.21.11 || 26.3 {
        ShelfBlock.class,
        //?}
        SignBlock.class,
        SlabBlock.class,
        SmallDripleafBlock.class,
        StairBlock.class,
        TrapDoorBlock.class,
        WallBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        WaterloggedTransparentBlock.class,
        //?}
})
public abstract class PhaseCapableMixin implements PhaseCapable {

}
