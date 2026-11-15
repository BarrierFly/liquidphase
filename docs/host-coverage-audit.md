# 相容宿主覆盖面审计（历史补丁逐个核对）

> 目的：把"哪些方块应当能含相"和"历史补丁实际覆盖了哪些"逐版本、逐提交算一遍，
> 找出**补丁声称修好、但实际没覆盖全部宿主**的地方，以及至今仍未覆盖的部分。
>
> 复现：`python tools/audit_host_coverage.py <guardian 仓库路径> . [--history]`
> （`--history` 遍历本仓库全部提交，约 1–2 分钟）。本文所有数字都来自该脚本。

---

## 1. 口径：四条可计算的链路 + 三类遮蔽检查

本 mod 的"相容宿主"不是一张显式名单，而是运行时推导出来的。`PhaseCapableMixin`
把标记接口挂在一组原版基类上，**这些基类的全部已注册子类都会继承**；`LiquidPhase`
再遍历注册表，给每个 `instanceof PhaseCapable` 的方块注入 `phase`。于是"能含相"
"有 blockstate""有红石外壳""桶/流体能灌进去""碰撞是整格"是**五条各走各的链路**，
任何一条漏掉一族方块，表现都是"含相了却看不见 / 点不动 / 灌不进 / 撞得到"。

审计用四个集合做差集，另外单列"能力被宿主覆写遮蔽"（这类不体现在任何清单里）。

| 记号 | 含义 | 来源 |
| --- | --- | --- |
| **A** | 原版真值：`Blocks.java` 里注册、且类（或祖先）实现 `SimpleWaterloggedBlock` 的方块 | guardian 反编译源码，与仓库状态无关 |
| **B** | 运行时真值：`@Mixin` 目标闭包 ∩ A，即**真的会拿到 `phase` 属性**的方块 | `PhaseCapableMixin.java` 按 stonecutter 分支求值 |
| **C** | 生成清单：`tools/phase-blocks/<版本>.ids`，决定**是否生成 blockstate / 相覆盖层** | 仓库文件 |
| **S** | 外壳覆盖：真正生成红石外壳的方块（≤0.1.10 由 `tools/redstone-hosts.txt` 门控） | 该提交的生成器源码 |

差集含义：

- `A-B`：应该能含相，但拿不到 `phase` 属性 → **完全不能用**；
- `B-C`：拿得到属性，但没有 blockstate → **含相后什么都看不见**（0.1.12 的根因）；
- `C-B`：生成了 blockstate，但拿不到属性 → 客户端报 `Unknown property 'phase'`，**整份 blockstate 加载失败**；
- `C-S`：清单里有，但不生成红石外壳 → **红石相不显示**。

附带三类"能力被宿主覆写遮蔽"的检查（mixin 挂在基类/接口上，宿主自己声明同名方法就会遮蔽）：

1. `PhaseCapable` 的 `canPlaceLiquid` / `placeLiquid` / `pickupBlock`；
2. 块级 `getCollisionShape`（固相"整格碰撞"）；
3. `getStateForPlacement`（放置吸收）。

---

## 2. 当前 HEAD（0.1.12）的总表

| 版本 | A 原版可含水 | B 注入 phase | C 生成清单 | S 红石外壳 | A-B | B-C | C-B | C-S |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.19.4 | 308 | 309 | 309 | 309 | 0 | 0 | 0 | 0 |
| 1.20.1 | 310 | 311 | 311 | 311 | 0 | 0 | 0 | 0 |
| 1.21.1 | 337 | 337 | 337 | 337 | 0 | 0 | 0 | 0 |
| 1.21.10 | 410 | 410 | 410 | 410 | 0 | 0 | 0 | 0 |
| 1.21.11 | 410 | 410 | 410 | 410 | 0 | 0 | 0 | 0 |
| 26.3 | 505 | 505 | 505 | 505 | 0 | 0 | 0 | 0 |

结论：**清单 / 注入 / 外壳三者的数量口径已经一致**（0.1.12 硬校验的成果）。
但 `B-A` 在 1.19.4 / 1.20.1 上都是 1 —— 见 §4.2，那不是"多登记"，而是一个真实的崩溃面。

---

## 3. 历史补丁覆盖缺口时间线

每一格是该提交**仍然存在**的缺口数（`B-C` = 有属性无 blockstate，`C-S` = 无红石外壳）。

| 提交 | 版本 | 1.19.4 | 1.20.1 | 1.21.1 | 1.21.10 | 1.21.11 | 26.3 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0.1.0–0.1.4（11 个提交） | B-C | 1 | 1 | 0 | 24 | 24 | 197 |
| 0.1.5–0.1.8（5 个提交） | B-C / C-S | 1 / 267 | 1 / 269 | 0 / 296 | 24 / 345 | 24 / 345 | 197 / 267 |
| 0.1.9 | B-C | 1 | 1 | 0 | 24 | 24 | 197 |
| 0.1.10 | B-C | 1 | 1 | 0 | 24 | 24 | 197 |
| 0.1.11 | B-C | 1 | 1 | 0 | 24 | 24 | 197 |
| 0.1.12（4 个提交） | B-C | **0** | **0** | **0** | **0** | **0** | **0** |

### 3.1 `B-C`：二十个提交周期都没消掉的 1 / 24 / 197 个方块

- 1.19.4 / 1.20.1：`barrier`（1 个）。
- 1.21.10 / 1.21.11：24 个铜方块 —— `copper_bars` / `copper_chain` / `copper_lantern` 的
  未氧化 + 打蜡 + 三档氧化全家。
- 26.3：197 个。按族归并：铜（含氧化/打蜡）72、混凝土台阶/楼梯 32、羊毛台阶/楼梯 32、
  墙告示牌/墙悬挂告示牌 26、染色玻璃板 16、墙珊瑚扇（含失活）10、氧化避雷针 8、其他 1。

这三个缺口从 0.1.0 一直存在到 0.1.11，**每一个"修复显示问题"的补丁都没有动它**：

| 补丁 | 声称 | 实际 |
| --- | --- | --- |
| 0.1.9「补全红石外壳到**全部**宿主」 | 外壳从 41 个代表宿主扩到全部 | 只扩到了**当时那份错误清单里的全部**（308/310/386/386/308）。那 1/24/197 个方块既没有外壳，也没有 blockstate，补丁对它们零效果 |
| 0.1.10「整格宿主看不到红石外壳」 | 渲染层 + cullface | 同上，未触及清单 |
| 0.1.11「显示问题逐项收口」 | RenderShape / 分档贴图 / 自动整格判定 | 只修了整格判定，`B-C` 一格没动 |

这正是 0.1.12 必须重写 `tools/gen_phase_blocks.py` 的原因：**0.1.9–0.1.11 的三次修复都建立在同一份错误清单上**，
而当时的回归检查只抽查 `oak_stairs` / `oak_leaves` / `glass_pane` 这些"清单里确实有"的代表方块，
所以三次都自证通过。现在 `EXPECTED_COUNTS` + 双向集合比对堵住了这个洞。

### 3.2 `C-S`：0.1.5–0.1.8 的红石外壳只覆盖 41 个代表宿主

0.1.5 把红石相改成 `phase` 取值后，外壳由 `tools/redstone-hosts.txt`（41 行）门控。
缺口：1.19.4 267、1.20.1 269、1.21.1 296、1.21.10/1.21.11 345、26.3 267。
0.1.9 起无条件对全部清单宿主生成，这一项才归零。

> 注意当时叠加了两个缺口：外壳只有 41 个；另外 1/24/197 个方块连 blockstate 都没有。
> 所以 0.1.8 的"部分透明方块所含红石相看不到"其实有**两个**独立原因，0.1.9 只修掉了前一个。

### 3.3 整格宿主判据的演进（同一类 bug 的三次复发）

| 阶段 | 做法 | 未覆盖 |
| --- | --- | --- |
| 0.1.0–0.1.1 | 没有整格概念，只有内缩覆盖层（`phase_*_full` 模型尚未存在） | **全部**整格宿主：11 / 11 / 19 / 20 / 20 / 15 个。含固相完全看不见 |
| 0.1.2–0.1.10 | 手写 `FULL_CUBE_HOSTS` 白名单（20 条） | 26.3 的 3 个白杨树叶（`orange/red/yellow_poplar_leaves`）。其余版本命中率 100% |
| 0.1.11+ | 按模型几何自动判定 | 自动判定解决了"新增方块漏登记"，但**引入了新的粒度问题**，见 §4.1 |

### 3.4 组件相（组合方块）时期的覆盖（仅历史）

0.1.0–0.1.4 的红石相是独立的"组合方块"，宿主覆盖面由 `CompositeFactories` 的实例表决定：

| 提交 | 实际注册的组合方块数（逐版本） | 说明 |
| --- | --- | --- |
| 0.1.0 | 1.21.1 = 3，其余 5 个版本 = **0** | 表里只有 3 个 TUFF 楼梯，且被 `//? if 1.21.1` 包住。提交信息里的"52 个宿主类 + 实例工厂"并未生效 |
| 0.1.1–0.1.4 | 1.21.10 / 1.21.11 / 26.3 = 41；1.19.4 / 1.20.1 / 1.21.1 = **0** | 手写 41 条，正好对应后来 `redstone-hosts.txt` 的 41 个代表宿主。但 `properties()` 的 stonecutter 分支只给 1.21.10 以上留了可编译实现，另外三个版本的 `buildRegistry()` 在预处理后是空的 |

> 0.1.3 的变更记录写的是"**1.21.1 重新启用红石相组合方块**，六个版本现在都可用"。
> 按 stonecutter 求值，该提交里 1.21.1 的实例表是 0 条 `map.put` —— 1.21.1 改的是
> `CompositeBlocks.register()` 的注册分支，没有同时补工厂条目。这一条在 0.1.5 整体删除
> （红石相并进 `phase`）后自动失效，但它说明当时"逐版本可用"的结论没有按版本逐一验证。

这一段在 0.1.5 整体删除（红石相并进 `phase`），不影响当前版本，但说明"41 个代表宿主"
这个口径的来源是 0.1.1 的手写表，而不是任何推导。

---

## 4. 至今仍未覆盖的宿主（0.1.12 仍然存在）

> **注（0.1.13）**：本节的 7 项已在 0.1.13 全部修复，修法与逐项更正见 **§6**；
> 其中 §4.7 的核心推断（"1.21.1+ 熔岩桶不再灌入活板门/箱子/光源"）是**错的**，
> 见 §6 的更正。本节以下保留 0.1.12 时的分析原文。

以下各项**都没有被任何历史补丁触及**，与 §3 的清单问题无关。

### 4.1 台阶的 `type=double` 变体：含固相完全看不见（57–101 个宿主）

`tools/gen_phase_blockstates.py` 的整格判定是**方块级**的：

```python
full_cube = bool(parts) and all(geometry.is_full_cube(m) for m in referenced_models(parts))
```

`all()` 意味着"只要有一个变体不是整格，整个方块都走内缩覆盖层"。台阶恰好是混合模型
（逐版本混合模型宿主 **100% 都是台阶**，没有例外）：

| 变体 | 模型 | 是否整格 |
| --- | --- | --- |
| `type=bottom` / `type=top` | `*_slab` / `*_slab_top`（半块） | 否 |
| `type=double` | `minecraft:block/<整块>`（0..16 完整立方体） | **是** |

于是在 `type=double` 状态下：宿主 part 画一个不透明的完整立方体，相覆盖层是 0.25 内缩的
立方体 —— **整个覆盖层都在宿主模型内部，一点都看不见**。含黑曜石/冰双台阶在视觉上与
普通双台阶完全一样（碰撞恰好也是整格，因为 `SlabBlock` 对 `DOUBLE` 直接返回 `Shapes.block()`）。

**双台阶是怎么带上相的**：不能靠灌入（`SlabBlock.canPlaceLiquid` 对 `DOUBLE` 恒为 false，
`PhaseHelper.canAcceptPhase` 会拒），而是靠**叠加**：`SlabBlock.getStateForPlacement`
在"点已有半砖"时执行

```java
return blockState.setValue(TYPE, SlabType.DOUBLE).setValue(WATERLOGGED, false);
```

`blockState` 是**已有的那半砖状态**，`setValue` 只改 `TYPE` 与 `WATERLOGGED`，
`phase` 被原样保留 —— 于是"含冰的下半砖 + 再叠一块"就得到一个带相的双台阶。

受影响宿主数（台阶，逐版本）：**57 / 57 / 60 / 62 / 62 / 101**。
26.3 的 101 个里包含羊毛与混凝土台阶全家。

这与 0.1.2 / 0.1.11 修的是**同一类根因**（"覆盖层藏在完整立方体里"），只是发生在
**变体粒度**而不是方块粒度，所以自动判定没能覆盖它。0.1.11 的自动判定修好之后，
这片区域反而看起来"已经有机制保证了"，更容易被忽略。

> 建议口径：按 **part（变体）** 判定，而不是按方块。整格变体用 `phase_*_full`，
> 其余变体用内缩覆盖层；或者对这一类"混合模型"宿主禁止在 double 状态写入固相。
> 现有自检只断言 `phase_ice_full` 是否出现（方块级），测不出这个问题。

### 4.2 `barrier` 在 1.19.4 / 1.20.1：`B-A` 的 1 个方块，且是崩溃面

`PhaseCapableMixin` 从 0.1.0 起就把 `BarrierBlock` 列为目标，但
**1.19.4 / 1.20.1 的 `BarrierBlock` 既没有实现 `SimpleWaterloggedBlock`，也没有 `WATERLOGGED` 属性**
（1.21.1 才加上）。于是这两个版本上 `barrier` 会拿到 `phase` 属性、成为 `LiquidBlockContainer`，
而 `PhaseCapable` 的默认方法直接调用 `SimpleWaterloggedBlock.super.placeLiquid(...)`，
后者第一行就是 `blockState.getValue(WATERLOGGED)`：

```java
// SimpleWaterloggedBlock.placeLiquid
if (!blockState.getValue(BlockStateProperties.WATERLOGGED) && ...)   // ← IllegalArgumentException
```

触发路径（两版本都有，从 0.1.0 起就存在，**与 0.1.12 的清单修复无关**）：

- 水桶点屏障 → `BucketItem.emptyContents` 里 `canPlaceLiquid(WATER)` 走默认方法返回 true →
  再走 `placeLiquid` → 抛 `IllegalArgumentException: Cannot get property ... as it does not exist`；
- 空桶点屏障 → `BucketPickup.pickupBlock` → 同一条 `getValue(WATERLOGGED)`；
- 水流进屏障 → `FlowingFluid.canHoldFluid → canPlaceLiquid` 返回 true →
  `spreadTo → placeLiquid` → 同上（这是最容易自然触发的一条）。

`canPlaceLiquid` 本身不崩（默认实现只比较 `fluid == WATER`，不读属性），
所以熔岩流向屏障会被拒、水才会走到 `placeLiquid` 并崩。

0.1.12 的清单修复让 `barrier` 终于有了 blockstate，于是"含红石相的屏障看得见了" ——
这反而让这个方块从"含相看不见、但也基本没人会去灌"变成"看得见、且水桶一碰就崩"的表面可达路径。
修复方向与清单无关，必须改源码。

> 三个互斥的修法，需要选一个：
> ① 1.19.4 / 1.20.1 的 `@Mixin` 目标里去掉 `BarrierBlock`（回到 `B-A = 0`，同时从 `.ids` 移除）；
> ② 给 `PhaseCapable` 的默认方法加"没有 `WATERLOGGED` 属性就当作未含水"的保护；
> ③ 1.19.4 / 1.20.1 单独给 `BarrierBlock` 注入 `WATERLOGGED`（成本最高，且会改变原版状态数）。
> 推荐 ①：这两个版本的屏障本来就不是可含水方块，原版也没有 `phase` 语义可保护。

### 4.3 熔岩"被相邻熔岩灌入"对 4 类宿主失效（76–121 个宿主）

`PhaseCapable` 的默认方法是**接口默认方法**。宿主类自己声明同名方法时，默认方法被遮蔽，
熔岩分支整个失效。逐版本清单：

| 宿主类 | 被遮蔽的方法 | 1.19.4 | 1.20.1 | 1.21.1 | 1.21.10 | 1.21.11 | 26.3 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SlabBlock` | `canPlaceLiquid` + `placeLiquid` | 57 | 57 | 60 | 62 | 62 | 101 |
| `CandleBlock` | `placeLiquid` | 17 | 17 | 17 | 17 | 17 | 17 |
| `CampfireBlock` | `placeLiquid` | 2 | 2 | 2 | 2 | 2 | 2 |
| `DriedGhastBlock` | `placeLiquid` | — | — | — | 1 | 1 | 1 |
| **合计** | | **76** | **76** | **79** | **82** | **82** | **121** |

> 1.19.4 / 1.20.1 的 `BarrierBlock` 既不声明这些方法、也没有 `WATERLOGGED`，所以**不计入**这里
> （它的问题更严重，见 §4.2）。1.21.1+ 的 `BarrierBlock` 单独计入 §4.2 的"建议移除"范围。

后果分两种：

- **台阶**（`canPlaceLiquid` 被遮蔽）：`FlowingFluid.canHoldFluid` → `SlabBlock.canPlaceLiquid`
  → `SimpleWaterloggedBlock.super.canPlaceLiquid` → `fluid == WATER` → **熔岩被拒**。
  于是"会被相邻熔岩按形状灌入"对台阶完全不成立（熔岩桶、放置吸收仍可用，因为那几条路
  走的是 `fillWithPhase` 而不是 `placeLiquid`）。
- **蜡烛 / 营火 / 干化恶魂**（`placeLiquid` 被遮蔽）：`canPlaceLiquid` 走默认方法**接受**熔岩，
  于是 `FlowingFluid.spreadTo` 会调用 `placeLiquid`，而宿主实现只处理 `WATER` 并返回 false →
  熔岩每 tick 重试、**永久灌不进去**（不会崩，但表现为"熔岩贴着蜡烛不流进去"）。

> 补充：`BucketItemMixin` 的熔岩桶 `emptyContents` 在台阶/蜡烛上不受影响吗？
> 受影响，但它**先**用 `PhaseHelper.fillWithPhase` 接管（走 `canAcceptPhase`，不读
> `canPlaceLiquid`），所以熔岩桶右键仍然能把相灌进台阶/蜡烛。只有"相邻熔岩自己流进来"
> 这条引擎路径会被遮蔽。

这两处都与 README / AGENTS 里"内部熔岩是真实熔岩源……也会被相邻熔岩按形状灌入"的表述矛盾。
0.1.2 起就存在，没有任何补丁处理。修法只有"给这些类补 mixin 或在 `placeLiquid` 的
`@Inject` 里接管熔岩分支"这一条路（和 `BucketItemMixin` 已经在做的事同类）。

### 4.4 固相"整格碰撞"只对不覆写 `getCollisionShape` 的宿主成立（66–88 个宿主）

`BlockBehaviourMixin` 的整格碰撞注入在 `BlockBehaviour.getCollisionShape` 的 `RETURN` 上。
宿主类自己声明 `getCollisionShape` 且不转调 `super` 时，注入完全不执行：

| 类 | 逐版本宿主数（1.19.4→26.3） | 实际碰撞 |
| --- | --- | --- |
| `CrossCollisionBlock`（栅栏/玻璃板/铁栏杆/铜栏杆） | 30 / 30 / 30 / 39 / 39 / 40 | 宿主的十字形状（`CrossCollisionShapeMixin` 显式返回宿主形状） |
| `WallBlock` | 22 / 22 / 25 / 26 / 26 / 32 | 墙形状（`WallBlockShapeMixin`） |
| `WallHangingSignBlock` | 11 / 11 / 11 / 12 / 12 / 13 | 招牌横板形状 |
| `ScaffoldingBlock` | 1 | 脚手架形状 |
| `SculkShriekerBlock` | 1 | 方块实体形状 |
| `BigDripleafBlock` | 1 | 叶片形状 |
| **合计** | **66 / 66 / 69 / 80 / 80 / 88** | |

也就是说 README 里"**碰撞**：冰/黑曜石相按整格实心（与'相填满宿主空隙'的视觉一致）"
只对**一半左右**的宿主成立：栅栏/玻璃板/墙/墙悬挂告示牌/脚手架这些"最容易看到缝隙"的宿主
恰恰都不是整格碰撞。

这不是新 bug，而是两条互相冲突的设计意图一直并存：
`*ShapeMixin` 为了避开"形状表查不到 → NPE"必须返回宿主形状，而 D7 又要求整格。
现有自检只对**楼梯**断言"碰撞跟随宿主"（第 197–198 行），刚好落在两种口径都不冲突的样本上。

> 需要明确取舍：要么承认"固相碰撞跟随宿主"（改文档 + 删 D7 相关表述），
> 要么把整格碰撞移到状态级（`BlockStateBase.getCollisionShape` 的 `RETURN`），
> 让它对所有宿主一致生效。二者不能同时自称成立。

### 4.5 `hasOffsetFunction()` 让 4–5 个宿主的"破坏保留"和"整格碰撞"整体跳过

```java
// BlockStateBaseMixin（两处） / BlockBehaviourMixin
if (PhaseHelper.isSolidPhase(self) && !self.hasOffsetFunction()) { ... }
```

带 `offsetType` 的宿主（模型按坐标随机位移）：`hanging_roots`、`mangrove_propagule`、
`pointed_dripstone`、`small_dripleaf`，26.3 另有 `sulfur_spike`（共 4 / 4 / 4 / 4 / 4 / 5 个）。
这个 guard 的意图是"不要把一个会位移的方块在格心实体化成整块"，但副作用是：

- **破坏含固相的这些方块，相直接消失**（不留下黑曜石/冰/霜冰），与"破坏保留"的全局承诺不符；
- 固相碰撞也不是整格。

这两个副作用没有任何文档记录（README/AGENTS 都没有提 offsetType 例外）。

### 4.6 `PlacementMixin` 缺 `MultifaceBlock`（1.19.4 / 1.20.1 / 1.21.1）

`glow_lichen` / `sculk_vein` 在**所有版本**都继承 `MultifaceBlock`，并继承它的
`getStateForPlacement`。但 `PlacementMixin` 的 `MultifaceBlock.class` 被包在
`//? if 1.21.10 || 1.21.11 || 26.3` 里：

| 版本 | `MultifaceBlock` 在 `PlacementMixin` 目标里 | 后果 |
| --- | --- | --- |
| 1.19.4 / 1.20.1 / 1.21.1 | 否 | 两个方块放进熔岩源格时**不吸收熔岩相** |
| 1.21.10 / 1.21.11 / 26.3 | 是 | 正常 |

0.1.0 起就是这样（`PhaseCapableMixin` 用的是同一段版本判断，但那一处 1.19.4–1.21.1 走的是
`GlowLichenBlock`+`SculkVeinBlock` 两个子类，所以注入没问题）。影响面小（2 个方块），
但属于"同一份版本判断被复制到第二个 mixin 时抄错了分支"。

### 4.7 1.21.1+ 起，熔岩桶右键活板门 / 箱子 / 光源方块不再灌入（行为跨版本不一致）

`BucketUseMixin` 注入的入口随版本变化：≤1.20.1 是块级 `use`，≥1.21.1 是块级 `useItemOn`。
但 1.21.1 起原版把"右键方块"拆成了两步：`useItemOn`（拿着物品）→ 未消费则 `useWithoutItem`（空手逻辑）。
`BucketUseMixin` 只挂了前一步。

对这些宿主来说，第二步会**消费掉**这次右键，桶的 `use` 根本不会被调用：

| 宿主类 | 1.21.1+ `useWithoutItem` 的返回值 | 结果 |
| --- | --- | --- |
| `TrapDoorBlock` | `SUCCESS`（开/关活板门） | 熔岩桶不再灌入，改为开关活板门 |
| `ChestBlock` | `SUCCESS`（打开容器） | 熔岩桶不再灌入，改为打开箱子 |
| `EnderChestBlock` | `SUCCESS`（打开末影箱） | 同上 |
| `LightBlock` | `SUCCESS`（切换亮度） | 熔岩桶不再灌入，改为调亮度 |
| `FenceBlock` | 无拴绳时 `PASS` | **仍会灌入**（落到桶路径） |
| `StairBlock` | 不声明（基类 `PASS`） | **仍会灌入** |

也就是说同一个手势在 ≤1.20.1 与 ≥1.21.1 上行为不同，且 1.21.1+ 上活板门/箱子/光源方块
**无法再用熔岩桶灌入**（只能走发射器 / 放置吸收 / 红石线替换等路径）。这些方块仍然能含相，
所以不属于"完全不能含相"，但"熔岩桶右键可含水方块"这句承诺在 1.21.1+ 上是**有条件成立**的。

反过来看，≤1.20.1 的目标清单把 `ChestBlock` / `EnderChestBlock` / `TrapDoorBlock` / `LightBlock`
也纳入了，意味着旧版本上**拿熔岩桶右键箱子不会开箱、而是灌熔岩** —— 这本身是对原版交互的覆盖，
是否为预期需要明确。

> 建议：要么给 `BucketUseMixin` 补 `useWithoutItem` 的注入点（并保证只在"手持液体桶"时接管），
> 要么在 README 里把"熔岩桶右键"限定为"不消费右键的宿主"。两种都需要同步更新测试矩阵。

---

## 5. 结论与建议优先级

按"是否会造成可达的崩溃 / 是否让补丁的承诺落空"排序：

| 优先级 | 项 | 影响 | 建议 |
| --- | --- | --- | --- |
| **P0** | §4.2 `barrier` 在 1.19.4/1.20.1 崩溃 | 水桶/空桶/水流可触发 `IllegalArgumentException`（0.1.0 起就存在） | 从这两个版本的 `@Mixin` 与 `.ids` 移除 `BarrierBlock` |
| **P1** | §4.1 双台阶含固相看不见 | 57–101 个宿主，用户明确可见 | 整格判定改到 part（变体）粒度；自检按变体断言 |
| **P1** | §4.3 台阶/蜡烛/营火/干化恶魂灌不进熔岩 | 76–121 个宿主，与 README 承诺矛盾 | 给这几类补 `placeLiquid`/`canPlaceLiquid` 的熔岩分支 |
| **P2** | §4.4 固相整格碰撞只在部分宿主生效 | 66–88 个宿主，与 D7 矛盾 | 二选一：改文档，或把整格碰撞移到状态级 |
| **P2** | §4.5 offsetType 宿主不保留相 | 4–5 个宿主 | 至少写进已知限制；能接受位移则去掉 guard |
| **P3** | §4.6 `MultifaceBlock` 放置吸收 | 2 个宿主 × 3 个版本 | 把 `PlacementMixin` 那条分支的版本判断与 `PhaseCapableMixin` 对齐 |
| **P3** | §4.7 1.21.1+ 熔岩桶右键活板门/箱子/光源不再灌入 | 5 类宿主 | 补 `useWithoutItem` 注入点，或把"熔岩桶右键"的适用范围写进文档 |

历史部分（§3）不需要再修，但值得记住两点：

1. **0.1.9 / 0.1.10 / 0.1.11 三次"显示修复"都建立在同一份错误清单上**，而当时的回归断言
   只抽查清单里确实存在的代表方块，所以三次都自证通过。真正解决它的是 0.1.12 的
   `EXPECTED_COUNTS` + 双向集合比对 —— 这个教训适用于所有"按清单生成资源"的补丁。
2. **"外壳只覆盖 41 个宿主"（0.1.5–0.1.8）与"清单漏登记 1/24/197 个"是两个独立缺口**，
   0.1.9 只修掉了前一个。当时的实测结论（"透明方块看不到红石"）其实同时被两个原因解释。

覆盖面的验收口径建议固定在 `tools/audit_host_coverage.py` 的四个差集上：
`A-B`、`B-C`、`C-B`、`C-S` **必须全为 0**；`B-A` 允许非空但必须逐个说明理由
（当前唯一允许项是 §4.2，而它建议被移除而不是保留）。

---

## 6. 0.1.13 的处置（逐项修复记录）

§4 的 7 处缺口在 0.1.13 全部处理完毕。以下是逐项的修法与**对本文档的更正**，
历史分析（§1–§5）保持原样作为审计快照。

| 项 | 修法 | 自检回归 |
| --- | --- | --- |
| §4.1 双台阶含固相看不见 | `tools/gen_phase_blockstates.py` 整格判定改到 **part（变体）粒度**：全部整格→原整格行为；全部非整格→原内缩行为；混合→整格 part 换 `phase_*_full` 相材质、宿主 part 限制在"宿主可见"的相，非整格 part 保持内缩（when 取整格条件的取反，如 `"type": "!double"`）。取反只用单值否定或 AND/OR 组合——`KeyValueCondition` 对 `"!a|b"` 多值否定的语义在 1.19.4 与 1.21.2+ 相反，不能写 | `checkSlabVariantRendering`（stone_slab 的 full/内缩两层条件） |
| §4.2 barrier 1.19.4/1.20.1 崩溃面 | 采纳建议 ①：`BarrierBlock` 从这两个版本的 `PhaseCapableMixin` 目标、`closure.txt`、`.ids` 移除；`EXPECTED_COUNTS` 309→308、311→310。1.21.1+ 保留（那里有 `WATERLOGGED`） | `checkPhasePropertyCoverage`（清单==运行时）+ `checkPhasedRenderShape`（1.19.4/1.20.1 改用 `light` 顶上） |
| §4.3 遮蔽的熔岩分支 | 新增 `SlabLiquidMixin`（台阶：`canPlaceLiquid`+`placeLiquid`）与 `HostPlaceLiquidMixin`（蜡烛/营火/干化恶魂：`placeLiquid`），只接管熔岩分支；水仍走宿主原版逻辑。**有意不接管** 1.21.1+ `barrier` 的 `canPlaceLiquid`/`pickupBlock`——原版语义是创造模式限定接受流体，探测调用拿不到玩家身份时本来就拒绝 | `checkAdjacentLavaFilling`（直接调遮蔽方法，与 `FlowingFluid` 同参；含反向断言"含水台阶仍拒绝熔岩"） |
| §4.4 固相整格碰撞只覆盖一半宿主 | 采纳"移到状态级"：整格碰撞从 `BlockBehaviourMixin` 移到 `ShapeFallbackMixin`（`BlockStateBase.getCollisionShape` 的 RETURN），**三参（实体碰撞路径）+ 两参（`Cache.collisionShape` 缓存路径，`isCollisionShapeFullBlock` 走它）都挂**。块级 null 兜底保留（`Cache.<init>` 直接调块级方法，兜底必须留在那） | `checkSolidPhaseCollision`（栅栏/墙/墙悬挂告示牌/脚手架/big_dripleaf/楼梯/台阶逐一 + 全量扫描两参三参 + 红石相不参与的反向断言） |
| §4.5 offsetType 宿主跳过 | 删除 `!state.hasOffsetFunction()` guard（实体化 hook 与块级碰撞注入两处；后者随 §4.4 整体移除） | `checkBreakRetainEverywhere`（全量扫描 offset 宿主：破坏后必须留冰；楼梯对照 + 扫描命中数下限） |
| §4.6 PlacementMixin 漏 MultifaceBlock | `MultifaceBlock.class` 改为**全版本**目标（guardian 核对：`getStateForPlacement` 全版本都由 `MultifaceBlock` 声明，比"对齐 PhaseCapableMixin 的两个子类分支"更直接；handler 有 `instanceof PhaseCapable` 保护） | `checkPlacementAbsorption`（点熔岩源格旁石头侧面，context 落点=熔岩格，断言 `getStateForPlacement` 带熔岩相） |
| §4.7 熔岩桶右键跨版本不一致 | **更正：§4.7 的核心推断是错的。** `BucketUseMixin` 在 ≥1.21.1 目标清单里有 `BlockBehaviour` 基类，未声明 `useItemOn` 的宿主（活板门/箱子/末影箱/光源）dispatch 全部落到基类 → 灌入在 `useItemOn` HEAD 就发生，`useWithoutItem` 根本没机会消费右键——"1.21.1+ 不再灌入"不成立，两个年代的行为其实一致（都强制灌入）。真实问题是**强制灌入凌驾原版交互**（≤1.20.1 连箱子/告示牌都灌）。按"参照原版倒水的交互"：删除 `BucketUseMixin`，桶交互完全走原版路径（`useItemOn` 通过 → `useItem` → `emptyContents` → `BucketItemMixin`）：原版交互消费右键的宿主不灌入（水桶点活板门只会开关它——同规则），其余宿主照常灌入；潜行右键绕过交互照常灌入；发射器/放置吸收不变。**顺带修复**：`BucketItemUseMixin` 在 ≤1.21.1 的 CIR 类型写成了 1.21.10+ 的 `InteractionResult`（宿主返回 `InteractionResultHolder`），`setReturnValue` 后强转必崩——以前被 `BucketUseMixin` 提前消费右键而很少走到，桶走原版路径后成了必经之路，按版本分支修正 | `checkBucketInteraction`（FakePlayer：楼梯灌入、活板门被原版开关不灌、光源方块保持原版交互不灌） |

修复后的审计输出：四个差集全零；"覆写遮蔽"清单只剩 1.21.1+ `barrier` 的
`canPlaceLiquid`/`pickupBlock`（有意不接管，见上）；台阶/蜡烛/营火/干化恶魂的条目
标为"覆写已接管"（`SlabLiquidMixin` / `HostPlaceLiquidMixin`）；
"块级碰撞被覆写"条目保留为结构信息（固相碰撞已不依赖块级）。

### 6.1 经验

1. **"基类 mixin 覆盖一切未声明者"这一层，读审计结果时必须想到。** §4.7 的错误推断
   正是漏了它：`@Mixin(BlockBehaviour.class)` 的注入对所有不覆写方法的子类生效，
   与"目标清单里有没有那个具体类"无关。
2. **mixin 只能注入目标类自己声明的方法**（AGENTS §4）反过来也限制了修复方式：
   `HostPlaceLiquidMixin` 能挂在蜡烛/营火上是因为它们真声明了 `placeLiquid`；
   `canPlaceLiquid` 只有台阶声明，所以两个 mixin 必须分开（共享 mixin 的注入点
   在未声明该方法的宿主上会直接报错，不能用 `require = 0` 掩盖）。
3. **CCE 类潜伏 bug 的教训**：跨版本改签名时，`CallbackInfoReturnable` 的类型参数
   是编译期擦除的，mixin 不检查；`setReturnValue` 的值最终会被强转回宿主返回类型。
   返回类型分版本的地方（`Item.use` 1.21.2 的返回类型重构）必须连 CIR 一起分支。
4. **多值否定不可跨版本**：`"!a|b"` 在 1.19.4 语义是 NOT(a|b)，1.21.2+ 是 (NOT a)|b。
   生成资源里的否定一律拆成单值否定或显式 AND/OR 组合。
