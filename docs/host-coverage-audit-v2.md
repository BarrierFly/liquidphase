# 相容宿主覆盖面审计 · 第二轮（0.1.13 之后）

> 定位：对 0.1.13 收口七处缺口（`docs/host-coverage-audit.md` §6）之后的**全量复查**。
> 与第一轮的差别：不再只查"清单/注入/外壳"三条可计算链路（它们已由
> `audit_host_coverage.py` 硬校验），而是把**每条功能链路逐宿主走一遍**——
> 放置（桶 / 流体流入 / 放置吸收 / 发射器 / 液体物品 / 红石相 / 下落方块）、
> 渲染（生成资源 / 整格判定 / 图层 / RenderShape）、收取（空桶左键 / 发射器空桶）、
> 以及其余功能（破坏保留、碰撞、冻结融化、火蔓延、炼药锅、漏斗、双端同步）。
>
> 复核命令见 §7。基线：HEAD = 144f6ef（0.1.13），1.21.11 自检 147/147 全过（本轮实跑）。

---

## 0. 结论速览

| # | 结论 | 性质 | 影响 |
| --- | --- | --- | --- |
| 1 | **发射器液体桶对宿主在默认配置下不灌相——整桶被弹射** | **真实缺口（P1）** | 全部宿主 × 6 版本；`liquidInteraction` 或 `cauldronAutomation` 任一开启时（两者默认都是 true） |
| 2 | **发射器空桶对含水宿主连方块一起毁掉**（原版只收水保宿主） | **真实缺口（P2）** | 全部含水宿主 × 6 版本；同上配置条件 |
| 3 | 交互型宿主需要**潜行**才能灌相/点相（60/61/69/102/102/108 个，逐版本），1.21.10+ 新增的搁物架/铜傀儡雕像/铜箱子**没有写进文档** | 设计内 + 文档缺口 | 见 §2.3 全集 |
| 4 | 红石粉点宿主对**同一批交互型宿主**同样被原版交互挡下，文档未写 | 设计内 + 文档缺口 | 同 §2.3 |
| 5 | **渲染链路未发现新缺口**：整格/混合/内缩判定 6 版本零误判；26.3 逐 quad 分层自动覆盖；barrier/light 为安全默认 | 绿灯（证据见 §3） | — |
| 6 | 清单/注入/外壳/blockstate 四集合差集保持全零（复跑第一轮脚本） | 绿灯 | — |
| 7 | 主放置链路（手持桶右键、流体流入、放置吸收、左键收取、潜行灌入、宿主落线格）在当前代码里**对全部宿主成立** | 绿灯（证据见 §2.5） | — |

对"已知的两个症状"的直答见 §6。

---

## 1. 方法

本轮证据来自四条互相独立的路径，避免重蹈"回归断言只抽查代表方块"的覆辙（第一轮 §3 的教训）：

1. **mixin 源码逐条走链路**：对每条放置/渲染/收取路径，从原版入口（`ServerPlayerGameMode` /
   `FlowingFluid` / `BucketItem` / `DispenserBlock`）读到本 mod 的注入点，再检查注入点的挂载层级
   （状态级 = 宿主覆写遮不住；块级/类级 = 需要逐类核对目标清单）。
2. **guardian 反编译源码独立重算方法声明矩阵**：不依赖 `audit_host_coverage.py` 的既有假设，
   直接对 6 个版本的 guardian 提交重算"宿主类层级里谁声明了 `canPlaceLiquid` / `placeLiquid` /
   `pickupBlock` / `use` / `useItemOn` / `useWithoutItem` / `getStateForPlacement` / `canBeReplaced`"，
   与本 mod 已处理集合求差集。
3. **生成 blockstate 与原版模型几何双向对照**：对 6 个版本的生成产物逐 id 扫描覆盖层 part，
   并用与生成器**相同口径但独立实现**的几何判定重算整格/混合/内缩分类，双向比对。
4. **实跑当前 HEAD 自检**（1.21.11，`LIQUIDPHASE_SELFTEST=true`，147/147 通过）。

guardian ↔ 真实 jar 的一致性抽查过两处（1.19.4 的 cherry/decorated_pot、1.21.1 的 copper_trapdoor），
guardian 提交、loom 缓存客户端 jar、ids 清单三者一致，排除了"版本↔提交映射错位"的可能。

---

## 2. 填充相放置：逐链路结论

### 2.1 缺口 A（P1）：发射器液体桶对宿主默认不灌相——整桶弹射

**路径**：`DispenserBlock` 对 `WATER_BUCKET`/`LAVA_BUCKET` 的原版行为是
`emptyContents(null, level, frontPos, null)` → 本 mod `BucketItemMixin` 在 `emptyContents` HEAD
接管熔岩 → `PhaseHelper.fillWithPhase`；水则走原版容器分支 → `placeLiquid` → 含水。
**全配置关闭时这条链路对全部宿主成立**（含熔岩相灌入、含水化）。

**但是**：`LiquidInteractions.registerDispenserBehaviors` 在 `liquidInteraction || cauldronAutomation`
时用 `LiquidBucketBehavior` **替换**原版行为，而两者**默认都是 true**
（`LiquidPhaseConfig` 第 29/41 行）：

```java
// LiquidInteractions.liquidDispense（功能三开启分支）
if (liquidInteraction) {
    if (!frontState.isAir() && !frontState.canBeReplaced()) {
        return null;                      // ← 前方是宿主（不可替换）→ null
    }
    ...
}
```

返回 null 后 `LiquidBucketBehavior.execute` 落到 `super.execute(source, stack)`
= `DefaultDispenseItemBehavior` = **把整桶当物品实体弹射出去**，既不灌相、也不含水。

`cauldronAutomation` 单开、`liquidInteraction` 关闭时同样命中：`liquidDispense` 的
"关闭时等价原版桶"分支对非可替换格直接跳过 → `return null` → 弹射。

| 配置 | 发射器液体桶 → 宿主 | 依据 |
| --- | --- | --- |
| 全关 | **灌相 / 含水**（原版行为 + BucketItemMixin） | 原版 `DispenseItemBehavior.java` 的桶行为调 `emptyContents` |
| `cauldronAutomation` 单开 | **弹射整桶** | `liquidDispense` 对不可替换格 return null → DefaultDispenseItemBehavior |
| `liquidInteraction` 开（含默认全开） | **弹射整桶** | 同上 |

**三处矛盾**：
1. `BucketItemMixin` javadoc："发射器装液体走同一个 emptyContents，一并覆盖"——只在全关时成立；
2. `LiquidInteractions` javadoc："关闭时等价原版桶（emptyContents+空桶）"——`cauldronAutomation`
   单开时对宿主并不等价（弹射 vs 灌入）；
3. 同一个手势，开关 `cauldronAutomation` 会改变发射器能否灌相——跨配置行为不一致，
   测试矩阵（AGENTS §5"发射器/漏斗"行）只测了锅，没有宿主用例，所以自检全绿也测不出来。

**受影响面**：全部相容宿主（308/310/337/410/410/505 个）× 6 版本 × 默认配置。
表现即"部分宿主无法正常处理填充相放置"——严格说是**全部宿主在发射器这一条路径上**。

**修法建议**（二选一，建议 ①）：
1. `LiquidBucketBehavior` 在 `liquidDispense` 返回 null 且前方是 `PhaseCapable` 时，
   按原版桶语义调 `emptyContents`（熔岩走 `BucketItemMixin`、水走容器分支），
   即"液体物品像方块，但宿主是它的容器"；
2. 或把发射器灌相明确写成"仅全配置关闭时可用"，同步改 README/测试矩阵/两处 javadoc。

### 2.2 缺口 B（P2）：发射器空桶对含水宿主连方块一起收走

同样的替换条件下，`EmptyBucketBehavior.emptyBucketCollect` 的判定链：

```java
if (PhaseHelper.isLava(frontState)) { ...收熔岩相、保宿主... }        // 熔岩相宿主：正确
FluidState fluid = level.getFluidState(front);
if (fluid.isSource() && (fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA))) {
    level.setBlock(front, Blocks.AIR.defaultBlockState(), ...);      // ← 含水宿主：连方块一起毁
    ...
}
```

含水（waterlogged=true）宿主的 `FluidState` 是水源，于是直接 `setBlock(AIR)`——
**宿主方块整个消失，掉一个水桶**。原版（配置全关时走 `BucketPickup.pickupBlock`）
只收水、保宿主；本 mod 自己的"空桶左键收取"（`PhaseTargeting.mineLiquid`）也只对
真实流体/熔岩相/锅 `removeBlock`，对含水宿主根本不进分支。三处口径里只有发射器这条坏。

**受影响面**：全部可含水宿主的 waterlogged 状态 × 6 版本 × 同 §2.1 配置条件（默认即触发）。

**修法建议**：`emptyBucketCollect` 在流体源判定之前加 `frontState.getBlock() instanceof BucketPickup`
→ `pickupBlock(null, level, front, frontState)`，非空产物按 D15 入槽/掉落；空产物再落回原判
定链。顺带把"含水宿主"从 `setBlock(AIR)` 分支里排除。

### 2.3 需潜行的交互型宿主全集（设计内，文档不全）

0.1.13 删除 `BucketUseMixin` 后，灌入完全走原版桶路径：`useItemOn` 通过 → `useItem` →
`emptyContents`。**原版交互消费右键的宿主**必须潜行（原版 `ServerPlayerGameMode.useItemOn`
的 `isSecondaryUseActive()` 绕过块交互——6 个版本的 guardian 源码逐一确认存在）。

逐版本全集（按 ids 清单实测计数；判定依据 = guardian 方法声明矩阵 × 各类对"桶"的实际响应）：

| 家族 | 1.19.4 | 1.20.1 | 1.21.1 | 1.21.10 | 1.21.11 | 26.3 | 桶在非潜行时的响应 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 告示牌/墙告示牌/悬挂告示牌 | 44 | 44 | 44 | 48 | 48 | 52 | 打开编辑界面（消费） |
| 活板门（含铜活板门） | 12 | 12 | 20 | 21 | 21 | 22 | 开关（消费） |
| 箱子/陷阱箱/末影箱/铜箱子 | 3 | 3 | 3 | 11 | 11 | 11 | 开箱（消费） |
| 搁物架 | — | — | — | 12 | 12 | 13 | 物品交换（消费） |
| 铜傀儡雕像 | — | — | — | 8 | 8 | 8 | 交互（消费） |
| 装饰陶罐 | — | 1 | 1 | 1 | 1 | 1 | 交互（消费） |
| 光源方块 | 1 | 1 | 1 | 1 | 1 | 1 | 调亮度（消费） |
| **合计** | **60** | **61** | **69** | **102** | **102** | **108** | |

**不在清单里的"看似会消费、实际 PASS"的宿主**（无需潜行，实测源码确认）：
楼梯（`use` 转调 `baseState.use`，全部楼梯基座无交互 → PASS）、蜡烛（仅空手对点燃蜡烛 →
PASS）、营火（仅食物 → PASS）、栅栏（仅拴绳 → PASS）、发光地衣/幽匿脉络/树脂团
（26.3 的 `MultifaceBlock` 无 `useItemOn`）、conduit、sculk 传感器/尖啸体、脚手架。

**文档与自检缺口**：
- README/AGENTS 测试矩阵只写了"活板门/箱子/光源/告示牌"四类代表；
  1.21.10+ 新增的**搁物架/铜傀儡雕像/铜箱子**没有记录，属于本轮补齐；
- 自检 `checkBucketInteraction` 断言了"不潜行不灌"（活板门/光源），但**没有断言
  "潜行能灌"**——潜行绕过万一被未来改动破坏，测试不会红。建议补一条
  `player.setShiftKeyDown(true)` → `useItemOn` → 断言灌入的用例。

### 2.4 有意排除：barrier（1.21.1+）

1.21.1+ 的 `barrier` 覆写 `canPlaceLiquid`/`pickupBlock` 且语义为"创造模式限定接受流体"，
探测调用拿不到玩家身份时本来就拒绝。0.1.13 已判定**有意不接管**，本轮维持该结论
（1.19.4/1.20.1 的 barrier 已在 0.1.13 从宿主集合移除，无崩溃面）。

### 2.5 主链路逐条绿灯的证据

| 链路 | 入口 | 覆盖层级 | 结论 |
| --- | --- | --- | --- |
| 熔岩桶右键（非交互宿主） | `BucketItem.emptyContents` → `BucketItemMixin` HEAD | 全宿主（HEAD 接管不依赖类清单） | ✅ |
| 水桶右键 | 原版 `emptyContents` 容器分支 → `placeLiquid` | 全宿主（`PhaseCapable` 默认方法） | ✅ |
| 相邻流体流入 | `FlowingFluid.canHoldFluid` → `canPlaceLiquid(null,…)` → `spreadTo` → `placeLiquid` | 全宿主；遮蔽族（台阶/蜡烛/营火/干化恶魂）由 `SlabLiquidMixin`/`HostPlaceLiquidMixin` 接管熔岩分支 | ✅（独立重算确认遮蔽全集无第五族） |
| 放置吸收（宿主放进熔岩源格） | `PlacementMixin`（`getStateForPlacement` RETURN） | 声明类全在目标清单（第一轮审计"放置吸收未覆盖"为空 + 本轮独立重算一致） | ✅ |
| 相材料物品（冰/黑曜石）点宿主 | `BlockItemMixin.place` HEAD (a) | 全宿主；交互型宿主需潜行（§2.3） | ✅ |
| 红石粉点宿主 | `BlockItemMixin.place` HEAD (a0) | 全宿主；交互型宿主需潜行（§2.4） | ✅ |
| 宿主放进红石线格 | `RedstoneWireReplaceMixin`（状态级 `canBeReplaced`）+ `BlockItemMixin.placeBlock` | 状态级，覆写遮不住 | ✅ |
| 下落方块落进线格 | `FallingBlockIntoWireMixin` | 相容宿主特判 | ✅ |
| 空桶左键收取 | `ClientLiquidMineMixin` + `ServerPlayerGameModeMixin` → `PhaseTargeting.mineLiquid` | 全宿主（熔岩相） | ✅ |
| 潜行灌入 | `ServerPlayerGameMode.useItemOn` 的 `isSecondaryUseActive()` | 6 版本 guardian 均存在 | ✅（无自检断言，见 §2.3） |

---

## 3. 填充相渲染：逐链路结论

**本轮未发现新缺口。** 证据：

### 3.1 整格/混合/内缩分类零误判

对 6 个版本的生成产物逐 id 扫描 + 用独立实现的几何判定重算，双向一致：

| 版本 | 整格 | 混合 | 内缩 | 混合家族 |
| --- | --- | --- | --- | --- |
| 1.19.4 | 11 | 57 | 240 | 全部是台阶 |
| 1.20.1 | 11 | 57 | 242 | 全部是台阶 |
| 1.21.1 | 19 | 60 | 258 | 全部是台阶 |
| 1.21.10 | 20 | 62 | 328 | 全部是台阶 |
| 1.21.11 | 20 | 62 | 328 | 全部是台阶 |
| 26.3 | 23 | 101 | 381 | 全部是台阶（含羊毛/混凝土台阶） |

关键反向验证（第一轮 §4.1 同类问题是否还有漏网）：
- **内缩宿主里没有任何一个的宿主模型其实是整格**（若有，内缩覆盖层会整个藏进宿主模型、
  固相完全看不见——0.1.2/0.1.11/0.1.13 三次修的都是这一类）。独立重算按
  "bbox 0..16 + 六面覆盖"判整格、按"elements 带 rotation"判否，与生成器口径一致且互为校验。
- **没有"带 rotation 但几何铺满整格"的宿主模型**（这类会被 rotation 拒绝判成内缩 → 藏进宿主）。
- `barrier`/`light` 的模型**没有 elements**（无 parent 链）→ 落到内缩是**安全默认**：
  这两个方块原生 `RenderShape.INVISIBLE`，模型本来就不画，强制 MODEL 后只有覆盖层可见。

### 3.2 图层

- 1.19.4–1.21.11：`PhaseRenderLayerMixin` 注入 `getChunkRenderType`（SOLID→CUTOUT）
  与 `getMovingBlockRenderType`（下落方块/活塞推动）两条，混合/内缩覆盖层与外壳的
  透明像素不会被 SOLID 丢弃；TRANSLUCENT 宿主（玻璃板/染色玻璃板）保持 TRANSLUCENT。
- **26.3**：没有 `ItemBlockRenderTypes`，图层由 **每个 quad 的材质透明度**决定
  （`BakedQuad.MaterialInfo.of` → `ChunkSectionLayer.byTransparency`，guardian d5822ca2a1 实证）。
  覆盖层/外壳的透明像素 quad 自动进 CUTOUT，宿主自身不透明 quad 留在 SOLID——
  不需要任何 mixin，也就不存在"漏接某族方块"的问题。

### 3.3 RenderShape / 方块实体宿主

`PhaseRenderShapeMixin` 挂在状态级 `getRenderShape` RETURN（任何块级覆写都遮不住）；
自检 `checkPhasedRenderShape` 全量扫描全部非 MODEL 原生宿主。1.21.10+ 原版已把
告示牌/箱子改成 MODEL；26.3 的无-elements 模型宿主（箱子族/铜箱子/陶罐/雕像/conduit）
模型只出粒子、可见几何来自方块实体渲染器，覆盖层独立渲染——均无缺口。

### 3.4 已知表现（不是缺口，但容易被当成"渲染不出"）

- **整格宿主的熔岩相没有覆盖层**（设计：熔岩由流体渲染画出来）。树叶/铜格栅有贴图孔洞，
  熔岩透孔可见；**红树根（mangrove_roots）的外皮近乎封闭**，熔岩相在视觉上基本不可见，
  只能靠 15 级光照/实体接触感知。这是 §3.1 整格替换语义的边界情况，如需改进应给
  "整格宿主 + 熔岩相"单独出半透明内壳，属于设计决策而非修 bug。
- 混合模型宿主（台阶）`type=double` 走整格替换、`type=bottom/top` 走内缩——回归断言
  `checkSlabVariantRendering` 在（0.1.13）。
- 含相状态的**遮挡**仍按宿主原形状（刻意决策）：黑曜石相的双台阶隔壁方块不消失、天光不被吃，
  但也意味着"从邻居缝隙看相"依赖覆盖层外扩 0.25px 的几何，极近视角有 1px 级接缝——
  与 AGENTS §3.2"遮挡/透光仍按宿主原形状"一致。

### 3.5 待实机取证

代码与资源层面绿灯不等于视觉验收通过。AGENTS §7.4 的手测清单仍然待做
（固定视角目视含相剔除接缝、0.1.12 补全宿主的红石相外观、双台阶固相外观、
0/15 档外壳色差等）。本轮没有运行真实客户端，这一条只能由 `runClient` 取证完成。

---

## 4. 其余功能逐条核对（均无逐宿主缺口）

| 功能 | 挂载点 | 层级 | 结论 |
| --- | --- | --- | --- |
| 熔岩相流体态/发光/火蔓延/凝固 | `BlockStateBaseMixin`（getFluidState/getLightEmission/randomTick/handleNeighborChanged） | 状态级 | 全宿主 |
| 破坏保留/红石相实体化 | 状态级 `onRemove`/`affectNeighborsAfterRemoval`；offsetType guard 已删（0.1.13） | 状态级 | 全宿主（自检全量扫描） |
| 固相整格碰撞 | `ShapeFallbackMixin` 两参（缓存路径）+三参（实体路径）RETURN | 状态级 | 全宿主（0.1.13 修，自检全量扫描） |
| 形状表兜底 | `ShapeFallbackMixin`（null→phase=none 兄弟）+ `BlockBehaviourMixin` 块级兜底 + 三个形状 mixin（CrossCollision/Wall/BigDripleaf/Multiface） | 状态级+块级+类级 | 全宿主 |
| 冰相随机刻融化/霜裂推进/霜冰相邻融化 | `BlockStateBaseMixin`（isRandomlyTicking/randomTick/tick/onPlace/handleNeighborChanged） | 状态级 | 全宿主 |
| 冻结（低温降水冻结水相宿主） | `ServerLevelMixin` redirect `Biome.shouldFreeze`（tickChunk/tickPrecipitation 按版本） | 全局 | 全水相宿主 |
| 红石相信号/重算/挖掘 | `RedstoneSignalMixin`（状态级 getSignal/getDirectSignal/isSignalSource）+ `RedstoneBlocks.exposed`（按 phase=none 兄弟形状判暴露面） | 状态级 | 全宿主 |
| 炼药锅/漏斗 | `CauldronBehaviors`/`CauldronHopperTransfer`——只涉及锅，不涉及宿主 | — | 不适用 |
| 双端同步 | 所有会改世界的客户端路径都有 `PhaseSyncState.canMutateWorld` 门控（BlockItem/Placement/BucketItem/BucketItemUse/RedstoneWireReplace/FallingBlockIntoWire/ClientLiquidMine/EntityPick） | — | 8 处齐全 |
| 发射器/漏斗对**锅** | `LiquidInteractions`/`CauldronHopperTransfer` | — | 见 §2.1/§2.2 的宿主旁路问题（锅不受影响） |

---

## 5. 自检与工具补强建议（按优先级）

1. **发射器 → 宿主**：FakePlayer 场景里对发射器装熔岩桶/水桶，分别在全关与默认配置下
   断言"宿主获得熔岩相 / waterlogged"——直接覆盖 §2.1；
2. **发射器空桶 → 含水宿主**：断言宿主保留、只收水（§2.2）；
3. **潜行灌入**：`checkBucketInteraction` 补 `player.setShiftKeyDown(true)` 分支，
   断言活板门/光源/告示牌潜行时灌入成功（§2.3）;
4. **交互型宿主全集**：把 §2.3 的计数写进 `audit_host_coverage.py` 作为固定清单列
   （声明了 `use`/`useItemOn`/`useWithoutItem` 且对桶 PASS 与否两类），防新增家族漏登记；
5. `audit_host_coverage.py` 的遮蔽检查把 `pickupBlock` 的"接管记录"（§2.2 修复后）同步进
   `LIQUID_COVERAGE` 表。

---

## 6. 对"已知的两个症状"的直答

**"部分宿主方块仍然无法正常处理填充相放置"**——当前代码里：
- 手持熔岩桶/水桶右键（含潜行）、相邻流体流入、放置吸收、左键收取、宿主落线格
  这些**主链路对全部宿主成立**（§2.5）；
- 真正失效的是**发射器两条路径**（§2.1/§2.2），默认配置即触发，影响全部宿主——
  如果实测场景是发射器灌相，这就是根因；
- 非发射器场景下"灌不进"的宿主应当全部落在 §2.3 的交互型全集里（没潜行）；
  逐版本精确清单和计数见那张表。若实测到的"灌不进"宿主不在该表内，请给出
  版本 + 具体方块 + 手势，那将指向本轮静态分析未覆盖的新链路。

**"部分宿主仍然无法正常渲染填充相"**——静态与资源层面**未复现出缺口**：
整格判定零误判（§3.1）、26.3 自动分层（§3.2）、隐形宿主强制 MODEL（§3.3）、
双台阶变体拆分有回归断言。两种可能：
1. 症状来自 0.1.13 之前的包（双台阶是 0.1.13 刚修的最后一例，发布前务必按
   AGENTS §6 核对 jar 里真的是新代码——曾发生"装了新 jar 但 bug 依旧"的教训）；
2. 症状在真实客户端的视觉层（剔除接缝/半透明排序/红树根熔岩相不可见这类 §3.4 表现），
   需要按 §3.5 跑 `runClient` 取证。请提供具体方块与版本，以便归类。

---

## 7. 复现命令

```bash
# 第一轮四集合差集 + 遮蔽/放置吸收清单（应保持 A-B/B-C/C-B/C-S 全零）
python tools/audit_host_coverage.py <guardian 仓库路径> .

# 当前 HEAD 行为自检（1.21.11；其余版本同式）
LIQUIDPHASE_SELFTEST=true LIQUIDPHASE_SELFTEST_STOP=true ./gradlew :1.21.11:runServer

# 本轮新增的四个独立扫描脚本（工作区 _audit_tmp/，尚未收编进 tools/）：
#   scan_shadow.py      独立重算 6 版本方法声明矩阵（canPlaceLiquid/placeLiquid/pickupBlock/
#                       use/useItemOn/useWithoutItem/getStateForPlacement/canBeReplaced × 宿主闭包）
#   scan_render.py      生成 blockstate 逐 id 覆盖层/外壳完整性 + full/inset/mixed 分类
#   scan_inset_full.py  反向验证：内缩宿主里模型其实是整格的（应为 0）
#   scan_rot.py         反向验证：带 rotation 的整格模型 + 无 elements 宿主清单
```
