package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
            return;
        }
        // 含水宿主（含 0.2.0 起补了 WATERLOGGED 的传送门）要像原版含水方块一样
        // 报告水为源，流体渲染与流体刻才会把它当水。带材料相时互斥（filling != none）。
        if (self.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
            && self.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
            && PhaseHelper.getPhase(self) == Phase.NONE) {
            cir.setReturnValue(Fluids.WATER.getSource(false));
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
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        boolean redstone = org.liquidphase.phase.PhaseExtra.isRedstone(extra);
        if ((PhaseHelper.isSolidPhase(self) || redstone) && level instanceof net.minecraft.world.level.Level actualLevel
            && PhaseTargeting.isPhaseTarget(player, actualLevel, pos)) {
            cir.setReturnValue(PhaseTargeting.pseudoState(self, extra).getDestroyProgress(player, level, pos));
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
        if (phase == Phase.LAVA || phase == Phase.OBSIDIAN) {
            // 熔岩相复刻火蔓延（决策点 D6）；黑曜石相不需要随机刻。
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
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
            org.liquidphase.redstone.RedstoneBlocks.tick(level, pos, self);
        } else if (org.liquidphase.phase.PhaseExtra.isElement(extra)) {
            org.liquidphase.element.ElementBlocks.tick(level, pos, self, extra);
        }
    }

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void liquidphase$onPlace(Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        PhaseHelper.scheduleFrostTickIfNeeded(level, pos, self);
        // 红石相落地（无论哪条组装路径、无论初始功率写的是 0 还是原线功率）一律立即
        // 按邻居重算——镜像原版红石线 onPlace → updatePowerStrength。
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
            org.liquidphase.redstone.RedstoneBlocks.neighborChanged(level, pos, self);
        } else if (org.liquidphase.phase.PhaseExtra.isElement(extra) && level instanceof ServerLevel serverLevel) {
            org.liquidphase.element.ElementBlocks.refreshLock(serverLevel, pos, self, extra);
        }
    }

    // ------------------------------------------------------------------
    // 推不动（0.2.3）：黑曜石填充相一律 BLOCK——推动、粘性拉回、黏液链全部失败。
    // ------------------------------------------------------------------

    @Inject(method = "getPistonPushReaction", at = @At("HEAD"), cancellable = true)
    private void liquidphase$pushReaction(CallbackInfoReturnable<net.minecraft.world.level.material.PushReaction> cir) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.getPhase(self) == Phase.OBSIDIAN) {
            // 黑曜石填充相一律推不动（0.2.3 用户口径）：推动、粘性拉回、黏液链全部失败。
            // 其余相可被推动，但相不随方块移动（见 liquidphase$removed 的活塞路径分发）。
            // 26.3 的 PushReaction 枚举改名（BLOCK → IMMOVEABLE，NORMAL → PUSH_PULL，
            // DESTROY → POPPED，PUSH_ONLY → PUSH）。
            //? if 26.3 {
            /*cir.setReturnValue(net.minecraft.world.level.material.PushReaction.IMMOVEABLE);
             *///?} else {
            cir.setReturnValue(net.minecraft.world.level.material.PushReaction.BLOCK);
            //?}
        }
    }

    // ------------------------------------------------------------------
    // 破坏保留（规划 §3.0.3）/活塞路径（0.2.3）：旧状态被移除时相的去向。
    // 四条路径（movedByPiston 与原格内容共同区分）：
    //   ① 普通破坏（!moved、原格空气、非破坏列表）→ 破坏保留：相实体化留原格
    //      （元件随宿主消失不掉落，维持 0.2.2 现状）。
    //   ② 活塞破坏列表摧毁（!moved + PistonPhaseOps 标记）→ 相弹出为物品。
    //   ③ 推动覆盖（moved、原格 = 移塞方块）→ 相弹出为物品（相不随方块移动）。
    //   ④ 拉动/剩余清理（moved、原格空气）→ 任意填充相实体化留在原位
    //      （元件无法实体化，掉落对应物品）。
    // 熔岩相经由 getFluidState 的 createLegacyBlock 在普通破坏时天然留下熔岩源，这里只补固体相。
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "onRemove", at = @At("HEAD"))
    private void liquidphase$onRemove(Level level, BlockPos pos, BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        liquidphase$removed(level, pos, self, movedByPiston);
    }
     *///?} else {
    @Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
    private void liquidphase$affectNeighborsAfterRemoval(ServerLevel level, BlockPos pos, boolean movedByPiston, CallbackInfo ci) {
        BlockState self = (BlockState) (Object) this;
        liquidphase$removed(level, pos, self, movedByPiston);
    }
    //?}

    /**
     * 方块被移除：按路径决定相的去向（见上），并清掉旁路条目。
     * <p>
     * 旁路表条目在 {@code LevelChunkMixin} 里**不会**因为换成空气而提前清掉
     * （否则这里读不到红石功率），所以本方法是红石/元件条目的正常清理点。
     * <p>
     * 弹出口径（0.2.3，用户拍板）：固相掉对应物品（冰/霜冰无物品即消失、黑曜石掉黑曜石）；
     * 熔岩相在"液体物品"功能开启时掉熔岩物品、关闭时消失；红石相掉红石粉；元件掉对应物品。
     * 实体化口径：材料相实体化为真实方块（熔岩相 = 熔岩源），红石相留对应功率红石线；
     * 元件无法实体化，按弹出口径掉物品。
     */
    private void liquidphase$removed(Level level, BlockPos pos, BlockState self, boolean movedByPiston) {
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        boolean hasEntry = !org.liquidphase.phase.PhaseExtra.isEmpty(extra);
        boolean hasMaterialPhase = PhaseHelper.getPhase(self) != Phase.NONE;
        if (!hasEntry && !hasMaterialPhase) {
            return;
        }
        boolean cellAir = level.getBlockState(pos).isAir();
        boolean popItems = false;
        if (movedByPiston) {
            // ③ 推动覆盖（原格被移塞方块占据）→ 弹出；④ 拉动/剩余清理（原格空气）→ 实体化。
            popItems = !cellAir;
        } else if (org.liquidphase.phase.PistonPhaseOps.inDestroyList()) {
            // ② 活塞破坏列表摧毁 → 弹出。
            popItems = true;
        } else if (!cellAir) {
            // 方块被另一个方块替换（非活塞、非破坏列表）：相随状态一起消失（现状语义）。
            if (hasEntry) {
                org.liquidphase.phase.PhaseExtras.set(level, pos, org.liquidphase.phase.PhaseExtra.EMPTY);
            }
            return;
        } else {
            // ① 普通破坏 → 破坏保留（实体化）。
        }
        if (popItems) {
            liquidphase$popPhase(level, pos, self, extra);
        } else {
            liquidphase$materializePhase(level, pos, self, extra, movedByPiston);
        }
        if (hasEntry) {
            org.liquidphase.phase.PhaseExtras.set(level, pos, org.liquidphase.phase.PhaseExtra.EMPTY);
        }
    }

    /** 弹出为物品（活塞"挤掉/摧毁"口径）：口径见 {@link org.liquidphase.phase.PhaseDrops}。 */
    private void liquidphase$popPhase(Level level, BlockPos pos, BlockState self, int extra) {
        ItemStack stack = org.liquidphase.phase.PhaseDrops.droppedItem(PhaseHelper.getPhase(self), extra);
        if (!stack.isEmpty()) {
            Block.popResource(level, pos, stack);
        }
    }

    /** 实体化到原格（普通破坏保留 / 活塞"留在原位"口径）。 */
    private void liquidphase$materializePhase(Level level, BlockPos pos, BlockState self, int extra, boolean movedByPiston) {
        if (org.liquidphase.phase.PhaseExtra.isElement(extra)) {
            // 元件无法实体化成方块：活塞"留在原位"路径掉落对应物品；
            // 普通破坏维持现状（随宿主消失、不掉落，README 已知限制）。
            if (movedByPiston) {
                ItemStack stack = org.liquidphase.element.ElementBlocks.itemFor(org.liquidphase.phase.PhaseExtra.kind(extra));
                if (!stack.isEmpty()) {
                    Block.popResource(level, pos, stack);
                }
            }
            return;
        }
        if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
            org.liquidphase.redstone.RedstoneBlocks.materializeWire(level, pos,
                org.liquidphase.phase.PhaseExtra.power(extra));
            return;
        }
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, PhaseHelper.materializedPhase(self), Block.UPDATE_ALL);
        }
    }


    // 26.3：红石线连接判定经 BSB.shouldRedstoneWireConnectTo
    //? if 26.3 {
    /*@Inject(method = "shouldRedstoneWireConnectTo", at = @At("HEAD"), cancellable = true)
    private void liquidphase$shouldRedstoneWireConnectTo(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                         net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (org.liquidphase.phase.PhaseExtra.isRedstone(org.liquidphase.phase.PhaseExtras.get(level, pos))) {
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

    /** 邻居变化：相凝固/霜冰相邻/红石相重算/元件重算/炼药锅——签名差异只在上面的注入。 */
    private void liquidphase$onNeighbor(Level level, BlockPos pos, Block neighborBlock) {
        BlockState self = (BlockState) (Object) this;
        if (level instanceof ServerLevel serverLevel) {
            if (PhaseHelper.isLava(self)) {
                PhaseHelper.solidifyIfWaterNearby(serverLevel, pos, self);
                // 缺口 C（audit v2 §2.3，0.1.15 修）：原版源块靠 updateShape/neighborChanged
                // 在邻居变化时重排流体 tick 实现重流；scheduleLavaFlowTick 此前是死代码，
                // 熔岩相流出一次后不再重流。接线后与原版源块语义对齐。
                PhaseHelper.scheduleLavaFlowTick(serverLevel, pos, self);
            }
            PhaseHelper.frostedNeighborChanged(level, pos, self, neighborBlock);
            int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
            if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
                org.liquidphase.redstone.RedstoneBlocks.neighborChanged(serverLevel, pos, self);
            } else if (org.liquidphase.phase.PhaseExtra.isElement(extra)) {
                org.liquidphase.element.ElementBlocks.neighborChanged(serverLevel, pos, self, extra);
            }
            org.liquidphase.cauldron.CauldronBehaviors.handleNeighborChanged(serverLevel, pos, self);
        }
    }
}
