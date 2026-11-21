package org.liquidphase.state;

// 仅用于 1.21.1+ 的自带注入器路径；1.19.4/1.20.1 由 Statement 完成同等工作。
//? if !(1.19.4 || 1.20.1) {

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.liquidphase.phase.Phase;

/**
 * 给已注册方块追加 {@code phase} 属性的注入器（1.21.1/1.21.10/1.21.11/26.3 自带实现；
 * 1.19.4/1.20.1 由 Statement 完成同等工作，见 {@code LiquidPhase}）。
 * <p>
 * 移植自 Lavalogged-fork 的 LavaStateRefresher（MIT）：既有状态原地扩展（注册表与网络 id 不动），
 * 缺失的 phase 变体经方块自己的状态工厂创建并追加到 {@link Block#BLOCK_STATE_REGISTRY} 尾部，
 * 重建全部状态的属性转移表，新状态手动 initCache（原版只在注册时调用）。
 * 客户端与服务端以相同注册表顺序执行本方法，追加 id 完全一致。
 */
public final class PhaseStateRefresher {

    private PhaseStateRefresher() {
    }

    public static <T extends Comparable<T>> void addBlockProperty(Block block, Property<T> property, T defaultValue) {
        @SuppressWarnings("unchecked")
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        if (definition.getProperty(property.getName()) != null) {
            return;
        }

        @SuppressWarnings("unchecked")
        StateDefinitionDuck<Block, BlockState> definitionDuck = (StateDefinitionDuck<Block, BlockState>) definition;
        StateDefinition.Factory<Block, BlockState> factory = definitionDuck.liquidphase$getFactory();
        if (factory == null) {
            throw new IllegalStateException("No state factory captured for " + block);
        }

        // 重建属性表（新属性追加到尾部）。
        ImmutableSortedMap.Builder<String, Property<?>> propertiesBuilder = ImmutableSortedMap.naturalOrder();
        for (Property<?> existing : definition.getProperties()) {
            propertiesBuilder.put(existing.getName(), existing);
        }
        propertiesBuilder.put(property.getName(), property);
        ImmutableSortedMap<String, Property<?>> properties = propertiesBuilder.build();

        // 重建 codec：显式传入扩展后的属性表（定义里还是旧的）。
        Supplier<BlockState> defaultState = () -> block.defaultBlockState();
        @SuppressWarnings("unchecked")
        MapCodec<BlockState> codec = (MapCodec<BlockState>) definitionDuck.liquidphase$rebuildCodec(properties, defaultState);

        // 既有状态原地补默认值；再为每个旧状态 × 每个非默认取值创建新状态。
        // 泛型化之后同一套机械扩展既用于 filling（7 个非默认相），也用于给传送门补
        // waterlogged（1 个非默认值 true）。
        List<T> variants = new ArrayList<>(property.getPossibleValues());
        variants.remove(defaultValue);
        ImmutableList<BlockState> oldStates = definition.getPossibleStates();
        Map<Map<Property<?>, Comparable<?>>, BlockState> statesByValues = new LinkedHashMap<>();
        for (BlockState state : oldStates) {
            ((StateHolderDuck<Block, BlockState>) state).liquidphase$addValue(property, defaultValue);
            statesByValues.put(snapshot(state), state);
        }

        List<BlockState> newStates = new ArrayList<>(oldStates.size() * variants.size());
        for (BlockState state : oldStates) {
            for (T value : variants) {
                Map<Property<?>, Comparable<?>> values =
                    new LinkedHashMap<>(((StateHolderDuck<Block, BlockState>) state).liquidphase$snapshotValues());
                values.put(property, value);
                BlockState created = definitionDuck.liquidphase$createState(properties, values, codec);
                newStates.add(created);
                statesByValues.put(snapshot(created), created);
            }
        }

        // 重建属性转移表，再初始化新状态缓存（原版只在注册时 initCache）。
        for (BlockState state : com.google.common.collect.Iterables.concat(oldStates, newStates)) {
            ((StateHolderDuck<Block, BlockState>) state).liquidphase$rebuildNeighbours(statesByValues);
        }
        for (BlockState state : newStates) {
            ((StateHolderDuck<Block, BlockState>) state).liquidphase$setCodec(codec);
            try {
                state.initCache();
            } catch (RuntimeException e) {
                // 单个宿主初始化缓存失败不应让整个游戏起不来：记录方块名并继续。
                org.liquidphase.LiquidPhase.LOGGER.error(
                    "[Liquid Phase] failed to init cache for {} ({})", block, state, e);
            }
        }

        ImmutableList.Builder<BlockState> allStates = ImmutableList.builder();
        allStates.addAll(oldStates).addAll(newStates);
        definitionDuck.liquidphase$setDefinition(properties, allStates.build(), codec);

        // 新变体追加到 id 列表尾部；既有 id 不动，客户端与服务端顺序一致则 id 一致。
        for (BlockState state : newStates) {
            if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) {
                Block.BLOCK_STATE_REGISTRY.add(state);
            }
        }
    }

    private static Map<Property<?>, Comparable<?>> snapshot(BlockState state) {
        return new Reference2ObjectArrayMap<>(((StateHolderDuck<Block, BlockState>) state).liquidphase$snapshotValues());
    }
}

//?}
