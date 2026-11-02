# 液相 Liquid Phase

**中文** | [English](#english)

让"可含水方块"（楼梯、台阶、墙、栅栏、告示牌、箱子、树叶、蜡、锁链……）能额外持有一个**相**：
熔岩、黑曜石、冰、霜冰，以及红石线。一格 = 固相（方块本身）+ 至多一个被含相。

支持 **Fabric** 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3。

---

## 这个 mod 做了什么

### 功能一：更多 logged 方块（双端必装）

- **含熔岩**：熔岩桶（或液体熔岩物品）右键可含水方块、发射器装填、熔岩源流进该格，都会让它变成"含熔岩方块"。
  内部熔岩是真实熔岩源：按宿主形状从没被固相挡住的方向流出，也会被相邻熔岩按形状灌入；
  整格发光 15；处于其中的实体会着火；随机刻复刻原版火蔓延。
- **凝固**：含熔岩方块旁边出现水 → 相变为**含黑曜石**，伴随嘶声粒子。
- **含冰 / 含霜冰**：雨天低温下"含水的方块"会冻结成含冰相（原版只会冻水源方块，本 mod 把判定扩展到宿主）；
  冰相在光照足够时融化回水相；下界/水蒸发维度融化时直接消失。
  霜冰相按原版 AGE 0–3 的裂纹推进与相邻融化规则演化（`frosted_0`…`frosted_3`）。
- **破坏保留**：破坏含相方块 → 掉落固相战利品，该格留成真实熔岩源 / 黑曜石 / 冰 / 霜冰。
- **收走相**：功能三开启时空桶左键；关闭时空桶右键。固体相（冰/霜冰/黑曜石）不响应右键收取。
- **放置吸收**：拿冰/黑曜石物品指向可含水方块 → 写入该格；把可含水方块放进冰/黑曜石/熔岩源所在的格子 → 吸收该相。
- **碰撞**：冰/黑曜石相按整格实心（与"相填满宿主空隙"的视觉一致）；熔岩相不额外加碰撞。
- **直接挖相**：准星指向相露出的部分时，挖掘速度与掉落按相材质计算（黑曜石需钻石镐；冰/霜冰不精准采集则化水，霜冰无物品）。

### 功能二：炼药锅（仅服务端，原版方块可表达的部分）

- 熔岩锅 + 水 → 掉落 1 个黑曜石、锅清空。
- 水锅 + 熔岩 → 按水位 3/2/1 掉落 石头 / 圆石 / 圆石台阶、锅清空。
- 空锅收流体：**只收六向直接接触的源**（水→水锅，熔岩→熔岩锅）。
- 混凝土粉末 + 水锅 → 消耗 1 水位，1 个粉末换成对应颜色的混凝土（16 色）。
- 细雪锅遇水/熔岩 → 满水锅。
- 发射器：液体物品填锅、空桶抽锅/抽液体、玻璃瓶从水锅装水瓶；漏斗：流体锅 ↔ 液体物品。
- **没有**药水锅、染色锅、装黑曜石的锅——这些需要方块实体，本版不做。

### 功能三：玩家与液体互动（**强制双端**）

- 手持**空桶**左键液体（水源、熔岩源、含熔岩方块的熔岩相）→ 瞬间"挖掉"并**掉落液体物品**（不直接进包）。
- 手持水/熔岩"液体物品"右键 → 像放方块一样放液体：对可含水方块写入相/含水，对空气放流体，
  **消耗 1 个、不返还空桶**。合成/其他消耗也不再返还空桶。
- 装了 mod 的客户端手持空桶时，准星会对液体启用流体射线（原版射线打不中液体）。
- 创造模式：仍只有空桶能挖，挖不掉落；放置不消耗。

> ⚠️ **功能三必须客户端与服务端都装**。只装服务端的原版客户端：桶的装/倒会被禁用，且因为原版射线打不中液体，无法挖液体（这是不装客户端功能时的固有限制）。

---

## 必须双端装 / 只要服务端装

| 功能 | 双端必装 | 仅服务端 |
| --- | --- | --- |
| 功能一（相属性、含熔岩/冰/黑曜石/霜冰、红石相） | ✅ | — |
| 功能二（炼药锅转化、发射器、漏斗） | — | ✅ |
| 功能三（液体物品、桶左键挖掘、准星射线） | ✅ | — |

**关闭双端功能以兼容原版客户端**：编辑 `config/liquidphase.json`，把 `phaseEnabled` 与 `liquidInteraction` 置 `false`。
关闭后服务端跳过状态注入与新方块注册，所有产生新相的路径返回拒绝；**旧存档里已有的相会直接丢失**（原版状态回退机制决定，无法补救）。

---

## 怎么玩

1. 装 Fabric Loader + Fabric API，把 jar 放进 `mods/`。
2. 拿熔岩桶点楼梯侧面 → 楼梯变含熔岩；用水桶点它 → 变含黑曜石，破坏楼梯掉楼梯 + 留下黑曜石。
3. 雨天放一个含水楼梯（水桶点楼梯）→ 低温会冻成含冰楼梯；光照够了会化回水相。
4. 空桶左键水源 → 掉落"水"物品；拿着它右键空气 → 放一个水源，物品消失、没有桶。
5. 熔岩锅旁边倒水 → 掉黑曜石；水锅上倒熔岩 → 按水位掉石头/圆石/圆石台阶。
6. 拿红石粉点楼梯 → 变成"含红石线的楼梯"：红石线铺满整格、按红石线规则衰减传递信号，
   被宿主遮住的面不输出；破坏它 → 留下对应功率的红石线。

---

## 兼容性注意

- **移除本 mod 会丢失所有相**（含熔岩/冰/黑曜石/红石相）。降级前请先清场。
- 本 mod 独占"相"语义，**不与 Fluidlogged 这类通用 fluidlogged mod 互通**（共存不崩，但互相看不见对方的数据）。
- 相位渲染用 blockstate JSON 覆盖（multipart + 内缩模型），与其他**重写过原版 blockstate JSON 的资源包/模组**会冲突。
- 26.x 的实验性红石评估器（`REDSTONE_EXPERIMENTS`）下，含红石相方块**不保证**与原版红石线行为一致。
- **1.21.1 暂不注册红石相组合方块**（该版本宿主形状表对组合状态仍返回 null，形状兜底完善后开启）；
  其余 5 个版本的红石相在楼梯/台阶/墙/栅栏共 41 种宿主上可用。
- 状态数会随宿主数量增长（每宿主 ×7 相状态，红石相组合方块再 ×16 功率）；已在各版本实测编译与加载，
  1.19.4 这类低内存环境请留意内存占用。
- 1.19.4/1.20.1 的状态注入使用 **Statement** 依赖（会自动随 mod 提供）。

---

## 许可

WTFPL v2（见 `LICENSE`）。引用的开源项目与许可：

- [Lavalogged](https://github.com/JimiIT92/Lavalogged)（MIT）：状态注入器与桶/放置 mixin 的方法论。
- [Statement](https://github.com/Virtuoel/Statement)（MIT）：1.19.4/1.20.1 的运行时方块状态属性注入。
- [cauldronfix](https://github.com/Ryan100c/cauldronfix)（CC0）：炼药锅行为对照（混凝土映射、水位掉落表、发射器/漏斗映射）。
- [Fluidlogged](https://github.com/Leximon/Fluidlogged)（MIT）：旁路流体与流体交互的参考。
- cauldron-expansion（CC-BY-NC-SA）：**仅作行为对照**，未复制代码或资源。
- 反编译源码来自 [guardian](https://github.com/hibiii/guardian)，仅用于本地比对，不随仓库分发。

---

## English

Let waterloggable blocks (stairs, slabs, walls, fences, signs, chests, leaves, chains, candles, …) hold a **second phase**:
lava, obsidian, ice, frosted ice, and redstone wire. One block = solid phase (the block itself) + at most one contained phase.

**Supported:** Fabric 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3.

### Feature 1 — more logged blocks (required on both sides)

- **Lava inside**: a lava bucket (or a liquid-lava item), a dispenser, or a lava source flowing into a waterloggable block fills it.
  The contained lava is a real source: it flows out through the faces the solid phase leaves open, is fed by neighbouring lava using
  vanilla shape checks, emits light 15, ignites entities inside, and reproduces vanilla fire spread on random ticks.
- **Solidification**: water next to a lava-containing block turns its phase into **obsidian** (with the vanilla fizz).
- **Ice / frosted ice**: during cold precipitation, water-containing blocks freeze (vanilla only freezes water source blocks;
  this mod extends the check to hosts). Ice melts back to water when lit; in water-evaporating dimensions it simply disappears.
  Frosted ice follows the vanilla AGE 0–3 crack progression and neighbour rules (`frosted_0`…`frosted_3`).
- **Breaking keeps the phase**: breaking a phased block drops the host loot and leaves the real lava/obsidian/ice/frosted ice block behind.
- **Removing the phase**: empty bucket with left-click when feature 3 is on (right-click when it is off). Solid phases cannot be collected.
- **Placement absorption**: point an ice/obsidian item at a waterloggable block to fill it; place a waterloggable block into a tile holding
  ice/obsidian/a lava source to absorb that phase.
- **Collision**: ice and obsidian phases are full-cube (matching the "phase fills the host's gaps" visuals); lava adds no collision.
- **Mining the phase directly**: when the crosshair points at an exposed phase, mining speed and drops follow the phase material
  (obsidian needs a diamond pickaxe; ice melts to water without silk touch; frosted ice has no item).

### Feature 2 — cauldron behaviour (server-side only, vanilla-representable part)

Lava cauldron + water drops one obsidian and empties; water cauldron + lava drops stone/cobblestone/cobblestone slab by level 3/2/1;
empty cauldrons collect only **directly adjacent** source fluid; concrete powder + water cauldron hardens into 16 coloured concretes
(one level each); powder-snow cauldron + water/lava becomes a water cauldron; dispensers fill/empty cauldrons and bottles;
hoppers move liquid items in and out of fluid cauldrons.
**Not** included: potion cauldrons, dyed-water cauldrons, obsidian-block cauldrons — all need block entities.

### Feature 3 — player ↔ liquid interaction (**required on both sides**)

Left-click liquids with an **empty bucket** to instantly mine them and **drop the liquid item**; right-click with a water/lava liquid item
to place liquid like a block (**one consumed, no bucket returned**, and no remainder in recipes). A modded client enables fluid rays for the
crosshair while holding an empty bucket. Creative mode keeps bucket-only mining with no drops and free placement.

> ⚠️ Feature 3 needs the mod on **both** sides. With a vanilla client on a modded server, bucket fill/place is disabled and liquids cannot be
> mined (the vanilla crosshair ray never hits fluids).

### Both sides vs server only

| Feature | Both sides | Server only |
| --- | --- | --- |
| Feature 1 (phases, composites) | ✅ | — |
| Feature 2 (cauldrons, dispensers, hoppers) | — | ✅ |
| Feature 3 (liquid items, mining, raycast) | ✅ | — |

Turn the two-sided features off for vanilla clients via `config/liquidphase.json` (`phaseEnabled`, `liquidInteraction` = `false`).
Existing phases in old worlds are then **lost permanently** (vanilla drops unknown states).

### Compatibility notes

Removing this mod loses every phase. It does not interoperate with generic fluidlogged mods (coexistence is crash-free, data is not shared).
Phase rendering overrides vanilla blockstate JSONs, so resource packs/mods that rewrite those files conflict.
Under the 26.x experimental redstone evaluator (`REDSTONE_EXPERIMENTS`) redstone-phase blocks are not guaranteed to match vanilla wire behaviour.
Redstone-phase composites are **not registered on 1.21.1 yet** (host shape tables still return null for composite states there); the other five
versions provide them for 41 stair/slab/wall/fence hosts.
1.19.4/1.20.1 use **Statement** for state injection (shipped as a dependency).

### Licence

WTFPL v2 — see `LICENSE`. Credits: Lavalogged (MIT), Statement (MIT), cauldronfix (CC0), Fluidlogged (MIT);
cauldron-expansion (CC-BY-NC-SA) used for **behaviour comparison only**, no code or assets copied.
Decompiled sources via guardian, used locally for verification only.
