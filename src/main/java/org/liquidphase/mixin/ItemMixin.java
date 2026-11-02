package org.liquidphase.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.liquidphase.LiquidPhaseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 功能三（规划 §5.1）：液体物品作为配方材料时不产生空桶剩余物——
 * 取消水/熔岩"桶"（液体物品）的 craftingRemainingItem。
 * （26.3 的返回类型是 ItemStackTemplate，其余版本是 ItemStack，对 handler 无影响。）
 */
@Mixin(Item.class)
public abstract class ItemMixin {

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getCraftingRemainingItem", at = @At("HEAD"), cancellable = true)
     *///?} else {
    @Inject(method = "getCraftingRemainder", at = @At("HEAD"), cancellable = true)
    //?}
    private void liquidphase$noBucketRemainder(CallbackInfoReturnable<Object> cir) {
        if (LiquidPhaseConfig.get().liquidInteraction
            && ((Object) this == Items.WATER_BUCKET || (Object) this == Items.LAVA_BUCKET)) {
            cir.setReturnValue(null);
        }
    }
}
