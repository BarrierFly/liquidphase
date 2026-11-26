//? if 1.21.11 {
package org.liquidphase.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.liquidphase.LiquidPhase;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 元件时序对照回归（0.2.8）：把"元件相"和"同一元件作为原版独立方块"放在两个完全相同的
 * 电路里**逐 tick 采样**，要求两者的输出波形一致（允许最多差 0 tick；出现任何偏差即 FAIL）。
 * <p>
 * 原版独立方块直接放在下半砖上不行（下半砖顶面不是 RIGID 支撑），所以对照组的原版元件
 * 放在**完整石头上**，实验组的元件相放在**下半砖**上——两者的信号场景（输入电源距离、
 * 输出引线长度、观察面邻居）逐一对应。被比较的量是"下游引线/中继器的实际 powered 变化时刻"，
 * 这排除了"元件贴在哪块宿主上"的几何差异，只比较电路语义。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>观察者：观察面邻居翻转 → 输出点亮 → 复位，逐 tick 记录 POWERED；</li>
 *   <li>中继器：输入电源开/关 → 输出翻转时刻与延迟档位；</li>
 *   <li>比较器：容器容量变化 → 输出功率变化时刻。</li>
 * </ol>
 * 跑法：{@code ./gradlew :1.21.11:runClientGameTest}。
 */
public final class ElementTimingTest implements FabricClientGameTest {

    private static final int SAMPLE_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);
        // 覆盖：观察者 / 中继器 / 比较器的输出波形必须与"原版元件方块"逐 tick 一致。
        // 调试开关：-Dliquidphase.debug.elements=true 时打印每次 updateShape / 排刻 / tick。
        if (Boolean.getBoolean("liquidphase.debug.elements")) {
            LiquidPhase.LOGGER.info("[gametest][timing] element tracing enabled");
        }
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null);
            BlockPos feet = context.computeOnClient(client -> client.player.blockPosition());
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");

            List<String> failures = new ArrayList<>();
            failures.addAll(fuzzObserverByWire(context, sp, feet.offset(40, 0, 6)));
            failures.addAll(compareObserverRelay(context, sp, feet.offset(50, 0, 6)));
            failures.addAll(compareObserverClock(context, sp, feet.offset(60, 0, 6)));
            failures.addAll(compareObserverSeesRepeater(context, sp, feet.offset(70, 0, 6)));
            failures.addAll(compareObserverSeesComparator(context, sp, feet.offset(80, 0, 6)));
            failures.addAll(compareObserver(context, sp, feet.offset(-8, 0, 6)));
            failures.addAll(compareObserverDownstream(context, sp, feet.offset(12, 0, 30)));
            failures.addAll(compareObserverSelfTriggered(context, sp, feet.offset(20, 0, 30)));
            failures.addAll(compareObserverByRedstonePhase(context, sp, feet.offset(28, 0, 30)));
            failures.addAll(compareRepeater(context, sp, feet.offset(0, 0, 6), 1));
            failures.addAll(compareRepeater(context, sp, feet.offset(0, 0, 14), 4));
            failures.addAll(compareRepeaterShortPulse(context, sp, feet.offset(8, 0, 20)));
            failures.addAll(compareComparator(context, sp, feet.offset(8, 0, 6)));
            failures.addAll(compareComparatorSubtract(context, sp, feet.offset(16, 0, 6)));
            failures.addAll(compareObserverSteadySource(context, sp, feet.offset(24, 0, 6)));
            failures.addAll(compareRepeaterChain(context, sp, feet.offset(0, 0, 30)));
            failures.addAll(compareComparatorSustained(context, sp, feet.offset(16, 0, 30)));
            failures.addAll(fuzzObserver(context, sp, feet.offset(30, 0, 6)));
            failures.addAll(fuzzRepeaterInputs(context, sp, feet.offset(30, 0, 16)));
            for (String failure : failures) {
                LiquidPhase.LOGGER.error("[gametest][timing] {}", failure);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("element timing mismatch:\n  " + String.join("\n  ", failures));
            }
            LiquidPhase.LOGGER.info("[gametest][timing] element tick timing matches vanilla element blocks");
        }
    }

    // ------------------------------------------------------------------
    // 观察者
    // ------------------------------------------------------------------

    private static List<String> compareObserver(ClientGameTestContext context, TestSingleplayerContext sp,
                                               BlockPos origin) {
        // 实验组：下半砖 + 观察者条目；对照组：完整石头 + 原版观察者。观察面都朝 NORTH。
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 4);
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            // 观察面邻居：两边各放一格空气→石头，由下面的 tick 序列统一触发。
        });
        context.waitTicks(2);

        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        // 观察面（NORTH）放一格方块 → 两边同时触发；再移走来触发第二次。
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.STONE.defaultBlockState());
                });
            }
            if (i == 20) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer", elementWave, vanillaWave);
    }

    /**
     * 观察者 × 下游：观察者直接驱动一个中继器（下游本身也是元件相 vs 原版）。
     * 检查"前方通知 + 形状更新"两个通道合起来后，下游元件的响应时刻是否一致。
     */
    private static List<String> compareObserverDownstream(ClientGameTestContext context, TestSingleplayerContext sp,
                                                         BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementDownstream = elementCell.south();
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos vanillaDownstream = vanillaCell.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementDownstream.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementDownstream, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementDownstream, level.getBlockState(elementDownstream),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaDownstream.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaDownstream, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.STONE.defaultBlockState());
                });
            }
            if (i == 24) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementDownstream, PhaseExtra.Kind.REPEATER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaDownstream);
                return state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer->repeater", elementWave, vanillaWave);
    }

    /**
     * 观察者自触发（元件时序最脆的一条）：观察面指向它**自己所在格的输入侧**不会触发，
     * 所以这里用"观察者观察自己上方/下方的一段导线"不行；改为直接改观察面邻居，
     * 但**每 tick 连续改两次**（先放再拆），验证 powered 的起振/复位时刻仍与原版一致。
     */
    private static List<String> compareObserverSelfTriggered(ClientGameTestContext context, TestSingleplayerContext sp,
                                                            BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            // 在一个 tick 内先放石头再拆掉：原版的 startSignal 只在"未排刻"时排一次，
            // 所以连续两次 updateShape 不应该变成两个脉冲。
            if (i == 2 || i == 12) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(elementCell.north(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer burst", elementWave, vanillaWave);
    }

    /**
     * 观察者被**红石线功率变化**触发——直接对照：
     * 实验组 = 观察者相 面对 红石相宿主（旁路表功率 0→15）；
     * 对照组 = 原版观察者 面对 原版红石线（power 0→15）。
     * 两者都应当是"变化后 2 tick 点亮、宽 2 tick"。
     */
    private static List<String> compareObserverByRedstonePhase(ClientGameTestContext context,
                                                              TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementObserver = origin;
        BlockPos elementWire = elementObserver.north();
        BlockPos vanillaObserver = origin.offset(0, 0, 6);
        BlockPos vanillaWire = vanillaObserver.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementObserver, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementObserver, level.getBlockState(elementObserver),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementWire, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            PhaseExtras.set(level, elementWire, PhaseExtra.redstone(0));

            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState());
        });
        // 装配过程本身会让观察者看到邻居变化而先脉冲一次：等它完全静下来再开始采样。
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    PhaseExtras.set(level, elementWire, PhaseExtra.redstone(15));
                    level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState()
                        .setValue(BlockStateProperties.POWER, 15));
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementObserver, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaObserver);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("wire-power->observer", elementWave, vanillaWave);
    }

    /**
     * 观察者 × **持续输出**的电源（红石块）：观察面邻居变化只发生一次，
     * 观察者必须只脉冲一次（不是每 tick 都亮），且脉冲宽度/起点与原版一致。
     * 这条覆盖"元件没有自己的方块实体、避免重复起振"的风险。
     */
    private static List<String> compareObserverSteadySource(ClientGameTestContext context, TestSingleplayerContext sp,
                                                           BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer steady source", elementWave, vanillaWave);
    }

    /**
     * 观察者接力（A 观察 B）：观察者相 A 的输出面朝南直对观察者 B 的观察面。
     * 触发 A 的观察面 → A 脉冲 → B 应当收到"观察面邻居（A 的输出）变化"并再脉冲一次。
     * 对照组同构（两个原版观察者）。
     */
    private static List<String> compareObserverRelay(ClientGameTestContext context, TestSingleplayerContext sp,
                                                    BlockPos origin) {
        BlockPos elementA = origin;
        BlockPos elementB = elementA.south();
        BlockPos elementTrigger = elementA.north();
        BlockPos vanillaA = origin.offset(0, 0, 6);
        BlockPos vanillaB = vanillaA.south();
        BlockPos vanillaTrigger = vanillaA.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementA, elementB, vanillaA, vanillaB}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            // 实验组：下半砖 + 元件条目。
            for (BlockPos cell : new BlockPos[]{elementA, elementB}) {
                level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            }
            ElementBlocks.place(level, elementA, level.getBlockState(elementA),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            ElementBlocks.place(level, elementB, level.getBlockState(elementB),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            // 对照组：真实元件方块直接放石头。**不能先垫下半砖**：垫了就会被
            // "元件方块作相材料格"吸收成"宿主 + 条目"，对照组从此读不到原版的 POWERED，
            // 波形恒 0（0.2.8 实测踩过：relay/clock/sees-repeater/sees-comparator 四处的写法）。
            for (BlockPos cell : new BlockPos[]{vanillaA, vanillaB}) {
                level.setBlockAndUpdate(cell, Blocks.OBSERVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            }
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer relay",
            Map.of(vanillaA, Blocks.OBSERVER, vanillaB, Blocks.OBSERVER));
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        List<Boolean> vanillaAWave = new ArrayList<>();
        List<Boolean> elementAWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementTrigger, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaTrigger, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            elementAWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementA, PhaseExtra.Kind.OBSERVER)));
            vanillaAWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaA);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementB, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaB);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        if (ElementBlocks.DEBUG) {
            LiquidPhase.LOGGER.info("[gametest][debug] relay A element={} vanilla={} | B element={} vanilla={} |"
                    + " vanillaA={} vanillaB={}", liquidphase$wave(elementAWave), liquidphase$wave(vanillaAWave),
                liquidphase$wave(elementWave), liquidphase$wave(vanillaWave),
                sp.getServer().computeOnServer(server -> server.getLevel(Level.OVERWORLD).getBlockState(vanillaA)),
                sp.getServer().computeOnServer(server -> server.getLevel(Level.OVERWORLD).getBlockState(vanillaB)));
        }
        problems.addAll(liquidphase$diff("observer relay", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者时钟（反馈环）：A 的输出面直对 B 的观察面、B 的输出面直对 A 的观察面。
     * 这是最容易暴露"少排/多排一个刻"的电路；逐 tick 比较两者的振荡波形是否同相位。
     * <p>
     * ⚠️ 朝向必须让两个观察者**互相看着对方**：B 在 A 的南边，A 的观察面朝 SOUTH、
     * B 的观察面朝 NORTH。0.2.8 第一版两个都朝外（A=NORTH、B=SOUTH），环没闭合，
     * 两边波形恒 0，这条对照会"通过"但什么都没测到（已加振荡断言）。
     */
    private static List<String> compareObserverClock(ClientGameTestContext context, TestSingleplayerContext sp,
                                                    BlockPos origin) {
        BlockPos elementA = origin;
        BlockPos elementB = elementA.south();
        BlockPos vanillaA = origin.offset(0, 0, 6);
        BlockPos vanillaB = vanillaA.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementA, elementB, vanillaA, vanillaB}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos cell : new BlockPos[]{elementA, elementB}) {
                level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            }
            ElementBlocks.place(level, elementA, level.getBlockState(elementA),
                PhaseExtra.Kind.OBSERVER, Direction.SOUTH);
            ElementBlocks.place(level, elementB, level.getBlockState(elementB),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(vanillaA, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(vanillaB, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer clock",
            Map.of(vanillaA, Blocks.OBSERVER, vanillaB, Blocks.OBSERVER));
        context.waitTicks(6);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementA, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaA);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer clock FAIL ring never oscillated (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer clock", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者观察**中继器**：中继器相同位置换成原版中继器作对照。
     * 覆盖"元件相的输出变化能否作为另一个元件相的观察输入"（A2 的另一半）。
     * <p>
     * ⚠️ 中继器 {@code FACING} = **输入面**，电源必须放在 {@code facing} 那一侧。
     * 0.2.8 第一版写成 {@code facing=EAST} + 电源放 {@code west()}（=输出面），
     * 中继器永远不会翻转，两边波形恒 0（已加"必须出现过脉冲"的断言）。
     */
    private static List<String> compareObserverSeesRepeater(ClientGameTestContext context, TestSingleplayerContext sp,
                                                            BlockPos origin) {
        BlockPos elementObserver = origin;
        BlockPos elementRepeater = elementObserver.north();
        BlockPos elementSource = elementRepeater.east();
        BlockPos vanillaObserver = origin.offset(0, 0, 6);
        BlockPos vanillaRepeater = vanillaObserver.north();
        BlockPos vanillaSource = vanillaRepeater.east();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementObserver, elementRepeater, vanillaObserver, vanillaRepeater}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos cell : new BlockPos[]{elementObserver, elementRepeater}) {
                level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            }
            ElementBlocks.place(level, elementObserver, level.getBlockState(elementObserver),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            ElementBlocks.place(level, elementRepeater, level.getBlockState(elementRepeater),
                PhaseExtra.Kind.REPEATER, Direction.EAST);

            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer sees repeater",
            Map.of(vanillaObserver, Blocks.OBSERVER, vanillaRepeater, Blocks.REPEATER));
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementObserver, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaObserver);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer sees repeater FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer sees repeater", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者观察**比较器**（容器容量变化 → 比较器输出变化 → 观察者脉冲）。
     * <p>
     * 几何：观察者在南、比较器在它北边（观察面朝 NORTH，正好看着比较器），
     * 箱子在比较器北边（= 比较器输入面，{@code FACING=NORTH}）。
     * <p>
     * ⚠️ 对照组必须先放真实元件方块、再垫地板，否则"垫下半砖再写元件方块"会被
     * 元件方块作相材料格那条路径吸收掉（见 {@link #liquidphase$expectVanilla} 的注释）。
     */
    private static List<String> compareObserverSeesComparator(ClientGameTestContext context,
                                                             TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementObserver = origin;
        BlockPos elementComparator = elementObserver.north();
        BlockPos elementChest = elementComparator.north();
        BlockPos vanillaObserver = origin.offset(0, 0, 8);
        BlockPos vanillaComparator = vanillaObserver.north();
        BlockPos vanillaChest = vanillaComparator.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementObserver, elementComparator, vanillaObserver, vanillaComparator}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos cell : new BlockPos[]{elementObserver, elementComparator}) {
                level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            }
            ElementBlocks.place(level, elementObserver, level.getBlockState(elementObserver),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            ElementBlocks.place(level, elementComparator, level.getBlockState(elementComparator),
                PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
            level.setBlockAndUpdate(elementChest, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaComparator, Blocks.COMPARATOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaChest, Blocks.CHEST.defaultBlockState());
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer sees comparator",
            Map.of(vanillaObserver, Blocks.OBSERVER, vanillaComparator, Blocks.COMPARATOR));
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            int tick = i;
            if (tick >= 2 && tick <= 5) {
                int count = (tick - 1) * 20;
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    liquidphase$setChestCount(level, elementChest, count);
                    liquidphase$setChestCount(level, vanillaChest, count);
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementObserver, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaObserver);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer sees comparator FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer sees comparator", elementWave, vanillaWave));
        return problems;
    }

    // ------------------------------------------------------------------
    // 中继器
    // ------------------------------------------------------------------

    private static List<String> compareRepeater(ClientGameTestContext context, TestSingleplayerContext sp,
                                               BlockPos origin, int delay) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 4);
        BlockPos elementSource = elementCell.north();
        BlockPos vanillaSource = vanillaCell.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);
            // 设成目标延迟档位。
            PhaseExtras.set(level, elementCell,
                PhaseExtra.withDelay(PhaseExtras.get(level, elementCell), delay - 1));

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, delay)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.LOCKED, false));
        });
        context.waitTicks(2);

        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            if (i == 22) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.REPEATER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("repeater delay=" + delay, elementWave, vanillaWave);
    }

    /**
     * 中继器链（两个串起来，各 1 档）：检查"优先级 + 前方通知"在两段链上的累计时序。
     */
    private static List<String> compareRepeaterChain(ClientGameTestContext context, TestSingleplayerContext sp,
                                                    BlockPos origin) {
        BlockPos elementFirst = origin;
        BlockPos elementSecond = elementFirst.south();
        BlockPos vanillaFirst = origin.offset(0, 0, 8);
        BlockPos vanillaSecond = vanillaFirst.south();
        BlockPos elementSource = elementFirst.north();
        BlockPos vanillaSource = vanillaFirst.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementFirst.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementFirst, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementFirst, level.getBlockState(elementFirst),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);
            level.setBlockAndUpdate(elementSecond.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementSecond, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementSecond, level.getBlockState(elementSecond),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);

            level.setBlockAndUpdate(vanillaFirst.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaFirst, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
            level.setBlockAndUpdate(vanillaSecond.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaSecond, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            if (i == 24) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementSecond, PhaseExtra.Kind.REPEATER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaSecond);
                return state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("repeater chain", elementWave, vanillaWave);
    }

    // ------------------------------------------------------------------
    // 比较器
    // ------------------------------------------------------------------

    /**
     * 中继器短脉冲：输入只亮 1 tick 就撤掉（原版 DiodeBlock#tick 的"已排队就补排一次"路径）。
     */
    private static List<String> compareRepeaterShortPulse(ClientGameTestContext context, TestSingleplayerContext sp,
                                                          BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos elementSource = elementCell.north();
        BlockPos vanillaSource = vanillaCell.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);
            PhaseExtras.set(level, elementCell,
                PhaseExtra.withDelay(PhaseExtras.get(level, elementCell), 1));
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 2));
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            if (i == 3) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSource, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSource, Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.REPEATER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("repeater short pulse", elementWave, vanillaWave);
    }

    private static List<String> compareComparator(ClientGameTestContext context, TestSingleplayerContext sp,
                                                 BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 4);
        BlockPos elementChest = elementCell.north();
        BlockPos vanillaChest = vanillaCell.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
            level.setBlockAndUpdate(elementChest, Blocks.CHEST.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.COMPARATOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaChest, Blocks.CHEST.defaultBlockState());
        });
        context.waitTicks(2);

        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                // 往两个箱子里放同样数量的物品：容器内容变化 → 比较器相/原版比较器都要刷新。
                liquidphase$fillChest(sp, elementChest);
                liquidphase$fillChest(sp, vanillaChest);
            }
            if (i == 20) {
                liquidphase$emptyChest(sp, elementChest);
                liquidphase$emptyChest(sp, vanillaChest);
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.COMPARATOR)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.COMPARATOR) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("comparator", elementWave, vanillaWave);
    }

    private static List<String> compareComparatorSubtract(ClientGameTestContext context, TestSingleplayerContext sp,
                                                          BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 4);
        BlockPos elementChest = elementCell.north();
        BlockPos vanillaChest = vanillaCell.north();
        // 侧面电源（比较器的 alternate 输入）在需要时才放，且只放一边、功率小一档，
        // 这样减法模式仍有非零输出（否则输入-侧向 ≤ 0，波形会恒 0，测不出时序）。
        BlockPos elementSide = elementCell.east();
        BlockPos vanillaSide = vanillaCell.east();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
            PhaseExtras.set(level, elementCell,
                PhaseExtra.withMode(PhaseExtras.get(level, elementCell), true));
            level.setBlockAndUpdate(elementChest, Blocks.CHEST.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.COMPARATOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH)
                .setValue(BlockStateProperties.MODE_COMPARATOR,
                    net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
            level.setBlockAndUpdate(vanillaChest, Blocks.CHEST.defaultBlockState());
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        // 先装满（输入=满容量），再把侧向电源放上（alternate=15），观察减法输出与非零→零的时序。
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            int tick = i;
            if (tick == 2) {
                liquidphase$fillChest(sp, elementChest);
                liquidphase$fillChest(sp, vanillaChest);
            }
            if (tick == 12) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementSide, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaSide, Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.COMPARATOR)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.COMPARATOR) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        List<String> out = liquidphase$diff("comparator subtract", elementWave, vanillaWave);
        boolean anyOn = elementWave.contains(Boolean.TRUE) && vanillaWave.contains(Boolean.TRUE);
        if (!anyOn) {
            out.add("comparator subtract FAIL scene produced no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        return out;
    }

    private static List<String> compareComparatorSustained(ClientGameTestContext context, TestSingleplayerContext sp,
                                                           BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 4);
        BlockPos elementChest = elementCell.north();
        BlockPos vanillaChest = vanillaCell.north();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
            level.setBlockAndUpdate(elementChest, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.COMPARATOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaChest, Blocks.CHEST.defaultBlockState());
        });
        context.waitTicks(2);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            int tick = i;
            // 连续逐格加物品：每次都该按 2 tick 节奏重新评估，而不是一次性拉到满。
            if (tick >= 2 && tick <= 6) {
                int count = (tick - 1) * 13;
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    liquidphase$setChestCount(level, elementChest, count);
                    liquidphase$setChestCount(level, vanillaChest, count);
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.COMPARATOR)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.COMPARATOR) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("comparator incremental", elementWave, vanillaWave);
    }

    // ------------------------------------------------------------------
    // 随机时序模糊（对照原版）
    // ------------------------------------------------------------------

    /**
     * 观察者随机触发：用固定种子的伪随机序列决定"观察面邻居是否有方块"，
     * 每 tick 都可能翻转。逐 tick 比较元件相与原版观察者的 POWERED 波形。
     * <p>
     * 这条是"时序是否有偏差"最直接的检查：任何少排/多排一个刻都会立刻体现为波形错位。
     */
    private static List<String> fuzzObserver(ClientGameTestContext context, TestSingleplayerContext sp,
                                             BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        java.util.Random random = new java.util.Random(20260826L);
        for (int i = 0; i < SAMPLE_TICKS * 2; i++) {
            if (random.nextInt(3) != 0) {
                boolean solid = random.nextBoolean();
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    BlockState state = solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlockAndUpdate(elementCell.north(), state);
                    level.setBlockAndUpdate(vanillaCell.north(), state);
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer fuzz", elementWave, vanillaWave);
    }

    /**
     * 中继器随机输入（1 档与 4 档各一条）：输入电源每 tick 随机开关。
     * 覆盖 {@code checkTickOnNeighbor} 的守卫、{@code willTickThisTick}、补排与优先级。
     */
    private static List<String> fuzzRepeaterInputs(ClientGameTestContext context, TestSingleplayerContext sp,
                                                   BlockPos origin) {
        List<String> out = new ArrayList<>();
        for (int delay : new int[]{1, 4}) {
            BlockPos elementCell = origin.offset(0, 0, (delay == 1 ? 0 : 10));
            BlockPos vanillaCell = elementCell.offset(0, 0, 4);
            BlockPos elementSource = elementCell.north();
            BlockPos vanillaSource = vanillaCell.north();
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                    PhaseExtra.Kind.REPEATER, Direction.NORTH);
                PhaseExtras.set(level, elementCell,
                    PhaseExtra.withDelay(PhaseExtras.get(level, elementCell), delay - 1));
                level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(vanillaCell, Blocks.REPEATER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                    .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, delay));
            });
            context.waitTicks(2);
            List<Boolean> elementWave = new ArrayList<>();
            List<Boolean> vanillaWave = new ArrayList<>();
            java.util.Random random = new java.util.Random(777000L + delay);
            for (int i = 0; i < SAMPLE_TICKS * 2; i++) {
                if (random.nextInt(4) == 0) {
                    boolean solid = random.nextBoolean();
                    sp.getServer().runOnServer(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        BlockState state = solid ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                        level.setBlockAndUpdate(elementSource, state);
                        level.setBlockAndUpdate(vanillaSource, state);
                    });
                }
                elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                    server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.REPEATER)));
                vanillaWave.add(sp.getServer().computeOnServer(server -> {
                    BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaCell);
                    return state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED);
                }));
                context.waitTicks(1);
            }
            out.addAll(liquidphase$diff("repeater fuzz delay=" + delay, elementWave, vanillaWave));
        }
        return out;
    }

    /**
     * 观察者被"红石线"随机驱动：实验组 = 观察者相观察**红石相宿主**（由旁边的红石块供电），
     * 对照组 = 原版观察者观察**原版红石线**（同一位置放红石块）。
     * 两边的电源每 tick 随机开关，逐 tick 比较观察者的 POWERED 波形。
     * <p>
     * 注意不能直接改表里的功率：红石相和原版红石线一样会立即按邻居重算（这是正确行为），
     * 所以必须用真实电源来驱动。
     */
    private static List<String> fuzzObserverByWire(ClientGameTestContext context, TestSingleplayerContext sp,
                                                   BlockPos origin) {
        BlockPos elementObserver = origin;
        BlockPos elementWire = elementObserver.north();
        BlockPos elementSource = elementWire.west();
        BlockPos vanillaObserver = origin.offset(0, 0, 6);
        BlockPos vanillaWire = vanillaObserver.north();
        BlockPos vanillaSource = vanillaWire.west();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementObserver, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementObserver, level.getBlockState(elementObserver),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementWire, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            PhaseExtras.set(level, elementWire, PhaseExtra.redstone(0));

            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState());
        });
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        java.util.Random random = new java.util.Random(424242L);
        for (int i = 0; i < SAMPLE_TICKS * 2; i++) {
            if (random.nextInt(3) == 0) {
                boolean powered = random.nextBoolean();
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    BlockState state = powered ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
                    level.setBlockAndUpdate(elementSource, state);
                    level.setBlockAndUpdate(vanillaSource, state);
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementObserver, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> {
                BlockState state = server.getLevel(Level.OVERWORLD).getBlockState(vanillaObserver);
                return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
            }));
            context.waitTicks(1);
        }
        return liquidphase$diff("observer fuzz by wire", elementWave, vanillaWave);
    }

    private static void liquidphase$setChestCount(ServerLevel level, BlockPos pos, int count) {
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
            chest.setItem(0, count <= 0 ? net.minecraft.world.item.ItemStack.EMPTY
                : new net.minecraft.world.item.ItemStack(Blocks.STONE, count));
            chest.setChanged();
        }
        level.updateNeighbourForOutputSignal(pos, Blocks.CHEST);
    }

    private static void liquidphase$fillChest(TestSingleplayerContext sp, BlockPos pos) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chest.setItem(0, new net.minecraft.world.item.ItemStack(Blocks.STONE, 64));
                chest.setChanged();
            }
            level.updateNeighbourForOutputSignal(pos, Blocks.CHEST);
        });
    }

    private static void liquidphase$emptyChest(TestSingleplayerContext sp, BlockPos pos) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chest.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
                chest.setChanged();
            }
            level.updateNeighbourForOutputSignal(pos, Blocks.CHEST);
        });
    }

    // ------------------------------------------------------------------

    /**
     * 断言对照格**仍然是原版元件方块**（不是被"元件方块作相材料格"吸收掉的宿主）。
     * <p>
     * 0.2.8 的教训：对照格先垫了下半砖、再 {@code setBlock} 真实元件方块时，
     * 改写层会把那次写入吸收成"下半砖 + 旁路条目"，于是 {@code state.is(OBSERVER)} 恒 false、
     * 原版侧波形整条恒 0——而采样比较仍然"通过"（或只在个别场景失败），看起来像产品 bug。
     * 现在每个元件对照场景设置完都先核对一次，把这类静默退化直接报成 FAIL。
     *
     * @return 失败描述（空 = 对照组确实还是原版方块）
     */
    private static List<String> liquidphase$expectVanilla(TestSingleplayerContext sp, String label,
                                                         Map<BlockPos, Block> expected) {
        List<String> out = new ArrayList<>();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (Map.Entry<BlockPos, Block> entry : expected.entrySet()) {
                BlockState state = level.getBlockState(entry.getKey());
                if (!state.is(entry.getValue())) {
                    out.add(label + " FAIL vanilla control " + entry.getKey() + " is " + state
                        + " instead of " + entry.getValue().defaultBlockState().getBlock()
                        + " (the element block was absorbed into a phase host, so the comparison is dead)");
                }
            }
        });
        return out;
    }

    // ------------------------------------------------------------------

    private static boolean liquidphase$powered(ServerLevel level, BlockPos pos, PhaseExtra.Kind kind) {
        int extra = PhaseExtras.get(level, pos);
        return PhaseExtra.kind(extra) == kind && PhaseExtra.powered(extra);
    }

    /** 逐 tick 比较两条波形，返回可读的差异描述（空 = 一致）。 */
    private static List<String> liquidphase$diff(String label, List<Boolean> element, List<Boolean> vanilla) {
        List<String> out = new ArrayList<>();
        if (element.size() != vanilla.size()) {
            out.add(label + " FAIL sample count differs (" + element.size() + " vs " + vanilla.size() + ")");
            return out;
        }
        StringBuilder elementWave = new StringBuilder();
        StringBuilder vanillaWave = new StringBuilder();
        boolean differs = false;
        for (int i = 0; i < element.size(); i++) {
            elementWave.append(element.get(i) ? '1' : '0');
            vanillaWave.append(vanilla.get(i) ? '1' : '0');
            if (!element.get(i).equals(vanilla.get(i))) {
                differs = true;
            }
        }
        LiquidPhase.LOGGER.info("[gametest][timing] {} element={} vanilla={}", label, elementWave, vanillaWave);
        if (differs) {
            out.add(label + " FAIL waveform differs (element=" + elementWave + " vanilla=" + vanillaWave + ")");
        }
        return out;
    }

    private static String liquidphase$wave(List<Boolean> wave) {
        StringBuilder builder = new StringBuilder(wave.size());
        for (boolean value : wave) {
            builder.append(value ? '1' : '0');
        }
        return builder.toString();
    }
}
//?}
