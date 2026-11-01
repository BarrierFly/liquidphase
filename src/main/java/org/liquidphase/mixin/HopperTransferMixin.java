package org.liquidphase.mixin;

import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.cauldron.CauldronHopperTransfer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * 功能二：漏斗对锅的液体转运（§4）。挂在原版 tryMoveItems 头部：
 * 完成转运则设置冷却并跳过原版本轮推拉；否则原版照常。
 * <p>
 * 冷却字段名跨版本不同（1.21.x 里叫 transferCooldown / cooldown 等），用反射按"int 且名字含 ooldown"
 * 兜底，避免 accessor 因改名而编译/加载失败。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperTransferMixin {

    @Inject(method = "tryMoveItems", at = @At("HEAD"), cancellable = true)
    private static void liquidphase$hopperTransfer(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, BooleanSupplier canSuck, CallbackInfoReturnable<Boolean> cir) {
        if (CauldronHopperTransfer.tryTransfer(level, pos, state, hopper)) {
            liquidphase$setCooldown(hopper, 8);
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    private static void liquidphase$setCooldown(HopperBlockEntity hopper, int ticks) {
        for (Field field : HopperBlockEntity.class.getDeclaredFields()) {
            if (field.getType() == int.class && field.getName().toLowerCase().contains("ooldown")) {
                try {
                    field.setAccessible(true);
                    field.setInt(hopper, ticks);
                } catch (ReflectiveOperationException ignored) {
                    // 反射不可用时退化为"下一 tick 重试"，只是转运更频繁。
                }
                return;
            }
        }
    }
}
