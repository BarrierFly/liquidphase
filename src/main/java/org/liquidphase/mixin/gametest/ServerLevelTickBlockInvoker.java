//? if 1.21.11 {
package org.liquidphase.mixin.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 测试用访问器：把 {@code ServerLevel.tickBlock}（私有）暴露出来。
 * <p>
 * 为什么需要它：{@code ElementTimingTest} 要在**一次服务端调用**里推进多个 tick（逐 tick 与客户端
 * 往返会慢三十倍）。而元件的计划刻是由 {@code ServerLevelTickBlockMixin} 注入
 * {@code tickBlock} 的 HEAD 执行的——测试若自己写"取状态 → state.tick"，就会绕过那个注入点，
 * 元件永远不 tick（表现为"元件侧波形全 0"）。所以必须调用**真实**的 {@code tickBlock}。
 * <p>
 * 单独放在 {@code org.liquidphase.mixin.gametest} 包：mixin 配置里的 {@code package} 会把这个包
 * 变成"mixin 专属包"，同包内的普通类就再也不能被直接引用了（把访问器放进
 * {@code org.liquidphase.client} 时踩过：{@code LiquidPhaseClient} 直接编译失败）。
 * <p>
 * 只有 1.21.11 会声明 {@code liquidphase.gametest.mixins.json}，正式发布包的其它版本不受影响。
 */
@Mixin(ServerLevel.class)
public interface ServerLevelTickBlockInvoker {

    @Invoker("tickBlock")
    void liquidphase$tickBlock(BlockPos pos, Block block);
}
//?}
