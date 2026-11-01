package org.liquidphase.state;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 鸭子接口：注入到 {@link StateDefinition}（见 state 包的 StateManagerMixin），
 * 供 {@link PhaseStateRefresher} 给既有方块定义追加 phase 属性。
 *
 * @param <O> the owner type (Block)
 * @param <S> the state type (BlockState)
 */
public interface StateDefinitionDuck<O, S> {

    /** {@return 构造时捕获的状态工厂，用于创建追加的状态} */
    StateDefinition.Factory<O, S> liquidphase$getFactory();

    /**
     * 重建定义的状态 codec 覆盖新属性（镜像原版构造器逻辑：逐属性 appendPropertyCodec）。
     *
     * @param properties   含新属性的完整属性表
     * @param defaultState 方块默认状态 supplier
     * @return 重建后的 codec
     */
    MapCodec<S> liquidphase$rebuildCodec(ImmutableSortedMap<String, Property<?>> properties, Supplier<S> defaultState);

    /**
     * 把定义内容替换为扩展后的属性表与状态表。26.3 的 codec 挂在定义上，也在这里更新。
     */
    void liquidphase$setDefinition(ImmutableSortedMap<String, Property<?>> properties, ImmutableList<S> states, MapCodec<S> codec);

    /**
     * 按给定属性值创建该定义的新状态（未注册、未初始化缓存）。
     */
    S liquidphase$createState(ImmutableSortedMap<String, Property<?>> properties, Map<Property<?>, Comparable<?>> values, MapCodec<S> codec);
}
