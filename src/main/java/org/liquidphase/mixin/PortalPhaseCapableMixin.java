package org.liquidphase.mixin;

import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.liquidphase.phase.PhaseCapable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 传送门三件套作为"可含填充相"的宿主（方案 C / 0.2.0）。
 * <p>
 * 它们**没有** {@code WATERLOGGED} 属性、也不是 {@code SimpleWaterloggedBlock}，所以只实现
 * 基接口 {@link PhaseCapable}（熔岩分支 + 空实现的水分支），绝不能实现
 * {@code WaterloggedPhaseCapable}——否则 {@code placeLiquid}/{@code pickupBlock} 读不存在的
 * 属性直接抛 {@code IllegalArgumentException}（0.1.13 的 barrier 崩溃同型）。
 * <p>
 * 语义：方块本身不变（下界门仍可传送、末地门仍有星空渲染），相是叠加层。
 * 固相（冰/霜冰/黑曜石）按本 mod 的通用规则给整格碰撞——含固相的传送门会被堵住，
 * 这是"固相 = 整格实心"的直接后果，写进 README。
 */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class, EndGatewayBlock.class})
public abstract class PortalPhaseCapableMixin implements PhaseCapable {
}
