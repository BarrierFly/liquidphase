package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.phase.PhaseTargeting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 全部相行为的状态级核心 mixin（BlockBehaviour.BlockStateBase 是统一分发点，
 * 子类覆写不会遮蔽注入——比 fork 的块级方案更稳）。
 * <p>
 * 覆盖：熔岩相的流体态与 15 级光照；随机刻（火蔓延/冰融化/霜裂推进）；
 * 计划刻（霜裂推进）；onPlace（霜裂起搏）；破坏保留（旧状态被移除时相实体化）；
 * 邻居变化（熔岩相遇水凝固、霜冰相邻融化）。
 * <p>
 * 版本差异：移除钩子 ≤1.21.1 用状态级 onRemove，1.21.10+ 用 affectNeighborsAfterRemoval；
 * 邻居更新 ≤1.20.1 叫 neighborChanged、1.21.1 起改名 handleNeighborChanged，
 * 1.21.10+ 的 Orientation 参数取代 BlockPos。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    // ------------------------------------------------------------------
    // 流体态与光照
    // ------------------------------------------------------------------

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getFluidState(CallbackInfoReturnable<FluidState> cir) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.isLava(self)) {
            cir.setReturnValue(Fluids.LAVA.defaultFluidState());
        }
    }

    @Inject(method = "getLightEmission", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getLightEmission(CallbackInfoReturnable<Integer> cir) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.isLava(self)) {
            cir.setReturnValue(15);
        }
    }

    // ------------------------------------------------------------------
    // 直接挖相（决策点 D8）：瞄准固体相区域时，挖掘进度按相材质伪状态计算
    // ------------------------------------------------------------------

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getDestroyProgress(Player player, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                CallbackInfoReturnable<Float> cir) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.isSolidPhase(self) && level instanceof net.minecraft.world.level.Level actualLevel
            && PhaseTargeting.isPhaseTarget(player, actualLevel, pos)) {
            cir.setReturnValue(PhaseTargeting.pseudoState(self).getDestroyProgress(player, level, pos));
        }
    }

    // ------------------------------------------------------------------
    // 随机刻资格（state 级是无兜底的字段缓存，必须在这里覆盖）
    // ------------------------------------------------------------------

    @Inject(method = "isRandomlyTicking", at = @At("HEAD"), cancellable = true)
    private void liquidphase$isRandomlyTicking(CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        Phase phase = PhaseHelper.getPhase(self);
        if (phase == Phase.NONE) {
            return;
        }
        boolean ticks;
        if (phase == Phase.LAVA || phase == Phase.OBSIDIAN || phase.isRedstone()) {
            // 熔岩相复刻火蔓延（决策点 D6）；黑曜石相/红石相不需要随机刻。
            ticks = phase == Phase.LAVA;
        } else if (phase == Phase.ICE) {
            ticks = true;
        } else {
            // 霜冰：1.19.4/1.20.1 原版走随机刻，1.21.1+ 原版走 onPlace+计划刻。
            //? if 1.19.4 || 1.20.1 {
            /*ticks = true;
             *///?} else {
            ticks = false;
            //?}
        }
        cir.setReturnValue(ticks);
    }

    // ------------------------------------------------------------------
    // 随机刻与计划刻
    // ------------------------------------------------------------------

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void liquidphase$randomTick(ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        Phase phase = PhaseHelper.getPhase(self);
        if (phase == Phase.LAVA) {
            PhaseHelper.lavaRandomTick(level, pos, self, random);
        } else if (phase == Phase.ICE) {
            PhaseHelper.iceRandomTick(level, pos, self, random);
        } else if (phase.isFrosted()) {
            //? if 1.19.4 || 1.20.1 {
            /*PhaseHelper.frostedRandomTick(level, pos, self, random);
             *///?}
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void liquidphase$tick(ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.getPhase(self).isFrosted()) {
            PhaseHelper.frostedScheduledTick(level, pos, self, random);
        }
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(self)) {
            org.liquidphase.redstone.RedstoneBlocks.tick(level, pos, self);
        }
    }

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void liquidphase$onPlace(Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        PhaseHelper.scheduleFrostTickIfNeeded(level, pos, self);
    }

    // ------------------------------------------------------------------
    // 破坏保留（规划 §3.0.3）：旧状态被移除、新状态为空气、非活塞移动 → 相实体化。
    // 熔岩相经由 getFluidState 的 createLegacyBlock 已天然留下熔岩源，这里只补固体相。
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "onRemove", at = @At("HEAD"))
    private void liquidphase$onRemove(Level level, BlockPos pos, BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        if (!movedByPiston && newState.isAir() && level.getBlockState(pos).isAir()) {
            if (PhaseHelper.isSolidPhase(self)) {
                level.setBlock(pos, PhaseHelper.materializedPhase(self), Block.UPDATE_ALL);
            } else if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(self)) {
                org.liquidphase.redstone.RedstoneBlocks.materializeWire(level, pos, self);
            }
        }
    }
     *///?} else {
    @Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
    private void liquidphase$affectNeighborsAfterRemoval(ServerLevel level, BlockPos pos, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        if (!movedByPiston && level.getBlockState(pos).isAir()) {
            if (PhaseHelper.isSolidPhase(self)) {
                level.setBlock(pos, PhaseHelper.materializedPhase(self), Block.UPDATE_ALL);
            } else if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(self)) {
                org.liquidphase.redstone.RedstoneBlocks.materializeWire(level, pos, self);
            }
        }
    }
    //?}

    // 26.3：红石线连接判定经 BSB.shouldRedstoneWireConnectTo（§3.4）
    //? if 26.3 {
    /*@Inject(method = "shouldRedstoneWireConnectTo", at = @At("HEAD"), cancellable = true)
    private void liquidphase$shouldRedstoneWireConnectTo(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                         net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(self)) {
            cir.setReturnValue(direction == null || org.liquidphase.redstone.RedstoneBlocks.exposed(self, direction.getOpposite()));
        }
    }
     *///?}

    // ------------------------------------------------------------------
    // 邻居变化：熔岩相遇水凝固（原版 shouldSpreadLiquid 等价检查）+ 霜冰相邻融化
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 {
    /*@Inject(method = "neighborChanged", at = @At("HEAD"))
    private void liquidphase$neighborChanged(Level level, BlockPos pos, Block neighborBlock, BlockPos fromPos, boolean movedByPiston, CallbackInfo ci) {
        liquidphase$onNeighbor(level, pos, neighborBlock);
    }
     *///?} elif 1.21.1 {
    /*@Inject(method = "handleNeighborChanged", at = @At("HEAD"))
    private void liquidphase$handleNeighborChanged(Level level, BlockPos pos, Block neighborBlock, BlockPos fromPos, boolean movedByPiston, CallbackInfo ci) {
        liquidphase$onNeighbor(level, pos, neighborBlock);
    }
     *///?} else {
    @Inject(method = "handleNeighborChanged", at = @At("HEAD"))
    private void liquidphase$handleNeighborChanged(Level level, BlockPos pos, Block neighborBlock,
                                                   net.minecraft.world.level.redstone.Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
        liquidphase$onNeighbor(level, pos, neighborBlock);
    }
    //?}

    /** 邻居变化的三件事（相凝固/霜冰相邻/红石相重算/炼药锅）——签名差异只在上面的注入。 */
    private void liquidphase$onNeighbor(Level level, BlockPos pos, Block neighborBlock) {
        BlockState self = (BlockState) (Object) this;
        if (level instanceof ServerLevel serverLevel) {
            if (PhaseHelper.isLava(self)) {
                PhaseHelper.solidifyIfWaterNearby(serverLevel, pos, self);
            }
            PhaseHelper.frostedNeighborChanged(level, pos, self, neighborBlock);
            if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(self)) {
                org.liquidphase.redstone.RedstoneBlocks.neighborChanged(serverLevel, pos, self);
            }
            org.liquidphase.cauldron.CauldronBehaviors.handleNeighborChanged(serverLevel, pos, self);
        }
    }
}
