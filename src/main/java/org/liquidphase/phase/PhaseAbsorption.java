package org.liquidphase.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 相材料的物品↔方块映射（规划 §3.0.2 放置吸收）与"相材料格"的唯一口径。
 * <p>
 * 拿相材料物品指向可 log 方块 → 相写入该格；拿可 log 方块指向相材料方块本体 → 相被吸收。
 * <p>
 * <b>0.2.6：填充种类不再由各调用点手写分支</b>。以前"这一格装着什么"在
 * {@code BlockItemMixin}、{@code FillingPlacementMixin}、{@code FallingBlockIntoWireMixin}、
 * {@code PhaseCellReplaceMixin} 里各写一份 {@code wire / material / element} 三分支，
 * 结果是元素（中继器/比较器/侦测器）被逐个调用点打补丁，任何一处漏改就少一条组装路径。
 * 现在统一到 {@link Cell} + {@link #cellOf(BlockState)}：判定只此一份，写入只此一份
 * （{@link #absorbCell}）。
 */
public final class PhaseAbsorption {

    private PhaseAbsorption() {
    }

    /**
     * 相材料格的四种内容。
     * <ul>
     *   <li>{@code MATERIAL}：冰/霜冰/黑曜石（占 {@link Phase} 取值）；</li>
     *   <li>{@code LAVA}：熔岩源（流体形态，占 {@link Phase#LAVA}）；</li>
     *   <li>{@code REDSTONE}：原版红石线（不占 {@code filling}，写在旁路表）；</li>
     *   <li>{@code ELEMENT}：真实中继器/比较器/侦测器方块（不占 {@code filling}，写在旁路表）。</li>
     * </ul>
     */
    public enum CellKind {
        MATERIAL,
        LAVA,
        REDSTONE,
        ELEMENT
    }

    /**
     * 一格"相材料格"的内容。
     *
     * @param kind    种类
     * @param filling 该格贡献的填充相（{@code REDSTONE}/{@code ELEMENT} 为 {@link Phase#NONE}）
     * @param element 该格贡献的元件条目（仅 {@code ELEMENT} 非空，其余为 {@link PhaseExtra#EMPTY}）
     */
    public record Cell(CellKind kind, Phase filling, int element) {

        public boolean isMaterial() {
            return this.kind == CellKind.MATERIAL;
        }

        public boolean isLava() {
            return this.kind == CellKind.LAVA;
        }

        public boolean isRedstone() {
            return this.kind == CellKind.REDSTONE;
        }

        public boolean isElement() {
            return this.kind == CellKind.ELEMENT;
        }
    }

    /** {@return 相材料物品的 BlockItem 对应写入的相；非相材料返回 null} */
    public static Phase phaseOfItem(Block block) {
        if (block == Blocks.ICE) {
            return Phase.ICE;
        }
        if (block instanceof FrostedIceBlock) {
            return Phase.FROSTED_0;
        }
        if (block == Blocks.OBSIDIAN) {
            return Phase.OBSIDIAN;
        }
        return null;
    }

    /** {@return 真实相材料方块状态对应被吸收的相；非相材料方块返回 null} */
    public static Phase phaseOfBlock(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ICE) {
            return Phase.ICE;
        }
        if (block == Blocks.FROSTED_ICE) {
            int age = state.hasProperty(BlockStateProperties.AGE_3) ? state.getValue(BlockStateProperties.AGE_3) : 0;
            return Phase.frosted(age);
        }
        if (block == Blocks.OBSIDIAN) {
            return Phase.OBSIDIAN;
        }
        return null;
    }

    /** {@return 该状态是否是熔岩源（流动熔岩不算）} */
    public static boolean isLavaSource(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.LAVA) && fluid.isSource();
    }

    /**
     * {@return 该格作为"相材料格"贡献的内容；不是相材料格返回 {@code null}}
     * <p>
     * <b>唯一判定源</b>：接受层（{@code PhaseCellReplaceMixin}）、交互层
     * （{@code BlockItemMixin}）、改写层（{@code FillingPlacementMixin}）、下落方块
     * （{@code FallingBlockIntoWireMixin}）一律走这里，新增填充种类只改这一处。
     * <p>
     * 顺序有意义：材料/线/元素都是"方块形态"，熔岩源是"流体形态"，放最后以免
     * 被同时带流体的宿主抢先判定。
     */
    public static Cell cellOf(BlockState state) {
        Phase material = phaseOfBlock(state);
        if (material != null) {
            return new Cell(CellKind.MATERIAL, material, PhaseExtra.EMPTY);
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return new Cell(CellKind.REDSTONE, Phase.NONE, PhaseExtra.EMPTY);
        }
        int element = org.liquidphase.element.ElementBlocks.extraOfBlock(state);
        if (!PhaseExtra.isEmpty(element)) {
            return new Cell(CellKind.ELEMENT, Phase.NONE, element);
        }
        if (isLavaSource(state)) {
            return new Cell(CellKind.LAVA, Phase.LAVA, PhaseExtra.EMPTY);
        }
        return null;
    }

    /**
     * 把相材料格的内容写进宿主格（<b>唯一写入源</b>）。
     * <p>
     * 门控由 {@link #canAbsorbCell} 负责（调用方必须先问它）：各路径的接受判定不同，
     * 但"哪种格子用哪条门控"同样只此一份。
     * <ul>
     *   <li>材料/熔岩：走 {@link PhaseHelper#fillWithPhase}（内部再走 canAcceptPhase）；</li>
     *   <li>红石：{@code redstonePower >= 0} = 吸收已存在的线（沿用原功率），
     *       {@code < 0} = 新放置（初始 0，落地由 onPlace 立即重算）；</li>
     *   <li>元件：{@code ElementBlocks.absorbBlock}（继承朝向/延迟/模式/锁定/供电）。</li>
     * </ul>
     *
     * @param redstonePower 仅红石格有意义：{@code >= 0} 沿用该功率，{@code < 0} 按新放置处理
     * @return 是否真的写入了
     */
    public static boolean absorbCell(Level level, BlockPos pos, BlockState host, Cell cell, int redstonePower) {
        if (cell == null) {
            return false;
        }
        return switch (cell.kind()) {
            case MATERIAL, LAVA -> PhaseHelper.fillWithPhase(level, pos, host, cell.filling());
            case REDSTONE -> {
                if (redstonePower < 0) {
                    yield org.liquidphase.redstone.RedstoneBlocks.setRedstonePhase(level, pos, host);
                }
                org.liquidphase.redstone.RedstoneBlocks.setRedstonePhase(level, pos, redstonePower);
                yield true;
            }
            case ELEMENT -> org.liquidphase.element.ElementBlocks.absorbBlock(level, pos, host, cell.element());
        };
    }

    /**
     * {@return 该宿主格现在能否吸收 {@code cell} 的内容}（<b>唯一门控选择源</b>）
     * <p>
     * 材料/熔岩走 {@link PhaseHelper#canAcceptPhase}（现有填充相可替换性），
     * 红石/元件走 {@link PhaseHelper#canAcceptExtra}（条目为空 + 填充相为 none 或熔岩）。
     * 0.2.6 之前这套"哪种格子用哪条门控"在四个调用点各写一遍，这就是"新加一种填充
     * 只对某几个路径生效"的来源。
     */
    public static boolean canAbsorbCell(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                        BlockState host, Cell cell) {
        if (cell == null) {
            return false;
        }
        return switch (cell.kind()) {
            case MATERIAL, LAVA -> PhaseHelper.canAcceptPhase(host, level, pos, cell.filling());
            case REDSTONE, ELEMENT -> PhaseHelper.canAcceptExtra(level, pos, host);
        };
    }

    /**
     * {@return 手持物品对应的填充相；非填充相材料返回 null}
     * <p>
     * 填充相材料的物品形态 → 相的唯一映射（0.1.17 起供接受层/改写层共用作门控入参，
     * 防止两处各写一份"物品 → 相"映射造成漂移）：红石粉 → {@code redstone_15}
     * （接受判定与功率无关，实际功率由写入路径决定）；其余走 {@link #phaseOfItem}。
     */
    public static Phase fillingOfItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        return phaseOfItem(item.getBlock());
    }
}
