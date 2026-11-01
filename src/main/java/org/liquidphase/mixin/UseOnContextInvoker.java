package org.liquidphase.mixin;

import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 UseOnContext 受保护的 getHitResult（BlockPlaceContext 没有公开的
 * "被点击方块"访问器；放置吸收需要原始命中点）。
 */
@Mixin(UseOnContext.class)
public interface UseOnContextInvoker {

    @Invoker("getHitResult")
    BlockHitResult liquidphase$getHitResult();
}
