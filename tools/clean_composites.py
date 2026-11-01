#!/usr/bin/env python3
'''删除已知不可生成（抽象基类/客户端专属 import）的组合方块类，并从工厂中移除其条目。'''
import sys
from pathlib import Path

DEAD = {
    "WireFallingParticlesLeavesBlock", "WireSpeleothemBlock", "WireSulfurSpikeBlock",
    "WireMultifaceSpreadeableBlock", "WireBaseCoralPlantTypeBlock", "WireBaseRailBlock",
    "WireCrossCollisionBlock", "WireLeavesBlock", "WireSignBlock",
    "WireChestBlock", "WireTrappedChestBlock", "WireEnderChestBlock", "WireMangrovePropaguleBlock",
    # 构造器跨版本签名不同（WoodType/BlockSetType 1.21+ 才有）→ 不生成组合方块
    "WireCeilingHangingSignBlock", "WireStandingSignBlock", "WireWallSignBlock",
    "WireWallHangingSignBlock", "WireTrapDoorBlock", "WireTrapDoorBlock", "WireCandleBlock",
    "WireWeatheringCopperTrapDoorBlock", "WireSculkSensorBlock", "WirePointedDripstoneBlock",
}


def main():
    d = Path(sys.argv[1])
    for name in DEAD:
        f = d / (name + ".java")
        if f.exists():
            f.unlink()
    fac = d / "CompositeFactories.java"
    if fac.exists():
        lines = fac.read_text(encoding="utf-8").splitlines()
        kept = [l for l in lines if not any(("new " + n + "(") in l for n in DEAD)]
        fac.write_text(chr(10).join(kept), encoding="utf-8")
    print("composites:", len(list(d.glob("*.java"))))


if __name__ == "__main__":
    main()
