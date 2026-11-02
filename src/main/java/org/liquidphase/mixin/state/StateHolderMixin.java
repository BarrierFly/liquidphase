package org.liquidphase.mixin.state;

// 仅用于 1.21.1+ 的自带注入器路径；1.19.4/1.20.1 由 Statement 完成同等工作。
//? if !(1.19.4 || 1.20.1) {

import com.mojang.serialization.MapCodec;
import com.google.common.collect.Table;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.liquidphase.state.StateHolderDuck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 注入 {@link StateHolder}：供 {@code PhaseStateRefresher} 扩展既有状态、重建转移表。
 * 三个版本结构（guardian 逐版本核实）：1.21.1 值表 + Guava Table 邻居；
 * 1.21.10/11 值表 + 属性→状态数组邻居；26.3 并行数组 + 邻居矩阵。
 */
@Mixin(StateHolder.class)
public abstract class StateHolderMixin<O, S extends StateHolder<O, S>> implements StateHolderDuck<O, S> {

    //? if 1.21.1 || 1.21.10 || 1.21.11 {
    @Shadow
    @Final
    @Mutable
    private Reference2ObjectArrayMap<Property<?>, Comparable<?>> values;

    @Shadow
    @Final
    @Mutable
    protected MapCodec<S> propertiesCodec;

    @Shadow
    public abstract void populateNeighbours(Map<Map<Property<?>, Comparable<?>>, S> map);
    //?}

    //? if 1.21.1 {
    /*@Shadow
    @Final
    @Mutable
    private Table<Property<?>, Comparable<?>, S> neighbours;
    *///?} elif 1.21.10 || 1.21.11 {
    @Shadow
    @Final
    @Mutable
    private Map<Property<?>, S[]> neighbours;
    //?} else {
    /*// 26.3：并行数组 + 邻居矩阵，全部通过反射访问（无 @Shadow）。
     *///?}

    @Override
    public void liquidphase$addValue(Property<?> property, Comparable<?> value) {
        //? if 1.21.1 || 1.21.10 || 1.21.11 {
        // 原版给每个状态 Reference2ObjectArrayMap，但其他 mod 可能换成只读包装：
        // 整体替换而不是原地改（fork 注释）。
        Reference2ObjectArrayMap<Property<?>, Comparable<?>> replaced = new Reference2ObjectArrayMap<>(this.values);
        replaced.put(property, value);
        this.values = replaced;
        //?} else {
        /*// 26.3：反射写回并行数组（propertyKeys / propertyValues）
        try {
            java.lang.reflect.Field keysF = StateHolder.class.getDeclaredField("propertyKeys");
            java.lang.reflect.Field valsF = StateHolder.class.getDeclaredField("propertyValues");
            keysF.setAccessible(true);
            valsF.setAccessible(true);
            Property<?>[] keys = (Property<?>[]) keysF.get(this);
            Comparable<?>[] vals = (Comparable<?>[]) valsF.get(this);
            Property<?>[] newKeys = new Property<?>[keys.length + 1];
            System.arraycopy(keys, 0, newKeys, 0, keys.length);
            newKeys[keys.length] = property;
            Comparable<?>[] newVals = new Comparable<?>[vals.length + 1];
            System.arraycopy(vals, 0, newVals, 0, vals.length);
            newVals[vals.length] = value;
            keysF.set(this, newKeys);
            valsF.set(this, newVals);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("liquidphase: cannot extend 26.3 state", e);
        }
         *///?}
    }

    @Override
    public Map<Property<?>, Comparable<?>> liquidphase$snapshotValues() {
        //? if 1.21.1 || 1.21.10 || 1.21.11 {
        Reference2ObjectArrayMap<Property<?>, Comparable<?>> snapshot = new Reference2ObjectArrayMap<>();
        snapshot.putAll(this.values);
        return snapshot;
        //?} else {
        /*// 26.3：反射读并行数组
        try {
            java.lang.reflect.Field keysF = StateHolder.class.getDeclaredField("propertyKeys");
            java.lang.reflect.Field valsF = StateHolder.class.getDeclaredField("propertyValues");
            keysF.setAccessible(true);
            valsF.setAccessible(true);
            Property<?>[] keys = (Property<?>[]) keysF.get(this);
            Comparable<?>[] vals = (Comparable<?>[]) valsF.get(this);
            Reference2ObjectArrayMap<Property<?>, Comparable<?>> snapshot = new Reference2ObjectArrayMap<>(keys.length);
            for (int i = 0; i < keys.length; i++) {
                snapshot.put(keys[i], vals[i]);
            }
            return snapshot;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("liquidphase: cannot read 26.3 state", e);
        }
         *///?}
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void liquidphase$rebuildNeighbours(Map<Map<Property<?>, Comparable<?>>, S> states) {
        //? if 1.21.10 || 1.21.11 {
        this.neighbours = null;
        this.populateNeighbours(states);
        //?} elif 1.21.1 {
        /*this.neighbours = null;
        this.populateNeighbours(states);
         *///?} else {
        /*// 26.3：反射重建邻居矩阵 neighbors[propertyIndex][valueIndex]
        try {
            java.lang.reflect.Field keysF = StateHolder.class.getDeclaredField("propertyKeys");
            java.lang.reflect.Field valsF = StateHolder.class.getDeclaredField("propertyValues");
            java.lang.reflect.Field neighF = StateHolder.class.getDeclaredField("neighbors");
            keysF.setAccessible(true);
            valsF.setAccessible(true);
            neighF.setAccessible(true);
            Property<?>[] keys = (Property<?>[]) keysF.get(this);
            Comparable<?>[] vals = (Comparable<?>[]) valsF.get(this);
            S[][] matrix = (S[][]) java.lang.reflect.Array.newInstance(StateHolder.class, keys.length, 0);
            for (int i = 0; i < keys.length; i++) {
                List<?> possible = keys[i].getPossibleValues();
                S[] row = (S[]) java.lang.reflect.Array.newInstance(StateHolder.class, possible.size());
                for (int j = 0; j < possible.size(); j++) {
                    Comparable<?> pivot = (Comparable<?>) possible.get(j);
                    Reference2ObjectArrayMap<Property<?>, Comparable<?>> query = new Reference2ObjectArrayMap<>(keys.length);
                    for (int k = 0; k < keys.length; k++) {
                        query.put(keys[k], k == i ? pivot : vals[k]);
                    }
                    row[j] = states.get(query);
                }
                matrix[i] = row;
            }
            neighF.set(this, matrix);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("liquidphase: cannot rebuild 26.3 neighbours", e);
        }
         *///?}
    }

    @Override
    public void liquidphase$setCodec(MapCodec<S> codec) {
        //? if 1.21.1 || 1.21.10 || 1.21.11 {
        this.propertiesCodec = codec;
        //?}
    }
}

//?}
