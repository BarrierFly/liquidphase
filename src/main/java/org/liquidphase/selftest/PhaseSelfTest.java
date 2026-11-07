package org.liquidphase.selftest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 红石相 / 炼药锅的自动化自检（默认不运行）。
 * <p>
 * 用 {@code -Dliquidphase.selftest=true} 启动服务端时，等服务器启动完成后在出生点附近
 * 造一小块测试场地逐项断言，把 {@code PASS}/{@code FAIL} 打进日志，最后清理场地。
 * 覆盖的是"无玩家参与"的确定性行为：组合方块注册、选择框/碰撞/遮挡、信号衰减、
 * 炼药锅取水门槛。玩家驱动的路径（挖掘/放置/物品消耗）仍需游戏内手测。
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
            checkRegistry();
            checkShape(level, base);
            checkSignal(level, base);
            checkInstantUpdate(level, base);
            checkPlaceIntoWire(level, base);
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
    // 组合方块注册 / 属性
    // ------------------------------------------------------------------

    private static void checkRegistry() {
        Block composite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.OAK_STAIRS);
        assertTrue("composite registered for oak_stairs", composite != null);
        if (composite == null) {
            return;
        }
        assertTrue("composite -> host reverse map",
            RedstoneBlocks.COMPOSITE_TO_HOST.get(composite) == Blocks.OAK_STAIRS);
        BlockState state = composite.defaultBlockState();
        assertTrue("composite has POWER", state.hasProperty(RedstoneBlocks.POWER));
        assertTrue("composite has no phase property", !state.hasProperty(PhaseHelper.PHASE));
        assertTrue("composite is redstone phase", RedstoneBlocks.isRedstonePhase(state));
        assertTrue("host item recognised", RedstoneBlocks.isPhaseHostItem(new ItemStack(Blocks.OAK_STAIRS)));
        assertTrue("non-host item rejected", !RedstoneBlocks.isPhaseHostItem(new ItemStack(Items.STONE)));
    }

    // ------------------------------------------------------------------
    // 选择框（交互箱）整格 / 碰撞与遮挡仍按宿主
    // ------------------------------------------------------------------

    private static void checkShape(ServerLevel level, BlockPos base) {
        Block composite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.OAK_STAIRS);
        if (composite == null) {
            return;
        }
        CollisionContext context = CollisionContext.empty();
        BlockState state = composite.defaultBlockState();
        BlockState host = Blocks.OAK_STAIRS.defaultBlockState();

        assertTrue("outline is full cube",
            Block.isShapeFullBlock(state.getShape(level, base, context)));
        assertTrue("collision follows host (not full cube)",
            !Block.isShapeFullBlock(state.getCollisionShape(level, base, context)));
        assertTrue("collision equals host collision",
            state.getCollisionShape(level, base, context).bounds()
                .equals(host.getCollisionShape(level, base, context).bounds()));
        // 实体碰撞读的是 Cache 里缓存的 collisionShape（initCache 走块级方法），必须一起按宿主。
        assertTrue("cached collision shape is not full cube", !state.isCollisionShapeFullBlock(level, base));
        assertTrue("solidRender (occlusion cache) is false", !solidRender(state, level, base));
        assertTrue("occlusion is not full cube", !Block.isShapeFullBlock(occlusionOf(state, level, base)));
        assertTrue("stair host exposes UP face", RedstoneBlocks.exposed(state, Direction.UP));

        Block slabComposite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.STONE_SLAB);
        if (slabComposite != null) {
            BlockState doubleSlab = slabComposite.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
            assertTrue("double slab hides every face",
                !RedstoneBlocks.exposed(doubleSlab, Direction.UP)
                    && !RedstoneBlocks.exposed(doubleSlab, Direction.NORTH));
        }
    }

    // ------------------------------------------------------------------
    // 信号：向红石线 -1，向固相满功率，DOWN 不输出，被覆盖面不输出
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        Block composite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.OAK_STAIRS);
        if (composite == null) {
            return;
        }
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, composite.defaultBlockState(), Block.UPDATE_ALL);

        // 默认橡木楼梯朝北：北面被台阶踏面盖满（按设计不输出），南面才是露出的。
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(
            level.getBlockState(pos), Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(
            level.getBlockState(pos), Direction.SOUTH));

        // 信号现在是即时结算的：手动 setValue(POWER,15) 会被立刻重算回真实值，
        // 所以必须放真实电源，而且只能放在**露出的**南面。
        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("composite powered to 15 by redstone block on exposed face (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);

        // 南侧换成红石线 → 以 NORTH 查询（接收方在南）→ 15 - 1 = 14
        // 红石线本身不供电；组合方块仍有北侧之外的电源吗？没有 —— 所以这里改为
        // 让线自己由红石块充能，再验证"组合方块 → 线"的 -1。
        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("wire placed south", level.getBlockState(pos.south()).is(Blocks.REDSTONE_WIRE));
        // 给组合方块重新供上电（南面现在是线，改放一个暴露的电源在下方？不行：DOWN 不输出）。
        // 用"上方"作为暴露面：楼梯 UP 面是露出的。
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        // 换成固相 → 同一方向满功率
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        Block slabComposite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.STONE_SLAB);
        if (slabComposite != null) {
            BlockPos slabPos = base.offset(2, 1, 0);
            level.setBlock(slabPos, slabComposite.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.DOUBLE), Block.UPDATE_ALL);
            level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);
        }
    }

    // ------------------------------------------------------------------
    // 供电即时性：邻居变化后同一 tick 内功率就已更新（不走计划刻）
    // ------------------------------------------------------------------

    private static void checkInstantUpdate(ServerLevel level, BlockPos base) {
        Block composite = RedstoneBlocks.HOST_TO_COMPOSITE.get(Blocks.OAK_STAIRS);
        if (composite == null) {
            return;
        }
        BlockPos pos = base.offset(4, 1, 0);
        level.setBlock(pos, composite.defaultBlockState(), Block.UPDATE_ALL);
        // 南面放一个能供电的方块（红石块，满功率 15，不依赖方向）
        BlockPos source = pos.south();
        level.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power updates immediately on neighbor change (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);
        // 移走电源后同样立即归零
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power drops immediately when source removed (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 0);
    }

    // ------------------------------------------------------------------
    // 向红石线所在格放置宿主方块
    // ------------------------------------------------------------------

    private static void checkPlaceIntoWire(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(6, 1, 0);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        // 电源放南侧：默认楼梯朝北，北面被盖住（按设计不供电），南面才是露出的。
        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("wire placed for placement test", level.getBlockState(pos).is(Blocks.REDSTONE_WIRE));
        int wirePower = level.getBlockState(pos).getValue(BlockStateProperties.POWER);
        assertTrue("wire powered by redstone block (got " + wirePower + ")", wirePower == 15);

        // 走与 RedstoneWireUseMixin 相同的实现（这里用一个固定的宿主状态代替放置上下文）。
        ItemStack stack = new ItemStack(Blocks.OAK_STAIRS);
        boolean placed = RedstoneBlocks.placeHostIntoWire(level, pos, stack,
            () -> Blocks.OAK_STAIRS.defaultBlockState());
        assertTrue("host item placed into wire tile", placed);
        assertTrue("tile became a redstone composite",
            RedstoneBlocks.isRedstonePhase(level.getBlockState(pos)));
        assertTrue("composite kept the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == wirePower);
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

    private static VoxelShape occlusionOf(BlockState state, ServerLevel level, BlockPos pos) {
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*return state.getOcclusionShape(level, pos);
         *///?} else {
        return state.getOcclusionShape();
        //?}
    }

    private static boolean solidRender(BlockState state, ServerLevel level, BlockPos pos) {
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*return state.isSolidRender(level, pos);
         *///?} else {
        return state.isSolidRender();
        //?}
    }

    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int x = -1; x <= 8; x++) {
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
