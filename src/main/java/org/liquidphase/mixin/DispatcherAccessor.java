package org.liquidphase.mixin;

import java.util.Map;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.3 的炼药锅 Dispatcher 把物品行为表设为私有、put 是包私有——追加行为条目需要 accessor。
 * （仅 26.3 存在该类；其余版本为空接口。）
 */
//? if 26.3 {
/*import net.minecraft.core.cauldron.CauldronInteraction;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CauldronInteraction.Dispatcher.class)
public interface DispatcherAccessor {

    @Accessor("items")
    Map<Item, CauldronInteraction> liquidphase$items();
}
 *///?} else {
public interface DispatcherAccessor {
}
//?}
