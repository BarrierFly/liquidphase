package org.liquidphase.redstone;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * "强制替换被点击方块"的放置上下文（§3.0.2 红石相两向吸收）。
 * <p>
 * 红石线不是可替换方块，原版 {@link BlockPlaceContext} 会把 {@code getClickedPos()}
 * 算成线旁边那一格；要把宿主放进"红石线所在的格"，必须显式声明替换点击格。
 * {@code replaceClicked} 在父类里是 protected，子类可以直接置位。
 */
public final class WirePlaceContext extends BlockPlaceContext {

    public WirePlaceContext(Level level, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        super(level, player, hand, stack, hit);
        this.replaceClicked = true;
    }
}
