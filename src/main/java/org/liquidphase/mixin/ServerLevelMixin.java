package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 含水相冻结（规划 §3.3）：原版 Biome.shouldFreeze 硬编码只冻 LiquidBlock 的水。
 * 在 tickChunk（≤1.20.1）/ tickPrecipitation（1.21.1+）的 shouldFreeze 调用点做 redirect：
 * 原版判定照常；原版返回 false 且该格是水相宿主时，用 shouldFreeze 的宿主镜像判定冻结成含冰相。
 * 原版自己的结冰行为完全不受影响。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
    @Redirect(
        method = "tickPrecipitation",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean liquidphase$freezeHost(Biome biome, LevelReader level, BlockPos pos) {
        boolean vanilla = biome.shouldFreeze(level, pos);
        if (!vanilla && level instanceof ServerLevel serverLevel) {
            PhaseHelper.tryFreezeHost(serverLevel, biome, pos);
        }
        return vanilla;
    }
    //?} else {
    /*@Redirect(
        method = "tickChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean liquidphase$freezeHost(Biome biome, LevelReader level, BlockPos pos) {
        boolean vanilla = biome.shouldFreeze(level, pos);
        if (!vanilla && level instanceof ServerLevel serverLevel) {
            PhaseHelper.tryFreezeHost(serverLevel, biome, pos);
        }
        return vanilla;
    }
     *///?}
}
