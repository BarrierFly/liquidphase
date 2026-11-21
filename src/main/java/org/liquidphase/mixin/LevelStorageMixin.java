package org.liquidphase.mixin;

import net.minecraft.world.level.Level;
import org.liquidphase.phase.PhaseStorage;
import org.liquidphase.phase.PhaseStorageHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给每个 {@link Level} 挂一张旁路表（方案 C）。服务端权威、客户端由网络包镜像。
 */
@Mixin(Level.class)
public abstract class LevelStorageMixin implements PhaseStorageHolder {

    @Unique
    private final PhaseStorage liquidphase$storage = new PhaseStorage();

    @Override
    public PhaseStorage liquidphase$phaseStorage() {
        return this.liquidphase$storage;
    }
}
