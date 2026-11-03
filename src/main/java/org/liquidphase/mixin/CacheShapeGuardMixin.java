package org.liquidphase.mixin;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * 缓存构造兜底（最后一道防线）：{@code BlockStateBase.Cache} 的构造器直接调用
 * <b>块级</b> {@code getCollisionShape(...)} / 状态级两参版本，宿主类（墙、栅栏、玻璃板等）
 * 覆写了这些方法并按状态查表，遇到追加的 phase 状态会返回 null，随即在
 * {@code this.collisionShape.isEmpty()} 处 NPE（客户端/服务端启动崩溃）。
 * <p>
 * 这里把进入 Cache 的 null 形状统一换成空形状：只影响"形状表里查不到的新状态"，
 * 不改变任何原版状态的缓存结果。
 */
// Cache 是 private 内部类，用 targets 字符串指定
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
public abstract class CacheShapeGuardMixin {

    @ModifyExpressionValue(
        method = "<init>",
        require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private static VoxelShape liquidphase$guardCollision(VoxelShape original) {
        return original == null ? Shapes.empty() : original;
    }

    @ModifyExpressionValue(
        method = "<init>",
        require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private VoxelShape liquidphase$guardCollisionNoContext(VoxelShape original) {
        return original == null ? Shapes.empty() : original;
    }
}
