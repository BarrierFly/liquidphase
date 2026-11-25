package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 刷怪判定的 {@code isSignalSource()} 缺口（0.2.7，F 的 E-2）。
 * <p>
 * 原版 {@code isValidEmptySpawnBlock} 的第二条判据是 {@code blockState.isSignalSource()}：
 * 三种元件方块满足它，所以**元件所在格禁止刷怪**。我们的元件不占方块、宿主的
 * {@code isSignalSource()} 仍是 false，于是同一格在生存玩法里允许刷怪。
 * <p>
 * 这里按坐标补一条：该格有元件条目时按原版结论（禁止刷怪）处理。
 * 注意原版判据顺序是"碰撞满方块 → isSignalSource → 流体非空 → PREVENT_MOB_SPAWNING_INSIDE"，
 * 我们插在 HEAD 并只在有元件条目时短路为 false，不改变其它分支。
 * <p>
 * 第三方 mod 依赖 {@code isSignalSource()} 本身（无坐标）的那一条仍然做不到，已写进 README。
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerElementMixin {

    @Inject(method = "isValidEmptySpawnBlock", at = @At("HEAD"), cancellable = true)
    private static void liquidphase$noSpawnInsideElements(BlockGetter level, BlockPos pos, BlockState state,
                                                          FluidState fluidState, EntityType<?> entityType,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (PhaseExtra.isElement(PhaseExtras.get(level, pos))) {
            // 原版元件方块是 isSignalSource() → 该格禁止刷怪；同一结论映射到元件所在格。
            cir.setReturnValue(false);
        }
    }
}
