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
            failures.addAll(compareObserverVertical(context, sp, feet.offset(-30, 0, 6)));
            failures.addAll(compareObserverDrivesLamp(context, sp, feet.offset(0, 0, 45)));
            failures.addAll(compareObserverDrivesWire(context, sp, feet.offset(10, 0, 45)));
            failures.addAll(compareObserverHeldDuringPulse(context, sp, feet.offset(20, 0, 45)));
            failures.addAll(compareObserverFeedsVanillaRepeater(context, sp, feet.offset(30, 0, 45)));
            failures.addAll(compareElementRepeaterFeedsVanillaObserver(context, sp, feet.offset(-30, 0, 45)));
            failures.addAll(compareObserverSeesVanillaWire(context, sp, feet.offset(-30, 0, 60)));
            failures.addAll(compareObserverWirePower(context, sp, feet.offset(-16, 0, 45)));
            failures.addAll(compareObserverWithHostTick(context, sp, feet.offset(-16, 0, 60)));
            failures.addAll(compareObserverAllFacings(context, sp, feet.offset(24, 0, 60)));
            failures.addAll(compareObserverMixedChain(context, sp, feet.offset(-8, 0, 75)));
            failures.addAll(compareObserverNotPulsedByComparatorValue(context, sp, feet.offset(12, 0, 75)));
            failures.addAll(compareObserverPulsedByRepeaterLock(context, sp, feet.offset(28, 0, 75)));
            failures.addAll(compareObserverPulseTrains(context, sp, feet.offset(44, 0, 75)));
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
            failures.addAll(fuzzRepeaterInputs(context, sp, feet.offset(36, 0, 19)));
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
        // 装配过程本身会让观察者看到邻居变化而先脉冲一次：等它完全静下来再开始采样。
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                // ⚠️ 必须用**真实电源**驱动：直接写表功率 / 直接 setBlock(POWER=15) 都不是
                // 可物理到达的状态（原版写线格功率会被 onPlace/邻居重算改写）。红石块放在
                // 线的外侧，两边由各自的红石线规则算出 15。
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
        List<String> out = liquidphase$diff("wire-power->observer", elementWave, vanillaWave);
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            out.add("wire-power->observer FAIL no pulse at all (element=" + liquidphase$wave(elementWave)
                + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        return out;
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
     * 观察者竖直朝向（观察面 = UP）：元件相放在下半砖上、原版观察者放在完整石头上，
     * 观察的都是**正上方那一格**。覆盖 {@code isTargeted}／{@code updateNeighborsInFront}
     * 在非水平轴上的方向换算（{@code input.getOpposite()} 对 UP 是 DOWN）。
     */
    private static List<String> compareObserverVertical(ClientGameTestContext context, TestSingleplayerContext sp,
                                                        BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos elementTrigger = elementCell.above();
        BlockPos vanillaTrigger = vanillaCell.above();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.UP);
            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.UP));
        });
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementTrigger, Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaTrigger, Blocks.STONE.defaultBlockState());
                });
            }
            if (i == 20) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementTrigger, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaTrigger, Blocks.AIR.defaultBlockState());
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
        return liquidphase$diff("observer vertical (UP)", elementWave, vanillaWave);
    }

    /**
     * 观察者驱动**原版红石灯**：把"下游消费者实际看到的信号时刻"也纳入对照。
     * <p>
     * 灯是最敏感的探针：原版 {@code RedstoneLampBlock} 收到信号立即点亮，信号消失后
     * 还要排 4 tick 才熄灭。观察者相的脉冲起点/宽度只要有 1 tick 偏差，灯的 LIT 波形
     * 就会错位（或者灯根本不亮）。
     */
    private static List<String> compareObserverDrivesLamp(ClientGameTestContext context, TestSingleplayerContext sp,
                                                         BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementLamp = elementCell.south();
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos vanillaLamp = vanillaCell.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementLamp, Blocks.REDSTONE_LAMP.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaLamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        });
        context.waitTicks(10);
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
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), elementLamp)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), vanillaLamp)));
            context.waitTicks(1);
        }
        List<String> out = liquidphase$diff("observer->lamp", elementWave, vanillaWave);
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            out.add("observer->lamp FAIL lamp never lit (element=" + liquidphase$wave(elementWave)
                + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        return out;
    }

    /**
     * 观察者 → **原版红石线** → 原版红石灯。这条同时检验：
     * <ul>
     *   <li>线的连接判定（观察者输出侧的线必须真的连上元件宿主）；</li>
     *   <li>线功率随观察者脉冲的建立/消失时刻；</li>
     *   <li>线的形状更新是否把功率变化转成下游灯的输入。</li>
     * </ul>
     */
    private static List<String> compareObserverDrivesWire(ClientGameTestContext context, TestSingleplayerContext sp,
                                                          BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementWire = elementCell.south();
        BlockPos elementLamp = elementWire.south();
        BlockPos vanillaCell = origin.offset(0, 0, 8);
        BlockPos vanillaWire = vanillaCell.south();
        BlockPos vanillaLamp = vanillaWire.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementCell, elementWire, vanillaCell, vanillaWire}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementWire, Blocks.REDSTONE_WIRE.defaultBlockState());
            level.setBlockAndUpdate(elementLamp, Blocks.REDSTONE_LAMP.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState());
            level.setBlockAndUpdate(vanillaLamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer via wire",
            Map.of(vanillaCell, Blocks.OBSERVER, vanillaWire, Blocks.REDSTONE_WIRE));
        context.waitTicks(10);
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
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), elementLamp)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), vanillaLamp)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer via wire FAIL lamp never lit (element=" + liquidphase$wave(elementWave)
                + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer via wire", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者脉冲**期间**观察面再次变化：原版 {@code updateShape} 的 {@code !POWERED} 守卫
     * 会挡掉这次变化，不该产生第二个脉冲。这里在 powered 窗口内连续翻转观察面邻居，
     * 逐 tick 比较两边是否都只脉冲一次。
     */
    private static List<String> compareObserverHeldDuringPulse(ClientGameTestContext context,
                                                               TestSingleplayerContext sp, BlockPos origin) {
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
            // i=2 起振；i=4..7 正好落在脉冲期（起振后 2 tick、宽 2 tick）里反复翻转观察面。
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.STONE.defaultBlockState());
                });
            }
            if (i >= 4 && i <= 8) {
                boolean solid = (i & 1) == 0;
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
        List<String> out = liquidphase$diff("observer held during pulse", elementWave, vanillaWave);
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            out.add("observer held during pulse FAIL no pulse at all (element=" + liquidphase$wave(elementWave)
                + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        return out;
    }

    /**
     * 观察者相 → **原版中继器** → 原版红石灯。
     * <p>
     * 覆盖"元件相的输出被原版二极管读取"这条真实玩法里最常见的混用路径：原版
     * {@code DiodeBlock.getInputSignal} 会经 {@code level.getSignal} 问元件宿主，只要
     * 方向语义/门控/通知有一处偏 1 tick，中继器的 POWERED 与灯的 LIT 都会错位。
     * <p>
     * 几何：中继器 {@code FACING=NORTH}（输入面朝北）紧贴观察者的**输出面**（观察者
     * {@code FACING=NORTH}，输出面 = SOUTH）。
     */
    private static List<String> compareObserverFeedsVanillaRepeater(ClientGameTestContext context,
                                                                    TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementRepeater = elementCell.south();
        BlockPos elementLamp = elementRepeater.south();
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos vanillaRepeater = vanillaCell.south();
        BlockPos vanillaLamp = vanillaRepeater.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementRepeater.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
            level.setBlockAndUpdate(elementLamp, Blocks.REDSTONE_LAMP.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaRepeater.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
            level.setBlockAndUpdate(vanillaLamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer->vanilla repeater",
            Map.of(vanillaCell, Blocks.OBSERVER, vanillaRepeater, Blocks.REPEATER));
        context.waitTicks(10);
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
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), elementLamp)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$lit(
                server.getLevel(Level.OVERWORLD), vanillaLamp)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer->vanilla repeater FAIL lamp never lit (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer->vanilla repeater", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 中继器相 → **原版观察者**：观察面朝北看着元件相中继器的输出面。
     * <p>
     * 覆盖反向组合：原版 {@code ObserverBlock.updateShape} 是"观察面邻居变化"的唯一入口，
     * 而元件相中继器只写旁路表。若 {@code PhaseNotifications} 少发一次形状更新，原版观察者
     * 就收不到这次变化（波形恒 0）。
     */
    private static List<String> compareElementRepeaterFeedsVanillaObserver(ClientGameTestContext context,
                                                                          TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementSource = elementCell.north();
        BlockPos elementObserver = elementCell.south();
        BlockPos vanillaCell = origin.offset(0, 0, 6);
        BlockPos vanillaSource = vanillaCell.north();
        BlockPos vanillaObserver = vanillaCell.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);
            // 探针用**原版观察者**（直接读 POWERED 状态），两侧同构；观察面朝北看着中继器。
            level.setBlockAndUpdate(elementObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));

            level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaCell, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        List<String> problems = liquidphase$expectVanilla(sp, "vanilla repeater->observer",
            Map.of(vanillaCell, Blocks.REPEATER, vanillaObserver, Blocks.OBSERVER,
                elementObserver, Blocks.OBSERVER));
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
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), elementObserver)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaObserver)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("vanilla repeater->observer FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("vanilla repeater->observer", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者相观察**真实红石线**（不是红石相）：排除"红石相 vs 原版线"这个额外变量，
     * 只对照"观察者自己是相还是方块"。线的功率由真实红石块驱动。
     */
    private static List<String> compareObserverSeesVanillaWire(ClientGameTestContext context,
                                                               TestSingleplayerContext sp, BlockPos origin) {
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
            // ⚠️ 线必须放在**完整石头**上，不能放在下半砖上：把红石线写进相容宿主格会被
            // "红石线 → 红石相"的改写层吸收成"宿主 + 条目"，对照组就不再是原版红石线了。
            // 两边的线都放石头：唯一变量是"观察者是相还是方块"。
            level.setBlockAndUpdate(elementWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementWire, Blocks.REDSTONE_WIRE.defaultBlockState());

            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState());
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer sees vanilla wire",
            Map.of(vanillaObserver, Blocks.OBSERVER, vanillaWire, Blocks.REDSTONE_WIRE));
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
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaObserver)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer sees vanilla wire FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer sees vanilla wire", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 观察者相 → **真实红石线**，逐 tick 采样线的 POWER。
     * <p>
     * 比"看灯"更精确：红石灯收到信号立即亮、信号消失后还要 4 tick 才灭，脉冲宽度差 1 tick
     * 会被那 4 tick 掩盖；线的功率是逐 tick 跟随观察者输出的，任何相位/宽度偏差都直接可见。
     */
    private static List<String> compareObserverWirePower(ClientGameTestContext context,
                                                        TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementCell = origin;
        BlockPos elementWire = elementCell.south();
        BlockPos vanillaCell = origin.offset(0, 0, 8);
        BlockPos vanillaWire = vanillaCell.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            for (BlockPos cell : new BlockPos[]{elementCell, elementWire, vanillaCell, vanillaWire}) {
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            // 线放完整石头（放下半砖会被吸收成红石相，见 compareObserverSeesVanillaWire 的注释）。
            level.setBlockAndUpdate(elementWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementWire, Blocks.REDSTONE_WIRE.defaultBlockState());

            level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaWire.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaWire, Blocks.REDSTONE_WIRE.defaultBlockState());
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer wire power",
            Map.of(vanillaCell, Blocks.OBSERVER, vanillaWire, Blocks.REDSTONE_WIRE));
        context.waitTicks(10);
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
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$wirePowered(
                server.getLevel(Level.OVERWORLD), elementWire)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$wirePowered(
                server.getLevel(Level.OVERWORLD), vanillaWire)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer wire power FAIL wire never powered (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer wire power", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 侦测器相 + **宿主自己的计划刻**：审计根因 B 的直接回归。
     * <p>
     * 元件相的计划刻 tick key 必须是原版元件方块（OBSERVER），不能与宿主的
     * {@code (pos, hostBlock)} 槽位共用。这里主动给宿主排一个刻来模拟"宿主自己有排刻"
     * （树叶/脚手架/珊瑚/垂滴叶/探测铁轨那一类），两个电路逐 tick 对照：
     * 若元件刻被宿主刻顶掉（或反过来），侦测器脉冲的起点/宽度就会立刻错位。
     * <p>
     * 用下半砖当宿主、手动排刻，而不是真的选一个会自己排刻的宿主：后者（珊瑚会死、
     * 垂滴叶会倾斜）会给测试引入额外的不确定性，而这里要钉住的只是"槽位不共享"。
     */
    private static List<String> compareObserverWithHostTick(ClientGameTestContext context,
                                                            TestSingleplayerContext sp, BlockPos origin) {
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
                    // 宿主自己的计划刻：占住 (pos, hostBlock) 槽位。
                    level.scheduleTick(elementCell, Blocks.SMOOTH_STONE_SLAB, 2);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.STONE.defaultBlockState());
                });
            }
            if (i == 20) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.scheduleTick(elementCell, Blocks.SMOOTH_STONE_SLAB, 4);
                    level.setBlockAndUpdate(elementCell.north(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(vanillaCell.north(), Blocks.AIR.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaCell)));
            context.waitTicks(1);
        }
        List<String> out = liquidphase$diff("observer with host tick", elementWave, vanillaWave);
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            out.add("observer with host tick FAIL no pulse at all (element=" + liquidphase$wave(elementWave)
                + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        return out;
    }

    /**
     * 侦测器相 × **六个朝向**：同一条"观察面邻居翻转→脉冲→复位"序列在每个朝向下都跑一遍。
     * <p>
     * 元件相的方向参数是压进 int 的一个 ordinal，观察面/输出面的换算（{@code facing} /
     * {@code facing.getOpposite()}）如果在某个轴上写错，只会体现在那一个朝向上——单测 NORTH
     * 是抓不到的。这里把 6 个朝向全部逐 tick 对照。
     */
    private static List<String> compareObserverAllFacings(ClientGameTestContext context,
                                                          TestSingleplayerContext sp, BlockPos origin) {
        List<String> out = new ArrayList<>();
        // DOWN 不在此列：原版侦测器朝下时观察的正是**自己脚下的支撑方块**，而
        // {@code ObserverBlock.canSurvive} 要求那一格必须能承载它（RIGID），所以原版的
        // 朝下侦测器在物理上永远观测不到"变成空气"，也就永远无法起振。元件相朝下时观察的是
        // 宿主底面的下方那一格（不需要存在），因此**能**触发——这是"元件贴在宿主表面"的
        // 表示法差异，不属于 I/O 时序，不在此对照（见 README 已知限制）。
        Direction[] facings = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.UP};
        for (int index = 0; index < facings.length; index++) {
            Direction facing = facings[index];
            // 每条用例占 origin .. origin+(0,0,8)，实际只用到 0..3 格。
            BlockPos elementCell = origin.offset(0, 0, index * 8);
            BlockPos vanillaCell = elementCell.offset(0, 0, 4);
            BlockPos elementTrigger = elementCell.relative(facing);
            BlockPos vanillaTrigger = vanillaCell.relative(facing);
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                    PhaseExtra.Kind.OBSERVER, facing);
                level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, facing));
            });
            List<String> setup = liquidphase$expectVanilla(sp, "observer facing=" + facing,
                Map.of(vanillaCell, Blocks.OBSERVER));
            context.waitTicks(10);
            List<Boolean> elementWave = new ArrayList<>();
            List<Boolean> vanillaWave = new ArrayList<>();
            for (int i = 0; i < SAMPLE_TICKS; i++) {
                if (i == 2) {
                    sp.getServer().runOnServer(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        level.setBlockAndUpdate(elementTrigger, Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(vanillaTrigger, Blocks.STONE.defaultBlockState());
                    });
                }
                if (i == 22) {
                    sp.getServer().runOnServer(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        level.setBlockAndUpdate(elementTrigger, Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(vanillaTrigger, Blocks.AIR.defaultBlockState());
                    });
                }
                elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                    server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
                vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                    server.getLevel(Level.OVERWORLD), vanillaCell)));
                context.waitTicks(1);
            }
            List<String> problems = liquidphase$diff("observer facing=" + facing, elementWave, vanillaWave);
            problems.addAll(setup);
            if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
                problems.add("observer facing=" + facing + " FAIL no pulse at all (element="
                    + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
            }
            out.addAll(problems);
        }
        return out;
    }

    /**
     * 侦测器相 **A** → 原版观察者 **B**（B 观察 A 的输出面），以及反向：
     * 原版侦测器 A → 侦测器相 B。把"相"和"方块"在链的两端各放一次，
     * 排除"只有同类才能对上"这种假通过。
     */
    private static List<String> compareObserverMixedChain(ClientGameTestContext context,
                                                          TestSingleplayerContext sp, BlockPos origin) {
        List<String> out = new ArrayList<>();

        // ① A = 元件相侦测器（观察面 NORTH），B = 原版观察者（观察面 NORTH，在 A 南侧看 A 的输出面）。
        BlockPos elementA = origin;
        BlockPos elementB = elementA.south();
        BlockPos vanillaA = origin.offset(0, 0, 6);
        BlockPos vanillaB = vanillaA.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementA.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementA, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementA, level.getBlockState(elementA),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
            level.setBlockAndUpdate(elementB, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));

            level.setBlockAndUpdate(vanillaA.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaA, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaB, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        List<String> problems = liquidphase$expectVanilla(sp, "observer mixed chain",
            Map.of(elementB, Blocks.OBSERVER, vanillaA, Blocks.OBSERVER, vanillaB, Blocks.OBSERVER));
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(elementA.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vanillaA.north(), Blocks.STONE.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), elementB)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaB)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer mixed chain FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer mixed chain", elementWave, vanillaWave));
        out.addAll(problems);

        // ② A = 原版侦测器（观察面 NORTH），B = 元件相侦测器（观察面 NORTH，在 A 南侧）。
        BlockPos vanillaC = origin.offset(0, 0, 12);
        BlockPos vanillaD = vanillaC.south();
        BlockPos elementC = origin.offset(0, 0, 18);
        BlockPos elementD = elementC.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(vanillaC.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaC, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaD, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));

            level.setBlockAndUpdate(elementC.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementC, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(elementD, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementD, level.getBlockState(elementD),
                PhaseExtra.Kind.OBSERVER, Direction.NORTH);
        });
        problems = liquidphase$expectVanilla(sp, "observer mixed chain reverse",
            Map.of(vanillaC, Blocks.OBSERVER, vanillaD, Blocks.OBSERVER, elementC, Blocks.OBSERVER));
        context.waitTicks(10);
        elementWave = new ArrayList<>();
        vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    level.setBlockAndUpdate(vanillaC.north(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(elementC.north(), Blocks.STONE.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                server.getLevel(Level.OVERWORLD), elementD, PhaseExtra.Kind.OBSERVER)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaD)));
            context.waitTicks(1);
        }
        if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
            problems.add("observer mixed chain reverse FAIL no pulse at all (element="
                + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
        }
        problems.addAll(liquidphase$diff("observer mixed chain reverse", elementWave, vanillaWave));
        out.addAll(problems);
        return out;
    }

    /**
     * 通知通道的**次数**口径：比较器相的模拟量单独变化**不应**触发相邻观察者。
     * <p>
     * 原版 {@code ComparatorBlock.refreshOutputState} 只在 {@code POWERED} 翻转时
     * {@code setBlock(..., 2)}；方块实体里的 output signal 变化不经过 {@code Level.setBlock}，
     * 所以相邻观察者**收不到**形状更新。若我们的表写入无条件发形状更新，观察者会对
     * "比较器数值变化"多脉冲一次——这比原版多，属于真实缺陷（审计 §13.0 第 2 条）。
     * <p>
     * 场景：比较器相（比较模式，输入恒为满容器的 15）驱动相邻观察者；随后只让容器
     * 容量小幅变化（15 → 14 → 13…），POWERED 仍是 true，只有 power 在变。
     */
    private static List<String> compareObserverNotPulsedByComparatorValue(ClientGameTestContext context,
                                                                         TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementComparator = origin;
        BlockPos elementChest = elementComparator.north();
        BlockPos elementObserver = elementComparator.south();
        BlockPos vanillaComparator = origin.offset(0, 0, 6);
        BlockPos vanillaChest = vanillaComparator.north();
        BlockPos vanillaObserver = vanillaComparator.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementComparator.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementComparator, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementComparator, level.getBlockState(elementComparator),
                PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
            level.setBlockAndUpdate(elementChest, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(elementObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));

            level.setBlockAndUpdate(vanillaComparator.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaComparator, Blocks.COMPARATOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(vanillaChest, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        List<String> problems = liquidphase$expectVanilla(sp, "comparator value->observer",
            Map.of(vanillaComparator, Blocks.COMPARATOR, vanillaObserver, Blocks.OBSERVER,
                elementObserver, Blocks.OBSERVER));
        // 先装满（POWERED=true），等观察者把"比较器从 0 变满"那一次脉冲走完，再开始采样。
        liquidphase$fillChest(sp, elementChest);
        liquidphase$fillChest(sp, vanillaChest);
        context.waitTicks(20);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            // 只改容量、不改 POWERED：比较器持续供电，观察者不应再脉冲。
            if (i >= 2 && i <= 6) {
                int count = 64 - (i - 1);
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    liquidphase$setChestCount(level, elementChest, count);
                    liquidphase$setChestCount(level, vanillaChest, count);
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), elementObserver)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaObserver)));
            context.waitTicks(1);
        }
        problems.addAll(liquidphase$diff("comparator value->observer", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 通知通道的**次数**口径：中继器相 {@code locked} 翻转**应**触发相邻观察者。
     * <p>
     * 原版 {@code LOCKED} 是一个状态位，{@code RepeaterBlock.updateShape} 写它之后经
     * {@code Block.updateOrDestroy → setBlock} 发形状更新，相邻观察者会收到。
     * 若我们把 {@code locked} 变化当成"不通知"，这里就会变成恒 0。
     */
    private static List<String> compareObserverPulsedByRepeaterLock(ClientGameTestContext context,
                                                                    TestSingleplayerContext sp, BlockPos origin) {
        BlockPos elementRepeater = origin;
        BlockPos elementLock = elementRepeater.east();
        BlockPos elementObserver = elementRepeater.south();
        BlockPos vanillaRepeater = origin.offset(0, 0, 6);
        BlockPos vanillaLock = vanillaRepeater.east();
        BlockPos vanillaObserver = vanillaRepeater.south();
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(elementRepeater.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementRepeater, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, elementRepeater, level.getBlockState(elementRepeater),
                PhaseExtra.Kind.REPEATER, Direction.NORTH);
            level.setBlockAndUpdate(elementLock.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementLock, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 3));
            level.setBlockAndUpdate(elementObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elementObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));

            level.setBlockAndUpdate(vanillaRepeater.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 1));
            level.setBlockAndUpdate(vanillaLock, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 3));
            level.setBlockAndUpdate(vanillaObserver.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(vanillaObserver, Blocks.OBSERVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
        });
        // 中继器相在装配时就按原版 getStateForPlacement 算了 locked；这里刻意把侧向二极管的
        // 电源在采样中放上/撤下，让 locked 翻转落在可控时刻。
        List<String> problems = liquidphase$expectVanilla(sp, "repeater lock->observer",
            Map.of(vanillaRepeater, Blocks.REPEATER, vanillaLock, Blocks.REPEATER,
                vanillaObserver, Blocks.OBSERVER, elementObserver, Blocks.OBSERVER));
        context.waitTicks(10);
        List<Boolean> elementWave = new ArrayList<>();
        List<Boolean> vanillaWave = new ArrayList<>();
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            if (i == 2) {
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    // 侧向二极管的电源朝东（它的输入面），让两只二极管真正进入"有侧向输入"。
                    level.setBlockAndUpdate(elementLock.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(vanillaLock.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                });
            }
            elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), elementObserver)));
            vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                server.getLevel(Level.OVERWORLD), vanillaObserver)));
            context.waitTicks(1);
        }
        problems.addAll(liquidphase$diff("repeater lock->observer", elementWave, vanillaWave));
        return problems;
    }

    /**
     * 侦测器相 × **不同周期的 1 tick 脉冲**：脉冲宽度 1 tick、周期分别取 2/3/4/5 tick。
     * <p>
     * 这是"侦测器时钟/延时器漏脉冲"的经典暴露方式：升沿排 2 tick、降沿也在 2 tick 后，
     * 只要排刻/复位与触发相位之间差 1 tick，就会在某些周期下变成"两拍并一拍"或"丢一个脉冲"。
     * 原版参考侧是同一几何下的真侦测器。
     */
    private static List<String> compareObserverPulseTrains(ClientGameTestContext context,
                                                           TestSingleplayerContext sp, BlockPos origin) {
        List<String> out = new ArrayList<>();
        int[] periods = {2, 3, 4, 5};
        for (int index = 0; index < periods.length; index++) {
            int period = periods[index];
            BlockPos elementCell = origin.offset(0, 0, index * 8);
            BlockPos vanillaCell = elementCell.offset(0, 0, 4);
            BlockPos elementTrigger = elementCell.north();
            BlockPos vanillaTrigger = vanillaCell.north();
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                level.setBlockAndUpdate(elementCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(elementCell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                ElementBlocks.place(level, elementCell, level.getBlockState(elementCell),
                    PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                level.setBlockAndUpdate(vanillaCell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(vanillaCell, Blocks.OBSERVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.NORTH));
                // ⚠️ 显式把触发格清成空气：不这样的话，触发格的环境初值（地形残留）会让
                // 第一次"写石头"变成 no-op（调用原版 setBlockAndUpdate 对同一状态直接返回），
                // 观察者根本不起振，波形恒 0。这条踩过：p=5 第一次运行全 0、重跑才通过。
                level.setBlockAndUpdate(elementTrigger, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(vanillaTrigger, Blocks.AIR.defaultBlockState());
            });
            if (ElementBlocks.DEBUG) {
                LiquidPhase.LOGGER.info("[gametest][debug] p={} element={} trigger={} vanilla={} vtrigger={}",
                    period, elementCell, elementTrigger, vanillaCell, vanillaTrigger);
            }
            List<String> problems = liquidphase$expectVanilla(sp, "observer pulse train p=" + period,
                Map.of(vanillaCell, Blocks.OBSERVER));
            context.waitTicks(10);
            List<Boolean> elementWave = new ArrayList<>();
            List<Boolean> vanillaWave = new ArrayList<>();
            for (int i = 0; i < SAMPLE_TICKS * 2; i++) {
                boolean high = i >= 2 && (i - 2) % period == 0;
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    BlockState state = high ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlockAndUpdate(elementTrigger, state);
                    level.setBlockAndUpdate(vanillaTrigger, state);
                });
                elementWave.add(sp.getServer().computeOnServer(server -> liquidphase$powered(
                    server.getLevel(Level.OVERWORLD), elementCell, PhaseExtra.Kind.OBSERVER)));
                vanillaWave.add(sp.getServer().computeOnServer(server -> liquidphase$observerPowered(
                    server.getLevel(Level.OVERWORLD), vanillaCell)));
                context.waitTicks(1);
            }
            problems.addAll(liquidphase$diff("observer pulse train p=" + period, elementWave, vanillaWave));
            if (!elementWave.contains(Boolean.TRUE) || !vanillaWave.contains(Boolean.TRUE)) {
                problems.add("observer pulse train p=" + period + " FAIL no pulse at all (element="
                    + liquidphase$wave(elementWave) + " vanilla=" + liquidphase$wave(vanillaWave) + ")");
            }
            out.addAll(problems);
        }
        return out;
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

    /** 原版红石灯的 LIT（作为"下游消费者看到信号"的探针）。 */
    private static boolean liquidphase$lit(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_LAMP) && state.getValue(BlockStateProperties.LIT);
    }

    /** 原版红石线的 POWER != 0（逐 tick 跟随观察者输出的精确探针）。 */
    private static boolean liquidphase$wirePowered(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) && state.getValue(BlockStateProperties.POWER) != 0;
    }

    /** 原版观察者方块的 POWERED（用作"读取元件相输出"的原版探针）。 */
    private static boolean liquidphase$observerPowered(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.OBSERVER) && state.getValue(BlockStateProperties.POWERED);
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
