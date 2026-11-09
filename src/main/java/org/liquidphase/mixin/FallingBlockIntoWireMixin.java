package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 下落方块落位（{@code FallingBlockEntity.tick()}）的红石线格放行。
 * <p>
 * <b>这条路径为什么单独处理</b>：下落方块落位<b>完全不经过</b> {@code BlockItem.place}，
 * 而是自己算一次
 * <pre>
 * landing.canBeReplaced(new DirectionalPlaceContext(level, pos, DOWN, ItemStack.EMPTY, UP))
 * </pre>
 * 然后直接 {@code level.setBlock(pos, this.blockState, flags)}。注意那个上下文装的是
 * <b>{@code ItemStack.EMPTY}</b>：它只问"这一格能不能被我落进去"，根本没有"手里的物品"，
 * 所以状态级 {@code canBeReplaced} 那边没法按物品判断。
 * <p>
 * <b>做法</b>：只在这一处、且<b>仅当</b>下落方块本身是相容宿主（{@link PhaseCapable}）时，
 * 把"空物品上下文"换成"以下落方块自己的物品构造的上下文"，让状态级放行钩子能认出
 * "要放的是相容宿主"。其它下落方块（沙子、砂砾、混凝土粉末……）保持
 * {@code ItemStack.EMPTY}，行为与原版完全一致，仍然会在红石线上弹成物品。
 * <p>
 * 落位写方块时一并补红石相：下落方块不走 {@code BlockItemMixin.placeBlock}，
 * 那条钩子在这里不会触发，所以只能在 {@code setBlock} 这一处分流。
 * <p>
 * 两个注入都用 {@code require = 1}：原版 {@code tick()} 每个版本都调这两处，
 * 万一某个版本没命中就该构建/启动失败，而不是静默退回"看起来正常、其实没生效"。
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockIntoWireMixin {

    @Shadow
    public abstract BlockState getBlockState();

    /** 把落位判定用的空物品上下文换成"下落方块自己的物品"上下文。 */
    @Redirect(
        method = "tick",
        at = @At(value = "NEW", target = "net/minecraft/world/item/context/DirectionalPlaceContext"),
        require = 1)
    private DirectionalPlaceContext liquidphase$hostContext(Level level, BlockPos pos, Direction direction,
                                                            ItemStack stack, Direction horizontal) {
        if (!level.getBlockState(pos).is(Blocks.REDSTONE_WIRE)) {
            return new DirectionalPlaceContext(level, pos, direction, stack, horizontal);
        }
        // Client-side prediction must wait for the server handshake (vanilla server keeps wires).
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return new DirectionalPlaceContext(level, pos, direction, stack, horizontal);
        }
        ItemStack self = liquidphase$selfStack();
        return new DirectionalPlaceContext(level, pos, direction,
            self.isEmpty() ? stack : self, horizontal);
    }

    /** {@return 下落方块对应的物品栈；不是相容宿主时返回 {@code EMPTY}} */
    private ItemStack liquidphase$selfStack() {
        BlockState state = this.getBlockState();
        if (state == null || !(state.getBlock() instanceof PhaseCapable)) {
            return ItemStack.EMPTY;
        }
        return state.getBlock().asItem() instanceof BlockItem item
            ? new ItemStack(item) : ItemStack.EMPTY;
    }

    /** {@return 落位要写的状态（目标格是红石线且自己是相容宿主时带上原线功率）} */
    private BlockState liquidphase$landingState(Level level, BlockPos pos, BlockState state) {
        if (!PhaseHelper.hasPhaseProperty(state)) {
            return state;
        }
        // Client-side prediction must wait for the server handshake (vanilla server keeps wires).
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return state;
        }
        BlockState landing = level.getBlockState(pos);
        if (!landing.is(Blocks.REDSTONE_WIRE)) {
            return state;
        }
        int power = landing.getValue(BlockStateProperties.POWER);
        return PhaseHelper.withPhase(state, Phase.redstone(power));
    }

    //? if 26.3 {
    /*@Redirect(
        method = "tick",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
        require = 1)
    private boolean liquidphase$writeWithPhase(Level level, BlockPos pos, BlockState state) {
        return level.setBlockAndUpdate(pos, liquidphase$landingState(level, pos, state));
    }
     *///?} else {
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 1)
    private boolean liquidphase$writeWithPhase(Level level, BlockPos pos, BlockState state, int flags) {
        return level.setBlock(pos, liquidphase$landingState(level, pos, state), flags);
    }
    //?}
}
