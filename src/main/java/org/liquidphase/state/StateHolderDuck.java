package org.liquidphase.state;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 鸭子接口：注入到 {@link StateHolder}（见 state 包的 StateHolderMixin），
 * 供 {@link PhaseStateRefresher} 扩展既有状态并重建属性转移表。
 *
 * @param <O> the owner type (Block)
 * @param <S> the state type (BlockState)
 */
public interface StateHolderDuck<O, S> {

    /** 给该状态追加一个属性与取值。 */
    void liquidphase$addValue(Property<?> property, Comparable<?> value);

    /** {@return 该状态当前全部属性值的副本} */
    Map<Property<?>, Comparable<?>> liquidphase$snapshotValues();

    /**
     * 依据整份定义的"值表→状态"映射重建转移表，使 setValue 跨新旧状态可达。
     */
    void liquidphase$rebuildNeighbours(Map<Map<Property<?>, Comparable<?>>, S> states);

    /** 给该状态挂重建后的 codec（仅 1.21.x 需要；26.3 的 codec 在定义上，此方法为空实现）。 */
    void liquidphase$setCodec(MapCodec<S> codec);
}
