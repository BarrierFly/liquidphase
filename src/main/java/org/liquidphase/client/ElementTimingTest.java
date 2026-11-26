//? if 1.21.11 {
package org.liquidphase.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import org.liquidphase.LiquidPhase;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.mixin.gametest.ServerLevelTickBlockInvoker;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 元件时序对照回归（0.2.8）：把"元件相"和"同一元件作为原版独立方块"放进两个几何对应的电路里
 * **逐 tick 采样**，要求两者的输出波形完全一致（允许 0 tick 偏差；任何偏差即 FAIL）。
 * <p>
 * 原版独立方块直接放在下半砖上不行（下半砖顶面不是 RIGID 支撑），所以对照组放**完整石头**，
 * 实验组放**下半砖**；两者的信号场景（输入电源距离、输出引线长度、观察面邻居）逐一对应。
 * 比较的量是"元件自身/下游的真实状态变化时刻"，排除"元件贴在哪块宿主上"的几何差异。
 * <p>
 * <b>性能</b>：所有场景**一次性搭好**，由一个 {@code END_SERVER_TICK} 监听器在**服务端 tick 内**
 * 统一驱动与采样，每个 tick 只与服务器往返一次（而不是每个场景每 tick 各来一次）。
 * 整个套件约 90 tick（≈1.5 秒游戏时间），不再逐用例 waitTicks。
 * <p>
 * 覆盖：观察者（单脉冲、接力、反馈时钟、观察中继器/比较器/红石线/红石相、五个朝向、随机模糊、
 * 周期 2/3/4/5 的 1-tick 脉冲列车、脉冲期内再触发、宿主自己排刻、驱动原版灯/线/中继器、
 * 元件→原版观察者、原版中继器→原版观察者、比较器数值变化不触发观察者、中继器 locked 翻转让观察者脉冲）、
 * 中继器（1/4 档、短脉冲、双段链、随机输入模糊）、比较器（比较/减法、连续增量）。
 */
public final class ElementTimingTest implements FabricClientGameTest {

    /** 采样 tick 数。所有场景共享同一条时间轴；96 tick ≈ 4.8 秒游戏时间。 */
    private static final int TICKS = 60;

    /** 每个场景占用的场地边长（含触发格与上下/左右探针）。 */
    private static final int PLOT = 10;

    /** 每行最多排几个场景。 */
    private static final int ROW = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null);
            BlockPos feet = context.computeOnClient(client -> client.player.blockPosition());
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("gamerule randomTickSpeed 0");
            sp.getServer().runCommand("gamerule doFireTick false");

            List<Scene> scenes = buildAll(feet);
            // 一次性搭场：所有方块都在同一 tick 内写完，避免用例之间互相看到对方的中间态。
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                for (Scene scene : scenes) {
                    scene.build(level);
                }
            });
            // 装配余震（观察者在装配时收到邻居变化）需要先静默；10 tick 覆盖最长脉冲周期。
            context.waitTicks(10);
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                for (Scene scene : scenes) {
                    scene.reset(level);
                }
            });
            context.waitTicks(2);

            // 🚀 采样：整条时间轴在**一次**服务端调用里跑完，不再逐 tick 与客户端往返
            // （那会把 60 tick 变成 60 次往返，每次 10ms 量级）。这里直接推进本维度的方块刻队列，
            // 等价于 ServerLevel.tick 里的 `blockTicks.tick(gameTime, 65536, this::tickBlock)`。
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                for (int tick = 0; tick < TICKS; tick++) {
                    for (Scene scene : scenes) {
                        scene.drive(level, tick);
                    }
                    for (Scene scene : scenes) {
                        scene.sample(level);
                    }
                    // 推进游戏时间：LevelTicks 只执行 triggerTick <= gameTime 的刻，
                    // 而 getGameTime() 只在 ServerLevel.tickTime() 里自增（本循环绕过了它）。
                    long now = level.getGameTime();
                    if (level.getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData data) {
                        data.setGameTime(now + 1L);
                    }
                    level.getBlockTicks().tick(now + 1L, 65536, (pos, block) ->
                        ((ServerLevelTickBlockInvoker) level).liquidphase$tickBlock(pos, block));
                }
            });

            List<String> failures = new ArrayList<>();
            for (Scene scene : scenes) {
                failures.addAll(scene.verify());
            }
            for (String failure : failures) {
                LiquidPhase.LOGGER.error("[gametest][timing] {}", failure);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("element timing mismatch:\n  " + String.join("\n  ", failures));
            }
            LiquidPhase.LOGGER.info("[gametest][timing] {} scenes / {} waveforms match vanilla element blocks",
                scenes.size(), scenes.stream().mapToInt(s -> s.waveforms).sum());
        }
    }

    // ------------------------------------------------------------------
    // 场景构建
    // ------------------------------------------------------------------

    private static List<Scene> buildAll(BlockPos feet) {
        List<Scene> scenes = new ArrayList<>();
        int index = 0;
        index = add(scenes, index, feet, Scene::observerBasic);
        index = add(scenes, index, feet, Scene::observerRelay);
        index = add(scenes, index, feet, Scene::observerClock);
        index = add(scenes, index, feet, Scene::observerSeesRepeater);
        index = add(scenes, index, feet, Scene::observerSeesComparator);
        index = add(scenes, index, feet, Scene::observerSeesRedstonePhase);
        index = add(scenes, index, feet, Scene::observerDrivesLamp);
        index = add(scenes, index, feet, Scene::observerDrivesWire);
        index = add(scenes, index, feet, Scene::observerHeldDuringPulse);
        index = add(scenes, index, feet, Scene::observerFeedsVanillaRepeater);
        index = add(scenes, index, feet, Scene::vanillaRepeaterFeedsObserver);
        index = add(scenes, index, feet, Scene::observerWithHostTick);
        index = add(scenes, index, feet, Scene::observerFuzz);
        index = add(scenes, index, feet, Scene::comparatorNotPulsingObserver);
        index = add(scenes, index, feet, Scene::repeaterLockPulsesObserver);
        index = add(scenes, index, feet, Scene::repeaterDelay1);
        index = add(scenes, index, feet, Scene::repeaterDelay4);
        index = add(scenes, index, feet, Scene::repeaterShortPulse);
        index = add(scenes, index, feet, Scene::repeaterChain);
        index = add(scenes, index, feet, Scene::repeaterFuzz);
        index = add(scenes, index, feet, Scene::comparatorBasic);
        index = add(scenes, index, feet, Scene::comparatorSubtract);
        index = add(scenes, index, feet, Scene::comparatorIncremental);
        for (int period : new int[]{2, 3, 4, 5}) {
            index = add(scenes, index, feet, (sp, plot) -> Scene.observerPulseTrain(plot, period));
        }
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.UP}) {
            index = add(scenes, index, feet, (sp, plot) -> Scene.observerFacing(plot, facing));
        }
        return scenes;
    }

    private interface SceneFactory {
        Scene create(TestSingleplayerContext sp, BlockPos plot);
    }

    private static int add(List<Scene> scenes, int index, BlockPos feet, SceneFactory factory) {
        BlockPos plot = feet.offset((index % ROW) * PLOT, 0, (index / ROW) * PLOT);
        scenes.add(factory.create(null, plot));
        return index + 1;
    }

    // ------------------------------------------------------------------
    // 场景：一段"元件 + 对照原版"的电路
    // ------------------------------------------------------------------

    /**
     * 一个对照场景：左边是元件相（贴在下半砖上），右边是同一元件的原版方块。
     * 两边用**同一套驱动规则**与**同一种探针**，逐 tick 采出一条波形。
     */
    abstract static class Scene {

        /** 元件侧/原版侧各自的观察点（可多个，采成一条按位或的波形）。 */
        final List<BlockPos> elementProbes = new ArrayList<>();
        final List<BlockPos> vanillaProbes = new ArrayList<>();
        final List<Boolean> elementWave = new ArrayList<>();
        final List<Boolean> vanillaWave = new ArrayList<>();
        final String label;
        final BlockPos origin;
        int waveforms = 1;
        /** 该场景是否必须至少出现一次脉冲（自激振荡器才允许恒低）。 */
        boolean requiresPulse = true;

        /**
         * 明确预期"恒低"的场景：{@code comparator value -> observer} 只改比较器数值、
         * 不翻 POWERED（原版不发形状更新，观察者不该脉冲）。这些场景不要求出现脉冲，
         * 但仍然要求两侧**同样**恒低。
         */
        private boolean expectsStaticLow() {
            return "comparator value -> observer".equals(label);
        }

        Scene(String label, BlockPos origin) {
            this.label = label;
            this.origin = origin;
        }

        /** 场地就绪：放方块、装配元件。 */
        abstract void build(ServerLevel level);

        /** 装配余震之后：把驱动格恢复成确定的初值，避免依赖"上一个用例留下的地形"。 */
        void reset(ServerLevel level) {
            // 默认无操作。
        }

        /** 每 tick 的驱动（服务端 tick 内调用）。 */
        abstract void drive(ServerLevel level, int tick);

        /** 每 tick 的采样（在驱动之后、同一服务端 tick 内）。 */
        void sample(ServerLevel level) {
            elementWave.add(anyPowered(level, elementProbes));
            vanillaWave.add(anyPowered(level, vanillaProbes));
        }

        List<String> verify() {
            List<String> out = new ArrayList<>();
            if (elementWave.size() != vanillaWave.size()) {
                out.add(label + " FAIL sample count differs");
                return out;
            }
            StringBuilder eb = new StringBuilder(elementWave.size());
            StringBuilder vb = new StringBuilder(vanillaWave.size());
            boolean differs = false;
            for (int i = 0; i < elementWave.size(); i++) {
                eb.append(elementWave.get(i) ? '1' : '0');
                vb.append(vanillaWave.get(i) ? '1' : '0');
                if (!elementWave.get(i).equals(vanillaWave.get(i))) {
                    differs = true;
                }
            }
            LiquidPhase.LOGGER.info("[gametest][timing] {} element={} vanilla={}", label, eb, vb);
            if (differs) {
                out.add(label + " FAIL waveform differs (element=" + eb + " vanilla=" + vb + ")");
            }
            // "两边都恒 0"通常是假阳性：说明驱动没生效（场地重叠/触发格残留/对照被吸收），
            // 这不是"一致"，必须显式失败。只有明确预期恒低的场景才放行。
            if (!requiresPulse || expectsStaticLow()) {
                return out;
            }
            if (elementWave.stream().noneMatch(Boolean::booleanValue)
                || vanillaWave.stream().noneMatch(Boolean::booleanValue)) {
                out.add(label + " FAIL no pulse at all (element=" + eb + " vanilla=" + vb
                    + "): the driver never took effect, so equality would be meaningless");
            }
            return out;
        }

        /** 探针语义：原版方块看它自己的状态，元件相看旁路表的 powered 字段。 */
        private static boolean anyPowered(ServerLevel level, List<BlockPos> probes) {
            for (BlockPos pos : probes) {
                BlockState state = level.getBlockState(pos);
                int extra = PhaseExtras.get(level, pos);
                if (PhaseExtra.isElement(extra)) {
                    if (PhaseExtra.powered(extra)) {
                        return true;
                    }
                } else if (state.is(Blocks.OBSERVER)) {
                    if (state.getValue(BlockStateProperties.POWERED)) {
                        return true;
                    }
                } else if (state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR)) {
                    if (state.getValue(BlockStateProperties.POWERED)) {
                        return true;
                    }
                } else if (state.is(Blocks.REDSTONE_LAMP)) {
                    if (state.getValue(BlockStateProperties.LIT)) {
                        return true;
                    }
                } else if (state.is(Blocks.REDSTONE_WIRE)) {
                    if (state.getValue(BlockStateProperties.POWER) != 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        // --------------------------------------------------------------
        // 场地工具
        // --------------------------------------------------------------

        /** 一方（元件侧或原版侧）的坐标布局。 */
        record Side(BlockPos cell, BlockPos input, BlockPos output) {
        }

        /** 元件侧：下半砖 + 元件相。 */
        static Side elementSide(ServerLevel level, BlockPos cell, PhaseExtra.Kind kind, Direction facing) {
            level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            ElementBlocks.place(level, cell, level.getBlockState(cell), kind, facing);
            return new Side(cell, cell.relative(facing), cell.relative(facing.getOpposite()));
        }

        /** 原版侧：完整石头 + 原版元件方块（下半砖不是 RIGID 支撑，放不上）。 */
        static Side vanillaSide(ServerLevel level, BlockPos cell, Block element, Direction facing) {
            level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            BlockState state = element.defaultBlockState();
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            }
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
            level.setBlockAndUpdate(cell, state);
            return new Side(cell, cell.relative(facing), cell.relative(facing.getOpposite()));
        }

        static void setPower(ServerLevel level, BlockPos pos, boolean powered) {
            level.setBlockAndUpdate(pos, powered ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                : Blocks.AIR.defaultBlockState());
        }

        /** 只翻转原版中继器的 POWERED，不替换方块（供"侧向二极管锁存"场景使用）。 */
        static void setRepeaterPowered(ServerLevel level, BlockPos pos, boolean powered) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.REPEATER)) {
                level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.POWERED, powered));
            }
        }

        /** 元件侧置功率（走旁路表，由真实电源驱动时也用这个模拟"红石相"）。 */
        static void setWirePower(ServerLevel level, BlockPos pos, boolean powered) {
            setPower(level, pos.west(), powered);
        }

        // --------------------------------------------------------------
        // 具体场景
        // --------------------------------------------------------------

        static Scene observerBasic(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer", plot) {
                Side e;
                Side v;

                @Override
                void build(ServerLevel level) {
                    e = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    v = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(e.cell());
                    vanillaProbes.add(v.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, e.input(), false);
                    setPower(level, v.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, e.input(), true);
                        setPower(level, v.input(), true);
                    } else if (tick == 30) {
                        setPower(level, e.input(), false);
                        setPower(level, v.input(), false);
                    }
                }
            };
        }

        static Scene observerRelay(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer relay", plot) {
                Side a;
                Side b;
                Side va;
                Side vb;

                @Override
                void build(ServerLevel level) {
                    a = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    b = elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    va = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    vb = vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(b.cell());
                    vanillaProbes.add(vb.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, a.input(), false);
                    setPower(level, va.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, a.input(), true);
                        setPower(level, va.input(), true);
                    }
                }
            };
        }

        static Scene observerClock(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer clock", plot) {
                Side a;
                Side va;

                @Override
                void build(ServerLevel level) {
                    // 真正的反馈环：A 在 z=0 朝 SOUTH（观察面朝 z=1），B 在 z=1 朝 NORTH（观察面朝 z=0）。
                    // 这样 A 的观察面正对 B、B 的观察面正对 A，才构成自激振荡。
                    a = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.SOUTH);
                    elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    va = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.SOUTH);
                    vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(a.cell());
                    vanillaProbes.add(va.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 自激振荡器：装配顺序可能让两侧都停在稳态，用一次两侧同构的外部踢同步启动。
                    // （踢的是观察面**旁边**那一格，不能落在观察者或它的观察面上，否则会覆盖元件。）
                    if (tick == 2) {
                        level.setBlockAndUpdate(origin.offset(0, 0, 2), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(origin.offset(0, 0, 8), Blocks.STONE.defaultBlockState());
                    }
                }
            };
        }

        static Scene observerSeesRepeater(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer sees repeater", plot) {
                Side observer;
                Side repeater;
                Side vObserver;
                Side vRepeater;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.EAST);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.EAST);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, repeater.input(), false);
                    setPower(level, vRepeater.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 6) {
                        setPower(level, repeater.input(), true);
                        setPower(level, vRepeater.input(), true);
                    }
                }
            };
        }

        static Scene observerSeesComparator(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer sees comparator", plot) {
                Side observer;
                Side comparator;
                BlockPos chest;
                Side vObserver;
                Side vComparator;
                BlockPos vChest;

                @Override
                void build(ServerLevel level) {
                    comparator = elementSide(level, origin, PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
                    chest = comparator.input();
                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                    observer = elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);

                    vComparator = vanillaSide(level, origin.offset(0, 0, 6), Blocks.COMPARATOR, Direction.NORTH);
                    vChest = vComparator.input();
                    level.setBlockAndUpdate(vChest, Blocks.CHEST.defaultBlockState());
                    vObserver = vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 6 || tick == 20) {
                        int count = tick == 6 ? 64 : 0;
                        fillChest(level, chest, count);
                        fillChest(level, vChest, count);
                    }
                }
            };
        }

        static Scene observerSeesRedstonePhase(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer sees redstone phase", plot) {
                Side observer;
                BlockPos wire;
                Side vObserver;
                BlockPos vWire;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    wire = origin;
                    level.setBlockAndUpdate(wire.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(wire, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                    PhaseExtras.set(level, wire, PhaseExtra.redstone(0));

                    vObserver = vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    vWire = origin.offset(0, 0, 6);
                    level.setBlockAndUpdate(vWire.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vWire, Blocks.REDSTONE_WIRE.defaultBlockState());
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 6 || tick == 24) {
                        boolean on = tick == 6;
                        setPower(level, wire.west(), on);
                        setPower(level, vWire.west(), on);
                    }
                }
            };
        }

        static Scene observerDrivesLamp(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer drives lamp", plot) {
                Side observer;
                BlockPos lamp;
                Side vObserver;
                BlockPos vLamp;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    lamp = observer.output();
                    level.setBlockAndUpdate(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    vLamp = vObserver.output();
                    level.setBlockAndUpdate(vLamp, Blocks.REDSTONE_LAMP.defaultBlockState());
                    elementProbes.add(lamp);
                    vanillaProbes.add(vLamp);
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, observer.input(), true);
                        setPower(level, vObserver.input(), true);
                    }
                }
            };
        }

        static Scene observerDrivesWire(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer drives wire", plot) {
                Side observer;
                BlockPos wire;
                Side vObserver;
                BlockPos vWire;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    wire = observer.output();
                    level.setBlockAndUpdate(wire.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(wire, Blocks.REDSTONE_WIRE.defaultBlockState());
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    vWire = vObserver.output();
                    level.setBlockAndUpdate(vWire.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vWire, Blocks.REDSTONE_WIRE.defaultBlockState());
                    elementProbes.add(wire);
                    vanillaProbes.add(vWire);
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, observer.input(), true);
                        setPower(level, vObserver.input(), true);
                    }
                }
            };
        }

        static Scene observerHeldDuringPulse(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer held during pulse", plot) {
                Side observer;
                Side vObserver;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 在脉冲期内（点亮后 1 tick）再触发一次，验证不会因此多排一个刻。
                    if (tick == 4 || tick == 5) {
                        setPower(level, observer.input(), true);
                        setPower(level, vObserver.input(), true);
                    } else if (tick == 6) {
                        setPower(level, observer.input(), false);
                        setPower(level, vObserver.input(), false);
                    }
                }
            };
        }

        static Scene observerFeedsVanillaRepeater(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer feeds vanilla repeater", plot) {
                Side observer;
                Side repeater;
                Side vObserver;
                Side vRepeater;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    repeater = vanillaSide(level, observer.output(), Blocks.REPEATER, Direction.NORTH);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    vRepeater = vanillaSide(level, vObserver.output(), Blocks.REPEATER, Direction.NORTH);
                    elementProbes.add(repeater.cell());
                    vanillaProbes.add(vRepeater.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, observer.input(), true);
                        setPower(level, vObserver.input(), true);
                    }
                }
            };
        }

        static Scene vanillaRepeaterFeedsObserver(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("vanilla repeater feeds observer", plot) {
                Side repeater;
                Side observer;
                Side vRepeater;
                Side vObserver;

                @Override
                void build(ServerLevel level) {
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    observer = elementSide(level, repeater.output(), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    vObserver = vanillaSide(level, vRepeater.output(), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, repeater.input(), false);
                    setPower(level, vRepeater.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, repeater.input(), true);
                        setPower(level, vRepeater.input(), true);
                    }
                }
            };
        }

        static Scene observerWithHostTick(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer with host tick", plot) {
                Side observer;
                Side vObserver;
                BlockPos hostTickCell;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    hostTickCell = observer.cell();
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4 || tick == 30) {
                        // 审计根因 B 的回归：给宿主自己排一个刻，占住 (pos, hostBlock) 槽位。
                        level.scheduleTick(hostTickCell, Blocks.SMOOTH_STONE_SLAB, 2);
                        boolean on = tick == 4;
                        setPower(level, observer.input(), on);
                        setPower(level, vObserver.input(), on);
                    }
                }
            };
        }

        static Scene observerFuzz(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("observer fuzz", plot) {
                Side observer;
                Side vObserver;
                int seed;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                    seed = 20260826;
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick < 2) {
                        return;
                    }
                    // 固定种子的伪随机 + 保证翻转：每 3 tick 驱动一次，用 tick/3 的奇偶保证
                    // 开/关交替出现（纯随机可能连续同值，会掩盖"驱动没生效"）。
                    int h = (tick * 1103515245 + seed) >>> 16;
                    if (tick % 3 != 0) {
                        return;
                    }
                    boolean on = (((tick / 3) & 1) == 0) ^ ((h & 3) == 0);
                    setPower(level, observer.input(), on);
                    setPower(level, vObserver.input(), on);
                }
            };
        }

        static Scene comparatorNotPulsingObserver(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("comparator value -> observer", plot) {
                Side comparator;
                Side observer;
                BlockPos chest;
                Side vComparator;
                Side vObserver;
                BlockPos vChest;

                @Override
                void build(ServerLevel level) {
                    comparator = elementSide(level, origin, PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
                    chest = comparator.input();
                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                    observer = elementSide(level, comparator.output(), PhaseExtra.Kind.OBSERVER, Direction.NORTH);

                    vComparator = vanillaSide(level, origin.offset(0, 0, 6), Blocks.COMPARATOR, Direction.NORTH);
                    vChest = vComparator.input();
                    level.setBlockAndUpdate(vChest, Blocks.CHEST.defaultBlockState());
                    vObserver = vanillaSide(level, vComparator.output(), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 只改变比较器的**数值**（容器容量），不改变 POWERED：原版不发形状更新，
                    // 因此观察者不应收到脉冲。两侧都必须保持 0。
                    if (tick == 6 || tick == 14 || tick == 22) {
                        int count = 32 + tick * 2;
                        fillChest(level, chest, count);
                        fillChest(level, vChest, count);
                    }
                }
            };
        }

        static Scene repeaterLockPulsesObserver(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("repeater lock -> observer", plot) {
                Side repeater;
                Side observer;
                Side vRepeater;
                Side vObserver;

                @Override
                void build(ServerLevel level) {
                    // 观察者要观察"中继器本体"所在格：中继器在 origin(z=0)，观察者放 z=1、
                    // 观察面朝 NORTH（正对中继器）。中继器 facing=NORTH → 侧向 EAST/WEST。
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    observer = elementSide(level, origin.offset(0, 0, 1), PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 7), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                    // 侧向必须是**二极管**：原版中继器锁存的 sideInputDiodesOnly = true，
                    // 只认 DiodeBlock（中继器/比较器），红石块/红石线都不算（实测已核对）。
                    // 侧向中继器朝 EAST → 输出面 WEST，正对本格。
                    level.setBlockAndUpdate(repeater.cell().east().below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(repeater.cell().east(), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                        .setValue(BlockStateProperties.POWERED, true));
                    level.setBlockAndUpdate(vRepeater.cell().east().below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(vRepeater.cell().east(), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                        .setValue(BlockStateProperties.POWERED, true));
                }

                @Override
                void reset(ServerLevel level) {
                    // 侧向二极管固定为"点亮"，锁存由它的输出决定；不需要额外驱动。
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 侧向二极管保持存在，只翻转它的 POWERED（模拟它的输入变化），
                    // 这样中继器本格的 LOCKED 会跟着翻转，而观察者观测的是中继器本体所在格。
                    if (tick == 6) {
                        setRepeaterPowered(level, repeater.cell().east(), false);
                        setRepeaterPowered(level, vRepeater.cell().east(), false);
                    }
                }
            };
        }

        static Scene repeaterDelay1(TestSingleplayerContext sp, BlockPos plot) {
            return repeaterScene("repeater delay=1", plot, 1);
        }

        static Scene repeaterDelay4(TestSingleplayerContext sp, BlockPos plot) {
            return repeaterScene("repeater delay=4", plot, 4);
        }

        static Scene repeaterScene(String label, BlockPos plot, int delay) {
            return new Scene(label, plot) {
                Side repeater;
                Side vRepeater;

                @Override
                void build(ServerLevel level) {
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    PhaseExtras.set(level, repeater.cell(),
                        PhaseExtra.withDelay(PhaseExtras.get(level, repeater.cell()), delay - 1));
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    level.setBlockAndUpdate(vRepeater.cell(), level.getBlockState(vRepeater.cell())
                        .setValue(BlockStateProperties.DELAY, delay));
                    elementProbes.add(repeater.cell());
                    vanillaProbes.add(vRepeater.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, repeater.input(), false);
                    setPower(level, vRepeater.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, repeater.input(), true);
                        setPower(level, vRepeater.input(), true);
                    } else if (tick == 28) {
                        setPower(level, repeater.input(), false);
                        setPower(level, vRepeater.input(), false);
                    }
                }
            };
        }

        static Scene repeaterShortPulse(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("repeater short pulse", plot) {
                Side repeater;
                Side vRepeater;

                @Override
                void build(ServerLevel level) {
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    PhaseExtras.set(level, repeater.cell(),
                        PhaseExtra.withDelay(PhaseExtras.get(level, repeater.cell()), 1));
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    level.setBlockAndUpdate(vRepeater.cell(), level.getBlockState(vRepeater.cell())
                        .setValue(BlockStateProperties.DELAY, 2));
                    elementProbes.add(repeater.cell());
                    vanillaProbes.add(vRepeater.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, repeater.input(), false);
                    setPower(level, vRepeater.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, repeater.input(), true);
                        setPower(level, vRepeater.input(), true);
                    } else if (tick == 5) {
                        setPower(level, repeater.input(), false);
                        setPower(level, vRepeater.input(), false);
                    }
                }
            };
        }

        static Scene repeaterChain(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("repeater chain", plot) {
                Side first;
                Side second;
                Side vFirst;
                Side vSecond;

                @Override
                void build(ServerLevel level) {
                    first = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    second = elementSide(level, first.output(), PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    vFirst = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    vSecond = vanillaSide(level, vFirst.output(), Blocks.REPEATER, Direction.NORTH);
                    elementProbes.add(second.cell());
                    vanillaProbes.add(vSecond.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, first.input(), false);
                    setPower(level, vFirst.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        setPower(level, first.input(), true);
                        setPower(level, vFirst.input(), true);
                    } else if (tick == 28) {
                        setPower(level, first.input(), false);
                        setPower(level, vFirst.input(), false);
                    }
                }
            };
        }

        static Scene repeaterFuzz(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("repeater fuzz", plot) {
                Side repeater;
                Side vRepeater;
                int seed;

                @Override
                void build(ServerLevel level) {
                    repeater = elementSide(level, origin, PhaseExtra.Kind.REPEATER, Direction.NORTH);
                    vRepeater = vanillaSide(level, origin.offset(0, 0, 6), Blocks.REPEATER, Direction.NORTH);
                    elementProbes.add(repeater.cell());
                    vanillaProbes.add(vRepeater.cell());
                    seed = 777001;
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, repeater.input(), false);
                    setPower(level, vRepeater.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 固定模式而不是纯随机：奇数拍开、偶数拍关，保证必有翻转（"恒低"会掩盖问题）。
                    // 再用 tick 的位混合制造不规律，覆盖 checkTickOnNeighbor 的各种组合。
                    if (tick < 2 || tick % 4 != 0) {
                        return;
                    }
                    int h = (tick * 1103515245 + seed) >>> 16;
                    boolean on = ((tick / 4) % 3 != 0) ^ ((h & 1) == 0);
                    setPower(level, repeater.input(), on);
                    setPower(level, vRepeater.input(), on);
                }
            };
        }

        static Scene comparatorBasic(TestSingleplayerContext sp, BlockPos plot) {
            return comparatorScene("comparator", plot, false);
        }

        static Scene comparatorSubtract(TestSingleplayerContext sp, BlockPos plot) {
            return comparatorScene("comparator subtract", plot, true);
        }

        static Scene comparatorScene(String label, BlockPos plot, boolean subtract) {
            return new Scene(label, plot) {
                Side comparator;
                BlockPos chest;
                Side vComparator;
                BlockPos vChest;

                @Override
                void build(ServerLevel level) {
                    comparator = elementSide(level, origin, PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
                    if (subtract) {
                        PhaseExtras.set(level, comparator.cell(),
                            PhaseExtra.withMode(PhaseExtras.get(level, comparator.cell()), true));
                    }
                    chest = comparator.input();
                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());

                    vComparator = vanillaSide(level, origin.offset(0, 0, 6), Blocks.COMPARATOR, Direction.NORTH);
                    if (subtract) {
                        level.setBlockAndUpdate(vComparator.cell(), level.getBlockState(vComparator.cell())
                            .setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT));
                    }
                    vChest = vComparator.input();
                    level.setBlockAndUpdate(vChest, Blocks.CHEST.defaultBlockState());
                    elementProbes.add(comparator.cell());
                    vanillaProbes.add(vComparator.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (!subtract && (tick == 6 || tick == 22)) {
                        int count = tick == 6 ? 64 : 0;
                        fillChest(level, chest, count);
                        fillChest(level, vChest, count);
                    } else if (subtract) {
                        if (tick == 6) {
                            fillChest(level, chest, 64);
                            fillChest(level, vChest, 64);
                        } else if (tick == 18) {
                            setPower(level, comparator.cell().east(), true);
                            setPower(level, vComparator.cell().east(), true);
                        }
                    }
                }
            };
        }

        static Scene comparatorIncremental(TestSingleplayerContext sp, BlockPos plot) {
            return new Scene("comparator incremental", plot) {
                Side comparator;
                BlockPos chest;
                Side vComparator;
                BlockPos vChest;

                @Override
                void build(ServerLevel level) {
                    comparator = elementSide(level, origin, PhaseExtra.Kind.COMPARATOR, Direction.NORTH);
                    chest = comparator.input();
                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                    vComparator = vanillaSide(level, origin.offset(0, 0, 6), Blocks.COMPARATOR, Direction.NORTH);
                    vChest = vComparator.input();
                    level.setBlockAndUpdate(vChest, Blocks.CHEST.defaultBlockState());
                    elementProbes.add(comparator.cell());
                    vanillaProbes.add(vComparator.cell());
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    // 连续逐格加物品：每次变化都该按 2 tick 节奏重新评估。
                    if (tick >= 4 && tick <= 8) {
                        int count = (tick - 3) * 13;
                        fillChest(level, chest, count);
                        fillChest(level, vChest, count);
                    }
                }
            };
        }

        static Scene observerPulseTrain(BlockPos plot, int period) {
            return new Scene("observer pulse train p=" + period, plot) {
                Side observer;
                Side vObserver;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, Direction.NORTH);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, Direction.NORTH);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick < 4) {
                        return;
                    }
                    boolean on = (tick - 4) % period == 0;
                    setPower(level, observer.input(), on);
                    setPower(level, vObserver.input(), on);
                }
            };
        }

        static Scene observerFacing(BlockPos plot, Direction facing) {
            return new Scene("observer facing=" + facing.getName(), plot) {
                Side observer;
                Side vObserver;

                @Override
                void build(ServerLevel level) {
                    observer = elementSide(level, origin, PhaseExtra.Kind.OBSERVER, facing);
                    vObserver = vanillaSide(level, origin.offset(0, 0, 6), Blocks.OBSERVER, facing);
                    elementProbes.add(observer.cell());
                    vanillaProbes.add(vObserver.cell());
                }

                @Override
                void reset(ServerLevel level) {
                    setPower(level, observer.input(), false);
                    setPower(level, vObserver.input(), false);
                }

                @Override
                void drive(ServerLevel level, int tick) {
                    if (tick == 4) {
                        // 观察面那一格放/拆方块。UP/DOWN 时输入格在元件自身上下，
                        // 需要确保不覆盖元件本体（原版观察者朝下时观察的正是它的支撑格）。
                        if (facing != Direction.DOWN) {
                            setPower(level, observer.input(), true);
                            setPower(level, vObserver.input(), true);
                        }
                    } else if (tick == 30) {
                        if (facing != Direction.DOWN) {
                            setPower(level, observer.input(), false);
                            setPower(level, vObserver.input(), false);
                        }
                    }
                }
            };
        }

        static void fillChest(ServerLevel level, BlockPos pos, int count) {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chest.setItem(0, count <= 0 ? ItemStack.EMPTY
                    : new ItemStack(Blocks.STONE, Math.min(64, count)));
                chest.setChanged();
            }
            level.updateNeighbourForOutputSignal(pos, Blocks.CHEST);
        }
    }
}
//?}
