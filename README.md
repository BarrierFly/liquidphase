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
  > ⚠️ "被相邻熔岩灌入"对台阶/蜡烛/营火/干化恶魂不成立（它们自己覆写了 `placeLiquid`）：
  > 熔岩桶右键、放置吸收照常可用。见 `docs/host-coverage-audit.md` §4.3。
- **凝固**：含熔岩方块旁边出现水 → 相变为**含黑曜石**，伴随嘶声粒子。
- **含冰 / 含霜冰**：雨天低温下"含水的方块"会冻结成含冰相（原版只会冻水源方块，本 mod 把判定扩展到宿主）；
  冰相在光照足够时融化回水相；下界/水蒸发维度融化时直接消失。
  霜冰相按原版 AGE 0–3 的裂纹推进与相邻融化规则演化（`frosted_0`…`frosted_3`）。
- **破坏保留**：破坏含相方块 → 掉落固相战利品，该格留成真实熔岩源 / 黑曜石 / 冰 / 霜冰。
  **含相方块本身可以像原版方块一样正常破坏**：准星落在宿主露出的部分就按宿主材质挖，落在相露出的部分才按相材质挖
  （`phase=none` 的兄弟状态参与命中判定，形状表型宿主也不会误判）。
  > ⚠️ 带 `offsetType` 的 4–5 个宿主（`hanging_roots`、`mangrove_propagule`、`pointed_dripstone`、
  > `small_dripleaf`、26.3 的 `sulfur_spike`）例外：破坏时**不留下相**。
- **收走相**：功能三开启时空桶左键；关闭时空桶右键。固体相（冰/霜冰/黑曜石）不响应右键收取。
- **放置吸收**：拿冰/黑曜石物品指向可含水方块 → 写入该格；把可含水方块放进冰/黑曜石/熔岩源所在的格子 → 吸收该相。
- **碰撞**：固相（冰/黑曜石）的组合碰撞 = **宿主碰撞 ∪ 整格实心**——栅栏/墙这类碰撞柱
  延伸到方块外（高 1.5 格）的宿主，方块外的部分照常保留（0.1.21 及以前被整格覆盖丢失）；
  熔岩相不额外加碰撞（内部是流体）；红石相不加碰撞。
  对所有宿主一致（状态级注入），包括自己覆写碰撞形状的栅栏/玻璃板/铁栏杆、墙、
  墙悬挂告示牌、脚手架、`sculk_shrieker`、`big_dripleaf`。逐版本数字见 `docs/host-coverage-audit.md`。
- **直接挖相**：准星指向相露出的部分时，挖掘速度与掉落按相材质计算（黑曜石需钻石镐；冰/霜冰不精准采集则化水，霜冰无物品）。
- **所有宿主的相都是"内缩叠加层"**：相材质是一层比方块小 0.25px 的立方体，
  宿主模型始终照常渲染，从宿主贴图的缝隙里露出相——和**含熔岩**的观感一致。
  树叶/铜格栅/红树根这类整格宿主也一样：不透明树叶遮住相、透明缝隙透出相，
  不再"整格替换成相材质、树叶消失"。
  > ⚠️ 这也意味着**薄宿主看不到自己的本体**：栅栏柱子只有 6..10、横杆 7..9，全在相立方体
  > （0.25..15.75）内部，栅栏会被相完整包住。这是"相就是整块材质"的直接后果，
  > 不是缺陷；要让栅栏可见必须改相的形状（挖空或贴合宿主），属于另一个设计决策。

### 功能二：炼药锅（仅服务端，原版方块可表达的部分）

- 熔岩锅 + 水 → 掉落 1 个黑曜石、锅清空。
- 水锅 + 熔岩 → 按水位 3/2/1 掉落 石头 / 圆石 / 圆石台阶、锅清空。
- 空锅收流体：**只收六向直接接触的源**（水→水锅，熔岩→熔岩锅）。
- 混凝土粉末 + 水锅 → 消耗 1 水位，1 个粉末换成对应颜色的混凝土（16 色）。
- 细雪锅遇水/熔岩 → 满水锅。
- 发射器：液体物品填锅、空桶抽锅/抽液体、玻璃瓶从水锅装水瓶；漏斗：流体锅 ↔ 液体物品。
- **桶与锅**（功能三开启时）：手持空桶**左键**水锅/熔岩锅 → 整锅一次拆空并掉落对应液体物品，
  **锅本身保留**；只有满锅才能取（水锅要求 3 级水位）。右键取水被禁用（桶不再被换成液体物品，
  也不再凭空消失）；拿液体物品往空锅/未满水锅倾倒照常填锅，消耗 1 个且不返还空桶。
  发射器空桶抽锅、漏斗抽锅同样按满锅口径。功能三关闭时全部回到原版桶语义。
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
6. 拿红石粉点楼梯 → 楼梯变成**含红石线的楼梯**：方块本身还是那格楼梯，只是多了一个 `filling` 取值
   （`redstone_0`…`redstone_15` 编码功率）。外面罩一层 16³ 红石粉尘外壳
   （透明底 cutout，贴图是**短碎屑铺满整面**的疏松红石纹理，
   **亮度随功率变化**——0 档接近熄灭、15 档满亮，和原版红石线一致），
   形状/碰撞/掉落仍是楼梯自己的。
   功率语义：拿红石粉点它 = 新放置一根红石粉，落地时是 0、立即按周围信号源结算
   （有电源就是 15，被宿主遮住的面不接收）；把相容方块放进红石线格 = 吸收那格已有的线，
   功率沿用原线。按红石线规则衰减传递信号（对相邻红石线 -1，对灯/活塞等元件满功率），
   被宿主遮住的面不输出；破坏它 → 留下对应功率的红石线。
   也可以反过来：把楼梯/台阶等可含水方块**放进红石线所在的那一格**，那条线就被吸进宿主、
   变成含红石相的方块（功率沿原线）。这一步走的是普通方块放置流程，和谁在放无关：
7. 拿空桶**左键**水锅/熔岩锅 → 整锅拆空、掉落"水"/"熔岩"物品；**锅本身保留**。
   只有满锅才能取（水锅必须是 3 级）；右键对着锅不再取水。

---

## 兼容性注意

- **移除本 mod 会丢失所有相**（含熔岩/冰/黑曜石/红石相）。降级前请先清场。
- **相属性名是 `filling`**：换用属性名不同的版本会丢相，切换前请先清场。
- 本 mod 独占"相"语义，**不与 Fluidlogged 这类通用 fluidlogged mod 互通**
  （共存不崩，但互相看不见对方的数据）。
- **与第三方资源包自动兼容**：资源包覆盖宿主 blockstate
  （`assets/minecraft/blockstates/<宿主>.json`——栅栏/树叶/告示牌类包很常见）时，
  客户端会把相部件**合并进包的定义**，包只生效于宿主 part、相照常显示，
  并且不会改动包的宿主几何。日志有一条 `merged phase overlays into …`，
  无法合并时为 `overlay could not be merged …`。针对本 mod 的资源包
  （引用 `liquidphase:` 模型）不受合并影响，仍可整体覆盖。
  配置 `packMerge=false` 会**关闭合并**，此时相部件整体丢失，仅用于排查问题。
- 26.x 的实验性红石评估器（`REDSTONE_EXPERIMENTS`）下，含红石相方块**不保证**
  与原版红石线行为一致。
- 红石相是**宿主方块上的 `filling` 取值**（`redstone_0`…`redstone_15`，功率直接编码在
  取值里），不注册任何新方块；方块仍是原版楼梯/台阶/墙/栅栏。渲染外壳对**全部**相容宿主
  生成（含树叶/格栅/玻璃板/铁栏杆/梯子/锁链等透明方块），**外壳亮度随功率变化**。
  外壳跟着"运行时真的拿到 `filling` 属性的方块"走：清单由 `tools/gen_phase_blocks.py`
  从原版 `Blocks.java` 推导，并用运行时注入数硬校验——漏一个，那个方块含红石相时就
  什么都不显示。
- **吸收按"可替换填充相"规则判定**：向宿主写填充材料时只看**现有填充相**——
  空格 / 含水 / 含熔岩相可替换（冰替换水=冻结、熔岩或黑曜石替换水、黑曜石覆盖熔岩相、
  红石粉也能替换水/熔岩），冰/霜冰/黑曜石/红石相不可替换、材料按不可放置处理。
  水的流体路径不变：水桶/水流仍只进无相宿主，水不能直接替换熔岩相
  （含熔岩相宿主遇水邻居走凝固成黑曜石相）。
- **组装时的红石相初始功率按"是不是重新放置"区分**（0.1.22 起）：
  **新放置**（红石粉点宿主、改写层把线状态写进宿主格）落地即"单独放置红石粉"的初始状态
  ——功率 0，随后立即按邻居结算出真实功率（0.1.21 及以前点粉直接写 15，无人纠正）；
  **吸收**（宿主放进/落进红石线格）那格的红石线本来就在、不是重新放置，
  功率**沿用原线**，落地不重算。
- **告示牌/箱子/屏障/光源方块等原生"不渲染方块模型"的宿主也能显示相**：带相状态一律按
  方块模型渲染，宿主本体仍由各自的原版渲染路径绘制。
- **状态数**：`filling` 共 24 个取值（其中 16 个 `redstone_*` 编码功率），
  1.21.11 加载后总状态数约 51 万，启动注入时间相应变长；低内存环境请留意。
- 含固相方块的**相覆盖层**是一层内缩 0.25px 的相材质立方体，碰撞是**宿主 ∪ 整格的组合形状**
  （对所有宿主一致，挂在状态级；栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架这些
  自己覆写碰撞的宿主也生效——栅栏/墙延伸到方块外的碰撞柱照常保留）。挖掘按双口径：
  准星落在宿主露出的部分就挖宿主，落在相露出的部分就挖相。黑曜石相需要钻石镐才能挖动相本身
  （没有钻石镐时按黑曜石速度计算，非常慢——有意的设计，不是卡死）。
  - 宿主模型**始终照常渲染**，相从宿主贴图的缝隙里露出；整格宿主（树叶/铜格栅/红树根）
    与台阶的各变体都如此，与含熔岩的观感一致。
  - ⚠️ **薄宿主的本体看不见**：栅栏柱 6..10、横杆 7..9、玻璃板/铁栏杆的板面等几何
    整个落在相立方体（0.25..15.75）内部，会被相完整包住。这是"相就是整块材质"的
    直接后果，不是缺陷；要让这些宿主可见，需要让相按宿主形状挖空或贴合。
- **熔岩桶右键按原版倒水优先级**（与原版"水桶点方块"完全同规则）：
  原版交互不消费右键的宿主（楼梯/台阶/栅栏/玻璃板/树叶等）→ 右键直接灌入；
  原版交互消费右键的宿主（**活板门开关、箱子/末影箱开箱、光源方块调亮度、告示牌开编辑、
  装饰陶罐收纳、搁物架/铜傀儡雕像交互**）→ 原版行为优先、不灌入，
  和"水桶点活板门只会开关它"一样。要给这些宿主灌相：**潜行右键**（原版绕过方块交互）、
  发射器、或放置吸收（拿方块放进熔岩源格）。
  1.21.1+ 的 `barrier` 仍是相容宿主（红石相正常显示），但桶/放置吸收/相邻熔岩充不进相：
  原版对 barrier 的流体接受是"创造模式限定"，而相充入的探测调用不携带玩家身份
  （熔岩桶点 barrier 只是拒绝，不会崩）。
- 1.19.4/1.20.1 的状态注入使用 **Statement** 依赖（会自动随 mod 提供）。
- **依赖下限**：`fabricloader` 按各版本实测最小需求声明（1.19.4 ≥0.15.0、1.20.1 ≥0.16.10、
  1.21.1 ≥0.15.11、1.21.10 ≥0.17.0、1.21.11 ≥0.17.3、26.3 ≥0.19.3），
  `minecraft` 仍是补丁级匹配（`~x.y.z`），`fabric-api` 与 `statement` 为必需依赖。
- ⚠️ **旧 loader + 新 JDK 会启动失败**：loader 0.15.x/0.16.x 内置的 Mixin 读不了 JDK 25 的类
  （`Unsupported class file major version 69`），这不是本 mod 的问题。用 JDK 25 时请把 loader
  升到较新版本，或改用 JDK 17/21 启动。

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
  The phased block itself breaks like a vanilla block: aim at a part where the host is exposed and it mines at host speed,
  aim at an exposed phase and it mines as the phase material.
- **Removing the phase**: empty bucket with left-click when feature 3 is on (right-click when it is off). Solid phases cannot be collected.
- **Placement absorption**: point an ice/obsidian item at a waterloggable block to fill it; place a waterloggable block into a tile holding
  ice/obsidian/a lava source to absorb that phase.
- **Collision**: the solid-phase (ice/obsidian) combined collision is the **host's collision ∪ a full
  cube** — hosts whose collision column extends above the block (fences and walls are 1.5 blocks tall)
  keep the part outside the block (up to 0.1.21 the full cube replaced the host shape and that part was
  lost); lava adds no collision (it is a fluid) and the redstone phase adds none either.
- **Mining the phase directly**: when the crosshair points at an exposed phase, mining speed and drops follow the phase material
  (obsidian needs a diamond pickaxe; ice melts to water without silk touch; frosted ice has no item).
- **Full-cube hosts**: leaves, copper grates and mangrove roots (whose vanilla model already fills the whole 16³ cube) render as
  the phase material when phased — otherwise the overlay would be hidden inside the host model. `phase=none`/`lava` still show the host.

### Feature 2 — cauldron behaviour (server-side only, vanilla-representable part)

Lava cauldron + water drops one obsidian and empties; water cauldron + lava drops stone/cobblestone/cobblestone slab by level 3/2/1;
empty cauldrons collect only **directly adjacent** source fluid; concrete powder + water cauldron hardens into 16 coloured concretes
(one level each); powder-snow cauldron + water/lava becomes a water cauldron; dispensers fill/empty cauldrons and bottles;
hoppers move liquid items in and out of fluid cauldrons.

With feature 3 on, buckets interact with cauldrons the "liquid item" way: **left-click** a water/lava cauldron with an empty bucket
to empty the whole cauldron and drop the matching liquid item **without breaking the cauldron itself**; only a full cauldron
(water cauldron at level 3) can be emptied. Right-click filling is disabled (the bucket is never swapped away);
pouring a liquid item into an empty/partially filled cauldron still fills it but consumes one item with no bucket returned.
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
The phase property is `filling`. Phase rendering overrides vanilla blockstate JSONs, and **resource packs are merged automatically**: when a
pack replaces a host's blockstate JSON, the client merges the phase parts into the winning definition, so the pack only affects the host part,
the phase visuals survive, and the pack's own host geometry is left untouched. A log line reports every merge
(`merged phase overlays into …`), and `overlay could not be merged …` when a definition cannot be merged. Packs made for this mod
(referencing `liquidphase:` models) still override freely; `packMerge=false` disables merging entirely (phase parts are then lost — for
troubleshooting only).
Filling absorption judges **the existing filling's replaceability**: empty / waterlogged / lava-filled hosts accept any filling
material (ice replaces water, obsidian covers lava, redstone dust replaces water or lava); ice/frosted/obsidian/redstone fillings are not
replaceable. The water fluid path is unchanged — buckets and flowing water still only enter empty hosts.
Under the 26.x experimental redstone evaluator (`REDSTONE_EXPERIMENTS`) redstone-phase blocks are not guaranteed to match vanilla wire behaviour.
Redstone-phase composites are available on **all six versions** (every phase-capable host, not just stairs/slabs/walls/fences).
The shell is generated for **all** phase hosts, including transparent ones (leaves, grates, glass panes, iron bars, ladders, chains).
Composites render as **the vanilla host model with a full-cube redstone-dust shell on top**:
the shell uses a porous redstone-wire texture that fills each face, transparent pixels let the host show through (cutout),
the crosshair outline covers the whole cube (collision stays with the host), and they have **no item form**; they are only created
through redstone-dust/phase-block interactions (either dust on a host, or a host block item placed into the redstone-wire tile
through the normal block placement pipeline). Power semantics differ by direction: placing dust starts the phase at power 0 and settles it
against the neighbours immediately, while a host placed into a wire tile absorbs that wire's current power (absorption is not re-placement).
Signals decay by 1 into neighbouring wires/composites
but stay at full power for lamps, pistons and other components.
Phased states never take part in vanilla same-block face culling, so two adjacent phased blocks do not lose the shared face.
The inset phase overlay and the redstone shell carry **no** `cullface`: their faces are not on the block boundary, so vanilla
culling would drop faces against partial neighbours (slabs/stairs) that the overlay actually covers. The full-cube overlays stay on
the boundary and keep `cullface`.
Phased states are forced onto the CUTOUT layer via `ItemBlockRenderTypes.getChunkRenderType` (only when the original layer is
SOLID); translucent hosts (glass panes, stained glass) keep TRANSLUCENT. The same mixin also fixes
`getMovingBlockRenderType`, so falling blocks and piston-moved phased blocks are not drawn on the opaque layer.
This must be a mixin: the `BlockRenderLayerMap` table is rebuilt by the resource reload and silently loses mappings
registered during client init.
Phased blocks with a solid phase look like a full cube of that material, and the combined collision
(host ∪ full cube) applies to **every** host (state-level, including fences, panes, bars, walls, wall
hanging signs and scaffolding that override the block-level shape — the fence/wall collision columns
that stick out above the block are kept); mining is two-lane: aim at the exposed host and it mines at
host speed, aim at the exposed phase and it mines as the phase material (obsidian still needs a diamond
pickaxe — that slowness is intended).
Every host keeps rendering its own model while phased: the phase is a 0.25px-inset cube of the phase material, so it shows through the
gaps in the host's texture (opaque leaves hide it, transparent pixels reveal it) — the same look as a lava-filled host.
⚠️ Thin hosts (fences, panes, bars, walls, ladders) are therefore enclosed by the phase: their geometry lies entirely inside the inset
cube, so the host itself is hidden. That is the direct consequence of "the phase is a whole block of material"; making those hosts
visible again would require carving the phase around the host geometry.
Lava buckets follow the vanilla water-bucket interaction priority: hosts whose vanilla interaction consumes the click
(trapdoors toggle, chests/ender chests open, light blocks cycle, signs open the editor, decorated pots store the item, shelves/statues
act) are not filled by a plain right-click — sneak right-click, dispensers and placing a block into a lava source still fill them;
every other host fills on right-click through the vanilla `useItem` path, identical on all six versions.
`barrier` is not a phase host on 1.19.4/1.20.1 (those versions have no `WATERLOGGED` on it); on 1.21.1+
it stays a host, but bucket/placement filling is refused there (vanilla only accepts fluids on barriers in creative mode).
Slabs/candles/campfires/dried ghasts accept lava from neighbouring lava sources (their shadowed `placeLiquid`/`canPlaceLiquid`
lava branch is restored by dedicated mixins), and `offsetType` hosts leave their phase behind when broken.
Host-coverage invariants are audited by `python tools/audit_host_coverage.py <guardian> .` and documented in
`docs/host-coverage-audit.md`.
The redstone shell's brightness follows its power level (0 = nearly dark, 15 = full bright), matching the vanilla wire colour ramp.
Hosts whose vanilla `RenderShape` is not `MODEL` (barriers, light blocks, all sign blocks, chest/pot families on older versions) still render
the phase: phased states are forced back to `MODEL`, while the host itself keeps being drawn by its own vanilla render path.
Feature 3 is client driven but server authoritative, so the server announces itself with a `liquidphase:handshake` packet on join and the client
only enables its phase features after receiving it. A vanilla server never sends it, so the client falls back to vanilla behaviour there
(single player works because the integrated server still sends the handshake).
1.19.4/1.20.1 use **Statement** for state injection (shipped as a dependency).
Dependency floors follow each version's real minimum (`fabricloader` 0.15.0 / 0.16.10 / 0.15.11 /
0.17.0 / 0.17.3 / 0.19.3 respectively), `minecraft` stays patch-level (`~x.y.z`), and `fabric-api` plus
`statement` remain required. Note: loader 0.15.x/0.16.x bundle an old Mixin that cannot read JDK 25 class
files (`Unsupported class file major version 69`) — use a newer loader or JDK 17/21 in that case.

### Licence

WTFPL v2 — see `LICENSE`. Credits: Lavalogged (MIT), Statement (MIT), cauldronfix (CC0), Fluidlogged (MIT);
cauldron-expansion (CC-BY-NC-SA) used for **behaviour comparison only**, no code or assets copied.
Decompiled sources via guardian, used locally for verification only.
