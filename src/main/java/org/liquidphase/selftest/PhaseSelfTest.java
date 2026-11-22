package org.liquidphase.selftest;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.server.packs.resources.ResourceManager;
import org.liquidphase.LiquidPhase;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.phase.PhaseStorage;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 红石相 / 炼药锅的自动化自检（默认不运行）。
 * <p>
 * 用 {@code -Dliquidphase.selftest=true} 启动服务端时，等服务器启动完成后在出生点附近
 * 造一小块测试场地逐项断言，把 {@code PASS}/{@code FAIL} 打进日志，最后清理场地。
 * <p>
 * 放置类断言分两层：
 * <ol>
 *   <li><b>无玩家层</b>：直接 {@link BlockItem#place}（{@code getPlayer()} 为 {@code null}），
 *       证明能力不依赖玩家实体；</li>
 *   <li><b>真实玩家层</b>：FakePlayer → {@code ServerPlayerGameMode.useItemOn}，
 *       走客户端实际落到的服务端入口。</li>
 * </ol>
 * 两层都要覆盖"物品自己搜索落点"的那一类（脚手架的 {@code updatePlacementContext}），
 * 这类路径不经过放置上下文改写，只在入口动手是测不出来的。
 */
public final class PhaseSelfTest {

    private static int passed;
    private static int failed;

    private PhaseSelfTest() {
    }

    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        // 固定高空坐标：六个版本的世界高度都覆盖 y=200，且不影响地形。
        BlockPos base = new BlockPos(0, 200, 0);
        passed = 0;
        failed = 0;
        LiquidPhase.LOGGER.info("[Liquid Phase][selftest] begin at {}", base);
        try {
            checkPhaseEnum();
            checkPhasePropertyCoverage();
            checkShapeAndBlockIdentity(level, base);
            checkSignal(level, base);
            checkInstantUpdate(level, base);
            // 方案 C 旁路表与元件放置（红石/元件参数不再占状态）
            checkExtraStorage();
            checkRedstoneTableReadback(level, base.offset(40, 1, 2));
            checkRedstoneUpdatePower(level, base.offset(40, 1, 4));
            checkElementPlacement(level, base.offset(40, 1, 6));
            checkEntryWriteClearsWater(level, base.offset(42, 1, 0));
            checkElementPlacementByPlayer(level, base);
            checkElementBlockAbsorption(level, base);
            checkElementSignalGating(level, base.offset(24, 1, 6));
            checkElementAssets();
            checkPistonPhaseBehavior(level, base);
            checkRedstonePhaseInitialState(level, base);
            checkPlaceIntoWire(level, base);
            checkPlaceIntoWireByPlayer(level, base);
            checkScaffoldingStacking(level, base);
            checkFallingBlockIntoWire(level, base);
            checkWireStaysUnreplaceable(level, base);
            checkRedstoneRenderParts();
            checkPhaseRenderCulling(level, base);
            checkSyncState();
            checkCauldron(level, base);
            // 相容宿主覆盖面（docs/host-coverage-audit.md §4 的逐项回归，0.1.13）
            checkSolidPhaseCollision(level, base);
            checkBreakRetainEverywhere(level, base);
            checkAdjacentLavaFilling(level, base);
            checkPlacementAbsorption(level, base);
            checkBucketInteraction(level, base);
            checkDispenserHostFilling(level, base);
            checkLavaReflowWiring(level, base);
            checkIndirectPhaseAbsorption(level, base);
            checkUniversalFillingPlacement(level, base);
            checkAbsorptionGateConsistency(level, base);
            checkPackMergeFragments(level, base);
            checkSlabVariantRendering();
            checkPortalWaterHosts(level, base);
            checkPersistenceRoundTrip(level, base);
        } catch (Throwable t) {
            failed++;
            LiquidPhase.LOGGER.error("[Liquid Phase][selftest] crashed", t);
        } finally {
            clearArea(level, base);
        }
        LiquidPhase.LOGGER.info("[Liquid Phase][selftest] result: {} passed, {} failed", passed, failed);
    }

    // ------------------------------------------------------------------
    // 枚举语义
    // ------------------------------------------------------------------

    private static void checkPhaseEnum() {
        assertTrue("phase count = 1 + 7 (redstone no longer occupies a state)", Phase.values().length == 8);
        assertTrue("FILLED excludes NONE", Phase.FILLED.length == 7);
        assertTrue("redstone_0 bypass entry is redstone", PhaseExtra.isRedstone(PhaseExtra.redstone(0))
            && PhaseExtra.power(PhaseExtra.redstone(0)) == 0);
        assertTrue("redstone power roundtrip for 15", PhaseExtra.power(PhaseExtra.redstone(15)) == 15);
        assertTrue("redstone power is packed into 4 bits",
            PhaseExtra.power(PhaseExtra.redstone(19)) == 3);
        assertTrue("repeater element roundtrip",
            PhaseExtra.kind(PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.EAST, 2, false, true))
                    == PhaseExtra.Kind.REPEATER
                && PhaseExtra.facing(PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.EAST, 2, false, true))
                    == Direction.EAST
                && PhaseExtra.delayTicks(
                    PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.EAST, 2, false, true)) == 3
                && PhaseExtra.powered(
                    PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.EAST, 2, false, true)));
        assertTrue("comparator subtract mode roundtrip",
            PhaseExtra.subtractMode(PhaseExtra.element(PhaseExtra.Kind.COMPARATOR, Direction.NORTH, 0, true, false))
                && !PhaseExtra.isRedstone(
                    PhaseExtra.element(PhaseExtra.Kind.COMPARATOR, Direction.NORTH, 0, true, false)));
        assertTrue("frosted stays frosted (frosted=" + Phase.FROSTED_3.isFrosted()
                + ", frozen=" + Phase.FROSTED_3.isFrozen() + ", ice=" + Phase.FROSTED_3.isIce() + ")",
            Phase.FROSTED_3.isFrosted() && Phase.FROSTED_3.isFrozen() && !Phase.FROSTED_3.isIce());
        assertTrue("frosted age works", Phase.FROSTED_2.frostedAge() == 2);
        assertTrue("obsidian is solid", Phase.OBSIDIAN.isSolid());
        // 可替换填充相（0.1.17）：只有熔岩相可被新的填充材料替换；水（含水态）在
        // canAcceptPhase 里单独算可替换，不进这个方法。未来加可替换相改 Phase 枚举。
        assertTrue("lava is the only replaceable filling",
            Phase.LAVA.isReplaceableFilling()
                && !Phase.OBSIDIAN.isReplaceableFilling()
                && !Phase.ICE.isReplaceableFilling()
                && !Phase.FROSTED_0.isReplaceableFilling()
                && !Phase.NONE.isReplaceableFilling());
        assertTrue("none is neither solid nor frosted", !Phase.NONE.isSolid() && !Phase.NONE.isFrosted());
        assertTrue("the bypass table's EMPTY entry is kind NONE",
            PhaseExtra.isEmpty(PhaseExtra.EMPTY)
                && PhaseExtra.kind(PhaseExtra.EMPTY) == PhaseExtra.Kind.NONE);
    }

    // ------------------------------------------------------------------
    // 红石相 = 宿主方块 + phase 取值（不再有新方块）
    // ------------------------------------------------------------------

    private static void checkPhasePropertyCoverage() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.List<String> unrendered = new java.util.ArrayList<>();
        // 运行时真实集合：所有 instanceof PhaseCapable 的方块（= 真的拿到 phase 属性的方块）。
        java.util.Set<String> runtime = new java.util.LinkedHashSet<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            runtime.add(id);
            if (!block.defaultBlockState().hasProperty(PhaseHelper.FILLING)) {
                missing.add(id);
                continue;
            }
            // 每一个拿到 phase 属性的方块都必须有一份被我们改写过的 blockstate：
            // 改写后的那份带一条无条件的 `liquidphase:block/dynamic` part。原版 blockstate
            // 里不会有这个模型，所以它就是"这个方块含红石/元件相时画得出东西"的直接证据。
            //
            // 这是 0.1.12 的根因：`tools/phase-blocks/*.ids` 漏登记时，方块照样能在运行时
            // 拿到 phase 属性、照样能被写入旁路条目，但客户端仍用原版 blockstate，
            // 没有任何 part 触发动态模型 —— 表现为"含红石相了但什么都不显示"。
            String json = liquidphase$blockState(id);
            if (!liquidphase$hasUnconditionalDynamicPart(json)) {
                unrendered.add(id);
            }
        }
        assertTrue("every PhaseCapable block has the phase property (missing: " + missing + ")",
            missing.isEmpty());
        assertTrue("every phased block has an unconditional dynamic part "
                + "(missing: " + unrendered + ")",
            unrendered.isEmpty());
        checkPhaseBlockListMatchesRuntime(runtime);
    }

    /**
     * 生成清单（`tools/phase-blocks/&lt;版本&gt;.ids`，随 jar 一起发布）必须与运行时真实集合
     * **完全相等**。
     * <p>
     * 只比数量是不够的：清单两边各错一个、数量仍然相同，漏掉的那个方块就是"含红石相
     * 却什么都不显示"。这里做双向集合差，并直接打印差集，方便一眼定位。
     * <p>
     * 清单来自构建产物（`phase-blocks.ids`）；读不到时不断言，避免把"资源没打进 jar"
     * 误报成"清单不一致"。
     */
    private static void checkPhaseBlockListMatchesRuntime(java.util.Set<String> runtime) {
        String raw = liquidphase$resource("/phase-blocks.ids");
        if (raw == null) {
            assertTrue("shipped phase-block list is present", false);
            return;
        }
        java.util.Set<String> listed = new java.util.LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String id = line.trim();
            if (id.isEmpty() || id.startsWith("#")) {
                continue;
            }
            listed.add(id.startsWith("minecraft:") ? id : "minecraft:" + id);
        }
        java.util.Set<String> runtimeOnly = new java.util.LinkedHashSet<>(runtime);
        runtimeOnly.removeAll(listed);
        java.util.Set<String> listedOnly = new java.util.LinkedHashSet<>(listed);
        listedOnly.removeAll(runtime);
        assertTrue("shipped phase-block list == runtime phase-capable set "
                + "(phased but not listed: " + runtimeOnly + "; listed but not phased: " + listedOnly + ")",
            runtimeOnly.isEmpty() && listedOnly.isEmpty());
    }

    /** {@return 该方块改写后的 blockstate 内容；不是 minecraft 命名空间或读不到时返回 null} */
    private static String liquidphase$blockState(String blockId) {
        if (!blockId.startsWith("minecraft:")) {
            return null;
        }
        return liquidphase$resource("/assets/minecraft/blockstates/"
            + blockId.substring("minecraft:".length()) + ".json");
    }

    private static void checkShapeAndBlockIdentity(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        // 邻近放一个红石块：红石相落地（onPlace）会立即按邻居重算，电源在旁时写入的
        // 15 重算后仍是 15（没有电源会被结算回 0，见 checkInstantUpdate）。
        level.setBlock(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        BlockState host = Blocks.OAK_STAIRS.defaultBlockState();
        level.setBlock(pos, host, Block.UPDATE_ALL);
        PhaseExtras.set(level, pos, PhaseExtra.redstone(15));

        BlockState actual = level.getBlockState(pos);
        assertTrue("redstone phase is the same block as host",
            actual.getBlock() == Blocks.OAK_STAIRS);
        assertTrue("redstone phase carried by the bypass table",
            RedstoneBlocks.hasRedstone(level, pos)
                && PhaseExtra.power(PhaseExtras.get(level, pos)) == 15);
        assertTrue("no extra liquidphase redstone blocks registered",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                .noneMatch(id -> id.getNamespace().equals("liquidphase") && id.getPath().startsWith("redstone_")));
        assertTrue("host outline unchanged (stairs are not a full cube)",
            !Block.isShapeFullBlock(actual.getShape(level, pos, CollisionContext.empty())));
        assertTrue("collision unchanged (not a solid phase)",
            !Block.isShapeFullBlock(actual.getCollisionShape(level, pos, CollisionContext.empty())));
        assertTrue("redstone power readable", RedstoneBlocks.getPower(level, pos) == 15);
        assertTrue("stair UP face exposed", RedstoneBlocks.exposed(actual, Direction.UP));

        BlockState doubleSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        assertTrue("double slab hides every face",
            !RedstoneBlocks.exposed(doubleSlab, Direction.UP)
                && !RedstoneBlocks.exposed(doubleSlab, Direction.NORTH));

        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 信号
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, pos, PhaseExtra.redstone(15));

        BlockState stairs = level.getBlockState(pos);
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(stairs, Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(stairs, Direction.SOUTH));

        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("redstone phase keeps power 15 (got " + RedstoneBlocks.getPower(level, pos) + ")",
            RedstoneBlocks.getPower(level, pos) == 15);

        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        BlockPos slabPos = base.offset(2, 1, 0);
        level.setBlock(slabPos, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Block.UPDATE_ALL);
        PhaseExtras.set(level, slabPos, PhaseExtra.redstone(15));
        level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);

        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        PhaseExtras.set(level, slabPos, PhaseExtra.EMPTY);
    }

    private static void checkInstantUpdate(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(4, 1, 0);
        level.setBlock(pos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, pos, PhaseExtra.redstone(0));
        BlockPos source = pos.south();
        level.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power updates immediately on neighbour change (got "
                + RedstoneBlocks.getPower(level, pos) + ")",
            RedstoneBlocks.getPower(level, pos) == 15);
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power drops immediately when source removed (got "
                + RedstoneBlocks.getPower(level, pos) + ")",
            RedstoneBlocks.getPower(level, pos) == 0);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
    }

    // ------------------------------------------------------------------
    // 方案 C 旁路表与元件放置
    // ------------------------------------------------------------------

    /** 旁路表本身（不依赖关卡）：put/get/remove/chunkCount/entries/clear。 */
    /**
     * 传送门三件套的含水（0.2.0）：它们原本没有 {@code WATERLOGGED}，由注入器补上，
     * 之后与其它宿主走同一套 {@code WaterloggedPhaseCapable} 语义。
     * <p>
     * 下界门需要合法框架才能存活（它的 {@code updateShape} 会检查框架），所以用 4×5
     * 黑曜石框架 + 2×3 传送门内部格来测真实 {@code placeLiquid}/pickup 路径；
     * 末地门/折跃门没有框架检查，直接测。
     */
    private static void checkPortalWaterHosts(ServerLevel level, BlockPos base) {
        org.liquidphase.LiquidPhase.LOGGER.info("[selftest] portal water hosts begin");

        // ---- 下界门：合法框架 ----
        BlockPos origin = base.offset(60, 1, 0);
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 4; y++) {
                boolean border = x == 0 || x == 3 || y == 0 || y == 4;
                if (border) {
                    level.setBlock(origin.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        BlockState portalState = Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS,
                net.minecraft.core.Direction.Axis.X);
        BlockPos portalPos = origin.offset(1, 1, 0);
        level.setBlock(portalPos, portalState, Block.UPDATE_CLIENTS);

        assertTrue("nether portal has WATERLOGGED after injection",
            level.getBlockState(portalPos)
                .hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        assertTrue("nether portal has filling after injection",
            PhaseHelper.hasPhaseProperty(level.getBlockState(portalPos)));

        boolean waterAccepted = ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.NETHER_PORTAL)
            .placeLiquid(level, portalPos, level.getBlockState(portalPos), Fluids.WATER.getSource(false));
        assertTrue("nether portal accepts water like a normal host",
            waterAccepted
                && level.getBlockState(portalPos).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && level.getFluidState(portalPos).is(Fluids.WATER));

        BlockState waterloggedPortal = level.getBlockState(portalPos);
        net.minecraft.world.item.ItemStack picked;
        //? if 1.19.4 || 1.20.1 {
        /*picked = ((net.minecraft.world.level.block.BucketPickup) Blocks.NETHER_PORTAL)
            .pickupBlock(level, portalPos, waterloggedPortal);
         *///?} elif 1.21.1 {
        /*picked = ((net.minecraft.world.level.block.BucketPickup) Blocks.NETHER_PORTAL)
            .pickupBlock((net.minecraft.world.entity.player.Player) null, level, portalPos, waterloggedPortal);
         *///?} else {
        picked = ((net.minecraft.world.level.block.BucketPickup) Blocks.NETHER_PORTAL)
            .pickupBlock((net.minecraft.world.entity.LivingEntity) null, level, portalPos, waterloggedPortal);
        //?}
        assertTrue("waterlogged nether portal can be picked up with a bucket",
            picked.is(net.minecraft.world.item.Items.WATER_BUCKET)
                && !level.getBlockState(portalPos).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));

        // 有材料相时拒水（与其它宿主同规则）
        level.setBlock(portalPos, PhaseHelper.withPhase(portalState, Phase.ICE), Block.UPDATE_CLIENTS);
        boolean rejected = !((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.NETHER_PORTAL)
            .placeLiquid(level, portalPos, level.getBlockState(portalPos), Fluids.WATER.getSource(false));
        assertTrue("nether portal with ice phase rejects water", rejected);
        level.setBlock(portalPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // ---- 末地门 / 末地折跃门：无框架检查 ----
        net.minecraft.world.level.block.Block[] endPortals = {Blocks.END_PORTAL, Blocks.END_GATEWAY};
        String[] names = {"end portal", "end gateway"};
        for (int i = 0; i < endPortals.length; i++) {
            BlockPos pos = base.offset(70 + i, 1, 0);
            level.setBlock(pos, endPortals[i].defaultBlockState(), Block.UPDATE_CLIENTS);
            boolean hasWaterlogged = level.getBlockState(pos)
                .hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
            boolean accepted = ((net.minecraft.world.level.block.LiquidBlockContainer) endPortals[i])
                .placeLiquid(level, pos, level.getBlockState(pos), Fluids.WATER.getSource(false));
            assertTrue(names[i] + " gets WATERLOGGED and accepts water",
                hasWaterlogged && accepted
                    && level.getBlockState(pos).getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    && level.getFluidState(pos).is(Fluids.WATER));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // 框架拆掉，避免影响后续断言/存档
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 4; y++) {
                level.setBlock(origin.offset(x, y, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * 持久化往返：写一条元件条目 → {@code PhasePersistence.save} → 清掉 → {@code load} →
     * 逐字段读回。走真实的 NBT 文件（{@code world/data/liquidphase_<dim>.dat}）。
     * <p>
     * 放在自检最后：{@code load} 会清空整张表（测试场地本应已清干净）。
     */
    private static void checkPersistenceRoundTrip(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(41, 1, 0);
        level.setBlock(pos, Blocks.STONE_SLAB.defaultBlockState(), Block.UPDATE_ALL);
        int extra = PhaseExtra.element(PhaseExtra.Kind.OBSERVER, Direction.NORTH, 0, false, true);
        PhaseExtras.set(level, pos, extra);
        org.liquidphase.phase.PhasePersistence.save(level);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        org.liquidphase.phase.PhasePersistence.load(level);
        int loaded = PhaseExtras.get(level, pos);
        assertTrue("persistence: side table survives a save/load round-trip",
            PhaseExtra.isElement(loaded)
                && PhaseExtra.kind(loaded) == PhaseExtra.Kind.OBSERVER
                && PhaseExtra.facing(loaded) == Direction.NORTH
                && PhaseExtra.powered(loaded));
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
    }

    private static void checkExtraStorage() {        PhaseStorage storage = new PhaseStorage();
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(20, 64, 5);
        assertTrue("storage: put returns EMPTY for a fresh cell",
            storage.put(a, PhaseExtra.redstone(7)) == PhaseExtra.EMPTY);
        assertTrue("storage: get reads the cell back",
            storage.get(a) == PhaseExtra.redstone(7));
        assertTrue("storage: writes mark the table dirty", storage.isDirty());
        storage.markClean();
        assertTrue("storage: markClean clears the dirty flag", !storage.isDirty());
        assertTrue("storage: one cell in one chunk",
            storage.cellCount() == 1 && storage.chunkCount() == 1);
        storage.put(b, PhaseExtra.redstone(3));
        assertTrue("storage: chunkCount groups cells by chunk",
            storage.chunkCount() == 2 && storage.cellCount() == 2);
        assertTrue("storage: remove returns the previous entry",
            storage.remove(a) == PhaseExtra.redstone(7)
                && storage.get(a) == PhaseExtra.EMPTY);
        assertTrue("storage: empty chunks are dropped after removal",
            storage.chunkCount() == 1);
        boolean[] listed = {false};
        storage.entries().forEach((packed, extra) -> {
            if (packed == b.asLong() && extra == PhaseExtra.redstone(3)) {
                listed[0] = true;
            }
        });
        assertTrue("storage: entries lists every live cell", listed[0]);
        storage.clear();
        assertTrue("storage: clear empties the whole table",
            storage.cellCount() == 0 && storage.chunkCount() == 0);
    }

    /** 关卡级读写入口：{@code PhaseExtras.set} → {@code get} 原样读回，EMPTY 即删除。 */
    private static void checkRedstoneTableReadback(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, pos, PhaseExtra.redstone(9));
        assertTrue("level table: set/get round-trip",
            PhaseExtras.get(level, pos) == PhaseExtra.redstone(9));
        assertTrue("level table: packed-position lookup agrees",
            PhaseExtras.get(level, pos.asLong()) == PhaseExtra.redstone(9));
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        assertTrue("level table: EMPTY removes the entry",
            PhaseExtras.get(level, pos) == PhaseExtra.EMPTY);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 红石条目重算：{@code updatePower} 从邻居电源算出 15，撤源回落 0。 */
    private static void checkRedstoneUpdatePower(ServerLevel level, BlockPos pos) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, pos, PhaseExtra.redstone(0));
        boolean changed = RedstoneBlocks.updatePower(level, pos, level.getBlockState(pos));
        assertTrue("updatePower computes 15 from a neighbouring source",
            changed && RedstoneBlocks.getPower(level, pos) == 15);
        assertTrue("updatePower does not write once settled",
            !RedstoneBlocks.updatePower(level, pos, level.getBlockState(pos)));
        level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power drops back to 0 when the source is removed",
            RedstoneBlocks.getPower(level, pos) == 0);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 元件放置前提：全整格宿主没有空腔 → 拒绝；半砖有空腔 → 接受并写入元件条目。 */
    private static void checkElementPlacement(ServerLevel level, BlockPos base) {
        BlockPos fullPos = base.offset(40, 1, 6);
        level.setBlock(fullPos, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        BlockState fullHost = level.getBlockState(fullPos);
        assertTrue("element placement: a full-cube host has no cavity",
            !ElementBlocks.hasCavity(level, fullPos, fullHost)
                && !ElementBlocks.place(level, fullPos, fullHost, PhaseExtra.Kind.REPEATER,
                    Direction.NORTH));
        assertTrue("element placement: the rejected full-cube host stays clean",
            PhaseExtras.get(level, fullPos) == PhaseExtra.EMPTY);
        level.setBlock(fullPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos slabPos = base.offset(40, 1, 8);
        level.setBlock(slabPos, Blocks.STONE_SLAB.defaultBlockState(), Block.UPDATE_ALL);
        BlockState slab = level.getBlockState(slabPos);
        assertTrue("element placement: a half slab has a cavity",
            ElementBlocks.hasCavity(level, slabPos, slab));
        assertTrue("element placement: repeater writes the element entry",
            ElementBlocks.place(level, slabPos, slab, PhaseExtra.Kind.REPEATER, Direction.EAST)
                && ElementBlocks.hasElement(level, slabPos)
                && PhaseExtra.kind(PhaseExtras.get(level, slabPos)) == PhaseExtra.Kind.REPEATER
                && PhaseExtra.facing(PhaseExtras.get(level, slabPos)) == Direction.EAST);
        assertTrue("element placement: an occupied cell no longer offers a cavity",
            !ElementBlocks.hasCavity(level, slabPos, slab));
        assertTrue("element item mapping round-trips",
            ElementBlocks.kindOfItem(ElementBlocks.itemFor(PhaseExtra.Kind.OBSERVER))
                == PhaseExtra.Kind.OBSERVER);
        PhaseExtras.set(level, slabPos, PhaseExtra.EMPTY);
        level.setBlock(slabPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 0.2.3 清含水与装配门控合一：写条目清掉含水/熔岩相；冰/黑曜石相仍拒绝一切覆盖。
    // ------------------------------------------------------------------

    private static void checkEntryWriteClearsWater(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(42, 1, 0);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState waterlogged = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true);

        // 红石粉点含水宿主（a0 判定源走 setRedstonePhase）：条目写入、水被清掉。
        level.setBlock(pos, waterlogged, Block.UPDATE_ALL);
        assertTrue("entry write: dust accepts a waterlogged host",
            RedstoneBlocks.setRedstonePhase(level, pos, level.getBlockState(pos)));
        BlockState after = level.getBlockState(pos);
        assertTrue("entry write: water is cleared from the state",
            RedstoneBlocks.hasRedstone(level, pos)
                && !after.getValue(BlockStateProperties.WATERLOGGED)
                && level.getFluidState(pos).isEmpty());
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);

        // 含熔岩宿主贴元件（元件门控与红石相共用 canAcceptExtra）：熔岩相清回 none。
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.LAVA), Block.UPDATE_ALL);
        assertTrue("element accepts a lava-filled host via the shared gate",
            ElementBlocks.hasCavity(level, pos, level.getBlockState(pos))
                && ElementBlocks.place(level, pos, level.getBlockState(pos),
                    PhaseExtra.Kind.OBSERVER, Direction.NORTH));
        BlockState afterLava = level.getBlockState(pos);
        assertTrue("element on lava host: lava cleared, entry written, no water",
            PhaseHelper.getPhase(afterLava) == Phase.NONE
                && PhaseExtra.kind(PhaseExtras.get(level, pos)) == PhaseExtra.Kind.OBSERVER
                && !afterLava.getValue(BlockStateProperties.WATERLOGGED));
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);

        // 冰相宿主仍拒绝覆盖（用户口径"其余填充不允许覆盖"）：红石与元件都不放行、不改动。
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        BlockState iceHost = level.getBlockState(pos);
        assertTrue("ice-filled host still refuses dust (canAcceptExtra)",
            !PhaseHelper.canAcceptExtra(level, pos, iceHost));
        assertTrue("ice-filled host still refuses elements (hasCavity uses the shared gate)",
            !ElementBlocks.hasCavity(level, pos, iceHost)
                && !ElementBlocks.place(level, pos, iceHost, PhaseExtra.Kind.REPEATER, Direction.NORTH));
        assertTrue("refused writes leave the ice phase untouched",
            PhaseHelper.getPhase(level.getBlockState(pos)) == Phase.ICE
                && PhaseExtras.get(level, pos) == PhaseExtra.EMPTY);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 0.2.3 活塞路径：黑曜石相推不动；推动覆盖/破坏列表弹出物品（相不随方块移动）；
    // 拉动/剩余清格实体化留在原位。真实活塞由 PistonBaseBlockInvoker 直驱 triggerEvent。
    // ------------------------------------------------------------------

    /**
     * 元件装配的**真实玩家点击**路径（0.2.3）：普通右键（不潜行）半砖/含水半砖/含熔岩半砖
     * 都要能贴元件，且写入时水/熔岩相被清掉；**潜行右键回归原版**（真元件方块放在上面一格）。
     * <p>
     * 与 {@link #checkElementPlacement} 的区别：那个直接调 {@code ElementBlocks.place}，
     * 绕过了玩家交互层；这里走 {@code ServerPlayerGameMode.useItemOn}——
     * 0.2.2 的"getClickedPos 指向空气格"就是靠这一层才暴露的。
     */
    private static void checkElementPlacementByPlayer(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);
        String[] names = {"clean", "waterlogged", "lava"};
        for (int i = 0; i < names.length; i++) {
            BlockPos pos = base.offset(50, 1, i * 2);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            BlockState host = switch (i) {
                case 1 -> Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true);
                case 2 -> PhaseHelper.withPhase(Blocks.STONE_SLAB.defaultBlockState(), Phase.LAVA);
                default -> Blocks.STONE_SLAB.defaultBlockState();
            };
            level.setBlock(pos, host, Block.UPDATE_ALL);
            PhaseExtras.set(level, pos, PhaseExtra.EMPTY);

            player.setShiftKeyDown(false);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.REPEATER));
            Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
            net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(player, level,
                player.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND, hit);

            BlockState after = level.getBlockState(pos);
            int extra = PhaseExtras.get(level, pos);
            assertTrue("player element on " + names[i] + " host: plain click writes a repeater entry (got "
                    + result + " / " + after.getBlock() + " / " + PhaseExtra.describe(extra) + ")",
                after.getBlock() == Blocks.STONE_SLAB
                    && PhaseExtra.kind(extra) == PhaseExtra.Kind.REPEATER);
            assertTrue("player element on " + names[i] + " host: water/lava phase cleared",
                PhaseHelper.getPhase(after) == Phase.NONE
                    && !after.getValue(BlockStateProperties.WATERLOGGED));

            PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // 潜行右键 = 原版：真中继器方块放在宿主上面一格，宿主不留条目。
        BlockPos sneakPos = base.offset(50, 1, 8);
        level.setBlock(sneakPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sneakPos, Blocks.STONE_SLAB.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, sneakPos, PhaseExtra.EMPTY);
        level.setBlock(sneakPos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        player.setShiftKeyDown(true);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.REPEATER));
        Vec3 sneakHit = new Vec3(sneakPos.getX() + 0.5, sneakPos.getY() + 1.0, sneakPos.getZ() + 0.5);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(),
            net.minecraft.world.InteractionHand.MAIN_HAND,
            new BlockHitResult(sneakHit, Direction.UP, sneakPos, false));
        player.setShiftKeyDown(false);
        // 下半砖顶面在原版不是中继器的合法支撑（SlabBlock.isFaceSturdy(UP) 对 bottom 为 false），
        // 所以"原版路径"在这里表现为放置失败——这恰好与装配（写条目 + 消耗物品）可区分。
        assertTrue("player element: sneak-click does not assemble (vanilla path, no entry, item kept)",
            PhaseExtras.get(level, sneakPos) == PhaseExtra.EMPTY
                && level.getBlockState(sneakPos).is(Blocks.STONE_SLAB)
                && level.getBlockState(sneakPos.above()).isAir()
                && player.getMainHandItem().is(net.minecraft.world.item.Items.REPEATER));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(sneakPos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sneakPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sneakPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 真实元件方块作为"相材料格"的三条通用路径（0.2.3）：
     * 宿主放进元件格、setBlock 写进宿主格、下落宿主落进元件格，都要吸收成元件相并继承参数。
     */
    private static void checkElementBlockAbsorption(ServerLevel level, BlockPos base) {
        BlockState repeater = Blocks.REPEATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 3)
            .setValue(net.minecraft.world.level.block.RepeaterBlock.LOCKED, true)
            .setValue(net.minecraft.world.level.block.RepeaterBlock.POWERED, true);
        BlockState comparator = Blocks.COMPARATOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.SOUTH)
            .setValue(net.minecraft.world.level.block.ComparatorBlock.MODE,
                net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT);

        // 宿主放进元件所在格（BlockItem.place + 捕获）
        BlockPos intoPos = base.offset(54, 1, 0);
        level.setBlock(intoPos, repeater, Block.UPDATE_ALL);
        BlockState placed = liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, intoPos, Direction.UP);
        int extra = PhaseExtras.get(level, intoPos);
        assertTrue("element cell: host placed into a repeater tile absorbs it (got "
                + (placed == null ? "null" : placed.getBlock()) + " / " + PhaseExtra.describe(extra) + ")",
            placed != null && placed.getBlock() == Blocks.OAK_STAIRS
                && PhaseExtra.kind(extra) == PhaseExtra.Kind.REPEATER
                && PhaseExtra.facing(extra) == Direction.EAST
                && PhaseExtra.delay(extra) == 2
                && PhaseExtra.locked(extra)
                && PhaseExtra.powered(extra));
        PhaseExtras.set(level, intoPos, PhaseExtra.EMPTY);
        level.setBlock(intoPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // setBlock 把元件方块写进宿主格（改写层）
        BlockPos rewritePos = base.offset(54, 1, 2);
        level.setBlock(rewritePos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, rewritePos, PhaseExtra.EMPTY);
        level.setBlock(rewritePos, comparator, Block.UPDATE_ALL);
        int rewriteExtra = PhaseExtras.get(level, rewritePos);
        assertTrue("element cell: setBlock of a comparator into a host rewrites to an entry (got "
                + PhaseExtra.describe(rewriteExtra) + ")",
            level.getBlockState(rewritePos).getBlock() == Blocks.OAK_STAIRS
                && PhaseExtra.kind(rewriteExtra) == PhaseExtra.Kind.COMPARATOR
                && PhaseExtra.facing(rewriteExtra) == Direction.SOUTH
                && PhaseExtra.subtractMode(rewriteExtra));
        PhaseExtras.set(level, rewritePos, PhaseExtra.EMPTY);
        level.setBlock(rewritePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 元件 I/O 的"宿主相应面露出"门控（0.2.4）：下半楼梯朝北时南脸被高半格完全盖住、
     * 北脸露出。输入/输出/观察面/侧向（比较器 alternate、中继器锁存）都按此判定。
     */
    private static void checkElementSignalGating(ServerLevel level, BlockPos origin) {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockPos pos = origin;
        level.setBlockAndUpdate(pos, stair);
        // 下半楼梯朝北 = 台阶升向北侧：北脸被高半格完全盖住，南脸露出。
        assertTrue("element gating: bottom stair facing north has a fully covered north face",
            !RedstoneBlocks.exposed(stair, Direction.NORTH)
                && RedstoneBlocks.exposed(stair, Direction.SOUTH));

        // 1) 输入面被覆盖（facing=NORTH）→ 不输入；输出面（SOUTH）露出 → 有输出。
        BlockPos covered = pos.offset(2, 0, 0);
        level.setBlockAndUpdate(covered, stair);
        level.setBlockAndUpdate(covered.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertTrue("element gating: repeater on a covered-input stair is placed",
            ElementBlocks.place(level, covered, stair, PhaseExtra.Kind.REPEATER, Direction.NORTH));
        int coveredExtra = PhaseExtras.get(level, covered);
        assertTrue("element gating: covered input face does not schedule a turn-on tick",
            !level.getBlockTicks().hasScheduledTick(covered, stair.getBlock())
                && !PhaseExtra.powered(coveredExtra));
        assertTrue("element gating: exposed output face emits 15",
            ElementBlocks.getSignal(stair, level, covered, Direction.NORTH,
                PhaseExtra.withPowered(coveredExtra, true)) == 15);

        // 红石线连接同样按"线 → 宿主"的方向与露出判定：
        // 输入侧（北，覆盖）不连；输出侧（南，露出）连。
        level.setBlockAndUpdate(covered.north(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(covered.north().below(), Blocks.STONE.defaultBlockState());
        // 红石块只作"孤立线补十字"的锚点：有东西向连接时原版不再给南北补 SIDE，
        // 这样读到 SOUTH=NONE 才能证明是被覆盖的宿主面不连，而不是孤立线补齐。
        level.setBlockAndUpdate(covered.north().east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        BlockPlaceContext northContext = liquidphase$context(level, Blocks.REDSTONE_WIRE,
            covered.north(), Direction.UP);
        BlockState northWire = Blocks.REDSTONE_WIRE.getStateForPlacement(northContext);
        assertTrue("element gating: wire placement produced a state", northWire != null);
        if (northWire != null) {
            net.minecraft.world.level.block.state.properties.RedstoneSide side =
                northWire.getValue(BlockStateProperties.SOUTH_REDSTONE);
            assertTrue("element gating: wire does not connect through a covered host face"
                    + " (got " + side + ", exposedNorth="
                    + RedstoneBlocks.exposed(stair, Direction.NORTH) + ")",
                side == net.minecraft.world.level.block.state.properties.RedstoneSide.NONE);
        }
        level.setBlockAndUpdate(covered.south().below(), Blocks.STONE.defaultBlockState());
        BlockPlaceContext southContext = liquidphase$context(level, Blocks.REDSTONE_WIRE,
            covered.south(), Direction.UP);
        BlockState southWire = Blocks.REDSTONE_WIRE.getStateForPlacement(southContext);
        assertTrue("element gating: wire placement produced a state", southWire != null);
        if (southWire != null) {
            assertTrue("element gating: wire connects to an exposed host face",
                southWire.getValue(BlockStateProperties.NORTH_REDSTONE)
                    != net.minecraft.world.level.block.state.properties.RedstoneSide.NONE);
        }

        // 2) 输入面露出（facing=SOUTH）→ 正常输入；输出面（NORTH）被覆盖 → 0。
        BlockPos open = pos.offset(4, 0, 0);
        level.setBlockAndUpdate(open, stair);
        level.setBlockAndUpdate(open.south(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertTrue("element gating: repeater on an exposed-input stair is placed",
            ElementBlocks.place(level, open, stair, PhaseExtra.Kind.REPEATER, Direction.SOUTH));
        int openExtra = PhaseExtras.get(level, open);
        ElementBlocks.tick(level, open, stair, openExtra);
        int openPowered = PhaseExtras.get(level, open);
        assertTrue("element gating: exposed input face powers the repeater",
            PhaseExtra.powered(openPowered));
        assertTrue("element gating: covered output face emits 0",
            ElementBlocks.getSignal(stair, level, open, Direction.SOUTH, openPowered) == 0);

        // 3) 侦测器观察面被覆盖 → 不排计划刻；观察面露出 → 排 2 tick。
        BlockPos observerCovered = pos.offset(6, 0, 0);
        level.setBlockAndUpdate(observerCovered, stair);
        assertTrue("element gating: observer on a covered-observation stair is placed",
            ElementBlocks.place(level, observerCovered, stair, PhaseExtra.Kind.OBSERVER, Direction.NORTH));
        ElementBlocks.onUpdateShape(level, observerCovered, stair,
            PhaseExtras.get(level, observerCovered), Direction.NORTH);
        assertTrue("element gating: covered observation face does not schedule the observer tick",
            !level.getBlockTicks().hasScheduledTick(observerCovered, stair.getBlock()));

        BlockPos observerOpen = pos.offset(8, 0, 0);
        level.setBlockAndUpdate(observerOpen, stair);
        assertTrue("element gating: observer on an exposed-observation stair is placed",
            ElementBlocks.place(level, observerOpen, stair, PhaseExtra.Kind.OBSERVER, Direction.SOUTH));
        ElementBlocks.onUpdateShape(level, observerOpen, stair,
            PhaseExtras.get(level, observerOpen), Direction.SOUTH);
        assertTrue("element gating: exposed observation face schedules the observer tick",
            level.getBlockTicks().hasScheduledTick(observerOpen, stair.getBlock()));

        // 4) 比较器侧向输入：被覆盖的北侧红石块不参与 alternate；露出的南侧参与。
        BlockPos comparator = pos.offset(10, 0, 0);
        level.setBlockAndUpdate(comparator, stair);
        assertTrue("element gating: comparator on the stair is placed",
            ElementBlocks.place(level, comparator, stair, PhaseExtra.Kind.COMPARATOR, Direction.WEST));
        int comparatorExtra = PhaseExtra.withMode(PhaseExtras.get(level, comparator), true);
        PhaseExtras.set(level, comparator, comparatorExtra);
        level.setBlockAndUpdate(comparator.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(comparator.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertTrue("element gating: comparator ignores the alternate signal on a covered side",
            ElementBlocks.comparatorOutputForTest(level, comparator, stair, comparatorExtra) == 15);
        level.setBlockAndUpdate(comparator.north(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(comparator.south(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertTrue("element gating: comparator reads the alternate signal on an exposed side",
            ElementBlocks.comparatorOutputForTest(level, comparator, stair, comparatorExtra) == 0);

        // 5) 中继器锁存：侧向元件在覆盖面不锁定，在露出面锁定。
        BlockPos lock = pos.offset(12, 0, 0);
        level.setBlockAndUpdate(lock, stair);
        assertTrue("element gating: repeater for the lock test is placed",
            ElementBlocks.place(level, lock, stair, PhaseExtra.Kind.REPEATER, Direction.WEST));
        int lockExtra = PhaseExtras.get(level, lock);
        PhaseExtras.set(level, lock.north(),
            PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.NORTH, 0, false, true));
        assertTrue("element gating: a side diode on a covered face does not lock the repeater",
            !ElementBlocks.isLocked(level, lock, stair, lockExtra));
        PhaseExtras.set(level, lock.north(), PhaseExtra.EMPTY);
        PhaseExtras.set(level, lock.south(),
            PhaseExtra.element(PhaseExtra.Kind.REPEATER, Direction.SOUTH, 0, false, true));
        assertTrue("element gating: a side diode on an exposed face locks the repeater",
            ElementBlocks.isLocked(level, lock, stair, lockExtra));

        // 清理旁路表条目（方块本身由 clearArea 统一清）。
        for (BlockPos cell : new BlockPos[]{covered, covered.north(), open, open.south(),
            observerCovered, observerOpen, comparator, comparator.west(), comparator.north(),
            lock, lock.north(), lock.south()}) {
            PhaseExtras.set(level, cell, PhaseExtra.EMPTY);
        }
    }

    /** 元件子模型/贴图的产物断言：只含要求的部位，且侦测器没有整面。 */
    private static void checkElementAssets() {
        String repeater = liquidphase$resource(
            "/assets/liquidphase/models/block/element_repeater_1tick_on.json");
        assertTrue("element repeater model exists", repeater != null);
        if (repeater != null) {
            assertTrue("element repeater renders no 2px smooth-stone base",
                !repeater.contains("smooth_stone") && !repeater.contains("#slab"));
            assertTrue("element repeater renders no lock bar",
                !repeater.contains("#lock") && !repeater.contains("bedrock"));
            assertTrue("element repeater renders a lit rod and the red bar",
                repeater.contains("#lit") && repeater.contains("element_repeater_bar_on"));
            assertTrue("element repeater geometry carries no cullface",
                !repeater.contains("\"cullface\""));
            int bars = liquidphase$countFaceTexture(repeater, "#bar");
            assertTrue("element repeater red bar is split into 16 pixel cells (got " + bars + ")",
                bars == 16);
        }
        String comparator = liquidphase$resource(
            "/assets/liquidphase/models/block/element_comparator_on_subtract.json");
        assertTrue("element comparator model exists", comparator != null);
        if (comparator != null) {
            assertTrue("element comparator renders no smooth-stone base or lock bar",
                !comparator.contains("smooth_stone") && !comparator.contains("#slab")
                    && !comparator.contains("#lock") && !comparator.contains("bedrock"));
            assertTrue("element comparator renders rods and the red bar",
                comparator.contains("#lit") && comparator.contains("element_comparator_bar_on"));
            assertTrue("element comparator red bar is split into 16 pixel cells (got "
                    + liquidphase$countFaceTexture(comparator, "#bar") + ")",
                liquidphase$countFaceTexture(comparator, "#bar") == 16);
        }
        for (String name : new String[]{"element_repeater_bar", "element_repeater_bar_on",
            "element_comparator_bar", "element_comparator_bar_on", "element_observer_front",
            "element_observer_port", "element_observer_port_on"}) {
            assertTrue("element texture " + name + " is shipped",
                liquidphase$exists("/assets/liquidphase/textures/block/" + name + ".png"));
        }
        // 侦测器 6 个朝向 × on/off 全部预烘焙：每个只有"正面五官"和"背面输出口"两块平面，
        // 平面在对应方块边界外侧 0.01（不会被宿主侧脸挡住）。
        String[][] facings = {
            {"", "north", "south"}, {"_south", "south", "north"},
            {"_east", "east", "west"}, {"_west", "west", "east"},
            {"_up", "up", "down"}, {"_down", "down", "up"},
        };
        for (String[] facing : facings) {
            for (boolean powered : new boolean[]{false, true}) {
                String name = "element_observer" + facing[0] + (powered ? "_on" : "");
                String json = liquidphase$resource("/assets/liquidphase/models/block/" + name + ".json");
                assertTrue("observer model " + name + " exists", json != null);
                if (json == null) {
                    continue;
                }
                assertTrue("observer model " + name + " has no full side/top face",
                    !json.contains("observer_side") && !json.contains("observer_top"));
                assertTrue("observer model " + name + " renders a front and a back plane",
                    liquidphase$countFaceTexture(json, "#front") == 1
                        && liquidphase$countFaceTexture(json, "#back") == 1);
                assertTrue("observer model " + name + " front plane faces " + facing[1],
                    liquidphase$observerPlane(json, 0, facing[1], "#front"));
                assertTrue("observer model " + name + " back plane faces " + facing[2],
                    liquidphase$observerPlane(json, 1, facing[2], "#back"));
            }
        }
    }

    /** {@return JSON 第 index 个元素是否为"只含 face 一个面、位于该边界外侧 0.01、贴图给定"} */
    private static boolean liquidphase$observerPlane(String json, int index, String face, String texture) {
        try {
            com.google.gson.JsonArray elements = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject().getAsJsonArray("elements");
            if (elements.size() != 2) {
                return false;
            }
            com.google.gson.JsonObject element = elements.get(index).getAsJsonObject();
            com.google.gson.JsonObject faces = element.getAsJsonObject("faces");
            if (faces.size() != 1 || !faces.has(face)) {
                return false;
            }
            com.google.gson.JsonElement value = faces.getAsJsonObject(face).get("texture");
            if (value == null || !texture.equals(value.getAsString())) {
                return false;
            }
            double[] from = liquidphase$vec(element.getAsJsonArray("from"));
            double[] to = liquidphase$vec(element.getAsJsonArray("to"));
            return switch (face) {
                case "north" -> from[2] == -0.01 && to[2] == -0.01;
                case "south" -> from[2] == 16.01 && to[2] == 16.01;
                case "west" -> from[0] == -0.01 && to[0] == -0.01;
                case "east" -> from[0] == 16.01 && to[0] == 16.01;
                case "up" -> from[1] == 16.01 && to[1] == 16.01;
                case "down" -> from[1] == -0.01 && to[1] == -0.01;
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static double[] liquidphase$vec(com.google.gson.JsonArray array) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    /** {@return JSON 文本里 face.texture 等于给定值的面数} */
    private static int liquidphase$countFaceTexture(String json, String texture) {
        int count = 0;
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            com.google.gson.JsonArray elements = root.getAsJsonObject().getAsJsonArray("elements");
            for (com.google.gson.JsonElement element : elements) {
                com.google.gson.JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                for (String key : faces.keySet()) {
                    com.google.gson.JsonElement value = faces.getAsJsonObject(key).get("texture");
                    if (value != null && texture.equals(value.getAsString())) {
                        count++;
                    }
                }
            }
        } catch (RuntimeException e) {
            return -1;
        }
        return count;
    }

    private static void checkPistonPhaseBehavior(ServerLevel level, BlockPos base) {
        // 自检跑在 SERVER_STARTED：测试区块此时可能还不是 entity-ticking，
        // addFreshEntity 成功但实体段不可见（1.21.10 实测），弹出断言会假失败。
        // 强制加载测试区块（1.21.10/11 均在 x=2..3、z=0..1 附近），跑完解除。
        int cx0 = base.getX() >> 4;
        int cz0 = base.getZ() >> 4;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                level.setChunkForced(cx0 + dx, cz0 + dz, true);
            }
        }
        try {
            checkPistonPhaseBehavior0(level, base);
        } finally {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    level.setChunkForced(cx0 + dx, cz0 + dz, false);
                }
            }
        }
    }

    private static void checkPistonPhaseBehavior0(ServerLevel level, BlockPos base) {
        // (1) 黑曜石相 push reaction = BLOCK（推动/粘性拉回/黏液链全部失败）。
        BlockState obsidianFilled = PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.OBSIDIAN);
        //? if 26.3 {
        /*assertTrue("obsidian phase pushes as IMMOVEABLE",
            obsidianFilled.getPistonPushReaction() == net.minecraft.world.level.material.PushReaction.IMMOVEABLE);
         *///?} else {
        assertTrue("obsidian phase pushes as BLOCK",
            obsidianFilled.getPistonPushReaction() == net.minecraft.world.level.material.PushReaction.BLOCK);
        //?}

        // (2) 推动含冰宿主：冰相弹出（无物品），宿主以无相状态被推走。
        BlockPos p1 = base.offset(46, 1, 0);
        BlockPos host1 = p1.relative(Direction.EAST);
        level.setBlock(p1.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host1.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host1.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p1.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p1, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host1, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        boolean moved1 = liquidphase$triggerPiston(level, p1, 0, 0);
        assertTrue("piston pushes an ice-filled host", moved1);
        assertTrue("pushed host leaves an extension at its origin",
            level.getBlockState(host1).is(Blocks.MOVING_PISTON));
        net.minecraft.world.level.block.entity.BlockEntity be1 = level.getBlockEntity(host1.relative(Direction.EAST));
        assertTrue("pushed host travels without its phase",
            be1 instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity pm1
                && pm1.getMovedState().getBlock() == Blocks.OAK_STAIRS
                && PhaseHelper.getPhase(pm1.getMovedState()) == Phase.NONE);
        // 掉落口径走纯函数对账：自检跑在 SERVER_STARTED，部分版本（1.21.10 实测）的
        // 测试区块还不是 entity-ticking，AABB/实体管理器查询看不到刚生成的掉落物。
        assertTrue("ice phase has no drop item",
            org.liquidphase.phase.PhaseDrops.droppedItem(Phase.ICE, PhaseExtra.EMPTY).isEmpty());
        liquidphase$cleanupPistonArena(level, p1, 3);

        // (3) 推动含熔岩宿主（液体物品功能开启 = 默认配置）：原格掉落熔岩物品。
        BlockPos p2 = base.offset(46, 1, 4);
        BlockPos host2 = p2.relative(Direction.EAST);
        level.setBlock(p2.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host2.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host2.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p2.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p2, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host2, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.LAVA), Block.UPDATE_ALL);
        assertTrue("piston pushes a lava-filled host",
            liquidphase$triggerPiston(level, p2, 0, 0));
        assertTrue("lava phase pops as a liquid item with feature 3 on",
            org.liquidphase.LiquidPhaseConfig.get().liquidInteraction
                ? org.liquidphase.phase.PhaseDrops.droppedItem(Phase.LAVA, PhaseExtra.EMPTY)
                    .is(net.minecraft.world.item.Items.LAVA_BUCKET)
                : org.liquidphase.phase.PhaseDrops.droppedItem(Phase.LAVA, PhaseExtra.EMPTY).isEmpty());
        assertTrue("pushed lava host leaves no phased host at the origin",
            PhaseHelper.getPhase(level.getBlockState(host2)) == Phase.NONE);
        liquidphase$cleanupPistonArena(level, p2, 3);

        // (4) 推动含红石相宿主：原格掉红石粉、条目清空。
        BlockPos p3 = base.offset(46, 1, 8);
        BlockPos host3 = p3.relative(Direction.EAST);
        level.setBlock(p3.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host3.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host3.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p3.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p3, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host3, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, host3, PhaseExtra.redstone(7));
        assertTrue("piston pushes a redstone-phase host",
            liquidphase$triggerPiston(level, p3, 0, 0));
        assertTrue("redstone phase pops as dust",
            org.liquidphase.phase.PhaseDrops.droppedItem(Phase.NONE, PhaseExtra.redstone(7))
                .is(net.minecraft.world.item.Items.REDSTONE));
        assertTrue("redstone entry cleared at the pushed origin",
            PhaseExtras.get(level, host3) == PhaseExtra.EMPTY);
        liquidphase$cleanupPistonArena(level, p3, 3);

        // (5) 粘性活塞拉回含冰宿主：任意填充相实体化留在原位，宿主无相被拉走。
        // 缩回走 moveBlocks 直驱：triggerEvent 的缩回分支要求活塞头位置有 PistonMovingBlockEntity，
        // 自检场地摆不出完整生命周期；moveBlocks 本身覆盖同一套移动/清格逻辑。
        BlockPos p4 = base.offset(46, 1, 12);
        BlockPos head4 = p4.relative(Direction.EAST);
        BlockPos host4 = p4.relative(Direction.EAST, 2);
        level.setBlock(p4.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(head4.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(host4.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p4, Blocks.STICKY_PISTON.defaultBlockState()
            .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED, true), Block.UPDATE_ALL);
        level.setBlock(head4, Blocks.PISTON_HEAD.defaultBlockState()
            .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, net.minecraft.world.level.block.state.properties.PistonType.STICKY), Block.UPDATE_ALL);
        level.setBlock(host4, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        assertTrue("sticky piston pulls an ice-filled host",
            liquidphase$moveBlocks(level, p4, Direction.EAST, false));
        assertTrue("pulled host leaves its ice phase materialized in place",
            level.getBlockState(host4).is(Blocks.ICE));
        net.minecraft.world.level.block.entity.BlockEntity be4 = level.getBlockEntity(head4);
        assertTrue("pulled host travels without its phase",
            be4 instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity pm4
                && pm4.getMovedState().getBlock() == Blocks.OAK_STAIRS
                && PhaseHelper.getPhase(pm4.getMovedState()) == Phase.NONE);
        liquidphase$cleanupPistonArena(level, p4, 3);

        // (6) 破坏列表摧毁含冰/红石的 DESTROY 反应宿主：相弹出（冰无物品、红石掉红石粉）。
        // 不用黑曜石：黑曜石相一律推不动（BLOCK），根本进不了破坏列表——那是 (1) 的口径。
        BlockPos p5 = base.offset(46, 1, 16);
        BlockPos host5 = p5.relative(Direction.EAST);
        level.setBlock(p5.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p5.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p5, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host5, PhaseHelper.withPhase(Blocks.HANGING_ROOTS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        assertTrue("piston destroys a destroy-reaction host",
            liquidphase$triggerPiston(level, p5, 0, 0));
        assertTrue("destroyed host cell is taken over by the extension",
            level.getBlockState(host5).is(Blocks.MOVING_PISTON));
        assertTrue("ice phase has no item from the destroy list",
            org.liquidphase.phase.PhaseDrops.droppedItem(Phase.ICE, PhaseExtra.EMPTY).isEmpty());
        liquidphase$cleanupPistonArena(level, p5, 3);

        // (6b) 破坏列表摧毁含红石条目的宿主：掉红石粉。
        level.setBlock(p5.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p5.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p5, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host5, Blocks.HANGING_ROOTS.defaultBlockState(), Block.UPDATE_ALL);
        PhaseExtras.set(level, host5, PhaseExtra.redstone(5));
        assertTrue("piston destroys a redstone-entry host",
            liquidphase$triggerPiston(level, p5, 0, 0));
        assertTrue("redstone entry pops as dust from the destroy list",
            org.liquidphase.phase.PhaseDrops.droppedItem(Phase.NONE, PhaseExtra.redstone(5))
                .is(net.minecraft.world.item.Items.REDSTONE));
        assertTrue("destroyed host's entry is cleared",
            PhaseExtras.get(level, host5) == PhaseExtra.EMPTY);
        liquidphase$cleanupPistonArena(level, p5, 3);

        // (7) 反向：黑曜石相宿主推不动（推动直接失败、方块原位不动、无掉落）。
        BlockPos p6 = base.offset(46, 1, 20);
        BlockPos host6 = p6.relative(Direction.EAST);
        level.setBlock(p6.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p6.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p6, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(host6, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.OBSIDIAN), Block.UPDATE_ALL);
        assertTrue("piston cannot push an obsidian-filled host",
            !liquidphase$triggerPiston(level, p6, 0, 0));
        assertTrue("obsidian-filled host stays in place",
            PhaseHelper.getPhase(level.getBlockState(host6)) == Phase.OBSIDIAN
                && level.getBlockState(host6).getBlock() == Blocks.OAK_STAIRS);
        assertTrue("obsidian phase would drop obsidian if popped (mapping sanity)",
            org.liquidphase.phase.PhaseDrops.droppedItem(Phase.OBSIDIAN, PhaseExtra.EMPTY)
                .is(net.minecraft.world.item.Items.OBSIDIAN));
        liquidphase$cleanupPistonArena(level, p6, 3);
    }

    /** 直驱活塞 block event（i=0 伸、i=1 缩；伸需要邻位电源，缩不需要）。 */
    private static boolean liquidphase$triggerPiston(ServerLevel level, BlockPos pistonPos, int id, int param) {
        return ((org.liquidphase.mixin.PistonBaseBlockInvoker) (Object) net.minecraft.world.level.block.Blocks.PISTON)
            .liquidphase$triggerEvent(level.getBlockState(pistonPos), level, pistonPos, id, param);
    }

    /** 直驱移动逻辑（{@code extending=false} 即粘性缩回），不依赖活塞头方块实体。 */
    private static boolean liquidphase$moveBlocks(ServerLevel level, BlockPos pistonPos, Direction direction, boolean extending) {
        return ((org.liquidphase.mixin.PistonBaseBlockInvoker) (Object) net.minecraft.world.level.block.Blocks.PISTON)
            .liquidphase$moveBlocks(level, pistonPos, direction, extending);
    }

    /** 清理活塞测试场（3×3 范围方块 + 残余掉落物 + 移塞方块实体残留）。 */
    private static void liquidphase$cleanupPistonArena(ServerLevel level, BlockPos pistonPos, int radius) {
        for (int dx = -1; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(pistonPos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        double r2 = (radius + 2.0) * (radius + 2.0);
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item
                && item.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pistonPos)) <= r2) {
                item.discard();
            }
        }
    }


    // ------------------------------------------------------------------
    // 组装初始状态与结算（0.1.22）：初始状态按"是不是重新放置"区分——
    //   新放置（红石粉点宿主、改写层写入线状态）：初始功率 0（单独放置红石粉的初始状态）；
    //   吸收（宿主落进线格/下落落进线格）：那格的红石线本来就在，初始功率沿用原线。
    //   以初始状态落地后一律立即按邻居重算出真实功率（onPlace 镜像原版红石线）。
    // ------------------------------------------------------------------

    /**
     * 红石相组装的初始状态与即时结算：
     * <ol>
     *   <li>红石粉点宿主（新放置）：无电源时初始/重算功率都是 0，来电源后重算为 15，
     *       电源已在旁边时落地即 15；</li>
     *   <li>宿主放进无电源线格（吸收）：初始沿用原线的 0，重算后仍 0；</li>
     *   <li>宿主放进通电线格但电源位于宿主遮蔽的北面：初始沿用原线的 15，落地重算
     *       跳过遮蔽面 → 0（不重算状态就错了）；</li>
     *   <li>宿主放进通电线格且电源暴露：重算结果与原线一致（15）。</li>
     * </ol>
     */
    private static void checkRedstonePhaseInitialState(ServerLevel level, BlockPos base) {
        // (1) 红石粉点楼梯（无电源）→ 功率 0；来电源 → 15；撤电源 → 0。
        BlockPos stairs = base.offset(36, 1, 0);
        level.setBlock(stairs.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, stairs, Direction.UP);
        BlockState phased = level.getBlockState(stairs);
        assertTrue("dust on host: redstone phase written",
            RedstoneBlocks.hasRedstone(level, stairs));
        assertTrue("dust on host: initial power is 0 like a standalone wire (got "
                + RedstoneBlocks.getPower(level, stairs) + ")",
            RedstoneBlocks.getPower(level, stairs) == 0);
        level.setBlock(stairs.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("dust on host: recalculated to 15 when a source arrives (got "
                + RedstoneBlocks.getPower(level, stairs) + ")",
            RedstoneBlocks.getPower(level, stairs) == 15);
        level.setBlock(stairs.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("dust on host: power drops to 0 when the source leaves (got "
                + RedstoneBlocks.getPower(level, stairs) + ")",
            RedstoneBlocks.getPower(level, stairs) == 0);
        PhaseExtras.set(level, stairs, PhaseExtra.EMPTY);
        level.setBlock(stairs, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (1b) 电源已在旁边时点粉：落地重算 → 15，不会停在初始 0。
        BlockPos stairs2 = base.offset(39, 1, 0);
        level.setBlock(stairs2.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs2.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs2, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, stairs2, Direction.UP);
        assertTrue("dust on host next to a source: power settles to 15 immediately (got "
                + RedstoneBlocks.getPower(level, stairs2) + ")",
            RedstoneBlocks.getPower(level, stairs2) == 15);
        PhaseExtras.set(level, stairs2, PhaseExtra.EMPTY);
        level.setBlock(stairs2, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs2.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairs2.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (2) 楼梯放进无电源线格（吸收）：初始沿用原线的 0，重算后仍 0。
        BlockPos wireTile = base.offset(37, 1, 0);
        level.setBlock(wireTile.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wireTile, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(2): wire starts unpowered",
            level.getBlockState(wireTile).getValue(BlockStateProperties.POWER) == 0);
        liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, wireTile.below(), Direction.UP);
        BlockState hostInWire = level.getBlockState(wireTile);
        assertTrue("host into unpowered wire: redstone phase stays at power 0 (got "
                + RedstoneBlocks.getPower(level, wireTile) + ")",
            hostInWire.is(Blocks.OAK_STAIRS) && RedstoneBlocks.hasRedstone(level, wireTile)
                && RedstoneBlocks.getPower(level, wireTile) == 0);
        PhaseExtras.set(level, wireTile, PhaseExtra.EMPTY);
        level.setBlock(wireTile, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wireTile.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (3) 楼梯放进通电线格，电源在楼梯遮蔽的北面（楼梯默认朝北）：初始沿用原线的
        // 15，落地重算跳过遮蔽面 → 0——组合方块以初始状态落地后本来就该重算，
        // 不重算状态就错了。
        BlockPos coveredWire = base.offset(38, 1, 0);
        level.setBlock(coveredWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coveredWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coveredWire.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(3): wire is powered from the north (got "
                + level.getBlockState(coveredWire).getValue(BlockStateProperties.POWER) + ")",
            level.getBlockState(coveredWire).getValue(BlockStateProperties.POWER) == 15);
        liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, coveredWire.below(), Direction.UP);
        BlockState coveredHost = level.getBlockState(coveredWire);
        assertTrue("host into powered wire with the source on a covered face: assembly "
                        + "recalculates to 0 (got " + RedstoneBlocks.getPower(level, coveredWire) + ")",
            coveredHost.is(Blocks.OAK_STAIRS) && RedstoneBlocks.hasRedstone(level, coveredWire)
                && RedstoneBlocks.getPower(level, coveredWire) == 0);
        PhaseExtras.set(level, coveredWire, PhaseExtra.EMPTY);
        level.setBlock(coveredWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coveredWire.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(coveredWire.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 第 1 层：无玩家，直接走 BlockItem.place
    // ------------------------------------------------------------------

    private static void checkPlaceIntoWire(ServerLevel level, BlockPos base) {
        // (1) 原版落点算到线格：对着线下面支撑它的方块 ↩ 的顶面放
        BlockPos wirePos = base.offset(6, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(1): wire present at target tile",
            level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE));
        int wirePower = level.getBlockState(wirePos).getValue(BlockStateProperties.POWER);
        assertTrue("scene(1): wire powered (got " + wirePower + ")", wirePower == 15);

        BlockState placed = liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP);
        BlockState after = level.getBlockState(wirePos);
        assertTrue("scene(1): placement pipeline accepted the wire tile (returned " + placed + ")", placed != null);
        assertTrue("scene(1): wire tile became the host (got " + after.getBlock() + ")", after.is(Blocks.OAK_STAIRS));
        assertTrue("scene(1): host carries the redstone phase (got "
                + PhaseExtra.describe(PhaseExtras.get(level, wirePos)) + ")",
            RedstoneBlocks.hasRedstone(level, wirePos));
        assertTrue("scene(1): host carried the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(level, wirePos) + ")",
            RedstoneBlocks.getPower(level, wirePos) == wirePower);

        PhaseExtras.set(level, wirePos, PhaseExtra.EMPTY);
        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (3) 非相容宿主不享受这个口子
        BlockPos stoneWire = base.offset(10, 1, 0);
        level.setBlock(stoneWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stoneWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState stonePlaced = liquidphase$placeBlockInto(level, Blocks.STONE, stoneWire.below(), Direction.UP);
        assertTrue("scene(3): non-host still cannot replace a wire (returned " + stonePlaced + ")",
            stonePlaced == null);
        assertTrue("scene(3): wire survived the rejected placement",
            level.getBlockState(stoneWire).is(Blocks.REDSTONE_WIRE));
        level.setBlock(stoneWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (4) 普通放置没被影响
        BlockPos dustPos = base.offset(11, 1, 0);
        level.setBlock(dustPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState dustPlaced = liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, dustPos.below(), Direction.UP);
        assertTrue("scene(4): redstone dust still places on a plain block (returned " + dustPlaced + ")",
            dustPlaced != null && dustPlaced.is(Blocks.REDSTONE_WIRE));
        level.setBlock(dustPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(dustPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 第 2 层：真实玩家入口（FakePlayer → ServerPlayerGameMode.useItemOn）
    // ------------------------------------------------------------------

    /**
     * 客户端右键在服务端最终落到 {@code ServerPlayerGameMode.useItemOn}，这里走同一条路。
     * <p>
     * 覆盖三种真实姿势：点线下面方块、点线旁方块朝线的侧面、点线上方方块底面。
     * 这条链路会先经过 {@code BlockState.useItemOn → useWithoutItem}，如果被方块交互吃掉，
     * 放置根本不会发生——这一层能测出来，直接调 {@code BlockItem.place} 测不出来。
     */
    private static void checkPlaceIntoWireByPlayer(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);

        // (1) 点线下面的方块
        assertTrue("player scene(1): host lands in the wire tile", liquidphase$playerGesture(level, player,
            base.offset(7, 1, 0).below(), Direction.UP, base.offset(7, 1, 0), Blocks.OAK_STAIRS));
        assertTrue("player scene(1): keeps 15 power",
            RedstoneBlocks.getPower(level, base.offset(7, 1, 0)) == 15);
        liquidphase$clearGesture(level, base.offset(7, 1, 0), base.offset(7, 1, 0).below());

        // (2) 点线旁方块朝线的侧面
        BlockPos sideWire = base.offset(8, 1, 0);
        BlockPos sideGround = sideWire.east();
        assertTrue("player scene(2): host lands in the wire tile", liquidphase$playerGesture(level, player,
            sideGround, Direction.WEST, sideWire, Blocks.OAK_STAIRS));
        assertTrue("player scene(2): keeps 15 power",
            RedstoneBlocks.getPower(level, sideWire) == 15);
        liquidphase$clearGesture(level, sideWire, sideGround);

        // (3) 点线上方方块底面
        BlockPos upWire = base.offset(9, 1, 0);
        BlockPos upGround = upWire.above();
        assertTrue("player scene(3): host lands in the wire tile", liquidphase$playerGesture(level, player,
            upGround, Direction.DOWN, upWire, Blocks.OAK_STAIRS));
        liquidphase$clearGesture(level, upWire, upGround);

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------
    // 脚手架堆叠：物品自己搜索落点（updatePlacementContext）的那一类
    // ------------------------------------------------------------------

    /**
     * {@code ScaffoldingBlockItem.updatePlacementContext} 会自己沿方向搜索
     * "能放"的格子，搜索时问的是 {@code BlockState.canBeReplaced(context)}。
     * 只在 {@code BlockItem.place} 入口上做手脚时，红石线格会被它跳过、方块落到别处——
     * 这条断言就是防这个回归。
     */
    private static void checkScaffoldingStacking(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);
        BlockPos wirePos = base.offset(12, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        // 线格上方立一段脚手架柱（堆叠场景），玩家点柱顶继续往上堆
        level.setBlock(wirePos.above(), Blocks.SCAFFOLDING.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos columnTop = wirePos.above();

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Blocks.SCAFFOLDING));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(columnTop), Direction.UP, columnTop, false);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(),
            net.minecraft.world.InteractionHand.MAIN_HAND, hit);

        // 脚手架堆叠会往柱子上面放；关键是不能把线格本身搞坏，且线格的相逻辑不受影响
        assertTrue("scaffolding stack: wire tile untouched while stacking the column",
            level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE));

        // 反向：脚手架直接堆进线格（把柱底换掉）——这条走 canBeReplaced 搜索
        level.setBlock(wirePos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scaffolding stack: can replace the wire tile when the item is a host",
            level.getBlockState(wirePos).canBeReplaced(liquidphase$context(level, Blocks.SCAFFOLDING,
                wirePos.below(), Direction.UP)));

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /**
     * 红石线自身的"可替换"语义要保持精确：只对相容宿主物品放行，
     * 对石头/红石粉等其它物品仍然是不可替换。
     */
    private static void checkWireStaysUnreplaceable(ServerLevel level, BlockPos base) {
        BlockPos wirePos = base.offset(14, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState wire = level.getBlockState(wirePos);

        assertTrue("wire state itself is still not replaceable", !wire.canBeReplaced());

        assertTrue("plain placement context refuses the wire tile (stone)",
            !wire.canBeReplaced(liquidphase$context(level, Blocks.STONE, wirePos.below(), Direction.UP)));
        assertTrue("plain placement context refuses the wire tile (dust)",
            !wire.canBeReplaced(liquidphase$context(level, Blocks.REDSTONE_WIRE, wirePos.below(), Direction.UP)));
        assertTrue("host placement context accepts the wire tile",
            wire.canBeReplaced(liquidphase$context(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP)));

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 下落方块（FallingBlockEntity）落位
    // ------------------------------------------------------------------

    /**
     * 下落方块落位不经过 {@code BlockItem.place}：{@code FallingBlockEntity.tick()}
     * 自己调 {@code landing.canBeReplaced(new DirectionalPlaceContext(..., ItemStack.EMPTY, ...))}
     * 再直接 {@code setBlock}。这条断言覆盖两个方向：
     * <ul>
     *   <li>相容宿主（钟乳石）能落进线格，并带上原线功率；</li>
     *   <li>非宿主（沙子）保持原版行为，落在线上会弹成物品、线格不变。</li>
     * </ul>
     */
    private static void checkFallingBlockIntoWire(ServerLevel level, BlockPos base) {
        // ---- (1) 相容宿主下落：应落进线格并带红石相 ----
        BlockPos hostWire = base.offset(16, 1, 0);
        level.setBlock(hostWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int hostPower = level.getBlockState(hostWire).getValue(BlockStateProperties.POWER);

        BlockState dripstone = Blocks.POINTED_DRIPSTONE.defaultBlockState();
        net.minecraft.world.entity.item.FallingBlockEntity hostEntity =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, hostWire.above(3), dripstone);
        // 直接把它放到线格正上方，避免等它自己掉（自检不推进 tick）
        hostEntity.setPos(hostWire.getX() + 0.5, hostWire.getY() + 0.02, hostWire.getZ() + 0.5);
        hostEntity.setOnGround(true);
        hostEntity.tick();

        BlockState hostAfter = level.getBlockState(hostWire);
        assertTrue("falling host: landed in the wire tile (got " + hostAfter.getBlock() + ")",
            hostAfter.is(Blocks.POINTED_DRIPSTONE));
        assertTrue("falling host: carried the redstone phase (got "
                + PhaseExtra.describe(PhaseExtras.get(level, hostWire)) + ")",
            RedstoneBlocks.hasRedstone(level, hostWire));
        assertTrue("falling host: power followed the wire (wire=" + hostPower + ", got "
                + RedstoneBlocks.getPower(level, hostWire) + ")",
            RedstoneBlocks.getPower(level, hostWire) == hostPower);

        hostEntity.discard();
        PhaseExtras.set(level, hostWire, PhaseExtra.EMPTY);
        level.setBlock(hostWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (2) 非宿主下落（沙子）：保持原版，红石线不被覆盖 ----
        BlockPos sandWire = base.offset(17, 1, 0);
        level.setBlock(sandWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sandWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);

        net.minecraft.world.entity.item.FallingBlockEntity sand =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, sandWire.above(3),
                Blocks.SAND.defaultBlockState());
        sand.setPos(sandWire.getX() + 0.5, sandWire.getY() + 0.02, sandWire.getZ() + 0.5);
        sand.setOnGround(true);
        sand.tick();

        assertTrue("falling sand: wire tile not replaced (got " + level.getBlockState(sandWire).getBlock() + ")",
            level.getBlockState(sandWire).is(Blocks.REDSTONE_WIRE));

        sand.discard();
        level.setBlock(sandWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sandWire.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (3) 相容宿主落进无电源线格：红石相初始功率 0（不继承任何非 0 初值）----
        BlockPos plainWire = base.offset(19, 1, 0);
        level.setBlock(plainWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(plainWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);

        net.minecraft.world.entity.item.FallingBlockEntity host2 =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, plainWire.above(3), dripstone);
        host2.setPos(plainWire.getX() + 0.5, plainWire.getY() + 0.02, plainWire.getZ() + 0.5);
        host2.setOnGround(true);
        host2.tick();

        BlockState plainAfter = level.getBlockState(plainWire);
        assertTrue("falling host into unpowered wire: redstone phase at power 0 (got "
                + RedstoneBlocks.getPower(level, plainWire) + ")",
            plainAfter.is(Blocks.POINTED_DRIPSTONE) && RedstoneBlocks.hasRedstone(level, plainWire)
                && RedstoneBlocks.getPower(level, plainWire) == 0);

        host2.discard();
        PhaseExtras.set(level, plainWire, PhaseExtra.EMPTY);
        level.setBlock(plainWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(plainWire.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(plainWire.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static net.minecraft.server.level.ServerPlayer liquidphase$fakePlayer(ServerLevel level) {
        return net.fabricmc.fabric.api.entity.FakePlayer.get(level, new com.mojang.authlib.GameProfile(
            java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"), "[liquidphase-selftest]"));
    }

    /**
     * 一次真实右键：点 {@code clickPos} 的 {@code face} 面，期望宿主落进 {@code wirePos}。
     * <p>
     * 通电用"线格西侧的方块 + 红石块"保证不挡住落点，功率稳定为 15。
     */
    private static boolean liquidphase$playerGesture(ServerLevel level,
                                                    net.minecraft.server.level.ServerPlayer player,
                                                    BlockPos clickPos, Direction face,
                                                    BlockPos wirePos, Block block) {
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        if (!clickPos.equals(wirePos) && !clickPos.equals(wirePos.below())) {
            level.setBlock(clickPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        if (!level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE)) {
            LiquidPhase.LOGGER.warn("[selftest] gesture scene broke: wire missing at {}", wirePos);
            return false;
        }

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(block));
        Vec3 hitVec = new Vec3(clickPos.getX() + 0.5 + face.getStepX() * 0.5,
            clickPos.getY() + 0.5 + face.getStepY() * 0.5,
            clickPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clickPos, false);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(),
            net.minecraft.world.InteractionHand.MAIN_HAND, hit);

        BlockState after = level.getBlockState(wirePos);
        boolean ok = after.is(block) && RedstoneBlocks.hasRedstone(level, wirePos);
        if (!ok) {
            LiquidPhase.LOGGER.warn("[selftest] gesture failed at {}: click={} face={} -> {} / {}",
                wirePos, clickPos, face, after.getBlock(),
                PhaseExtra.describe(PhaseExtras.get(level, wirePos)));
        }
        return ok;
    }

    private static void liquidphase$clearGesture(ServerLevel level, BlockPos wirePos, BlockPos clickPos) {
        PhaseExtras.set(level, wirePos, PhaseExtra.EMPTY);
        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (!clickPos.equals(wirePos) && !clickPos.equals(wirePos.below())) {
            level.setBlock(clickPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static BlockState liquidphase$placeBlockInto(ServerLevel level, Block block,
                                                         BlockPos clickedPos, Direction face) {
        ItemStack stack = new ItemStack(block);
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        BlockPlaceContext context = liquidphase$context(level, block, clickedPos, face);
        net.minecraft.world.InteractionResult result = item.place(context);
        if (!result.consumesAction()) {
            return null;
        }
        BlockState state = level.getBlockState(context.getClickedPos());
        return state.isAir() ? null : state;
    }

    /** {@return 无玩家的标准放置上下文（落点由原版 {@link BlockPlaceContext} 自己算）} */
    private static BlockPlaceContext liquidphase$context(ServerLevel level, Block block,
                                                         BlockPos clickedPos, Direction face) {
        Vec3 hitVec = new Vec3(
            clickedPos.getX() + 0.5 + face.getStepX() * 0.5,
            clickedPos.getY() + 0.5 + face.getStepY() * 0.5,
            clickedPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clickedPos, false);
        return new BlockPlaceContext(level, null, net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(block), hit);
    }

    private static void checkRedstoneRenderParts() {
        // 全量：**每一个**被我们改写过的 blockstate 都必须同时满足
        //   (a) 带一条无条件的 `liquidphase:block/dynamic` part（方案 C：红石/元件几何
        //       由带坐标的动态模型按旁路表发射，不再按 filling 取值一档一档写 part）；
        //   (b) 宿主 part 不带 phase 条件（否则红石相下宿主消失）。
        // 只抽查 oak_stairs/oak_leaves/glass_pane 曾经放过了整批漏登记的方块（0.1.12 的根因）。
        String raw = liquidphase$resource("/phase-blocks.ids");
        assertTrue("shipped phase-block list is present", raw != null);
        java.util.List<String> noDynamic = new java.util.ArrayList<>();
        java.util.List<String> badHost = new java.util.ArrayList<>();
        java.util.List<String> staticShell = new java.util.ArrayList<>();
        java.util.List<String> fullCubeOverlayReferenced = new java.util.ArrayList<>();
        int scanned = 0;
        if (raw != null) {
            for (String line : raw.split("\\R")) {
                String id = line.trim();
                if (id.isEmpty() || id.startsWith("#")) {
                    continue;
                }
                String json = liquidphase$blockState(id.startsWith("minecraft:") ? id : "minecraft:" + id);
                if (json == null) {
                    noDynamic.add(id + " (no blockstate)");
                    continue;
                }
                scanned++;
                if (!liquidphase$hasUnconditionalDynamicPart(json)) {
                    noDynamic.add(id);
                }
                // 0.1.18 起**所有**宿主（含整格/混合）的宿主 part 都不带相条件：
                // 相只作为内缩叠加层，宿主始终可见（与含熔岩一致）。
                if (!hostPartsCarryNoPhaseCondition(json)) {
                    badHost.add(id);
                }
                // 方案 C：红石外壳不再作为静态 part 写进 blockstate，改由动态模型按坐标发射。
                // 静态 part 还在的话，无相时也会画外壳（动态模型再画一遍）。
                if (json.contains("liquidphase:block/redstone_phase_")) {
                    staticShell.add(id);
                }
                // 0.1.18 起不再使用整格相材质（与宿主模型共面会 z-fighting）。
                if (json.contains("liquidphase:block/phase_obsidian_full")
                    || json.contains("liquidphase:block/phase_ice_full")) {
                    fullCubeOverlayReferenced.add(id);
                }
            }
        }
        assertTrue("scanned more than just a handful of blockstates (" + scanned + " scanned)", scanned > 10);
        assertTrue("every phased blockstate has one unconditional dynamic part (broken: " + noDynamic + ")",
            noDynamic.isEmpty());
        assertTrue("no blockstate carries a static redstone shell part any more (broken: " + staticShell + ")",
            staticShell.isEmpty());
        assertTrue("no host part carries a phase condition — every host stays visible (broken: " + badHost + ")",
            badHost.isEmpty());

        // 16 档外壳模型/贴图仍是动态模型的子资源：缺一个，对应功率档位就画不出外壳。
        // 模型按功率着色（0 档暗、15 档亮），贴图各自一份。
        java.util.List<String> missingShell = new java.util.ArrayList<>();
        for (int power = 0; power < 16; power++) {
            String modelPath = "/assets/liquidphase/models/block/redstone_phase_" + power + ".json";
            String texturePath = "/assets/liquidphase/textures/block/redstone_phase_" + power + ".png";
            if (!liquidphase$exists(modelPath)) {
                missingShell.add("model:" + power);
            }
            if (!liquidphase$exists(texturePath)) {
                missingShell.add("texture:" + power);
            }
        }
        assertTrue("all 16 redstone shell models and textures are shipped (missing: " + missingShell + ")",
            missingShell.isEmpty());
        String dimShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_0.json");
        String brightShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_15.json");
        assertTrue("redstone shell has one model per power level",
            dimShell != null && brightShell != null && !dimShell.equals(brightShell));
        assertTrue("redstone shell power models use distinct textures",
            dimShell != null && brightShell != null
                && dimShell.contains("redstone_phase_0") && brightShell.contains("redstone_phase_15"));

        // 整格宿主（树叶/铜格栅/红树根）单独确认：宿主 part 常驻、动态模型无条件追加。
        String leaves = liquidphase$resource("/assets/minecraft/blockstates/oak_leaves.json");
        assertTrue("leaves blockstate resource is present", leaves != null);
        if (leaves != null) {
            assertTrue("leaves host part still renders next to the dynamic part",
                liquidphase$hasUnconditionalDynamicPart(leaves)
                    && leaves.contains("minecraft:block/oak_leaves"));
        }

        // Transparent hosts previously got no shell at all (only the 41 redstone hosts did),
        // which is exactly why "some transparent blocks still show no redstone phase".
        String pane = liquidphase$resource("/assets/minecraft/blockstates/glass_pane.json");
        assertTrue("glass pane blockstate resource is present", pane != null);
        if (pane != null) {
            assertTrue("transparent host (glass pane) gets the dynamic geometry too",
                liquidphase$hasUnconditionalDynamicPart(pane)
                    && !pane.contains("liquidphase:block/redstone_phase_"));
        }

        // ---- regression for the 0.1.11 bugs ----
        // 整格宿主的相用**内缩**覆盖层（0.1.18 起）：宿主模型是 0..16 整格，
        // 若相也用 0..16 会与之共面 z-fighting，含相时树叶与相随机互相遮挡。
        // 内缩后树叶稳定在外层、相从贴图缝隙透出，与含熔岩的观感一致。
        String poplar = liquidphase$resource("/assets/minecraft/blockstates/orange_poplar_leaves.json");
        if (poplar != null) {
            assertTrue("full-cube host (orange poplar leaves) uses the inset overlay, not the full one",
                poplar.contains("liquidphase:block/phase_ice")
                    && !poplar.contains("phase_ice_full"));
            assertTrue("full-cube host keeps its own model visible (no phase gating on the host part)",
                poplar.contains("minecraft:block/orange_poplar_leaves")
                    && hostPartsCarryNoPhaseCondition(poplar));
        }
        // 树叶同样：宿主 part 无条件、相用内缩覆盖层。
        assertTrue("leaves host part is unconditional and the overlay is inset",
            leaves != null
                && leaves.contains("\"model\":\"minecraft:block/oak_leaves\"")
                && !leaves.contains("phase_ice_full"));
        // 反向：部分覆盖的宿主必须留在内缩路径（整格替换会把它的空隙吃掉）。
        String stairsRes = liquidphase$resource("/assets/minecraft/blockstates/oak_stairs.json");
        assertTrue("partial host (oak stairs) keeps the inset overlay",
            stairsRes != null && !stairsRes.contains("phase_ice_full"));
        // (a) The inset phase overlay / outer shell must NOT carry cullface: vanilla cullface
        // compares "does the neighbour cover the block-boundary face" and misfires against
        // partial neighbours (slabs/stairs), eating faces the overlay does cover.
        for (String model : new String[]{
            "phase_obsidian", "phase_ice", "phase_frosted_0", "phase_frosted_1",
            "phase_frosted_2", "phase_frosted_3", "redstone_phase_0", "redstone_phase_15"}) {
            String modelJson = liquidphase$resource("/assets/liquidphase/models/block/" + model + ".json");
            assertTrue("model " + model + " exists", modelJson != null);
            if (modelJson != null) {
                assertTrue("model " + model + " must not use cullface (inset/oversized geometry)",
                    !modelJson.contains("\"cullface\""));
            }
        }
        // 0.1.18 起所有宿主的相都用内缩覆盖层（含整格宿主），因此 _full 系列模型
        // 不再被任何 blockstate 引用；仍留在资源里只为兼容旧存档/旧包引用。
        // 这里改为断言"产物里不出现 phase_*_full"——一旦回退到整格替换，
        // 树叶/铜格栅会与宿主共面 z-fighting，必须由断言拦住。
        assertTrue("no generated blockstate uses the full-cube phase material any more",
            fullCubeOverlayReferenced.isEmpty());

        // (a2) 宿主 part 不能再带"覆盖全部 8 个相"的恒真条件：原版
        // KeyValueCondition.instantiate 会为它打 `Condition ... is always true` 警告，
        // 1.21.10+ 每个方块状态各一条，加载日志被刷屏。
        String stairs = liquidphase$resource("/assets/minecraft/blockstates/oak_stairs.json");
        assertTrue("host parts carry no phase condition at all (no always-true warnings)",
            stairs != null && !stairs.contains("\"filling\":\"none|lava|obsidian"));

        // (b) The render layer fix lives in a mixin (the BlockRenderLayerMap table is rebuilt
        // by the resource reload and would silently lose an init-time putBlock). Assert the
        // mixin config is shipped and wired.
        //? if !(26.3) {
        //# The mixin config must ship and the mixin class must be present; jar-level wiring is
        //# asserted by tools/verify_jars.py (the dev-server classpath copy of fabric.mod.json can
        //# lag behind a fresh processResources run, so don't assert on it here).
        String mixinCfg = liquidphase$resource("/liquidphase.layer.mixins.json");
        assertTrue("render-layer mixin config is shipped", mixinCfg != null);
        if (mixinCfg != null) {
            assertTrue("render-layer mixin is registered in the config",
                mixinCfg.contains("PhaseRenderLayerMixin"));
        }
        //?}

        checkPhasedRenderShape();
    }

    /**
     * 含相状态必须按 {@code RenderShape.MODEL} 渲染。
     * <p>
     * 区块构建器只在"该渲染方块模型"时才请求模型。`barrier` / `light` 与告示牌家族原生是
     * `INVISIBLE`；1.21.1 起的判据是 `== MODEL`，所以连 `ENTITYBLOCK_ANIMATED`
     * （1.19.4–1.21.1 的箱子/附魔神像等）也会被跳过。不修这一处，
     * 给它们写入相以后界面上什么都不显示（相的数据在，模型从没被请求过）。
     */
    private static void checkPhasedRenderShape() {
        // 1.19.4 / 1.20.1 的 barrier 不再是相容宿主（audit §4.2：这两个版本的 BarrierBlock
        // 没有 WATERLOGGED 属性，注入 PhaseCapable 会在默认方法里抛 IllegalArgumentException），
        // 改用同为 INVISIBLE 且全版本相容的 light 顶上；1.21.1+ 继续用 barrier
        // （它是 0.1.12 清单修复的回归样本）。
        //? if 1.19.4 || 1.20.1 {
        /*Block invisibleHost = Blocks.LIGHT;
        String hostName = "light";
         *///?} else {
        Block invisibleHost = Blocks.BARRIER;
        String hostName = "barrier";
        //?}
        BlockState phasedHost = PhaseHelper.withPhase(invisibleHost.defaultBlockState(), Phase.ICE);
        // 方案 C：状态里看不出有没有红石/元件，所以**所有**宿主状态一律按 MODEL 渲染
        // （否则 barrier/告示牌这类隐形宿主上的红石外壳永远不被请求）。这些宿主自己的
        // blockstate 模型只有 particle 贴图、没有 elements，不会凭空多出几何。
        assertTrue(hostName + " is a phase host", PhaseHelper.hasPhaseProperty(invisibleHost.defaultBlockState()));
        assertTrue(hostName + " always renders its model under scheme C (element presence lives in the side table)",
            invisibleHost.defaultBlockState().getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased " + hostName + " renders its model",
            phasedHost.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased solid-state " + hostName + " also renders",
            PhaseHelper.withPhase(invisibleHost.defaultBlockState(), Phase.OBSIDIAN).getRenderShape()
                == net.minecraft.world.level.block.RenderShape.MODEL);
        // 反向：非宿主方块的隐形语义不受影响（本 mod 只改拿到了 filling 属性的方块）。
        assertTrue("non-host invisible blocks keep their vanilla render shape",
            Blocks.STRUCTURE_VOID.defaultBlockState().getRenderShape()
                != net.minecraft.world.level.block.RenderShape.MODEL);

        // 全量扫描：每个宿主的默认态都必须能请求到方块模型。
        liquidphase$assertAllHostsRenderModels();
    }

    /**
     * 遍历**全部** phase 方块，对"原生不是 MODEL"的那些逐个断言：带相后 RenderShape 变成
     * MODEL，不带相时保持原值。本版本里原生就是 MODEL 的宿主跳过（原版自己已经会渲染模型）。
     * <p>
     * 用全量扫描而不是写死几个候选：告示牌/悬挂告示牌全家、箱子系列、`decorated_pot`、
     * `conduit`、`barrier`、`light` 等等在每个版本里清单都不一样，写死必然漏
     * （写死版本只覆盖了 6 个方块，而实际原生非 MODEL 的 phase 宿主远多于此）。
     */
    private static void liquidphase$assertAllHostsRenderModels() {
        int checked = 0;
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            BlockState plain = block.defaultBlockState();
            if (!PhaseHelper.hasPhaseProperty(plain)) {
                continue;
            }
            checked++;
            String name = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            if (plain.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL
                || PhaseHelper.withPhase(plain, Phase.ICE).getRenderShape()
                    != net.minecraft.world.level.block.RenderShape.MODEL) {
                broken.add(name);
            }
        }
        assertTrue("every host whose vanilla RenderShape is not MODEL renders its model "
                + "(" + checked + " checked, broken: " + broken + ")",
            broken.isEmpty());
        // 这条能确保扫描真的命中了东西（410+ 个宿主）。
        assertTrue("the render-shape scan actually inspected some hosts (" + checked + " checked)",
            checked > 0);
    }

    private static String liquidphase$resource(String path) {
        try (java.io.InputStream stream = LiquidPhase.class.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * {@return 该 blockstate 是否带一条**无条件**的 {@code liquidphase:block/dynamic} part}
     * <p>
     * 方案 C：红石外壳/元件几何由动态模型按坐标查旁路表发射。blockstate 里只追加一条
     * 不带 {@code when} 的 part，所有材料相与无相状态都会触发它；动态模型自己决定画不画。
     * 这里用 JSON 解析而不是字符串匹配：要确认 part 真的没有条件（带条件时只有部分状态会画）。
     */
    private static boolean liquidphase$hasUnconditionalDynamicPart(String json) {
        return liquidphase$hasUnconditionalPart(json, "multipart");
    }

    /** {@return 资源包片段是否带一条无条件的 dynamic part（片段顶层键是 {@code parts}} */
    private static boolean liquidphase$fragmentHasDynamicPart(String json) {
        return liquidphase$hasUnconditionalPart(json, "parts");
    }

    private static boolean liquidphase$hasUnconditionalPart(String json, String arrayName) {
        if (json == null) {
            return false;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return false;
            }
            com.google.gson.JsonElement parts = root.getAsJsonObject().get(arrayName);
            if (parts == null || !parts.isJsonArray()) {
                return false;
            }
            for (com.google.gson.JsonElement element : parts.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject part = element.getAsJsonObject();
                if (part.has("when")) {
                    continue;
                }
                com.google.gson.JsonElement apply = part.get("apply");
                if (apply != null && liquidphase$applyUsesDynamic(apply)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** {@return apply 对象/数组里是否引用 {@code liquidphase:block/dynamic}} */
    private static boolean liquidphase$applyUsesDynamic(com.google.gson.JsonElement apply) {
        if (apply.isJsonArray()) {
            for (com.google.gson.JsonElement element : apply.getAsJsonArray()) {
                if (element.isJsonObject() && liquidphase$applyUsesDynamic(element)) {
                    return true;
                }
            }
            return false;
        }
        if (!apply.isJsonObject()) {
            return false;
        }
        com.google.gson.JsonElement model = apply.getAsJsonObject().get("model");
        return model != null && model.isJsonPrimitive()
            && "liquidphase:block/dynamic".equals(model.getAsString());
    }

    /** {@return 随包资源是否存在（模型/贴图等二进制资源只判存在，不读内容）} */
    private static boolean liquidphase$exists(String path) {
        try (java.io.InputStream stream = LiquidPhase.class.getResourceAsStream(path)) {
            return stream != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * {@return 宿主 part 是否完全不带 phase 条件}
     * <p>
     * 普通宿主的覆盖层与外壳都是叠加层，宿主照常渲染，所以不需要 phase 条件。
     * 一旦给宿主 part 补上"全部 8 个相"的条件，原版 {@code KeyValueCondition}
     * 会判定它恒真并打 {@code Condition ... is always true} 警告（每个状态一条）。
     */
    private static boolean hostPartsCarryNoPhaseCondition(String json) {
        return !json.contains("\"filling\":\"none|lava|obsidian");
    }

    // ------------------------------------------------------------------
    // 渲染剔除 + 双端同步
    // ------------------------------------------------------------------

    /**
     * 相邻面剔除：含相状态**不**需要任何干预。
     * <p>
     * 相覆盖层与红石外壳都不带 {@code cullface}（几何内缩/外扩，不在方块边界上），
     * 所以它们根本不会进入 {@code Block.shouldRenderFace} 的剔除判定；宿主的带
     * {@code cullface} 面属于宿主自己的事，原版怎么剔就怎么剔。
     * <p>
     * 这里断言的是"我们生成的模型里确实没有 {@code cullface}"这条前提——
     * 一旦有人给覆盖层/外壳加回 {@code cullface}，相才会开始被错误剔除。
     * 曾经为此加过 {@code PhaseSkipRenderingMixin}（强制含相状态不参与剔除），
     * 那只会让含相楼梯/栅栏对本该剔除的邻居面也照画，属于改坏原版语义，已删除。
     */
    private static void checkPhaseRenderCulling(ServerLevel level, BlockPos base) {
        for (String model : new String[]{
            "phase_obsidian", "phase_ice", "phase_frosted_0", "phase_frosted_3",
            "redstone_phase_0", "redstone_phase_15"}) {
            String json = liquidphase$resource("/assets/liquidphase/models/block/" + model + ".json");
            assertTrue("phase model " + model + " exists", json != null);
            if (json != null) {
                assertTrue("phase model " + model + " carries no cullface (so vanilla never culls it)",
                    !json.contains("\"cullface\""));
            }
        }
    }

    /**
     * 双端同步：客户端侧功能必须等服务器握手。
     * <p>
     * 三种情形：单人（集成服务器）、连有 mod 的服务器、连原版服务器。
     * 这里只验证"默认不支持"这一条最关键的语义——只要它成立，
     * 原版服务器就不会被客户端单方面预测的状态覆盖。
     */
    private static void checkSyncState() {
        // 玩家进服那一刻（握手包到达前）必须是 false。
        assertTrue("client phase sync starts disabled until the server handshake arrives",
            !org.liquidphase.sync.PhaseSyncState.serverSupportsPhases());
        int bits = org.liquidphase.sync.PhaseHandshake.bits(true, true);
        assertTrue("handshake mask advertises phases and liquid interaction (got " + bits + ")",
            org.liquidphase.sync.PhaseHandshake.hasPhase(bits)
                && org.liquidphase.sync.PhaseHandshake.hasLiquidInteraction(bits));
        assertTrue("phase-disabled config advertises nothing",
            !org.liquidphase.sync.PhaseHandshake.hasPhase(
                org.liquidphase.sync.PhaseHandshake.bits(false, false)));
    }

    // ------------------------------------------------------------------
    // 相容宿主覆盖面（docs/host-coverage-audit.md §4 的逐项回归，0.1.13）
    // ------------------------------------------------------------------

    /**
     * 固相"整格碰撞"（决策点 D7）对**全部**相容宿主成立（audit §4.4，0.1.13 修）。
     * 0.1.12 及以前挂在块级 {@code BlockBehaviour.getCollisionShape}，被
     * {@code CrossCollisionBlock}（栅栏/玻璃板/铁栏杆/铜栏杆）、{@code WallBlock}、
     * {@code WallHangingSignBlock}、{@code ScaffoldingBlock}、{@code SculkShriekerBlock}、
     * {@code BigDripleafBlock} 自己覆写且不转调 super，这 66–88 个宿主的固相碰撞仍是宿主形状。
     * 现在挂在状态级 {@code BlockStateBase.getCollisionShape} 的 RETURN 上：
     * 三参覆盖实体碰撞主路径，两参覆盖 {@code Cache.collisionShape} 缓存路径
     * （{@code isCollisionShapeFullBlock} 走它）。
     */
    private static void checkSolidPhaseCollision(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        // 代表宿主逐个断言：形状表宿主（栅栏/墙/墙悬挂告示牌）、自建形状宿主（脚手架/大垂滴叶）、
        // 以及基类分发的普通宿主（楼梯/台阶）。既查三参（实体碰撞），也查两参（缓存路径）。
        // 组合口径（0.1.22）：固相碰撞 = 宿主碰撞 ∪ 整格——栅栏/墙延伸到方块外的碰撞柱必须保留。
        for (Block block : new Block[]{Blocks.OAK_FENCE, Blocks.BRICK_WALL, Blocks.OAK_WALL_HANGING_SIGN,
                Blocks.SCAFFOLDING, Blocks.BIG_DRIPLEAF, Blocks.OAK_STAIRS, Blocks.STONE_SLAB}) {
            BlockState vanilla = block.defaultBlockState();
            BlockState solid = PhaseHelper.withPhase(vanilla, Phase.OBSIDIAN);
            assertTrue("solid phase collision = host ∪ cube via the 3-arg query (" + block + ")",
                liquidphase$sameShape(solid.getCollisionShape(level, pos, CollisionContext.empty()),
                    Shapes.or(vanilla.getCollisionShape(level, pos, CollisionContext.empty()), Shapes.block())));
            assertTrue("solid phase collision = host ∪ cube via the cached 2-arg query (" + block + ")",
                liquidphase$sameShape(solid.getCollisionShape(level, pos),
                    Shapes.or(vanilla.getCollisionShape(level, pos), Shapes.block())));
        }
        // 栅栏回归断言（0.1.21 及以前整格覆盖丢了 1.0～1.5 的碰撞柱）。
        BlockState fenceSolid = PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), Phase.OBSIDIAN);
        assertTrue("fence keeps its above-cube collision post under the solid phase (3-arg)",
            fenceSolid.getCollisionShape(level, pos, CollisionContext.empty()).max(Direction.Axis.Y) > 1.0);
        assertTrue("fence keeps its above-cube collision post under the solid phase (2-arg)",
            fenceSolid.getCollisionShape(level, pos).max(Direction.Axis.Y) > 1.0);
        // 全量：每个相容宿主的固相碰撞都必须 = 宿主碰撞 ∪ 整格（新增宿主自动被扫进来）。
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            BlockState vanilla = block.defaultBlockState();
            BlockState solid = PhaseHelper.withPhase(vanilla, Phase.OBSIDIAN);
            boolean ok3 = liquidphase$sameShape(solid.getCollisionShape(level, pos, CollisionContext.empty()),
                Shapes.or(vanilla.getCollisionShape(level, pos, CollisionContext.empty()), Shapes.block()));
            boolean ok2 = liquidphase$sameShape(solid.getCollisionShape(level, pos),
                Shapes.or(vanilla.getCollisionShape(level, pos), Shapes.block()));
            if (!ok3 || !ok2) {
                broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));
            }
        }
        assertTrue("every phase host combines host collision with the solid phase cube (broken: " + broken + ")",
            broken.isEmpty());
        // 反向：红石相/熔岩相不加碰撞，组合碰撞 = 宿主形状（三参、缓存两参都查）。
        // 方案 C 起红石相不在状态里，这里用 NONE（无相）与 LAVA 代表"非固相"。
        for (Phase phase : new Phase[]{Phase.NONE, Phase.LAVA}) {
            BlockState fencePhased = PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), phase);
            BlockState fenceVanilla = Blocks.OAK_FENCE.defaultBlockState();
            assertTrue("non-solid phase keeps the host collision shape (3-arg, " + phase + ")",
                liquidphase$sameShape(fencePhased.getCollisionShape(level, pos, CollisionContext.empty()),
                    fenceVanilla.getCollisionShape(level, pos, CollisionContext.empty())));
            assertTrue("non-solid phase keeps the host collision shape (2-arg, " + phase + ")",
                liquidphase$sameShape(fencePhased.getCollisionShape(level, pos),
                    fenceVanilla.getCollisionShape(level, pos)));
        }
    }

    /** {@return 两个形状逐点相同（NOT_SAME 区域为空，与 {@code Block.isShapeFullBlock} 同判法）} */
    private static boolean liquidphase$sameShape(VoxelShape a, VoxelShape b) {
        return !Shapes.joinIsNotEmpty(a, b, BooleanOp.NOT_SAME);
    }

    /**
     * 破坏保留对**全部**相容宿主成立，包括带 offsetType 的 4–5 个
     * （audit §4.5，0.1.13 修：0.1.12 及以前 {@code !state.hasOffsetFunction()} 把
     * hanging_roots / mangrove_propagule / pointed_dripstone / small_dripleaf
     * （26.3 另有 sulfur_spike）排除在外，破坏它们相直接消失）。
     */
    private static void checkBreakRetainEverywhere(ServerLevel level, BlockPos base) {
        // 对照：普通宿主（楼梯）破坏 → 原地留冰。
        BlockPos pos = base.offset(20, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        level.destroyBlock(pos, false);
        assertTrue("breaking a phased stairs leaves the ice behind (got "
                + level.getBlockState(pos).getBlock() + ")",
            level.getBlockState(pos).is(Blocks.ICE));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // 带 offsetType 的宿主逐个验证：破坏后必须留下相实体化的方块。
        java.util.List<String> broken = new java.util.ArrayList<>();
        int checked = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())
                || !block.defaultBlockState().hasOffsetFunction()) {
                continue;
            }
            checked++;
            BlockState solid = PhaseHelper.withPhase(block.defaultBlockState(), Phase.ICE);
            level.setBlock(pos, solid, Block.UPDATE_ALL);
            level.destroyBlock(pos, false);
            if (!level.getBlockState(pos).is(Blocks.ICE)) {
                broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block))
                    + " (got " + level.getBlockState(pos).getBlock() + ")");
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        assertTrue("offset-type hosts still retain their phase on break ("
                + checked + " checked, broken: " + broken + ")",
            broken.isEmpty());
        // 反向：这条扫描必须真的命中了 offset 宿主（每版本 4–5 个），否则断言落空。
        assertTrue("the offset-host scan actually inspected some hosts (" + checked + " checked)",
            checked >= 4);
    }

    /**
     * 台阶/蜡烛/营火（1.21.10+ 另有干化恶魂）自己覆写 {@code canPlaceLiquid}/{@code placeLiquid}，
     * 遮住了 {@code PhaseCapable} 的熔岩分支（audit §4.3，0.1.13 修）：
     * "相邻熔岩按形状灌入"必须重新成立。这里直接调宿主的遮蔽方法——
     * 与 {@code FlowingFluid.canHoldFluid}/{@code spreadTo} 同一条引擎路径。
     */
    private static void checkAdjacentLavaFilling(ServerLevel level, BlockPos base) {
        // (1) 台阶：canPlaceLiquid 对熔岩放行（0.1.12 及以前恒拒 → 引擎层面熔岩灌不进）。
        BlockPos slabPos = base.offset(22, 1, 0);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        level.setBlock(slabPos, slab, Block.UPDATE_ALL);
        assertTrue("slab accepts adjacent lava (canPlaceLiquid is no longer shadowed)",
            liquidphase$canPlaceLava((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB,
                level, slabPos, slab));
        assertTrue("slab placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(slabPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB)
                .placeLiquid(level, slabPos, slab, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(slabPos)));
        level.setBlock(slabPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (2) 蜡烛 / 营火：placeLiquid 遮蔽只处理水、对熔岩返回 false → 引擎每 tick 重试、永久灌不进。
        BlockPos candlePos = base.offset(23, 1, 0);
        BlockState candle = Blocks.CANDLE.defaultBlockState();
        level.setBlock(candlePos, candle, Block.UPDATE_ALL);
        assertTrue("candle placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(candlePos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.CANDLE)
                .placeLiquid(level, candlePos, candle, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(candlePos)));
        level.setBlock(candlePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos campfirePos = base.offset(24, 1, 0);
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
        level.setBlock(campfirePos, campfire, Block.UPDATE_ALL);
        assertTrue("campfire placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(campfirePos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.CAMPFIRE)
                .placeLiquid(level, campfirePos, campfire, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(campfirePos)));
        level.setBlock(campfirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        //? if 1.21.10 || 1.21.11 || 26.3 {
        /*BlockPos ghastPos = base.offset(25, 1, 0);
        BlockState ghast = Blocks.DRIED_GHAST.defaultBlockState();
        level.setBlock(ghastPos, ghast, Block.UPDATE_ALL);
        assertTrue("dried ghast placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(ghastPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.DRIED_GHAST)
                .placeLiquid(level, ghastPos, ghast, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(ghastPos)));
        level.setBlock(ghastPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
         *///?}

        // 反向（0.1.17 反转）：含水台阶是"现有填充相=水（可替换）"——熔岩替换水，
        // 台阶变为熔岩相且不再含水。水的流体容器路径对"写入水"仍只进空宿主。
        BlockPos wetSlabPos = base.offset(22, 1, 2);
        BlockState wetSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        level.setBlock(wetSlabPos, wetSlab, Block.UPDATE_ALL);
        assertTrue("waterlogged slab accepts lava (water is a replaceable filling; got phase "
                + PhaseHelper.getPhase(level.getBlockState(wetSlabPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB)
                .placeLiquid(level, wetSlabPos, level.getBlockState(wetSlabPos), Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(wetSlabPos))
                && !level.getBlockState(wetSlabPos)
                    .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        level.setBlock(wetSlabPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** {@return 宿主能否接受熔岩（签名按版本分支，与 {@code FlowingFluid.canHoldFluid} 同参）} */
    private static boolean liquidphase$canPlaceLava(net.minecraft.world.level.block.LiquidBlockContainer container,
                                                    net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                    BlockState state) {
        //? if 1.19.4 || 1.20.1 {
        /*return container.canPlaceLiquid(level, pos, state, Fluids.LAVA);
         *///?} elif 1.21.1 {
        /*return container.canPlaceLiquid(null, level, pos, state, Fluids.LAVA);
         *///?} else {
        return container.canPlaceLiquid(null, level, pos, state, Fluids.LAVA);
        //?}
    }

    /**
     * 放进熔岩源格吸收相（0.2.3 起走统一路径）：熔岩源原版可替换 →
     * {@code BlockItem.place} 正常落格，{@code BlockItemMixin} 的 placeBlock 捕获读出熔岩源格、
     * RETURN 注入写熔岩相（原 PlacementMixin 的 getStateForPlacement 改写已删除）。
     * 场景按真实放置姿势摆：点熔岩源格旁边石头的侧面（clickedPos 落到熔岩源格上）。
     */
    private static void checkPlacementAbsorption(ServerLevel level, BlockPos base) {
        BlockPos lavaPos = base.offset(26, 1, 0);
        BlockPos stonePos = lavaPos.west();
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);
        for (Block block : new Block[]{Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN}) {
            level.setBlock(lavaPos, Fluids.LAVA.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
            level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            // 多面体宿主的 getStateForPlacement 要读玩家朝向，必须用真实玩家上下文
            // （无玩家上下文会在 Direction.orderedByNearest(null) 里 NPE）。
            Vec3 hitVec = new Vec3(stonePos.getX() + 1.5, stonePos.getY() + 0.5, stonePos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.EAST, stonePos, false);
            BlockPlaceContext context = new BlockPlaceContext(level, player,
                net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(block), hit);
            assertTrue("placement context targets the lava source tile (got " + context.getClickedPos() + ")",
                context.getClickedPos().equals(lavaPos));
            net.minecraft.world.InteractionResult result = ((BlockItem) block.asItem()).place(context);
            BlockState placed = level.getBlockState(lavaPos);
            assertTrue(block + " absorbs the lava phase when placed into a lava source (got "
                    + result + " / " + PhaseHelper.getPhase(placed) + ")",
                result.consumesAction() && placed.getBlock() == block
                    && PhaseHelper.getPhase(placed) == Phase.LAVA);
        }
        level.setBlock(lavaPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 熔岩桶右键按**原版倒水**的交互优先级（audit §4.7，0.1.13 修）：
     * 原版交互不消费右键的宿主（楼梯）→ 灌入；原版交互消费右键的宿主（活板门开关、
     * 光源方块调亮度）→ 原版行为优先、不灌入。0.1.12 及以前 {@code BucketUseMixin} 在
     * use/useItemOn 上强制灌入（连箱子/告示牌都灌），≤1.20.1 与 ≥1.21.1 的目标清单还不一致；
     * 0.1.13 删除该 mixin，让灌入完全走原版桶路径（useItemOn 通过 → useItem →
     * emptyContents → BucketItemMixin），与"用水桶点活板门只会开关它"的语义对齐。
     * 潜行右键（原版绕过方块交互）仍可灌入，发射器/放置吸收不受影响。
     */
    private static void checkBucketInteraction(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);

        // (1) 楼梯：原版不消费右键 → 熔岩灌入。useItemOn 返回 PASS 时，
        // 真实客户端会补发 UseItem 包（useItem → BucketItem.use），这里走同一条链。
        BlockPos stairPos = base.offset(18, 1, 0);
        level.setBlock(stairPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairPos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, stairPos);
        BlockHitResult stairHit = new BlockHitResult(Vec3.atCenterOf(stairPos), Direction.UP, stairPos, false);
        net.minecraft.world.InteractionResult interactionResult = player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, stairHit);
        if (!interactionResult.consumesAction()) {
            player.gameMode.useItem(player, level,
                player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
                net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        assertTrue("lava bucket pours into stairs (vanilla interaction does not consume the click; got "
                + PhaseHelper.getPhase(level.getBlockState(stairPos)) + ")",
            PhaseHelper.isLava(level.getBlockState(stairPos)));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(stairPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (2) 活板门：原版 useWithoutItem 消费右键（开关）→ 不灌入、方块被开关。
        BlockPos trapPos = base.offset(18, 1, 2);
        level.setBlock(trapPos, Blocks.OAK_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        boolean openBefore = level.getBlockState(trapPos)
            .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, trapPos);
        BlockHitResult trapHit = new BlockHitResult(Vec3.atCenterOf(trapPos), Direction.UP, trapPos, false);
        player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, trapHit);
        BlockState trapAfter = level.getBlockState(trapPos);
        assertTrue("lava bucket on a trapdoor toggles it instead of pouring (got phase "
                + PhaseHelper.getPhase(trapAfter) + ")",
            !PhaseHelper.isLava(trapAfter)
                && trapAfter.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN) != openBefore);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(trapPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (3) 光源方块：原版 useWithoutItem 消费右键（非管理员 → CONSUME）→ 不灌入、亮度不变。
        BlockPos lightPos = base.offset(18, 1, 4);
        level.setBlock(lightPos, Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, lightPos);
        BlockHitResult lightHit = new BlockHitResult(Vec3.atCenterOf(lightPos), Direction.UP, lightPos, false);
        player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, lightHit);
        BlockState lightAfter = level.getBlockState(lightPos);
        assertTrue("lava bucket on a light block keeps the vanilla interaction (got phase "
                + PhaseHelper.getPhase(lightAfter) + ")",
            !PhaseHelper.isLava(lightAfter));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(lightPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 把假玩家放到目标正上方、俯视它（桶的 use 路径按玩家视角射线取目标格）。 */
    private static void liquidphase$lookAt(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        // 1.21.10+ 的 Entity.moveTo 改名 snapTo（guardian 逐版本核对）。
        //? if 1.21.10 || 1.21.11 || 26.3 {
        player.snapTo(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5, 0.0F, 90.0F);
        //?} else {
        /*player.moveTo(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5, 0.0F, 90.0F);
         *///?}
    }

    /**
     * 发射器灌宿主 + 空桶保宿主（audit v2 缺口 A/B，0.1.15 修）：
     * 0.1.14 及以前发射器液体桶对宿主整桶弹射、空桶把含水宿主连方块一起毁。
     * 直接调 {@code LiquidInteractions.dispenseIntoHost} / {@code collectWithEmptyBucket}
     * （发射器行为类的共享核心，与真实触发链同参）。
     */
    private static void checkDispenserHostFilling(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(24, 1, 0);
        for (Block host : new Block[]{Blocks.OAK_FENCE, Blocks.OAK_LEAVES}) {
            // (A) 熔岩灌入宿主
            level.setBlock(pos, host.defaultBlockState(), Block.UPDATE_ALL);
            assertTrue("dispenser lava fills " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos)));
            // (A) 反向 1（0.1.17 反转）：熔岩相可替换 → 再灌熔岩幂等成功（状态不变）。
            assertTrue("dispenser lava idempotently re-fills the lava phase of " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos)));
            // (A) 反向 2（0.1.17 反转）：含水宿主接受熔岩——水是可替换填充相，
            // 熔岩替换水，宿主变熔岩相且不再含水。
            level.setBlock(pos, host.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
            assertTrue("dispenser lava replaces the water in waterlogged " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos))
                    && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            level.setBlock(pos, host.defaultBlockState(), Block.UPDATE_ALL);
            // (A) 水灌入宿主
            assertTrue("dispenser water waterlogs " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), false))
                    && level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            // (B) 空桶收含水宿主：只收水、保宿主
            ItemStack bucket = org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
                level, pos, level.getBlockState(pos));
            assertTrue("dispenser empty bucket scoops water from " + host + " and keeps the block",
                bucket != null && bucket.is(net.minecraft.world.item.Items.WATER_BUCKET)
                    && level.getBlockState(pos).is(host)
                    && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            // (B) 反向：干宿主无可收目标
            assertTrue("dispenser empty bucket ignores dry " + host,
                org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
                    level, pos, level.getBlockState(pos)) == null);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // (B) 熔岩相宿主：收相、保宿主
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), Phase.LAVA),
            Block.UPDATE_ALL);
        ItemStack lavaBucket = org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
            level, pos, level.getBlockState(pos));
        assertTrue("dispenser empty bucket scoops the lava phase and keeps the host",
            lavaBucket != null && lavaBucket.is(net.minecraft.world.item.Items.LAVA_BUCKET)
                && level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && !PhaseHelper.isLava(level.getBlockState(pos)));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 熔岩相重流接线（audit v2 缺口 C，0.1.15 修）：scheduleLavaFlowTick 此前是死代码，
     * 邻居变化后源不再重排流体 tick。这里用 hasScheduledTick 做同步判别：
     * 放置本身不排 tick，邻居更新后必须排上。
     */
    private static void checkLavaReflowWiring(ServerLevel level, BlockPos base) {
        BlockPos fencePos = base.offset(28, 1, 0);
        BlockPos leavesPos = base.offset(29, 1, 0);
        for (Block host : new Block[]{Blocks.OAK_FENCE, Blocks.OAK_LEAVES}) {
            BlockPos pos = host == Blocks.OAK_FENCE ? fencePos : leavesPos;
            // Fluids.LAVA 的 scheduled tick 会随存档持久化——历次自检/探针用过的坐标都可能带
            // 未到期的残留 tick。先清空该位置的流体 tick，判别才可复现。
            clearFluidTicks(level, pos);
            clearFluidTicks(level, pos.east());
            level.setBlock(pos, PhaseHelper.withPhase(host.defaultBlockState(), Phase.LAVA),
                Block.UPDATE_CLIENTS);
            // 放置后把 tick 再清一次：这样后面出现的 tick 只可能来自
            // "邻居变化 → handleNeighborChanged → scheduleLavaFlowTick"这条接线（缺口 C）。
            clearFluidTicks(level, pos);
            level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            assertTrue("lava phase " + host + " reschedules its flow tick on neighbour change",
                level.getFluidTicks().hasScheduledTick(pos, Fluids.LAVA));
            level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 非直接点击吸收（0.1.16 新增手势）：点**旁边**的方块让红石粉/冰"落进"相容宿主格——
     * 原版对不可替换的落点格直接 FAIL，这条路径此前从未被接管（用户实测缺口）。
     * 反向：落点格是空气时走原版放置（红石粉落成线、冰落成方块），宿主不受影响。
     */
    private static void checkIndirectPhaseAbsorption(ServerLevel level, BlockPos base) {
        // 注意坐标语义：32 是被点击的石头，33/34 是落点格；破坏保留会把含固相的方块
        // 实体化成相材料（冰/黑曜石），所以每个子场景结束时必须把材料方块一并清掉。
        BlockPos stonePos = base.offset(32, 1, 0);
        BlockPos targetA = base.offset(33, 1, 0);
        BlockPos targetB = base.offset(34, 1, 0);
        level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // (1) 红石粉落进栅栏格 → 红石相
        level.setBlock(targetA, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, stonePos, Direction.EAST);
        assertTrue("indirect dust lands into fence as redstone phase",
            RedstoneBlocks.hasRedstone(level, targetA));
        PhaseExtras.set(level, targetA, PhaseExtra.EMPTY);
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (2) 冰落进树叶格 → 冰相
        level.setBlock(targetA, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.ICE, stonePos, Direction.EAST);
        assertTrue("indirect ice lands into leaves as ice phase",
            PhaseHelper.getPhase(level.getBlockState(targetA)) == Phase.ICE);
        // 冰相破坏保留会把冰实体化，换用 targetB 做原版对照
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (3) 反向：落点格是空气 → 原版放置（红石粉落成线）
        level.setBlock(targetB.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, targetA, Direction.EAST);
        assertTrue("dust into air cell stays vanilla wire",
            level.getBlockState(targetB).is(Blocks.REDSTONE_WIRE));
        level.setBlock(targetB, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(targetB.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (4) 反向：非相材料（石头）落进栅栏格 → 原版 FAIL，栅栏不变
        level.setBlock(targetA, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.STONE, stonePos, Direction.EAST);
        assertTrue("stone item does not replace the host cell",
            level.getBlockState(targetA).is(Blocks.OAK_FENCE));
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 通用放置改写层（0.1.16）：任意来源经 {@code Level.setBlock} 向无相未含水宿主格
     * 写填充相材料（冰/黑曜石/红石线）→ 改写为宿主 + 填充相。同时断言排除口径：
     * 非材料方块、水、已有相的宿主都不改写。
     */
    private static void checkUniversalFillingPlacement(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(35, 1, 0);
        // (1) 冰写进栅栏格 → 栅栏 + 冰相
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: ice setBlock into fence becomes ice phase",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && PhaseHelper.getPhase(level.getBlockState(pos)) == Phase.ICE);
        // (2) 黑曜石写进含冰栅栏格 → 互斥，原版覆盖
        level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: obsidian setBlock replaces the phased host (mutex)",
            level.getBlockState(pos).is(Blocks.OBSIDIAN));
        // (3) 红石线写进栅栏格 → 栅栏 + 红石相（新放置口径：初始 0 并立即按邻居重算，
        // 场景无电源 → 0；来料状态自带的 POWER 不作为初始状态）。
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: wire setBlock into fence becomes redstone phase",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && RedstoneBlocks.hasRedstone(level, pos));
        assertTrue("universal: rewritten redstone phase recalculates from neighbours (got "
                + RedstoneBlocks.getPower(level, pos) + ")",
            RedstoneBlocks.getPower(level, pos) == 0);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        // (4) 水写进栅栏格 → 不在材料口径（走容器路径），原版覆盖
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, net.minecraft.world.level.material.Fluids.WATER.defaultFluidState().createLegacyBlock(),
            Block.UPDATE_ALL);
        assertTrue("universal: water setBlock is not converted (container path owns water)",
            level.getBlockState(pos).is(Blocks.WATER));
        // (5) 接受层：冰物品的放置上下文里栅栏"可替换"；石头物品不可；
        // 含水栅栏可替换（水=可替换填充相，0.1.17 反转）且改写后不再含水。
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPlaceContext iceCtx = liquidphase$context(level, Blocks.ICE, pos.west(), Direction.EAST);
        BlockPlaceContext stoneCtx = liquidphase$context(level, Blocks.STONE, pos.west(), Direction.EAST);
        assertTrue("acceptance: fence is replaceable for an ice item placement",
            level.getBlockState(pos).canBeReplaced(iceCtx));
        assertTrue("acceptance: fence is NOT replaceable for a stone item placement",
            !level.getBlockState(pos).canBeReplaced(stoneCtx));
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        assertTrue("acceptance: waterlogged fence is replaceable for an ice item placement "
                    + "(water is a replaceable filling)",
            level.getBlockState(pos).canBeReplaced(iceCtx));
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: ice setBlock into waterlogged fence becomes ice phase without water",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && PhaseHelper.getPhase(level.getBlockState(pos)) == Phase.ICE
                && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 吸收门控的**防漂移对账**（0.1.17）。可替换填充相规则只有唯一判定源
     * {@code PhaseHelper.canAcceptPhase}，但接线分四层，各自都能被后来人单独改坏：
     * <ol>
     *   <li>A 判定源：{@code PhaseHelper.canAcceptPhase}；</li>
     *   <li>B 接受层：{@code BlockState.canBeReplaced(BlockPlaceContext)}
     *       （{@code FillingReplaceMixin}）；</li>
     *   <li>C 改写层：{@code Level.setBlock(材料)}（{@code FillingPlacementMixin}）；</li>
     *   <li>D 交互层：{@code BlockItem.place} 落点吸收（{@code BlockItemMixin}）。</li>
     * </ol>
     * 用例：一座楼梯 × 现有填充相 {空/含水/熔岩/冰/黑曜石/红石} × 写入材料
     * {冰/黑曜石/红石粉} 全矩阵，A==B==C==D 且接受时终态一致（相写上、水清掉）；
     * 熔岩/水作为写入材料走容器路径（E），对 A 同样对账，水对任何含相宿主保持互斥。
     * 另有全量扫描：每个相容宿主的默认态（及含水态）上 A==B。
     */
    private static void checkAbsorptionGateConsistency(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(31, 1, 0);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Block hostBlock = Blocks.OAK_STAIRS;

        // 现有填充相 → 预期接受（空/含水/熔岩可替换；冰/黑曜石/红石不可替换）。
        // 红石相不占状态：这一行用"空宿主状态 + 旁路表红石条目"表示，由 setCurrent 统一摆放。
        Object[][] currents = {
            {"empty", hostBlock.defaultBlockState(), true},
            {"waterlogged", hostBlock.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), true},
            {"lava", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.LAVA), true},
            {"ice", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.ICE), false},
            {"obsidian", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.OBSIDIAN), false},
            {"redstone", hostBlock.defaultBlockState(), false},
        };
        Block[] materials = {Blocks.ICE, Blocks.OBSIDIAN, Blocks.REDSTONE_WIRE};

        for (Object[] current : currents) {
            String currentName = (String) current[0];
            BlockState currentState = (BlockState) current[1];
            boolean expected = (Boolean) current[2];

            for (Block material : materials) {
                boolean redstoneMaterial = material == Blocks.REDSTONE_WIRE;
                Phase incoming = redstoneMaterial
                    ? null : org.liquidphase.phase.PhaseAbsorption.phaseOfItem(material);
                String label = currentName + "->" + material;

                // 摆放"现有填充相"（红石用例同时写旁路表）。
                liquidphase$setCurrent(level, pos, currentState, currentName);

                // A 判定源：材料相走 canAcceptPhase；红石粉走 canAcceptExtra
                // （它写旁路表，不写 filling 取值）。
                boolean a = redstoneMaterial
                    ? PhaseHelper.canAcceptExtra(level, pos, currentState)
                    : PhaseHelper.canAcceptPhase(currentState, level, pos, incoming);
                assertTrue("gate A matches the replaceability rule for " + label, a == expected);

                // B 接受层：材料相的 canBeReplaced 与 A 一致；红石粉不走"可替换"路径
                // （它由 BlockItemMixin 的落点吸收写表），所以永远不放行上下文替换。
                // 现有条目是红石时跳过 B：红石条目不占状态，放置上下文的目标格会退到
                // 命中面之外的邻格，"B 与 A 对同一格"这个前提不成立；真实路径由 D 覆盖。
                BlockPlaceContext ctx = liquidphase$context(level, material, pos, Direction.DOWN);
                boolean b = currentState.canBeReplaced(ctx);
                if (redstoneMaterial) {
                    assertTrue("gate B: dust never makes the host replaceable for " + label, !b);
                } else if (!currentName.equals("redstone")) {
                    assertTrue("gate B (canBeReplaced) agrees with A for " + label, b == a);
                }

                // C 改写层：Level.setBlock(材料) 与 A 一致，接受时终态一致
                liquidphase$setCurrent(level, pos, currentState, currentName);
                level.setBlock(pos, material.defaultBlockState(), Block.UPDATE_ALL);
                BlockState afterC = level.getBlockState(pos);
                boolean c;
                if (redstoneMaterial) {
                    c = afterC.getBlock() == hostBlock && RedstoneBlocks.hasRedstone(level, pos);
                } else {
                    c = afterC.getBlock() == hostBlock && phaseMatches(afterC, incoming);
                }
                assertTrue("gate C (setBlock rewrite) agrees with A for " + label, c == a);
                if (a) {
                    assertTrue("gate C final state carries the incoming phase for " + label,
                        redstoneMaterial
                            ? RedstoneBlocks.hasRedstone(level, pos)
                                && !afterC.getValue(BlockStateProperties.WATERLOGGED)
                            : PhaseHelper.getPhase(afterC) == incoming
                                && !afterC.getValue(BlockStateProperties.WATERLOGGED));
                }

                // D 交互层：BlockItem.place 落点吸收与 A 一致
                liquidphase$setCurrent(level, pos, currentState, currentName);
                int extraBefore = PhaseExtras.get(level, pos);
                BlockState placedD = liquidphase$placeBlockInto(level, material, pos, Direction.DOWN);
                boolean d;
                if (redstoneMaterial) {
                    // 红石粉成功 = 宿主还在且旁路条目真的被这次放置改写（当前已是红石时不算）。
                    d = level.getBlockState(pos).getBlock() == hostBlock
                        && PhaseExtras.get(level, pos) != extraBefore;
                } else {
                    d = placedD != null && placedD.getBlock() == hostBlock
                        && phaseMatches(placedD, incoming);
                }
                assertTrue("gate D (BlockItem.place) agrees with A for " + label, d == a);
            }

            // E 容器路径（熔岩作为写入材料）：placeLiquid(LAVA) 与 A 一致
            liquidphase$setCurrent(level, pos, currentState, currentName);
            boolean aLava = PhaseHelper.canAcceptPhase(currentState, level, pos, Phase.LAVA);
            clearFluidTicks(level, pos);
            boolean e = ((net.minecraft.world.level.block.LiquidBlockContainer) hostBlock)
                .placeLiquid(level, pos, level.getBlockState(pos), Fluids.LAVA.getSource(false));
            assertTrue("gate E (container lava) agrees with A for " + currentName, e == aLava);
            if (aLava) {
                assertTrue("gate E fills the lava phase for " + currentName,
                    PhaseHelper.isLava(level.getBlockState(pos)));
            }
            clearFluidTicks(level, pos);

            // 水作为写入材料：互斥不变——只有空宿主接受水，任何含相宿主拒绝（0.1.17 决策）。
            liquidphase$setCurrent(level, pos, currentState, currentName);
            boolean waterAccepted = ((net.minecraft.world.level.block.LiquidBlockContainer) hostBlock)
                .placeLiquid(level, pos, level.getBlockState(pos), Fluids.WATER.getSource(false));
            assertTrue("water stays on the container path and only enters empty hosts for "
                    + currentName,
                waterAccepted == currentName.equals("empty"));
            if (waterAccepted) {
                assertTrue("water waterlogs the empty host", level.getBlockState(pos)
                    .getValue(BlockStateProperties.WATERLOGGED));
            }
            PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // 全量扫描：每个相容宿主的默认态与含水态上 A==B（新宿主自动被扫进来）。
        BlockPos probePos = base.offset(32, 1, 0);
        level.setBlock(probePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        java.util.List<String> broken = new java.util.ArrayList<>();
        int scanned = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            scanned++;
            BlockState def = block.defaultBlockState();
            BlockState[] variants = def.hasProperty(BlockStateProperties.WATERLOGGED)
                ? new BlockState[]{def, def.setValue(BlockStateProperties.WATERLOGGED, true)}
                : new BlockState[]{def};
            for (BlockState variant : variants) {
                boolean a = PhaseHelper.canAcceptPhase(variant, level, probePos, Phase.ICE);
                BlockPlaceContext ctx = liquidphase$context(level, Blocks.ICE, probePos, Direction.DOWN);
                boolean b = variant.canBeReplaced(ctx);
                if (a != b) {
                    broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block))
                        + " (A=" + a + ", B=" + b + ")");
                }
            }
        }
        assertTrue("every phase host agrees between canAcceptPhase and canBeReplaced ("
                + scanned + " hosts scanned, broken: " + broken + ")",
            broken.isEmpty() && scanned >= 300);
    }

    /**
     * 摆出对账矩阵里的"现有填充相"。
     * <p>
     * 红石相不占状态：用例名是 {@code redstone} 时写空宿主状态 + 旁路条目；
     * 其它用例直接写状态。每次先清旁路表，避免上一轮的红石条目污染当前用例。
     */
    private static void liquidphase$setCurrent(ServerLevel level, BlockPos pos, BlockState state, String name) {
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if ("redstone".equals(name)) {
            PhaseExtras.set(level, pos, PhaseExtra.redstone(15));
        }
    }

    /** {@return 状态的填充相是否等于给定材料对应的相} */
    private static boolean phaseMatches(BlockState state, Phase incoming) {
        return PhaseHelper.getPhase(state) == incoming;
    }

    /**
     * 资源包兼容合并（0.1.17）：片段完整性 + 合并核心对"仿第三方包 winner"的行为 +
     * 代理包装的基础行为。合并核心 {@code PhasePackMergeCore} 是纯 Gson 实现，
     * 服务端也能直接跑；服务端资源管理器只有 data 域拿不到 blockstate，
     * 所以 winner 用 mod 自带的仿第三方 fixtures
     * （{@code assets/liquidphase/packmerge_test/}，variants 形式、变体键与原版一致）。
     * 客户端注入点（{@code PhaseLoaderMixin}）的真实覆盖需要客户端资源重载，
     * 不在服务端自检范围（README 记录手测项）。
     */
    private static void checkPackMergeFragments(ServerLevel level, BlockPos base) {
        // (1) 全量：每个相容宿主都有片段，且片段带无条件的 dynamic part（与烘焙 blockstate 同判据）。
        // 方案 C 不再往片段里写 16 条红石外壳 part：外壳由动态模型按坐标发射。
        java.util.List<String> missing = new java.util.ArrayList<>();
        int fragments = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            if (!id.startsWith("minecraft:")) {
                continue;
            }
            String fragment = liquidphase$resource("/assets/liquidphase/phase_parts/"
                + id.substring("minecraft:".length()) + ".json");
            fragments++;
            if (!liquidphase$fragmentHasDynamicPart(fragment)
                || fragment.contains("liquidphase:block/redstone_phase_")) {
                missing.add(id);
            }
        }
        assertTrue("every phase host ships a pack-merge fragment with the dynamic part ("
                + fragments + " checked, missing: " + missing + ")",
            missing.isEmpty() && fragments >= 300);

        // (2) 标记扫描：烘焙定义引用 liquidphase → 判定为"自带相部件"，不会被二次合并。
        String baked = liquidphase$resource("/assets/minecraft/blockstates/stone_slab.json");
        assertTrue("baked stone_slab blockstate ships in the jar", baked != null);
        if (baked == null) {
            return;
        }
        assertTrue("referencesLiquidphase detects the baked definition",
            org.liquidphase.client.PhasePackMergeCore.referencesLiquidphase(
                com.google.gson.JsonParser.parseString(baked)));
        assertTrue("referencesLiquidphase ignores foreign definitions",
            !org.liquidphase.client.PhasePackMergeCore.referencesLiquidphase(
                com.google.gson.JsonParser.parseString(liquidphase$foreignFixture("stone_slab"))));

        // (3) 合并核心 × 仿第三方包（variants 形式）：混合宿主（台阶）按变体门控。
        JsonObject slabWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("stone_slab")).getAsJsonObject();
        JsonObject slabFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/stone_slab.json")).getAsJsonObject();
        JsonObject slabMerged = org.liquidphase.client.PhasePackMergeCore.merge(slabWinner, slabFragment);
        assertTrue("merge converts the variants winner into multipart", slabMerged != null);
        if (slabMerged == null) {
            return;
        }
        String slabJson = slabMerged.toString();
        // 0.1.18 起不再按变体门控宿主：所有宿主 part 一律保持渲染，相只用内缩覆盖层。
        assertTrue("merged slab keeps every host variant ungated (host always visible)",
            slabJson.contains("\"type\":\"double\"") && !slabJson.contains("\"type\":\"double\",\"filling\""));
        assertTrue("merged slab keeps the bottom/top variants ungated",
            slabJson.contains("\"type\":\"bottom\"") && !slabJson.contains("\"type\":\"bottom\",\"filling\""));
        assertTrue("merged slab appends the inset overlays and the dynamic part",
            slabJson.contains("liquidphase:block/phase_ice")
                && !slabJson.contains("phase_ice_full")
                && liquidphase$hasUnconditionalDynamicPart(slabJson)
                && !slabJson.contains("liquidphase:block/redstone_phase_"));

        // (4) 合并核心 × 仿第三方包：内缩宿主（楼梯）无 gate，宿主 part 原样。
        JsonObject stairsWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("oak_stairs")).getAsJsonObject();
        JsonObject stairsFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/oak_stairs.json")).getAsJsonObject();
        JsonObject stairsMerged = org.liquidphase.client.PhasePackMergeCore.merge(stairsWinner, stairsFragment);
        assertTrue("merge handles the inset host", stairsMerged != null);
        if (stairsMerged == null) {
            return;
        }
        String stairsJson = stairsMerged.toString();
        assertTrue("inset host parts stay ungated (no host-visibility gate)",
            !stairsJson.contains("\"filling\":\"none|lava|"));
        assertTrue("inset host keeps its variant keys after variants→multipart conversion",
            stairsJson.contains("\"facing\":\"east\"") && stairsJson.contains("\"half\":\"bottom\""));
        assertTrue("inset overlays and dynamic part are appended",
            stairsJson.contains("liquidphase:block/phase_obsidian")
                && liquidphase$hasUnconditionalDynamicPart(stairsJson)
                && !stairsJson.contains("liquidphase:block/redstone_phase_"));

        // (4b) 合并核心 × 整格宿主（树叶）：宿主 part 一律保持渲染，相用内缩覆盖层。
        // 原始树叶是无条件单一变体，第三方包常拆成 per-distance 变体；
        // 两种形态下宿主都必须可见、相都必须内缩（否则与 0..16 的树叶共面 z-fighting）。
        JsonObject leavesWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("oak_leaves")).getAsJsonObject();
        JsonObject leavesFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/oak_leaves.json")).getAsJsonObject();
        JsonObject leavesMerged = org.liquidphase.client.PhasePackMergeCore.merge(leavesWinner, leavesFragment);
        assertTrue("merge handles the full-cube host", leavesMerged != null);
        if (leavesMerged == null) {
            return;
        }
        String leavesJson = leavesMerged.toString();
        assertTrue("split leaf variants stay ungated (host visible under every phase)",
            leavesJson.contains("\"distance\":\"1\",\"persistent\":\"false\"")
                && leavesJson.contains("\"distance\":\"3\",\"persistent\":\"true\"")
                && !leavesJson.contains("\"persistent\":\"false\",\"filling\"")
                && !leavesJson.contains("\"persistent\":\"true\",\"filling\""));
        assertTrue("full-cube host uses the inset phase material and the dynamic part "
                + "(no z-fighting with its 0..16 model)",
            leavesJson.contains("liquidphase:block/phase_ice")
                && !leavesJson.contains("phase_ice_full")
                && liquidphase$hasUnconditionalDynamicPart(leavesJson)
                && !leavesJson.contains("liquidphase:block/redstone_phase_"));

        // (5) 代理包装：幂等 + 透传（用 ResourceManager.Empty 做最小可调用的 RM）。
        // 合并开关强制打开，避免本机配置文件影响自检结论（内存态，不落盘）。
        org.liquidphase.LiquidPhaseConfig.get().packMerge = true;
        ResourceManager empty = net.minecraft.server.packs.resources.ResourceManager.Empty.INSTANCE;
        ResourceManager proxied = org.liquidphase.client.PhasePackMerge.wrap(empty);
        assertTrue("wrap wraps a plain manager", proxied != empty);
        assertTrue("wrap is idempotent", org.liquidphase.client.PhasePackMerge.wrap(proxied) == proxied);
        assertTrue("proxy preserves empty blockstate listings",
            proxied.listResourceStacks("blockstates", id -> true).isEmpty());

        // (6) 分派识别回归（0.1.20）：识别必须只看**运行时实参/结果形状**，与方法名无关。
        // 生产环境方法名是 intermediary（1.21.11 实测 listResourceStacks = method_41265），
        // 0.1.19 及以前按 Yarn 名分派在生产环境静默失配——表现正是本 bug：
        // 日志有 "pack merge active" 却没有 "blockstate merge scan"，相部件整体丢失。
        // 断言参数里没有 Method 对象，结构上杜绝"再改回按名分派"。
        assertTrue("blockstates stack call is recognized by args alone",
            org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{"blockstates", new Object()}));
        assertTrue("other directories are not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{"models", new Object()}));
        assertTrue("non-string first args are not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{new Object(), new Object()}));
        assertTrue("wrong arity is not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(new Object[]{"blockstates"}));
        assertTrue("stack maps (List values) get merged",
            org.liquidphase.client.PhasePackMerge.isStackMap(
                Map.of("minecraft:blockstates/oak_fence.json", List.of())));
        assertTrue("single-resource maps are passed through",
            !org.liquidphase.client.PhasePackMerge.isStackMap(
                Map.of("minecraft:blockstates/oak_fence.json", new Object())));
        assertTrue("empty maps are passed through",
            !org.liquidphase.client.PhasePackMerge.isStackMap(Map.of()));
    }

    /** {@return 仿第三方包的 winner fixture 文本（assets/liquidphase/packmerge_test/）} */
    private static String liquidphase$foreignFixture(String blockName) {
        String fixture = liquidphase$resource("/assets/liquidphase/packmerge_test/"
            + blockName + "_foreign.json");
        assertTrue("foreign-winner fixture ships for " + blockName, fixture != null);
        return fixture;
    }

    /** 清空某位置全部流体 scheduled tick（自检场地复用坐标，残留 tick 会污染 hasScheduledTick）。 */
    private static void clearFluidTicks(ServerLevel level, BlockPos pos) {
        ((net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>) level.getChunkAt(pos).getFluidTicks())
            .removeIf(tick -> tick.pos().equals(pos));
    }

    /**
     * 台阶的相渲染（0.1.18 起统一）：所有变体都用**内缩覆盖层**，宿主 part 一律保持渲染。
     * <p>
     * 0.1.13–0.1.17 按变体拆分（type=double 换 phase_*_full 并屏蔽宿主）。问题有两个：
     * 屏蔽宿主让双台阶含固相变成"一整块冰/黑曜石"，与含熔岩（流体渲染、宿主照常可见）不一致；
     * phase_*_full 与 double 变体的整块模型共面，会 z-fighting。
     * 现在双台阶与半砖外观一致，宿主始终可见。
     */
    private static void checkSlabVariantRendering() {
        String slab = liquidphase$resource("/assets/minecraft/blockstates/stone_slab.json");
        assertTrue("stone slab blockstate resource is present", slab != null);
        if (slab == null) {
            return;
        }
        assertTrue("double slab uses the inset phase material (not the full one)",
            !slab.contains("phase_ice_full") && !slab.contains("phase_obsidian_full"));
        // 三个阶段变体（bottom/double/top）都保持宿主 part 常驻，不带相条件。
        assertTrue("every slab variant keeps its host part ungated",
            slab.contains("\"type\":\"bottom\"")
                && slab.contains("\"type\":\"double\"")
                && slab.contains("\"type\":\"top\"")
                && !slab.contains("\"type\":\"double\",\"filling\"")
                && !slab.contains("\"type\":\"bottom\",\"filling\"")
                && !slab.contains("\"type\":\"top\",\"filling\""));
        // 内缩覆盖层：条件只有 filling，不再按变体拆分（也没有 "!double" 取反）。
        assertTrue("the inset overlay is unconditional on slab variant (only filling gates it)",
            slab.contains("{\"when\":{\"filling\":\"obsidian\"}")
                && !slab.contains("!double"));
        // 反向：部分覆盖宿主（楼梯）不应突然获得整格层（现有断言已在 checkRedstoneRenderParts 覆盖）。
    }

    // ------------------------------------------------------------------
    // 炼药锅
    // ------------------------------------------------------------------

    private static void checkCauldron(ServerLevel level, BlockPos base) {
        BlockPos full = base.offset(0, 1, 4);
        BlockPos partial = base.offset(0, 1, 6);
        BlockPos lava = base.offset(0, 1, 8);

        level.setBlock(full, Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL), Block.UPDATE_ALL);
        level.setBlock(partial, Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 1), Block.UPDATE_ALL);
        level.setBlock(lava, Blocks.LAVA_CAULDRON.defaultBlockState(), Block.UPDATE_ALL);

        assertTrue("full water cauldron is a liquid mine target", PhaseTargeting.isLiquidMineTarget(level, full));
        assertTrue("partial water cauldron is NOT a liquid mine target",
            !PhaseTargeting.isLiquidMineTarget(level, partial));
        assertTrue("lava cauldron is a liquid mine target", PhaseTargeting.isLiquidMineTarget(level, lava));
    }

    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int x = -1; x <= 40; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -1; z <= 9; z++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            LiquidPhase.LOGGER.info("[Liquid Phase][selftest] PASS {}", name);
        } else {
            failed++;
            LiquidPhase.LOGGER.error("[Liquid Phase][selftest] FAIL {}", name);
        }
    }
}
