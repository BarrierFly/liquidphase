package org.liquidphase.cauldron;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 混凝土粉末 → 混凝土 的 16 色映射（规划 §4；与 cauldronfix 的映射一致）。
 * 通过注册表 id 查询而不是 Items 常量——26.x 把物品常量迁到了 ItemIds，常量名不稳。
 */
public final class ConcreteMappings {

    private static final String[] COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    };

    private static final Map<Item, Item> POWDER_TO_CONCRETE = new LinkedHashMap<>();

    private ConcreteMappings() {
    }

    /**
     * 按路径查物品：遍历注册表而不是构造 ResourceLocation / 用 Items 常量——
     * 各版本的 id 构造与物品常量位置都不同（26.x 迁到 ItemIds），遍历是唯一稳定口径。
     */
    private static Item item(String path) {
        for (Item candidate : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(candidate).getPath().equals(path)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<Item, Item> map() {
        if (POWDER_TO_CONCRETE.isEmpty()) {
            for (String color : COLORS) {
                Item powder = item(color + "_concrete_powder");
                Item concrete = item(color + "_concrete");
                if (powder != null && concrete != null) {
                    POWDER_TO_CONCRETE.put(powder, concrete);
                }
            }
        }
        return POWDER_TO_CONCRETE;
    }

    /** 全部 16 种混凝土粉末物品（注册顺序用）。 */
    public static Item[] powders() {
        return map().keySet().toArray(new Item[0]);
    }

    /** {@return 手持粉末对应的 1 个混凝土；非混凝土粉末返回 EMPTY} */
    public static ItemStack concreteFor(ItemStack stack) {
        Item concrete = map().get(stack.getItem());
        return concrete == null ? ItemStack.EMPTY : new ItemStack(concrete);
    }
}
