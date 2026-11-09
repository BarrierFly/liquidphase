package org.liquidphase.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.sync.PhaseSyncState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 功能三（规划 §5.1 准星）：客户端手持空桶时，准星对液体启用流体射线
 * （原版交互射线 {@code ClipContext.Fluid.NONE} 打不中液体——这是功能三必须双端的原因）。
 * <p>
 * 注入点 = {@code Entity.pick} 里的 {@code ClipContext} 构造（全版本一致，
 * 准星的方块拾取都走这里）。仅当拾取者是本地玩家、持空桶、<b>且服务端已确认支持相</b>时，
 * 把 {@code NONE} 升级成 {@code SOURCE_ONLY}。
 * <p>
 * 最后那条服务端能力检查是关键：连原版服务器时如果仍然启用流体射线，客户端准星会指向
 * 自己世界里"看不见的液体"，随后发出的挖掘包对原版服务器毫无意义——表现为
 * 准星选中了空气、点了没反应。握手前/无 mod 服务器一律保持原版行为。
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
            && PhaseSyncState.serverSupportsPhases()
            && PhaseTargeting.holdingEmptyBucket(net.minecraft.client.Minecraft.getInstance().player)) {
            fluid = ClipContext.Fluid.SOURCE_ONLY;
        }
        return new ClipContext(from, to, block, fluid, entity);
    }
}
