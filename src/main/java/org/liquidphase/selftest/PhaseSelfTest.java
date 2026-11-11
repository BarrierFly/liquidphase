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
import org.liquidphase.phase.PhaseRenderSupport;
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
            checkPlaceIntoWire(level, base);
            checkPlaceIntoWireByPlayer(level, base);
            checkScaffoldingStacking(level, base);
            checkFallingBlockIntoWire(level, base);
            checkWireStaysUnreplaceable(level, base);
            checkRedstoneRenderParts();
            checkPhaseRenderCulling(level, base);
            checkSyncState();
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
    // 信号
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_15),
            Block.UPDATE_ALL);

        BlockState stairs = level.getBlockState(pos);
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(stairs, Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(stairs, Direction.SOUTH));

        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("redstone phase keeps power 15 (got " + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);

        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        BlockPos slabPos = base.offset(2, 1, 0);
        level.setBlock(slabPos, PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15), Block.UPDATE_ALL);
        level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);
    }

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
        assertTrue("scene(1): host carries the redstone phase (got " + PhaseHelper.getPhase(after) + ")",
            RedstoneBlocks.isRedstonePhase(after));
        assertTrue("scene(1): host carried the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(after) + ")",
            RedstoneBlocks.getPower(after) == wirePower);

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
            RedstoneBlocks.getPower(level.getBlockState(base.offset(7, 1, 0))) == 15);
        liquidphase$clearGesture(level, base.offset(7, 1, 0), base.offset(7, 1, 0).below());

        // (2) 点线旁方块朝线的侧面
        BlockPos sideWire = base.offset(8, 1, 0);
        BlockPos sideGround = sideWire.east();
        assertTrue("player scene(2): host lands in the wire tile", liquidphase$playerGesture(level, player,
            sideGround, Direction.WEST, sideWire, Blocks.OAK_STAIRS));
        assertTrue("player scene(2): keeps 15 power",
            RedstoneBlocks.getPower(level.getBlockState(sideWire)) == 15);
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
        assertTrue("falling host: carried the redstone phase (got " + PhaseHelper.getPhase(hostAfter) + ")",
            RedstoneBlocks.isRedstonePhase(hostAfter));
        assertTrue("falling host: power followed the wire (wire=" + hostPower + ", got "
                + RedstoneBlocks.getPower(hostAfter) + ")",
            RedstoneBlocks.getPower(hostAfter) == hostPower);

        hostEntity.discard();
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
        boolean ok = after.is(block) && RedstoneBlocks.isRedstonePhase(after);
        if (!ok) {
            LiquidPhase.LOGGER.warn("[selftest] gesture failed at {}: click={} face={} -> {} / {}",
                wirePos, clickPos, face, after.getBlock(), PhaseHelper.getPhase(after));
        }
        return ok;
    }

    private static void liquidphase$clearGesture(ServerLevel level, BlockPos wirePos, BlockPos clickPos) {
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
                && hostPartsCarryNoPhaseCondition(json));
        assertTrue("stair blockstate adds a redstone shell overlay for every power level",
            liquidphase$hasShellForEveryPower(json));

        // Full-cube hosts (leaves / grates / mangrove roots): the host part must stay visible
        // under redstone phases and the shell must be added. 0.1.8 limited the host part to
        // none|lava, so redstone-phase leaves rendered nothing at all.
        String leaves = liquidphase$resource("/assets/minecraft/blockstates/oak_leaves.json");
        assertTrue("leaves blockstate resource is present", leaves != null);
        if (leaves != null) {
            assertTrue("leaves host part still renders under redstone phase",
                leaves.contains("redstone_15") && leaves.contains("minecraft:block/oak_leaves"));
            assertTrue("leaves blockstate adds a redstone shell overlay",
                leaves.contains("liquidphase:block/redstone_phase_"));
        }

        // Transparent hosts previously got no shell at all (only the 41 redstone hosts did),
        // which is exactly why "some transparent blocks still show no redstone phase".
        String pane = liquidphase$resource("/assets/minecraft/blockstates/glass_pane.json");
        assertTrue("glass pane blockstate resource is present", pane != null);
        if (pane != null) {
            assertTrue("transparent host (glass pane) gets a redstone shell too",
                pane.contains("liquidphase:block/redstone_phase_") && pane.contains("redstone_15"));
        }

        // ---- regression for the 0.1.11 bugs ----
        // 整格宿主要靠模型几何自动判定，不能用手写白名单：26.3 新增的白杨树叶就是
        // 漏登记的例子，漏了等于"含冰相完全看不见"。
        String poplar = liquidphase$resource("/assets/minecraft/blockstates/orange_poplar_leaves.json");
        if (poplar != null) {
            assertTrue("new full-cube host (orange poplar leaves) is detected as full-cube",
                poplar.contains("phase_ice_full"));
        }
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
        // 外壳按功率分成 16 份：0 档（熄灭）与 15 档（满功率）必须是不同贴图。
        String dimShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_0.json");
        String brightShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_15.json");
        assertTrue("redstone shell has one model per power level",
            dimShell != null && brightShell != null && !dimShell.equals(brightShell));
        assertTrue("redstone shell power models use distinct textures",
            dimShell != null && brightShell != null
                && dimShell.contains("redstone_phase_0") && brightShell.contains("redstone_phase_15"));
        // Full-cube overlays sit exactly on the block boundary, so cullface is legitimate there.
        String full = liquidphase$resource("/assets/liquidphase/models/block/phase_ice_full.json");
        assertTrue("full-cube overlay exists", full != null);
        if (full != null) {
            assertTrue("full-cube overlay keeps cullface (faces are on the boundary)",
                full.contains("\"cullface\""));
        }

        // (a2) 宿主 part 不能再带"覆盖全部 24 个相"的恒真条件：原版
        // KeyValueCondition.instantiate 会为它打 `Condition ... is always true` 警告，
        // 1.21.10+ 每个方块状态各一条，加载日志被刷屏。
        String stairs = liquidphase$resource("/assets/minecraft/blockstates/oak_stairs.json");
        assertTrue("host parts carry no phase condition at all (no always-true warnings)",
            stairs != null && !stairs.contains("\"phase\":\"none|lava|obsidian"));

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
        BlockState phasedBarrier = PhaseHelper.withPhase(Blocks.BARRIER.defaultBlockState(), Phase.REDSTONE_15);
        assertTrue("barrier is invisible without a phase",
            Blocks.BARRIER.defaultBlockState().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased barrier renders its model (otherwise the phase would be invisible)",
            phasedBarrier.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased solid-state barrier also renders",
            PhaseHelper.withPhase(Blocks.BARRIER.defaultBlockState(), Phase.ICE).getRenderShape()
                == net.minecraft.world.level.block.RenderShape.MODEL);
        // 反向：不带相的状态必须保持原值（否则 barrier 会凭空显出方块模型）。
        assertTrue("unphased states keep their vanilla render shape",
            Blocks.BARRIER.defaultBlockState().getRenderShape()
                != net.minecraft.world.level.block.RenderShape.MODEL);

        // 方块实体宿主（告示牌/箱子）：它们的可见几何来自方块实体渲染器，blockstate 模型
        // 只有 particle 贴图。凡是在本版本原生不是 MODEL 的，含相后都必须能请求到模型，
        // 否则相完全不可见。1.21.10 起原版把告示牌/箱子改成了 MODEL，这时无需干预，
        // 断言退化成"原值就是 MODEL"。
        liquidphase$assertNonModelHostsRenderPhases(new Block[]{
            Blocks.OAK_SIGN, Blocks.OAK_HANGING_SIGN, Blocks.CHEST, Blocks.ENDER_CHEST,
            Blocks.DECORATED_POT, Blocks.CONDUIT,
        });
    }

    /**
     * 对"原生不是 MODEL"的宿主逐个断言：带相后 RenderShape 变成 MODEL，不带相时保持原值。
     * 本版本里原生就是 MODEL 的宿主跳过（原版自己已经会渲染模型）。
     */
    private static void liquidphase$assertNonModelHostsRenderPhases(Block[] candidates) {
        int checked = 0;
        for (Block block : candidates) {
            if (block == null) {
                continue;
            }
            BlockState plain = block.defaultBlockState();
            // 不是相容宿主（拿不到 phase 属性）或原生就是 MODEL 的，都不需要本 mixin。
            if (!PhaseHelper.hasPhaseProperty(plain)
                || plain.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                continue;
            }
            checked++;
            String name = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            assertTrue("phased " + name + " renders its model so the phase becomes visible",
                PhaseHelper.withPhase(plain, Phase.REDSTONE_15).getRenderShape()
                    == net.minecraft.world.level.block.RenderShape.MODEL);
            assertTrue("phased " + name + " also renders for a solid phase",
                PhaseHelper.withPhase(plain, Phase.ICE).getRenderShape()
                    == net.minecraft.world.level.block.RenderShape.MODEL);
            assertTrue("unphased " + name + " keeps its vanilla render shape",
                plain.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
        }
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*// 这三个版本里告示牌/箱子家族不是 MODEL（1.19.4/1.20.1 判据是 != INVISIBLE，
        // 所以 ENTITYBLOCK_ANIMATED 仍会渲染；告示牌的 INVISIBLE 才是真问题）。
        assertTrue("at least one non-MODEL block-entity host exists to check (" + checked + " checked)",
            checked > 0);
         *///?}
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
     * {@return 是否每个功率档位都有自己的一条外壳 part}
     * <p>
     * 外壳按功率分成 16 份（0 档熄灭、15 档满功率）。只留一条 part 指向同一个模型时，
     * 所有功率的外观会完全一样——0 档看起来就像通电了。
     */
    private static boolean liquidphase$hasShellForEveryPower(String json) {
        for (int power = 0; power < 16; power++) {
            if (!json.contains("\"phase\":\"redstone_" + power + "\"")) {
                return false;
            }
            if (!json.contains("liquidphase:block/redstone_phase_" + power + "\"")) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return 宿主 part 是否完全不带 phase 条件}
     * <p>
     * 普通宿主的覆盖层与外壳都是叠加层，宿主照常渲染，所以不需要 phase 条件。
     * 一旦给宿主 part 补上"全部 24 个相"的条件，原版 {@code KeyValueCondition}
     * 会判定它恒真并打 {@code Condition ... is always true} 警告（每个状态一条）。
     */
    private static boolean hostPartsCarryNoPhaseCondition(String json) {
        return !json.contains("\"phase\":\"none|lava|obsidian");
    }

    // ------------------------------------------------------------------
    // 渲染剔除 + 双端同步
    // ------------------------------------------------------------------

    /**
     * 相邻面剔除：含相状态不得参与原版"同种方块共面剔除"。
     * <p>
     * 否则两块相邻的含相同相方块会在贴合处各少一面（相缺一角），
     * 两块含红石相的楼梯之间的粉尘也会消失。
     */
    private static void checkPhaseRenderCulling(ServerLevel level, BlockPos base) {
        BlockState iceStairs = PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE);
        BlockState redStairs = PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_15);
        BlockState plainStairs = Blocks.OAK_STAIRS.defaultBlockState();

        assertTrue("phased state never culls its own faces",
            !iceStairs.skipRendering(iceStairs, Direction.NORTH)
                && !redStairs.skipRendering(redStairs, Direction.UP));
        assertTrue("a plain neighbour may not cull against a phased block",
            !plainStairs.skipRendering(iceStairs, Direction.NORTH)
                && !plainStairs.skipRendering(redStairs, Direction.UP));
        assertTrue("unphased states keep vanilla culling",
            PhaseRenderSupport.allowVanillaCulling(plainStairs, plainStairs));

        // Leaves are the classic "same block culls faces" case; make sure such a host also
        // refuses to cull once phased (skip the check if this version has no leaves).
        if (Blocks.OAK_LEAVES != null) {
            BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
            BlockState iceLeaves = PhaseHelper.withPhase(leaves, Phase.ICE);
            assertTrue("phased leaves do not cull against each other",
                !iceLeaves.skipRendering(iceLeaves, Direction.NORTH));
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
        for (int x = -1; x <= 18; x++) {
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
