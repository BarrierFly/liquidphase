package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 服务端自检用：直接触发活塞的 block event（{@code triggerEvent}），绕过
 * {@code level.blockEvent} 队列（自检跑在 {@code SERVER_STARTED}，取不到事件队列的排空入口）。
 * 自检里配合"活塞后方放红石块"即可真实执行 {@code moveBlocks}（含本 mod 的
 * {@code PistonBaseBlockMixin} 两处重定向），覆盖活塞路径的真实接线。
 */
@Mixin(PistonBaseBlock.class)
public interface PistonBaseBlockInvoker {

    @Invoker("triggerEvent")
    boolean liquidphase$triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param);

    /**
     * 直驱移动逻辑（伸 {@code true} / 缩 {@code false}）。
     * <p>
     * 自检用：缩回路径在 {@code triggerEvent} 里要求活塞头位置有
     * {@code PistonMovingBlockEntity}，自检场地摆不出完整生命周期；{@code moveBlocks} 本身
     * 不依赖它，直接调用即可覆盖同一套移动/清格逻辑（含 mod 的两处重定向）。
     */
    @Invoker("moveBlocks")
    boolean liquidphase$moveBlocks(Level level, BlockPos pos, net.minecraft.core.Direction direction, boolean extending);
}
