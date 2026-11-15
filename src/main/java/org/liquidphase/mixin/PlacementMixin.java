package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 放置吸收之"放进熔岩源格"：可 log 方块放进熔岩源格时（原版可替换路径），
 * 写入熔岩相（fork 的 PlacementMixin 语义，phase 化）。目标 = Block 基类
 * （覆盖未覆写的方块）+ 各版本自己声明 getStateForPlacement 的类。
 * 目标清单由 guardian 声明矩阵生成（见 AGENTS.md 再生成方法）。
 */
@Mixin({
        Block.class,
        AmethystClusterBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        BarrierBlock.class,
        //?}

        BaseCoralPlantTypeBlock.class,
        BaseCoralWallFanBlock.class,
        BaseRailBlock.class,
        BigDripleafBlock.class,
        //? if 1.20.1 || 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        //? if 1.20.1 || 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        CalibratedSculkSensorBlock.class,
        //?}

        //?}
        CampfireBlock.class,
        CandleBlock.class,
        CeilingHangingSignBlock.class,
        ChainBlock.class,
        ChestBlock.class,
        ConduitBlock.class,
        //? if 1.21.10 || 1.21.11 || 26.3 {
        //? if 1.21.10 || 1.21.11 || 26.3 {
        CopperChestBlock.class,
        //?}

        //? if 1.21.10 || 1.21.11 || 26.3 {
        CopperGolemStatueBlock.class,
        //?}

        //? if 1.21.10 || 1.21.11 || 26.3 {
        DriedGhastBlock.class,
        //?}

        //?}
        //? if 1.20.1 || 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        DecoratedPotBlock.class,
        //?}
        EnderChestBlock.class,
        FenceBlock.class,
        HangingRootsBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        HeavyCoreBlock.class,
        //?}
        IronBarsBlock.class,
        LadderBlock.class,
        LanternBlock.class,
        LeavesBlock.class,
        LightningRodBlock.class,
        MangrovePropaguleBlock.class,
        MangroveRootsBlock.class,
        // 多面体家族全部版本都继承 MultifaceBlock、且 getStateForPlacement 全版本都由它声明
        //（guardian 逐版本核对过）。0.1.12 及以前这里被包在 1.21.10+ 分支里，1.19.4–1.21.1 的
        // glow_lichen / sculk_vein 放进熔岩源格不吸收相（audit §4.6；0.1.13 修）。
        MultifaceBlock.class,
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
        //? if 1.21.10 || 1.21.11 || 26.3 {
        ShelfBlock.class,
        //?}

        //?}
        SlabBlock.class,
        SmallDripleafBlock.class,
        StairBlock.class,
        StandingSignBlock.class,
        TrapDoorBlock.class,
        WallBlock.class,
        WallHangingSignBlock.class,
        WallSignBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        WaterloggedTransparentBlock.class,
        //?}

        //?}
})
public class PlacementMixin {

    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void liquidphase$getStateForPlacement(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(context.getLevel())) {
            return;
        }
        if (this instanceof PhaseCapable && cir.getReturnValue() != null && cir.getReturnValue().hasProperty(PhaseHelper.FILLING)) {
            BlockState placed = cir.getReturnValue();
            BlockPos pos = context.getClickedPos();
            FluidState fluidState = context.getLevel().getFluidState(pos);
            boolean inLavaSource = fluidState.is(Fluids.LAVA) && fluidState.isSource();
            if (inLavaSource && PhaseHelper.canAcceptPhase(placed, context.getLevel(), pos, Phase.LAVA)) {
                cir.setReturnValue(PhaseHelper.withPhase(placed, Phase.LAVA));
            }
        }
    }
}
