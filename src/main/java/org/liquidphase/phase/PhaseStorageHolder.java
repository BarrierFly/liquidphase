package org.liquidphase.phase;

/**
 * 允许把 {@link PhaseStorage} 挂到 {@code Level} 上（鸭子接口，跨版本混入）。
 */
public interface PhaseStorageHolder {

    PhaseStorage liquidphase$phaseStorage();
}
