package org.liquidphase.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.liquidphase.mixin.UseOnContextInvoker;

/**
 * 把方块放置的<b>目标格</b>定成红石线所在的那一格。
 * <p>
 * <b>根因</b>：{@code BlockItem.place} 的第一步是 {@code context.canPlace()}，而
 * {@code canPlace()} 只问"目标那一格现在能不能被替换"。红石线不是可替换方块，所以这一步
 * 直接 {@code FAIL}——这才是"可含水方块放不进红石线那一格"的唯一原因。
 * <p>
 * <b>不做什么</b>：
 * <ul>
 *   <li>不接管红石线自己的 {@code use}/{@code useWithoutItem}。那是方块交互，属于点击路径，
 *       前两轮就是在这里把玩家手势写进了逻辑；</li>
 *   <li>不改红石线的 {@code canBeReplaced}。那会让水流、活塞、邻居更新、其它模组
 *       统统把红石线当可替换方块（前两轮另一版踩过的坑，自检里有回归断言）。</li>
 * </ul>
 * <b>只做什么</b>：在本上下文里回答两个问题——{@link #canPlace()} 为真、
 * {@link #getClickedPos()} 指向线格；红石线自身的语义一点没动。
 * <p>
 * <b>目标格怎么定</b>——只看"要替换的那一格是不是红石线"，有三种等价的到达方式，
 * 全是原版自己算出来的位置或命中的方块本身：
 * <ol>
 *   <li>原版算出的落点已经是线格（对着线下面撑它的方块、对着线旁的方块朝线的那个面）；</li>
 *   <li>命中的方块就是红石线本身（原版会算成"线的上面/旁边那一格"，但对相容宿主来说
 *       目标格就是线所在的格）。</li>
 * </ol>
 * 判定里没有玩家、没有手、没有 {@code InteractionHand}、没有潜行——只有方块和坐标。
 * 任何自己构造 {@link BlockPlaceContext} 并调用 {@code BlockItem.place} 的来源（玩家用物品
 * 放置、其它模组复用同一条物品放置路径）走的都是同一条路，行为一致。
 */
public final class WirePlaceContext extends BlockPlaceContext {

    /** 原上下文：朝向类查询沿用它的语义（{@code DirectionalPlaceContext} 会重写这些）。 */
    private final BlockPlaceContext source;
    private final BlockPos wirePos;
    private final int wirePower;

    private WirePlaceContext(BlockPlaceContext source, BlockPos wirePos, int wirePower) {
        super(source);
        this.source = source;
        this.wirePos = wirePos;
        this.wirePower = wirePower;
    }

    /**
     * {@return 目标格是红石线时改写过的上下文；否则原样返回}
     * <p>
     * 纯几何判断，见类注释的两种情况。目标格在线格以外时一律维持原版行为。
     */
    public static BlockPlaceContext of(BlockPlaceContext context) {
        if (context instanceof WirePlaceContext) {
            return context;
        }
        Level level = context.getLevel();
        // (1) 原版自己算出的落点就是线格。
        BlockPos computed = context.getClickedPos();
        if (level.getBlockState(computed).is(Blocks.REDSTONE_WIRE)) {
            return wrap(context, computed);
        }
        // (2) 命中的方块本身是红石线 → 目标格就是线所在的那一格。
        BlockPos hit = ((UseOnContextInvoker) context).liquidphase$getHitResult().getBlockPos();
        if (level.getBlockState(hit).is(Blocks.REDSTONE_WIRE)) {
            return wrap(context, hit);
        }
        return context;
    }

    private static BlockPlaceContext wrap(BlockPlaceContext context, BlockPos wirePos) {
        BlockState wire = context.getLevel().getBlockState(wirePos);
        int power = wire.hasProperty(BlockStateProperties.POWER)
            ? wire.getValue(BlockStateProperties.POWER) : 0;
        return new WirePlaceContext(context, wirePos.immutable(), power);
    }

    /** {@return 宿主将要落进去的红石线格} */
    public BlockPos wirePos() {
        return this.wirePos;
    }

    /** {@return 被替换掉的那条红石线的功率（0–15），宿主沿用} */
    public int wirePower() {
        return this.wirePower;
    }

    @Override
    public BlockPos getClickedPos() {
        // 父类构造器里会用本对象回调 canBeReplaced(context)，那时本字段还没赋值；
        // 此时退回父类自己的计算（replaceClicked / relativePos 已就绪）。
        return this.wirePos != null ? this.wirePos : super.getClickedPos();
    }

    /** 红石线格本来不可替换，只对本上下文放行；红石线自身语义不变。 */
    @Override
    public boolean canPlace() {
        return true;
    }

    // ------------------------------------------------------------------
    // 朝向类查询原样委托：包一层之后不能让原上下文的语义退化成默认值。
    // ------------------------------------------------------------------

    @Override
    public Direction getNearestLookingDirection() {
        return this.source != null ? this.source.getNearestLookingDirection() : super.getNearestLookingDirection();
    }

    @Override
    public Direction[] getNearestLookingDirections() {
        return this.source != null ? this.source.getNearestLookingDirections() : super.getNearestLookingDirections();
    }

    @Override
    public Direction getNearestLookingVerticalDirection() {
        return this.source != null
            ? this.source.getNearestLookingVerticalDirection() : super.getNearestLookingVerticalDirection();
    }

    @Override
    public Direction getHorizontalDirection() {
        return this.source != null ? this.source.getHorizontalDirection() : super.getHorizontalDirection();
    }

    @Override
    public float getRotation() {
        return this.source != null ? this.source.getRotation() : super.getRotation();
    }

    @Override
    public boolean isSecondaryUseActive() {
        return this.source != null ? this.source.isSecondaryUseActive() : super.isSecondaryUseActive();
    }
}
