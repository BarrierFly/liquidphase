package org.liquidphase.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseTargeting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 功能三（规划 §5.1 准星）：客户端手持空桶时，准星对液体启用流体射线
 * （原版交互射线 ClipContext.Fluid.NONE 打不中液体——这是功能三必须双端的原因）。
 * 注入点 = Entity.pick 里的 ClipContext 构造（全版本一致，准星的方块拾取都走这里）；
 * 仅当拾取者是本地玩家且手持空桶时把 NONE 升级为 SOURCE_ONLY。
 */
@Mixin(Entity.class)
public abstract class EntityPickMixin {

    @Redirect(
        method = "pick",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/ClipContext;"
        )
    )
    private ClipContext liquidphase$fluidPick(Vec3 from, Vec3 to, ClipContext.Block block, ClipContext.Fluid fluid, Entity entity) {
        if (net.minecraft.client.Minecraft.getInstance().player == entity
            && LiquidPhaseConfig.get().liquidInteraction
            && PhaseTargeting.holdingEmptyBucket(net.minecraft.client.Minecraft.getInstance().player)) {
            fluid = ClipContext.Fluid.SOURCE_ONLY;
        }
        return new ClipContext(from, to, block, fluid, entity);
    }
}
