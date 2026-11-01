package org.liquidphase.mixin.state;

// 仅用于 1.21.1+ 的自带注入器路径；1.19.4/1.20.1 由 Statement 完成同等工作。
//? if !(1.19.4 || 1.20.1) {

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.liquidphase.state.StateDefinitionDuck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 {@link StateDefinition}：捕获状态工厂，供 {@code PhaseStateRefresher} 扩展定义。
 * 结构差异（fork 已验证）：1.21.x 的 codec 在状态实例上、工厂收 (map, codec)；
 * 26.3 的 codec 挪到定义上、工厂收 (keys[], values[])。
 */
@Mixin(StateDefinition.class)
public abstract class StateManagerMixin<O, S extends StateHolder<O, S>> implements StateDefinitionDuck<O, S> {

    @Shadow
    @Final
    @Mutable
    private ImmutableSortedMap<String, Property<?>> propertiesByName;

    @Shadow
    @Final
    @Mutable
    private ImmutableList<S> states;

    @Shadow
    public abstract O getOwner();

    @Unique
    private StateDefinition.Factory<O, S> liquidphase$factory;

    // 26.3 起 codec 从状态挪到定义上；1.21.x 没有这个字段。
    //? if 26.3 {
    /*@Shadow
    @Final
    @Mutable
    private MapCodec<S> propertiesCodec;
    *///?}

    @Inject(method = "<init>", at = @At("RETURN"))
    private void liquidphase$captureFactory(Function<O, S> defaultState, O owner, StateDefinition.Factory<O, S> factory, Map<String, Property<?>> properties, CallbackInfo info) {
        this.liquidphase$factory = factory;
    }

    @Invoker("appendPropertyCodec")
    private static <S extends StateHolder<?, S>, T extends Comparable<T>> MapCodec<S> liquidphase$appendPropertyCodec(MapCodec<S> codec, Supplier<S> defaultState, String name, Property<T> property) {
        throw new AssertionError();
    }

    @Override
    public StateDefinition.Factory<O, S> liquidphase$getFactory() {
        return this.liquidphase$factory;
    }

    @Override
    public MapCodec<S> liquidphase$rebuildCodec(ImmutableSortedMap<String, Property<?>> properties, Supplier<S> defaultState) {
        MapCodec<S> codec = MapCodec.of(Encoder.empty(), Decoder.unit(defaultState));
        for (Entry<String, Property<?>> entry : properties.entrySet()) {
            codec = liquidphase$appendPropertyCodec(codec, defaultState, entry.getKey(), entry.getValue());
        }
        return codec;
    }

    @Override
    public void liquidphase$setDefinition(ImmutableSortedMap<String, Property<?>> properties, ImmutableList<S> newStates, MapCodec<S> codec) {
        this.propertiesByName = properties;
        this.states = newStates;
        //? if 26.3 {
        /*this.propertiesCodec = codec;
         *///?}
    }

    @Override
    @SuppressWarnings("unchecked")
    public S liquidphase$createState(ImmutableSortedMap<String, Property<?>> properties, Map<Property<?>, Comparable<?>> values, MapCodec<S> codec) {
        //? if 1.21.1 || 1.21.10 || 1.21.11 {
        Reference2ObjectArrayMap<Property<?>, Comparable<?>> map = new Reference2ObjectArrayMap<>();
        for (Entry<Property<?>, Comparable<?>> entry : values.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return this.liquidphase$factory.create(this.getOwner(), map, codec);
        //?} else {
        /*Property<?>[] keys = properties.values().toArray(new Property<?>[0]);
        Comparable<?>[] newValues = new Comparable<?>[keys.length];
        for (int i = 0; i < keys.length; i++) {
            newValues[i] = values.get(keys[i]);
        }
        return this.liquidphase$factory.create(this.getOwner(), keys, newValues);
         *///?}
    }
}

//?}
