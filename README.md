# 液相 Liquid Phase

**中文** | [English](#english)

让"可含水方块"（楼梯、台阶、墙、栅栏、告示牌、箱子、树叶、蜡、锁链……）能额外持有一个**填充相**：
熔岩、黑曜石、冰、霜冰，以及在宿主表面工作的红石线 / 中继器 / 比较器 / 侦测器。
一格 = 固相（方块本身）+ 至多一个被含相。

支持 **Fabric** 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3。

| 版本 | Minecraft | 说明 |
| --- | --- | --- |
| 0.2.0 | 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3 | 方案 C：`filling` 只保留 8 个材料类别，红石功率与元件参数进旁路表；**旧存档不兼容**（属性取值域变更） |
| 0.2.1 | 同上 | 修复：红石相/元件相"有时显示有时不显示"——旁路表变化后客户端 section 未重画（`markDirty` 走了 `setBlockDirty` 的 `requiresRender` 门，同状态对象恒 false，静默 no-op） |
| 0.2.2 | 同上 | 修复：**潜行右键宿主放中继器/比较器/侦测器从未生效**——装配取宿主用了 `getClickedPos()`，而原版对不可替换方块把它指向宿主旁的空气格（真实点击回归 `AssemblyInteractionTest` 抓到） |

---

## 这个 mod 做了什么

### 功能一：更多 logged 方块（双端必装）

- **含熔岩**：熔岩桶（或液体熔岩物品）右键可含水方块、发射器装填、熔岩源流进该格，都会让它变成"含熔岩方块"。
  内部熔岩是真实化熔岩源：按宿主形状从没被固相挡住的方向流出，也会被相邻熔岩按形状灌入；
  整格发光 15；处于其中的实体会着火；随机刻复刻原版火蔓延。
  
  > 台阶/蜡烛/营火/干化恶魂等自己覆写了 `placeLiquid` 的宿主由专门 mixin 接管熔岩分支：
  > 熔岩桶右键、相邻熔岩按形状灌入、放置吸收都可用。见 `docs/host-coverage-audit.md` §4.3。
- **凝固**：含熔岩方块旁边出现水 → 相变为**含黑曜石**，伴随嘶声粒子。
- **含冰 / 含霜冰**：雨天低温下"含水的方块"会冻结成含冰相（原版只会冻水源方块，本 mod 把判定扩展到宿主）；
  冰相在光照足够时融化回水相；下界/水蒸发维度融化时直接消失。
  霜冰相按原版 AGE 0–3 的裂纹推进与相邻融化规则演化（`frosted_0`…`frosted_3`）。
- **破坏保留**：破坏含相方块 → 掉落固相战利品，该格留成真实熔岩源 / 黑曜石 / 冰 / 霜冰。
  **含相方块本身可以像原版方块一样正常破坏**：准星落在宿主露出的部分就按宿主材质挖，落在相露出的部分才按相材质挖
  （`filling=none` 的兄弟状态参与命中判定，形状表型宿主也不会误判）。
  > ⚠️ 带 `offsetType` 的 4–5 个宿主（`hanging_roots`、`mangrove_propagule`、`pointed_dripstone`、
  > `small_dripleaf`、26.3 的 `sulfur_spike`）例外：破坏时**不留下相**。
- **收走相**：功能三开启时空桶左键；关闭时空桶右键。固体相（冰/霜冰/黑曜石）不响应右键收取。
- **放置吸收**：拿冰/黑曜石物品指向可含水方块 → 写入该格；把可含水方块放进冰/黑曜石/熔岩源所在的格子 → 吸收该相。
- **碰撞**：固相（冰/黑曜石）的组合碰撞 = **宿主碰撞 ∪ 整格实心**——栅栏/墙这类碰撞柱
  延伸到方块外（高 1.5 格）的宿主，方块外的部分照常保留（0.1.21 及以前被整格覆盖丢失）；
  熔岩相不额外加碰撞（内部是流体）；红石相不加碰撞。
  对所有宿主一致（状态级注入），包括自己覆写碰撞形状的栅栏/玻璃板/铁栏杆、墙、
  墙悬挂告示牌、脚手架、`sculk_shrieker`、`big_dripleaf`。
- **直接挖相**：准星指向相露出的部分时，挖掘速度与掉落按相材质计算（黑曜石需钻石镐；冰/霜冰不精准采集则化水，霜冰无物品）。
- **所有宿主的相都是"内缩叠加层"**：宿主模型始终照常渲染，相材质是一层比方块小 0.25px 的立方体。
- **红石相 / 元件相**：红石相在宿主表面显示一层按功率着色的红石外壳；**潜行右键**宿主上表面，
  手持中继器 / 比较器 / 侦测器可把元件贴到宿主表面。两者都不占用 `filling` 取值，
  参数存在旁路表里（见下文"方案 C"）。

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
- 暂未添加药水锅、染色锅、装黑曜石的锅。

### 功能三：玩家与液体互动（**强制双端**）

- 手持**空桶**左键液体（水源、熔岩源、含熔岩方块的熔岩相）→ 瞬间"挖掉"并**掉落液体物品**（不直接进包）。
- 手持水/熔岩"液体物品"右键 → 像放方块一样放液体：对可含水方块写入相/含水，对空气放流体，
  消耗 1 个、不返还空桶。合成/其他消耗也不再返还空桶。
- 装了 mod 的客户端手持空桶时，准星会对液体启用流体射线（原版射线打不中液体）。
- 创造模式：仍只有空桶能挖，挖不掉落；放置不消耗。

> ⚠️ **功能三必须客户端与服务端都装**。只装服务端的原版客户端：桶的装/倒会被禁用，且因为原版射线打不中液体，无法挖液体（这是不装客户端功能时的固有限制）。

---

## 必须双端装 / 只要服务端装

| 功能 | 双端必装 | 仅服务端 |
| --- | --- | --- |
| 功能一（相属性、含熔岩/冰/黑曜石/霜冰、红石相、元件相） | ✅ | — |
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
6. 拿红石粉点楼梯 → 楼梯变成**含红石线的楼梯**（红石外壳按功率着色）。
   也可以反过来：把楼梯/台阶等可含水方块**放进红石线所在的那一格**，那条线就被吸进宿主、
   变成含红石相的方块（功率沿原线）。
7. **潜行右键**宿主上表面（下半砖、下半楼梯等顶面留有空腔的宿主），手持中继器/比较器/侦测器
   → 元件贴到宿主表面。普通右键仍是原版"把元件放在宿主上面一格"。
   左键元件先拆元件并掉落对应物品，宿主保留；元件只能放在顶面有空腔的宿主上
   （整格方块、上半砖放不下）。
8. 拿空桶**左键**水锅/熔岩锅 → 整锅拆空、掉落"水"/"熔岩"物品；**锅本身保留**。
   只有满锅才能取（水锅必须是 3 级）；右键对着锅不再取水。

---

## 方案 C（0.2.0）

`filling` 只保留 8 个**材料类别**：`none`、`lava`、`obsidian`、`ice`、`frosted_0..3`。
红石功率（0–15）与元件相的全部参数（kind/power/facing/delay/mode/locked/powered）
压在每格一条旁路表记录的 `PhaseExtra` 里，不新增方块、不新增属性，宿主方块本身不变。

- **存储**：每个 `Level` 一张按区块分组的稀疏表（`chunkPos -> (BlockPos -> int)`），
  内层表不可变、渲染线程无锁读；服务端持久化到 `world/data/liquidphase_<dimension>.dat`
  （NBT，30 s 自动保存 + 关服保存），客户端由网络包镜像。
- **同步**：客户端在区块加载 / 握手 / 更换 `Level` 时发 `extra_request`，服务端回
  `extra_chunk`（合并语义）；服务端每次写表把 `extra_update` 发给该维度**全部**玩家，
  避免"开始追踪前写入丢失"。客户端应用数据后把所在 section（含邻居）标脏重画——
  旁路表变化不伴随方块状态变化，必须绕过 `setBlockDirty` 的 `requiresRender` 门
  （同状态对象直接返回 false），直接走 `setSectionDirtyWithNeighbors`。
- **红石线连接**：`isSignalSource()` 没有坐标，不再对红石相返回 true；红石线改在
  `getConnectingSide`（26.3 是 `shouldRedstoneWireConnectTo`）按坐标判断"该格有红石条目且该面露出"。
- **渲染**：每个宿主 blockstate 只追加一条**无条件** part `liquidphase:block/dynamic`。
  ≤1.21.1 由 `PhaseDynamicModelLegacy` 接管该模型 id，1.21.10+ 由
  `PhaseDynamicModelModern` 包装宿主 `BlockStateModel`；两者都按坐标查旁路表发射几何。
  红石外壳 16 份子模型保留；元件的 22 份子模型 + `dynamic.json` 由
  `tools/gen_element_models.py` 从客户端 jar 的原版模型复制（共 23 个文件）。
- **元件**：几何按逐顶点采样宿主碰撞形状顶面高度抬升，并按 facing 旋转，
  所以五官/输出口/杆画在宿主表面上层。

完整口径、位布局、信号语义与取舍见 [`docs/plan-c-elements.md`](docs/plan-c-elements.md)。

---

## 兼容性注意

- **移除本 mod 会丢失所有填充相**（含熔岩/冰/黑曜石/红石相）与旁路表里的元件；
  `world/data/liquidphase_<dimension>.dat` 也不会再被读取。
- 本 mod 独占"填充相"语义，**不兼容 Fluidlogged 这类通用 fluidlogged mod 互通**。
- **与第三方资源包自动兼容**：资源包覆盖宿主 blockstate
  （`assets/minecraft/blockstates/<宿主>.json`——栅栏/树叶/告示牌类包很常见）时，
  客户端会把相部件**合并进包的定义**，包只生效于宿主 part、相照常显示，
  并且不会改动包的宿主几何。日志有一条 `merged phase overlays into …`，
  无法合并时为 `overlay could not be merged …`。针对本 mod 的资源包
  （引用 `liquidphase:` 模型）不受合并影响，仍可整体覆盖。
  配置 `packMerge=false` 会**关闭合并**，此时相部件整体丢失，仅用于排查问题。
- 含红石相方块不与原版红石线行为一致，例如没有弱充能方块能力。
- **红石相/元件相不占 `filling` 取值**：红石功率与元件参数在旁路表里（见"方案 C"），
  不注册任何新方块；方块仍是原版楼梯/台阶/墙/栅栏。红石外壳与元件几何由动态模型按坐标
  发射，外壳亮度随功率变化。覆盖面口径仍是"运行时真的拿到 `filling` 属性的方块"，
  清单由 `tools/gen_phase_blocks.py` 从原版注册表推导，并用运行时注入数硬校验。
- **吸收按"可替换填充相"规则判定**：向宿主写材料填充相时只看**现有填充相**——
  空格 / 含水 / 含熔岩相可替换（冰替换水=冻结、熔岩或黑曜石替换水、黑曜石覆盖熔岩相），
  冰/霜冰/黑曜石相不可替换、材料按不可放置处理。红石/元件条目走单独的
  `canAcceptExtra`：现有条目必须为空，且 `filling` 为 `none` 或熔岩（水/熔岩会被清回
  `none`）。水的流体路径不变：水桶/水流仍只进无相宿主，水不能直接替换熔岩相
  （含熔岩相宿主遇水邻居走凝固成黑曜石相）。
- **红石相初始功率按"是不是重新放置"区分，落地后一律立即重算**：
  **新放置**（红石粉点宿主、改写层把线状态写进宿主格）的初始状态是"单独放置红石粉"的
  初始状态——功率 0；**吸收**（宿主放进/落进红石线格）那格的红石线本来就在、不是重新
  放置，初始功率**沿用原线**。以初始状态落地后，该格像原版红石线一样立即按邻居
  重算出真实功率。
- **告示牌/箱子/屏障/光源方块等原生"不渲染方块模型"的宿主也能显示相**：全部相宿主状态
  强制 `getRenderShape = MODEL`，宿主本体仍由各自的原版渲染路径绘制；
  这些方块的 blockstate 模型本身没有 `elements`，不会凭空多出宿主几何。
- **状态数**：`filling` 共 **8** 个取值（`none` + 7 个材料类别）。
  1.21.11 注入 **413** 个方块、总状态 **176,563**（含给传送门补的 WATERLOGGED）（方案 C 预算档）；启动注入时间仍随宿主数增长。
- 含固相方块的**相覆盖层**是一层内缩 0.25px 的相材质立方体，碰撞是**宿主 ∪ 整格的组合形状**
  （对所有宿主一致，挂在状态级；栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架这些
  自己覆写碰撞的宿主也生效——栅栏/墙延伸到方块外的碰撞柱照常保留）。挖掘按双口径：
  准星落在宿主露出的部分就挖宿主，落在相露出的部分就挖相。黑曜石相需要钻石镐才能挖动相本身。
- **熔岩桶右键按原版倒水优先级**（与原版"水桶点方块"完全同规则）：
  原版交互不消费右键的宿主（楼梯/台阶/栅栏/玻璃板/树叶等）→ 右键直接灌入；
  原版交互消费右键的宿主（**活板门开关、箱子/末影箱开箱、光源方块调亮度、告示牌开编辑、
  装饰陶罐收纳、搁物架/铜傀儡雕像交互**）→ 原版行为优先、不灌入，
  和"水桶点活板门只会开关它"一样。要给这些宿主灌相：**潜行右键**（原版绕过方块交互）、
  发射器、或放置吸收（拿方块放进熔岩源格）。
- **传送门也是宿主**：下界门 / 末地门 / 末地折跃门可含填充相。它们原版没有 `WATERLOGGED`，
  0.2.0 起由注入器补上该布尔属性，含水语义与其它宿主完全一致（水桶灌入/空桶取水/
  水流判定/融化回水）；含冰/霜冰/黑曜石时整格碰撞会堵住传送门（见已知限制）。
- 1.21.1+ 的 `barrier` 仍是相容宿主（红石相正常显示），但桶/放置吸收/相邻熔岩充不进相：
  原版对 barrier 的流体接受是"创造模式限定"，而相充入的探测调用不携带玩家身份
  （熔岩桶点 barrier 只是拒绝，不会崩）。
- 1.19.4/1.20.1 的状态注入使用 **Statement** 依赖。
- **依赖下限**：`fabricloader` 按各版本实测最小需求声明（1.19.4 ≥0.15.0、1.20.1 ≥0.16.10、
  1.21.1 ≥0.15.11、1.21.10 ≥0.17.0、1.21.11 ≥0.17.3、26.3 ≥0.19.3），
  `minecraft` 仍是补丁级匹配（`~x.y.z`），`fabric-api` 与 `statement` 为必需依赖。
- ⚠️ **旧 loader + 新 JDK 会启动失败**：loader 0.15.x/0.16.x 内置的 Mixin 读不了 JDK 25 的类
  （`Unsupported class file major version 69`），这不是本 mod 的问题。用 JDK 25 时请把 loader
  升到较新版本，或改用 JDK 17/21 启动。

### 已知限制（0.2.0）

1. **旧存档不兼容**：`filling` 的取值域已变更（8 个材料类别），旧状态无法映射。
2. **元件只能放在有顶面空腔的宿主**：下半砖/下半楼梯等可以（足迹内存在 `top < 1` 的列），
   整格方块、上半砖拒绝。
3. **元件参数与红石功率在旁路表里，不随活塞移动**：活塞推动宿主时该格条目被清掉，
   元件/红石相不会跟着走。
4. **中继器 `locked` 只做逻辑锁定**，不显示原版的锁形。
5. **1.19.4–1.21.11 所有相宿主方块（所有状态）都走 CUTOUT**：红石外壳是透明底贴图，
   因此失去原版 SOLID 的 mipmap（视觉/性能取舍）；26.3 由贴图透明度自动推导。
6. **含固相的传送门会被整格碰撞堵住**：下界门/末地门/末地折跃门都是相容宿主，
   冰/霜冰/黑曜石相按通用规则给整格碰撞。
7. **Sodium/光影**：渲染走 Fabric Rendering API（Indigo），未承诺 Sodium 兼容。
8. **`getRenderShape` 对全部相宿主状态强制 `MODEL`**：红石/元件存在性在状态里看不出来。
   隐形宿主（barrier/light/告示牌）的 blockstate 模型本身没有 `elements`，不会凭空多出几何。
9. 红石相没有弱充能方块能力；破坏宿主时元件随宿主一起消失，不额外掉落。

## English

Let waterloggable blocks (stairs, slabs, walls, fences, signs, chests, leaves, chains, candles, …) hold a **filling phase**:
lava, obsidian, ice, frosted ice, and redstone wire / repeaters / comparators / observers working on the host surface.
One block = solid phase (the block itself) + at most one contained phase.

**Supported:** Fabric 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3.

| Version | Minecraft | Notes |
| --- | --- | --- |
| 0.2.0 | 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3 | Plan C: `filling` keeps only 8 material classes; redstone power and element parameters live in the side table; **old worlds are incompatible** (property value domain changed) |
| 0.2.1 | same | Fix: redstone/element phase sometimes invisible — a table-only change never re-rendered its section (`markDirty` went through `setBlockDirty`'s `requiresRender` gate, which returns false for an identical state object) |
| 0.2.2 | same | Fix: **sneak-placing repeaters/comparators/observers into hosts never fired** — assembly looked the host up via `getClickedPos()`, which vanilla points at the air cell next to a non-replaceable host (caught by the new real-click regression `AssemblyInteractionTest`) |

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
  keep the part outside the block; lava adds no collision (it is a fluid) and the redstone phase adds none either.
- **Mining the phase directly**: when the crosshair points at an exposed phase, mining speed and drops follow the phase material
  (obsidian needs a diamond pickaxe; ice melts to water without silk touch; frosted ice has no item).
- **Redstone / elements**: the redstone phase draws a power-tinted redstone shell on the host surface; **sneak right-click**
  the top face with a repeater / comparator / observer to attach the element to the host. Neither occupies a `filling`
  value; their parameters live in the side table (see "Plan C").

### Feature 2 — cauldron behaviour (server-side only, vanilla-representable part)

Lava cauldron + water drops one obsidian and empties; water cauldron + lava drops stone/cobblestone/cobblestone slab by level 3/2/1;
empty cauldrons collect only **directly adjacent** source fluid; concrete powder + water cauldron hardens into 16 coloured concretes
(one level each); powder-snow cauldron + water/lava becomes a water cauldron; dispensers fill/empty cauldrons and bottles;
hoppers move liquid items in and out of fluid cauldrons.

With feature 3 on, buckets interact with cauldrons the "liquid item" way: **left-click** a water/lava cauldron with an empty bucket
to empty the whole cauldron and drop the matching liquid item **without breaking the cauldron itself**; only a full cauldron
(water cauldron at level 3) can be emptied. Right-click filling is disabled (the bucket is never swapped away);
pouring a liquid item into an empty/partially filled cauldron still fills it but consumes one item with no bucket returned.
**Currently not** included: potion cauldrons, dyed-water cauldrons, obsidian-block cauldrons.

### Feature 3 — player ↔ liquid interaction (**required on both sides**)

Left-click liquids with an **empty bucket** to instantly mine them and **drop the liquid item**; right-click with a water/lava liquid item
to place liquid like a block (**one consumed, no bucket returned**, and no remainder in recipes). A modded client enables fluid rays for the
crosshair while holding an empty bucket. Creative mode keeps bucket-only mining with no drops and free placement.

> ⚠️ Feature 3 needs the mod on **both** sides. With a vanilla client on a modded server, bucket fill/place is disabled and liquids cannot be
> mined (the vanilla crosshair ray never hits fluids).

### Both sides vs server only

| Feature | Both sides | Server only |
| --- | --- | --- |
| Feature 1 (phases, elements) | ✅ | — |
| Feature 2 (cauldrons, dispensers, hoppers) | — | ✅ |
| Feature 3 (liquid items, mining, raycast) | ✅ | — |

Turn the two-sided features off for vanilla clients via `config/liquidphase.json` (`phaseEnabled`, `liquidInteraction` = `false`).
Existing phases in old worlds are then **lost permanently** (vanilla drops unknown states).

### Plan C (0.2.0)

`filling` keeps only eight **material classes**: `none`, `lava`, `obsidian`, `ice`, `frosted_0..3`.
Redstone power (0–15) and every element parameter (kind/power/facing/delay/mode/locked/powered) live in one
side-table entry per position (`PhaseExtra`); no new blocks, no new properties, and the host block itself is unchanged.

- **Storage**: one sparse, chunk-grouped table per `Level` (`chunkPos -> (BlockPos -> int)`); inner tables are
  immutable and read lock-free by the render thread. The server persists it to
  `world/data/liquidphase_<dimension>.dat` (NBT, 30 s autosave + save on shutdown); the client mirrors it from packets.
- **Sync**: the client sends `extra_request` on chunk load / handshake / `Level` change and the server answers
  `extra_chunk` (merge semantics); every server-side table write broadcasts `extra_update` to **all** players in
  that dimension, so writes made before tracking starts are not lost.
- **Wire connection**: `isSignalSource()` has no position, so it no longer returns true for the redstone phase;
  wire connection is decided per position in `getConnectingSide` (26.3: `shouldRedstoneWireConnectTo`):
  "this cell has a redstone entry and that face is exposed".
- **Rendering**: every host blockstate gets one **unconditional** part, `liquidphase:block/dynamic`.
  On ≤1.21.1 `PhaseDynamicModelLegacy` takes over that model id; on 1.21.10+ `PhaseDynamicModelModern` wraps the
  host `BlockStateModel`; both emit geometry by looking up the side table at the position. The 16 redstone-shell
  sub-models are kept; the 22 element sub-models + `dynamic.json` are generated from the client jar by
  `tools/gen_element_models.py` (23 files).
- **Elements**: geometry is lifted per vertex to the top of the host collision shape and rotated by facing, so the
  repeater torch / comparator output / observer face are drawn above the host surface.

Full details: [`docs/plan-c-elements.md`](docs/plan-c-elements.md).

### Compatibility notes

Removing this mod loses every phase and every side-table element; `world/data/liquidphase_<dimension>.dat` is no
longer read. It does not interoperate with generic fluidlogged mods (coexistence is not supported, data is not shared).
The phase property is `filling`. Resource packs are merged automatically: when a pack replaces a host's blockstate
JSON, the client merges the phase parts into the winning definition, so the pack only affects the host part, the
phase visuals survive, and the pack's own host geometry is left untouched. A log line reports every merge
(`merged phase overlays into …`), and `overlay could not be merged …` when a definition cannot be merged. Packs made
for this mod (referencing `liquidphase:` models) still override freely; `packMerge=false` disables merging entirely
(phase parts are then lost — for troubleshooting only).
Filling absorption judges **the existing filling's replaceability**: empty / waterlogged / lava-filled hosts accept
any material filling (ice replaces water, obsidian covers lava); ice/frosted/obsidian fillings are not replaceable.
Redstone/element entries use `canAcceptExtra`: the entry must be empty and `filling` must be `none` or lava
(water/lava is then cleared back to `none`). The water fluid path is unchanged — buckets and flowing water still only
enter empty hosts.
Redstone power and element parameters do not occupy `filling` values; they live in the side table (see "Plan C").
Redstone-phase blocks are not guaranteed to match vanilla wire behaviour (no weak charging capability for example).
The shell and element geometry are emitted by the dynamic model for **all** phase-capable hosts, including
transparent ones (leaves, grates, glass panes, iron bars, ladders, chains).
Initial power differs by direction — placing dust starts at power 0, while a host placed into a wire tile carries the
wire's current power as its initial state — and the block then settles against its neighbours immediately, exactly
like a freshly placed vanilla wire. Signals decay by 1 into neighbouring wires but stay at full power for lamps,
pistons and other components.
Every host keeps rendering its own model while phased; the solid phase is a 0.25px-inset cube of the phase material.
The solid-phase combined collision (host ∪ full cube) applies to **every** host (state-level, including fences, panes,
bars, walls, wall hanging signs and scaffolding that override the block-level shape — the fence/wall collision columns
that stick out above the block are kept); mining is two-lane: aim at the exposed host and it mines at host speed, aim
at the exposed phase and it mines as the phase material (obsidian still needs a diamond pickaxe).
On 1.19.4–1.21.11 every state of every phase host is forced onto the CUTOUT layer via
`ItemBlockRenderTypes.getChunkRenderType` (only when the original layer is SOLID); translucent hosts (glass panes,
stained glass) keep TRANSLUCENT. The same mixin also fixes `getMovingBlockRenderType`, so falling blocks and
piston-moved phased blocks are not drawn on the opaque layer. This must be a mixin: the `BlockRenderLayerMap` table is
rebuilt by the resource reload and silently loses mappings registered during client init. 26.3 has no
`ItemBlockRenderTypes` and derives the layer from texture transparency.
All phase hosts force `getRenderShape = MODEL` for every state, so hosts whose vanilla shape is not `MODEL` (barriers,
light blocks, sign blocks, chest/pot families on older versions) still render the phase; the host itself keeps being
drawn by its own vanilla render path, and blockstate models without `elements` do not gain host geometry.
Lava buckets follow the vanilla water-bucket interaction priority: hosts whose vanilla interaction consumes the click
(trapdoors toggle, chests/ender chests open, light blocks cycle, signs open the editor, decorated pots store the item,
shelves/statues act) are not filled by a plain right-click — sneak right-click, dispensers and placing a block into a
lava source still fill them; every other host fills on right-click through the vanilla `useItem` path, identical on
all six versions.
Portals are hosts too: nether portals / end portals / end gateways accept material fillings and were given a
`WATERLOGGED` property at injection time, so waterlogging works exactly like on any other host (bucket fill,
bucket pickup, water flow, melting back to water); with ice/frosted ice/obsidian the full-cube collision blocks the
portal (see known limitations).
`barrier` is not a phase host on 1.19.4/1.20.1 (those versions have no `WATERLOGGED` on it); on 1.21.1+ it stays a
host, but bucket/placement filling is refused there (vanilla only accepts fluids on barriers in creative mode).
Slabs/candles/campfires/dried ghasts accept lava from neighbouring lava sources (their shadowed
`placeLiquid`/`canPlaceLiquid` lava branch is restored by dedicated mixins), and `offsetType` hosts leave their phase
behind when broken.
Feature 3 is client driven but server authoritative, so the server announces itself with a `liquidphase:handshake`
packet on join and the client only enables its phase features after receiving it. A vanilla server never sends it, so
the client falls back to vanilla behaviour there (single player works because the integrated server still sends the
handshake).
1.19.4/1.20.1 use **Statement** for state injection (shipped as a dependency).
Dependency floors follow each version's real minimum (`fabricloader` 0.15.0 / 0.16.10 / 0.15.11 /
0.17.0 / 0.17.3 / 0.19.3 respectively), `minecraft` stays patch-level (`~x.y.z`), and `fabric-api` plus
`statement` remain required. Note: loader 0.15.x/0.16.x bundle an old Mixin that cannot read JDK 25 class
files (`Unsupported class file major version 69`) — use a newer loader or JDK 17/21 in that case.

### Known limitations (0.2.0)

1. **Old worlds are incompatible**: the `filling` value domain changed (8 material classes); old states cannot be mapped.
2. **Elements only fit hosts with top-face cavity**: bottom slabs / bottom stairs work (some column has `top < 1`);
   full blocks and top slabs are refused.
3. **Element parameters and redstone power live in the side table and do not move with pistons**: pushing the host
   clears the entry, so elements/redstone phases are not carried along.
4. **Repeater `locked` only implements the logical lock**, without the vanilla lock-bar visual.
5. **On 1.19.4–1.21.11 every state of every phase host uses CUTOUT**: the redstone shell is a cutout texture, so
   vanilla SOLID mipmaps are lost (a visual/performance trade-off); 26.3 derives the layer from texture transparency.
6. **Portals containing a solid phase are blocked by full-cube collision**: nether portals / end portals / end gateways are
   phase-capable hosts, and ice/frosted ice/obsidian apply the usual full-cube collision.
7. **Sodium / shaders**: rendering uses the Fabric Rendering API (Indigo); Sodium compatibility is not promised.
8. **`getRenderShape` is forced to `MODEL` for every state of every phase host**: redstone/element presence is not visible
   in the state. Invisible hosts (barrier/light/signs) have no `elements` in their blockstate model, so no host geometry
   appears out of nowhere.
9. The redstone phase has no weak-charging capability; breaking the host removes its element without an extra drop.

### Licence

WTFPL v2 — see `LICENSE`. Credits: Lavalogged (MIT), Statement (MIT), cauldronfix (CC0), Fluidlogged (MIT);
cauldron-expansion (CC-BY-NC-SA) used for **behaviour comparison only**, no code or assets copied.
Decompiled sources via guardian, used locally for verification only.
