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
    /*@Shadow
    @Final
    @Mutable
    private Property<?>[] propertyKeys;

    @Shadow
    @Final
    @Mutable
    private Comparable<?>[] propertyValues;

    @Shadow
    private S[][] neighbors;
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
        /*Property<?>[] newKeys = new Property<?>[this.propertyKeys.length + 1];
        System.arraycopy(this.propertyKeys, 0, newKeys, 0, this.propertyKeys.length);
        newKeys[this.propertyKeys.length] = property;
        Comparable<?>[] newValues = new Comparable<?>[this.propertyValues.length + 1];
        System.arraycopy(this.propertyValues, 0, newValues, 0, this.propertyValues.length);
        newValues[this.propertyValues.length] = value;
        this.propertyKeys = newKeys;
        this.propertyValues = newValues;
         *///?}
    }

    @Override
    public Map<Property<?>, Comparable<?>> liquidphase$snapshotValues() {
        //? if 1.21.1 || 1.21.10 || 1.21.11 {
        Reference2ObjectArrayMap<Property<?>, Comparable<?>> snapshot = new Reference2ObjectArrayMap<>();
        snapshot.putAll(this.values);
        return snapshot;
        //?} else {
        /*Reference2ObjectArrayMap<Property<?>, Comparable<?>> snapshot =
            new Reference2ObjectArrayMap<>(this.propertyKeys.length);
        for (int i = 0; i < this.propertyKeys.length; i++) {
            snapshot.put(this.propertyKeys[i], this.propertyValues[i]);
        }
        return snapshot;
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
        /*Property<?>[] keys = this.propertyKeys;
        S[][] matrix = (S[][])(new StateHolder[keys.length][]);
        for (int i = 0; i < keys.length; i++) {
            List<?> possibleValues = keys[i].getPossibleValues();
            S[] row = (S[])(new StateHolder[possibleValues.size()]);
            for (int j = 0; j < possibleValues.size(); j++) {
                Comparable<?> pivot = (Comparable<?>) possibleValues.get(j);
                Reference2ObjectArrayMap<Property<?>, Comparable<?>> query = new Reference2ObjectArrayMap<>(keys.length);
                for (int k = 0; k < keys.length; k++) {
                    query.put(keys[k], k == i ? pivot : this.propertyValues[k]);
                }
                row[j] = states.get(query);
            }
            matrix[i] = row;
        }
        this.neighbors = matrix;
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
