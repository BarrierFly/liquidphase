package org.liquidphase.selftest;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 红石相 / 炼药锅的自动化自检（默认不运行）。
 * <p>
 * 用 {@code -Dliquidphase.selftest=true} 启动服务端时，等服务器启动完成后在出生点附近
 * 造一小块测试场地逐项断言，把 {@code PASS}/{@code FAIL} 打进日志，最后清理场地。
 * <p>
 * 放置类断言刻意<b>不</b>使用 FakePlayer：这个模组的放置能力属于"方块放置流水线"，
 * 与谁在放、用哪只手、是否潜行都无关。用 {@link BlockItem#place} 直接驱动，
 * 才能证明它真的和玩家无关（其它复用同一条物品放置路径的来源行为一致）。
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
            checkPlaceIntoWire(level, base);
            checkWireStaysUnreplaceable(level, base);
            checkRedstoneRenderParts();
            checkCauldron(level, base);
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
        assertTrue("phase count = 1 + 7 + 16", Phase.values().length == 24);
        assertTrue("FILLED excludes NONE", Phase.FILLED.length == 23);
        assertTrue("redstone_0 is redstone", Phase.REDSTONE_0.isRedstone());
        assertTrue("redstone power roundtrip", Phase.redstone(7).redstonePower() == 7);
        assertTrue("redstone power clamped", Phase.redstone(99).redstonePower() == 15
            && Phase.redstone(-5).redstonePower() == 0);
        assertTrue("frosted stays frosted", Phase.FROSTED_3.isFrosted() && !Phase.FROSTED_3.isRedstone());
        assertTrue("frosted age works", Phase.FROSTED_2.frostedAge() == 2);
        assertTrue("redstone is not solid", !Phase.REDSTONE_15.isSolid());
        assertTrue("obsidian is solid", Phase.OBSIDIAN.isSolid());
        assertTrue("none is neither", !Phase.NONE.isSolid() && !Phase.NONE.isRedstone());
    }

    // ------------------------------------------------------------------
    // 红石相 = 宿主方块 + phase 取值（不再有新方块）
    // ------------------------------------------------------------------

    /**
     * 兼容宿主必须真的拿到 phase 属性：多面体家族（glow_lichen / sculk_vein / resin_clump）
     * 在 1.21.10+ 统一成 MultifaceBlock，如果把 Mixin 目标错写成旧版的
     * GlowLichenBlock/SculkVeinBlock，它们的 blockstate 里会出现 phase 条件却没有该属性，
     * 客户端直接报 Unknown property 'phase' 并退化成 missing model。
     */
    private static void checkPhasePropertyCoverage() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            if (!block.defaultBlockState().hasProperty(PhaseHelper.PHASE)) {
                missing.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));
            }
        }
        assertTrue("every PhaseCapable block has the phase property (missing: " + missing + ")",
            missing.isEmpty());
    }

    private static void checkShapeAndBlockIdentity(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        BlockState host = Blocks.OAK_STAIRS.defaultBlockState();
        BlockState redstone = PhaseHelper.withPhase(host, Phase.REDSTONE_15);
        level.setBlock(pos, redstone, Block.UPDATE_ALL);

        BlockState actual = level.getBlockState(pos);
        assertTrue("redstone phase is the same block as host",
            actual.getBlock() == Blocks.OAK_STAIRS);
        assertTrue("redstone phase carried by phase property",
            PhaseHelper.getPhase(actual) == Phase.REDSTONE_15);
        assertTrue("no extra liquidphase redstone blocks registered",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                .noneMatch(id -> id.getNamespace().equals("liquidphase") && id.getPath().startsWith("redstone_")));
        assertTrue("host outline unchanged (stairs are not a full cube)",
            !Block.isShapeFullBlock(actual.getShape(level, pos, CollisionContext.empty())));
        assertTrue("collision unchanged (not a solid phase)",
            !Block.isShapeFullBlock(actual.getCollisionShape(level, pos, CollisionContext.empty())));
        assertTrue("redstone power readable", RedstoneBlocks.getPower(actual) == 15);
        assertTrue("stair UP face exposed", RedstoneBlocks.exposed(actual, Direction.UP));

        BlockState doubleSlab = PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15);
        assertTrue("double slab hides every face",
            !RedstoneBlocks.exposed(doubleSlab, Direction.UP)
                && !RedstoneBlocks.exposed(doubleSlab, Direction.NORTH));

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 信号：向红石线 -1，向固相满功率，DOWN 不输出，被覆盖面不输出
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_15),
            Block.UPDATE_ALL);

        // 默认橡木楼梯朝北：北面被台阶踏面盖满（按设计不输出），南面才是露出的。
        BlockState stairs = level.getBlockState(pos);
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(stairs, Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(stairs, Direction.SOUTH));

        // 信号是即时结算的：必须放真实电源，且只能放在露出的面上。
        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("redstone phase keeps power 15 (got " + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);

        // 南侧换成红石线 → 以 NORTH 查询（接收方在南）→ 15 - 1 = 14
        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        // 换成固相 → 同一方向满功率
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        // 双台阶（整格宿主）被完全覆盖 → 不输出
        BlockPos slabPos = base.offset(2, 1, 0);
        level.setBlock(slabPos, PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15), Block.UPDATE_ALL);
        level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);
    }

    // ------------------------------------------------------------------
    // 供电即时性：邻居变化后同一 tick 内功率就已更新（不走计划刻）
    // ------------------------------------------------------------------

    private static void checkInstantUpdate(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(4, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_0),
            Block.UPDATE_ALL);
        BlockPos source = pos.south();
        level.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power updates immediately on neighbour change (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power drops immediately when source removed (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 0);
    }

    // ------------------------------------------------------------------
    // 向红石线所在格放置宿主方块（走方块放置流水线，不经过玩家/手）
    // ------------------------------------------------------------------

    /**
     * 用 {@link BlockItem#place} 直接驱动——这是方块物品放置的公共通道（玩家用物品放置、
     * 其它复用这条路径的来源都汇到这里）。断言里刻意不出现 FakePlayer / 手 / 潜行：
     * 这个能力属于"放置流水线"，与谁在放无关。
     */
    private static void checkPlaceIntoWire(ServerLevel level, BlockPos base) {
        // ---- (1) 原版落点算到线格：对着线下面支撑它的方块 ↩ 的顶面放 ----
        BlockPos wirePos = base.offset(6, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(1): wire present at target tile",
            level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE));
        int wirePower = level.getBlockState(wirePos).getValue(BlockStateProperties.POWER);
        assertTrue("scene(1): wire powered by redstone block above (got " + wirePower + ")", wirePower == 15);

        BlockState placed = liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP);
        BlockState after = level.getBlockState(wirePos);
        assertTrue("scene(1): placement pipeline accepted the wire tile (returned " + placed + ")",
            placed != null);
        assertTrue("scene(1): wire tile became the host (got " + after.getBlock() + ")",
            after.is(Blocks.OAK_STAIRS));
        assertTrue("scene(1): host carries the redstone phase (got " + PhaseHelper.getPhase(after) + ")",
            RedstoneBlocks.isRedstonePhase(after));
        assertTrue("scene(1): host carried the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(after) + ")",
            RedstoneBlocks.getPower(after) == wirePower);

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (2) 原版落点算到线格：对着线旁的方块 ↩ 朝线的侧面放 ----
        BlockPos sideWire = base.offset(8, 1, 0);
        BlockPos sideGround = sideWire.east();
        level.setBlock(sideWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sideWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sideWire.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sideGround, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(2): wire present at side tile",
            level.getBlockState(sideWire).is(Blocks.REDSTONE_WIRE));

        BlockState sidePlaced = liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, sideGround, Direction.WEST);
        BlockState sideAfter = level.getBlockState(sideWire);
        assertTrue("scene(2): side placement accepted the wire tile (returned " + sidePlaced + ")",
            sidePlaced != null);
        assertTrue("scene(2): side placement became the host with redstone phase (got " + sideAfter.getBlock()
                + "/" + PhaseHelper.getPhase(sideAfter) + ")",
            sideAfter.is(Blocks.OAK_STAIRS) && RedstoneBlocks.isRedstonePhase(sideAfter));
        assertTrue("scene(2): side placement kept the wire power (got "
                + RedstoneBlocks.getPower(sideAfter) + ")",
            RedstoneBlocks.getPower(sideAfter) == 15);

        level.setBlock(sideWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sideWire.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sideGround, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (3) 非相容宿主不享受这个口子：石头仍然放不进线格（原版语义） ----
        BlockPos stoneWire = base.offset(10, 1, 0);
        level.setBlock(stoneWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stoneWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState stonePlaced = liquidphase$placeBlockInto(level, Blocks.STONE, stoneWire.below(), Direction.UP);
        assertTrue("scene(3): non-host still cannot replace a wire (returned " + stonePlaced + ")",
            stonePlaced == null);
        assertTrue("scene(3): wire survived the rejected placement",
            level.getBlockState(stoneWire).is(Blocks.REDSTONE_WIRE));
        level.setBlock(stoneWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (4) 普通放置没被影响：红石粉照常铺在石头顶上 ----
        BlockPos dustPos = base.offset(11, 1, 0);
        level.setBlock(dustPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState dustPlaced = liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, dustPos.below(), Direction.UP);
        assertTrue("scene(4): redstone dust still places on a plain block (returned " + dustPlaced + ")",
            dustPlaced != null && dustPlaced.is(Blocks.REDSTONE_WIRE));
        level.setBlock(dustPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(dustPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 红石线自身的"可替换"语义必须保持原版：前两轮有一版把
     * {@code BlockBehaviour.canBeReplaced(state, context)} 整个放宽了，
     * 那会让水流、活塞、邻居更新、其它模组都把红石线当可替换方块。
     * 这里断言那个口子没有回来。
     */
    private static void checkWireStaysUnreplaceable(ServerLevel level, BlockPos base) {
        BlockPos wirePos = base.offset(12, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState wire = level.getBlockState(wirePos);

        assertTrue("wire state itself is still not replaceable", !wire.canBeReplaced());

        // 用相容宿主物品构造一个"普通"放置上下文并瞄准线格：原版判定必须是 false。
        BlockPlaceContext plain = liquidphase$context(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP);
        assertTrue("plain placement context still refuses the wire tile", !plain.canPlace());

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 走 {@link BlockItem#place} 放置一个方块：命中 {@code clickedPos} 的 {@code face} 面。
     * <p>
     * 没有玩家、没有手、没有潜行——放置上下文由 {@link BlockPlaceContext} 自己按命中结果
     * 算落点，和命令/结构方块/其它模组走的是同一条路。
     *
     * @return 实际写入的方块状态；放置失败返回 {@code null}
     */
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

    /**
     * 红石相渲染：宿主模型必须**继续渲染**，粉尘外壳只是额外叠加一层。
     * <p>
     * 这是 0.1.7 的实机 bug：当时把宿主 part 限制成 `phase != redstone_*`，
     * 于是含红石相时宿主不渲染，整格只剩一个外壳 → 楼梯形状消失、
     * 整格变成不透明粉尘面（表现为"红石相表面按不透明渲染"）。
     */
    private static void checkRedstoneRenderParts() {
        java.io.InputStream in = LiquidPhase.class.getResourceAsStream(
            "/assets/minecraft/blockstates/oak_stairs.json");
        if (in == null) {
            assertTrue("stair blockstate resource is present", false);
            return;
        }
        String json;
        try (java.io.InputStream stream = in) {
            json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            assertTrue("stair blockstate resource is readable", false);
            return;
        }
        assertTrue("stair blockstate keeps rendering the host under redstone phase",
            json.contains("\"shape\":\"straight\"")
                && json.contains("redstone_15")
                && hostPhaseConditionCoversRedstone(json));
        assertTrue("stair blockstate adds a redstone shell overlay",
            json.contains("liquidphase:block/redstone_phase"));
    }

    /** {@return 是否存在"宿主 part 的 phase 条件包含 redstone_*"的写法} */
    private static boolean hostPhaseConditionCoversRedstone(String json) {
        int idx = json.indexOf("\"shape\"");
        while (idx >= 0) {
            int phase = json.indexOf("\"phase\":\"", idx);
            if (phase >= 0 && phase - idx < 400) {
                int end = json.indexOf('"', phase + 9);
                if (end > 0) {
                    String value = json.substring(phase + 9, end);
                    if (value.contains("redstone_15")) {
                        return true;
                    }
                }
            }
            idx = json.indexOf("\"shape\"", idx + 1);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 炼药锅：只有满锅才是取水目标
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int x = -1; x <= 14; x++) {
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
