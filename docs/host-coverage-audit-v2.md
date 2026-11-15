# 相容宿主覆盖面审计 · 第二轮（0.1.13 之后）

> 口径注记（0.1.15）：填充相属性名由 `phase` 改为 **`filling`**（取值不变）。
> 本文历史章节中的 `phase=`/`"phase":` 字样对应当时的代码，读时按新名映射。

> 定位：对 0.1.13 收口七处缺口（`docs/host-coverage-audit.md` §6）之后的**全量复查**。
> 与第一轮的差别：不再只查"清单/注入/外壳"三条可计算链路（它们已由
> `audit_host_coverage.py` 硬校验），而是把**每条功能链路逐宿主走一遍**，并做到：
> - `src/main/java` 全部 31 个 mixin、phase/state/sync/redstone/cauldron/interact/selftest
>   全部源码逐文件读完；
> - 全部 6 个版本的服务端自检**实跑**（不是引用历史日志）；
> - 仓库自带静态校验全部实跑（validate_blockstates / check_mixin_targets / verify_jars）；
> - 逐版本核对全部 mixin 的注入方法目标在 guardian 里确实存在（新增脚本化核对）；
> - 对原版引擎语义（`emptyContents` / `canHoldFluid` / `BucketPickup` / `LiquidBlock.updateShape` /
>   潜行绕过 / 26.3 分层）逐条到 guardian 实证。
>
> 复核命令见 §8。基线：HEAD = 4acea66（0.1.13 + 本报告），实跑环境 win32 / JDK 21。

---

## 0. 结论速览

| # | 结论 | 性质 | 影响 |
| --- | --- | --- | --- |
| 1 | ~~发射器液体桶对宿主在默认配置下不灌相——整桶被弹射~~ | **已修复（0.1.15）** | 按原版桶语义灌宿主：熔岩写相、水含水；拒绝才弹射 |
| 2 | ~~发射器空桶对含水宿主连方块一起毁掉~~ | **已修复（0.1.15）** | 走 `BucketPickup` 口径只收水、保宿主 |
| 3 | ~~熔岩相"重流"缺失：`scheduleLavaFlowTick` 死代码~~ | **已修复（0.1.15）** | 邻居变化时重排流体 tick；自检以 hasScheduledTick 前后差分断言 |
| 4 | **README 功能一节残留 4 条 0.1.12 时代的 ⚠️**（熔岩灌入不成立/整格碰撞只覆盖部分宿主/双台阶看不见/offsetType 破坏不留相）——这些 0.1.13 都已修掉，且与同文件后部已更新的"兼容性注意"自相矛盾 | 文档缺陷（P3） | 读者会被误导以为这四类宿主仍是坏的 |
| 5 | 交互型宿主需要**潜行**才能灌相/点相（60/61/69/102/102/108 个，逐版本），1.21.10+ 新增的搁物架/铜傀儡雕像/铜箱子已在此表中 | 设计内（潜行绕过 6 版本实证存在） | 见 §2.3 全集 |
| 6 | 次级语义偏差 4 条（活塞推宿主进线格不带相、功能三开启时空桶收不了含水宿主、霜冰级联融化不跨宿主、含相树叶继续衰减）——单条影响面小，均记录在 §2.6/§4 | 记录（P4） | — |
| 7 | **渲染链路缺口（0.1.14 修复）：26.3 上红石外壳模型的 -0.01..16.01 外扩几何使 bake 出界，整个 blockstate 回退 → 全部相不可见**；其余 5 版本零误判（证据 §3/§3.6） | **已修复** | 26.3 × 全部宿主 |
| 8 | 清单/注入/外壳/blockstate 四集合差集全零；6 版本自检 147/147/147/148/147/147 全过（本轮实跑）；全部 mixin 注入目标逐版本存在性核对通过；validate_blockstates/check_mixin_targets/verify_jars 全绿 | 绿灯 | — |

对"已知的两个症状"的直答见 §7。

---

## 1. 方法

四条互相独立的证据链，避免重蹈"回归断言只抽查代表方块"的覆辙（第一轮 §3 的教训）：

1. **mixin 源码逐条走链路**：对每条放置/渲染/收取路径，从原版入口（`ServerPlayerGameMode` /
   `FlowingFluid` / `BucketItem` / `DispenserBlock` / `LiquidBlock`）读到本 mod 的注入点，
   再检查注入点的挂载层级（状态级 = 宿主覆写遮不住；块级/类级 = 需要逐类核对目标清单）。
2. **guardian 反编译源码独立重算方法声明矩阵**：对 6 个版本的 guardian 提交重算
   "宿主类层级里谁声明了 `canPlaceLiquid` / `placeLiquid` / `pickupBlock` / `use` /
   `useItemOn` / `useWithoutItem` / `getStateForPlacement` / `canBeReplaced`"，与本 mod
   已处理集合求差集（不依赖 `audit_host_coverage.py` 的既有假设）。
3. **生成 blockstate 与原版模型几何双向对照**：对 6 个版本的生成产物逐 id 扫描覆盖层 part，
   并用与生成器**相同口径但独立实现**的几何判定重算整格/混合/内缩分类，双向比对。
4. **静态校验全量实跑 + 6 版本自检实跑**（详见 §6）。

guardian ↔ 真实 jar 的一致性抽查过三处（1.19.4 的 cherry/decorated_pot/suspicious_sand、
1.21.1 的 copper_trapdoor/copper_grate、26.3 的清单规模），guardian 提交、loom 缓存客户端
jar（`version.json` 实证）、ids 清单三者一致，排除了"版本↔提交映射错位"的可能。

---

## 2. 填充相放置：逐链路结论

### 2.1 缺口 A（P1）：发射器液体桶对宿主默认不灌相——整桶弹射

**全配置关闭时的原版链路是好的**：`DispenserBlock` 的桶行为调
`emptyContents(null, level, frontPos, null)` → 本 mod `BucketItemMixin` 在 `emptyContents`
HEAD 接管熔岩 → `fillWithPhase`；水走原版容器分支 → `placeLiquid` → 含水。对全部宿主成立。

**但是**：`LiquidInteractions.registerDispenserBehaviors` 在 `liquidInteraction || cauldronAutomation`
时用 `LiquidBucketBehavior` **替换**原版行为，而两者**默认都是 true**（`LiquidPhaseConfig`
第 29/41 行）：

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
`cauldronAutomation` 单开、`liquidInteraction` 关闭时同样命中。

| 配置 | 发射器液体桶 → 宿主 | 依据 |
| --- | --- | --- |
| 全关 | **灌相 / 含水**（原版行为 + BucketItemMixin） | 原版 `DispenseItemBehavior.java` 的桶行为调 `emptyContents` |
| `cauldronAutomation` 单开 | **弹射整桶** | `liquidDispense` 对不可替换格 return null → DefaultDispenseItemBehavior |
| `liquidInteraction` 开（含默认全开） | **弹射整桶** | 同上 |

**三处矛盾**：
1. `BucketItemMixin` javadoc："发射器装液体走同一个 emptyContents，一并覆盖"——只在全关时成立；
2. `LiquidInteractions` javadoc："关闭时等价原版桶（emptyContents+空桶）"——`cauldronAutomation`
   单开时对宿主并不等价（弹射 vs 灌入）；
3. 开关 `cauldronAutomation` 会改变发射器能否灌相——跨配置行为不一致；测试矩阵
   （AGENTS §5"发射器/漏斗"行）只测了锅，没有宿主用例，所以自检全绿也测不出来。

**修法建议**（二选一，建议 ①）：
1. `LiquidBucketBehavior` 在 `liquidDispense` 返回 null 且前方是 `PhaseCapable` 时，
   按原版桶语义调 `emptyContents`（熔岩走 `BucketItemMixin`、水走容器分支），
   即"液体物品像方块，但宿主是它的容器"；
2. 或把发射器灌相明确写成"仅全配置关闭时可用"，同步改 README/测试矩阵/两处 javadoc。

### 2.2 缺口 B（P2）：发射器空桶对含水宿主连方块一起收走

`EmptyBucketBehavior.emptyBucketCollect` 的判定链：

```java
if (PhaseHelper.isLava(frontState)) { ...收熔岩相、保宿主... }        // 熔岩相宿主：正确
FluidState fluid = level.getFluidState(front);
if (fluid.isSource() && (fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA))) {
    level.setBlock(front, Blocks.AIR.defaultBlockState(), ...);      // ← 含水宿主：连方块一起毁
```

含水（waterlogged=true）宿主的 `FluidState` 是水源，直接 `setBlock(AIR)`——宿主整个消失、
掉一个水桶。原版（配置全关时走 `BucketPickup.pickupBlock`）只收水、保宿主；本 mod 自己的
"空桶左键收取"也只对真实流体/熔岩相/锅 `removeBlock`，对含水宿主根本不进分支。
三处口径里只有发射器这条坏。

**修法建议**：`emptyBucketCollect` 在流体源判定之前加
`frontState.getBlock() instanceof BucketPickup` → `pickupBlock(null, level, front, frontState)`，
非空产物按 D15 入槽/掉落；空产物再落回原判定链。

### 2.3 缺口 C（P2）：熔岩相"重流"缺失——`scheduleLavaFlowTick` 是死代码

原版熔岩/水源能**持续重流**的机制在 1.21.11 guardian 实证：

```java
// LiquidBlock（1.21.11）
protected BlockState updateShape(...) {
    if (blockState.getFluidState().isSource() || neighbor...) {
        scheduledTickAccess.scheduleTick(blockPos, fluidType, delay);   // ← 邻居形状变化 → 重排 tick
    }
    ...
}
protected void neighborChanged(...) {
    if (this.shouldSpreadLiquid(level, pos, state)) {
        level.scheduleTick(pos, ...);                                    // ← 邻居变化 → 重排 tick
    }
}
```

mod 侧的对应设施**已经写好**：`PhaseHelper.scheduleLavaFlowTick`（javadoc："熔岩相邻居变化后
让内部熔岩继续按源流动"）。但全仓库检索它**零调用**——`BlockStateBaseMixin.liquidphase$onNeighbor`
处理了凝固/霜裂/红石/锅四件事，唯独没有接上这一件；也没有任何 `updateShape` 注入。

**后果**：`fillWithPhase` 只在充入那一刻排一次流体 tick → 熔岩流出一次；流出部分被移走
（挖掉/烧掉/被水流冲走）后，宿主内的熔岩源**不会再流出**。与 README
"内部熔岩是真实熔岩源：按宿主形状从没被固相挡住的方向流出"的持续性承诺不符。
（原版真实熔岩源在同场景下会重流。）

**影响面**：全部宿主 × 6 版本。**修法**：在 `BlockStateBaseMixin.liquidphase$onNeighbor` 里对
`PhaseHelper.isLava(self)` 调 `scheduleLavaFlowTick`（方法本身已就位，一行接线），
并在自检里补"流出部分被移走 → 源重流"断言。

### 2.4 需潜行的交互型宿主全集（设计内，文档不全）

0.1.13 删除 `BucketUseMixin` 后，灌入完全走原版桶路径。**原版交互消费右键的宿主**必须潜行
（原版 `ServerPlayerGameMode.useItemOn` 的 `isSecondaryUseActive()` 绕过块交互——6 个版本
guardian 源码逐一确认存在）。逐版本全集（按 ids 清单实测计数 × guardian 方法声明矩阵）：

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

**不在清单里的"看似会消费、实际 PASS"的宿主**（无需潜行，源码逐个确认）：
楼梯（`use` 转调 `baseState.use`，全部楼梯基座无交互 → PASS）、蜡烛（仅空手对点燃蜡烛 →
PASS）、营火（仅食物 → PASS）、栅栏（仅拴绳 → PASS）、发光地衣/幽匿脉络/树脂团
（`MultifaceBlock` 无 `useItemOn`）、conduit、sculk 传感器/尖啸体、脚手架。

**文档与自检缺口**：
- 自检 `checkBucketInteraction` 断言了"不潜行不灌"（活板门/光源），但**没有断言
  "潜行能灌"**——潜行绕过被未来改动破坏时测试不会红。建议补一条
  `player.setShiftKeyDown(true)` → `useItemOn` → 断言灌入的用例。

### 2.5 有意排除：barrier（1.21.1+）

1.21.1+ 的 `barrier` 覆写 `canPlaceLiquid`/`pickupBlock` 且语义为"创造模式限定接受流体"，
探测调用拿不到玩家身份时本来就拒绝。0.1.13 已判定**有意不接管**，本轮维持该结论
（1.19.4/1.20.1 的 barrier 已在 0.1.13 从宿主集合移除，无崩溃面）。

### 2.6 次级语义偏差（记录，单条影响面小）

| 偏差 | 细节 | 影响面 |
| --- | --- | --- |
| 活塞推宿主进线格不带红石相 | `FallingBlockIntoWireMixin` 只覆盖下落实体；活塞把宿主推进/拉进线格 = 线被 DESTROY、宿主落位不带相。原版一致性可接受（活塞放方块本来不继承线的功率），但与"宿主↔线两向生成"的意图有偏差，README/AGENTS 未写 | 全宿主 |
| 功能三开启时空桶收不了含水宿主的水 | 右键被 `BucketItemUseMixin` 拒绝（"收取只走左键挖掘"），左键 `isLiquidMineTarget` 只认真实流体/熔岩相/锅，含水宿主不在列表 → 功能三开启时含水宿主**没有任何收取路径**（关闭时右键 `BucketPickup` 正常）。属设计盲区，README 未写 | 全含水宿主 |
| 霜冰级联融化不跨宿主 | `PhaseHelper.frostedNeighborChanged` 只认 `neighborBlock == Blocks.FROSTED_ICE`；相邻**含霜冰宿主**被破坏时不触发"周围霜冰 < 2 → 融化"的级联。原版霜冰之间的级联不受影响 | 含霜冰宿主之间 |
| 含相树叶继续随机刻衰减 | `BlockStateBaseMixin.liquidphase$randomTick` 在 HEAD 注入且**不取消**，含冰/黑曜石相的树叶仍按原版距离规则衰减 → 衰减破坏时相照常实体化（掉冰）。行为自洽但未记录 | 树叶（10–14 个/版本） |

### 2.7 主链路逐条绿灯的证据

| 链路 | 入口 | 覆盖层级 | 结论 |
| --- | --- | --- | --- |
| 熔岩桶右键（非交互宿主） | `BucketItem.emptyContents` → `BucketItemMixin` HEAD | 全宿主（HEAD 接管不依赖类清单） | ✅ |
| 水桶右键 | 原版 `emptyContents` 容器分支 → `placeLiquid` | 全宿主（`PhaseCapable` 默认方法） | ✅ |
| 相邻流体流入 | `FlowingFluid.canHoldFluid` → `canPlaceLiquid(null,…)` → `spreadTo` → `placeLiquid` | 全宿主；遮蔽族（台阶/蜡烛/营火/干化恶魂）由 `SlabLiquidMixin`/`HostPlaceLiquidMixin` 接管熔岩分支 | ✅（独立重算确认遮蔽全集无第五族） |
| 放置吸收（宿主放进熔岩源格） | `PlacementMixin`（`getStateForPlacement` RETURN） | 声明类全在目标清单（第一轮审计"放置吸收未覆盖"为空 + 本轮独立重算一致；`Block.getStateForPlacement` 6 版本均由 Block 自己声明，注入合法） | ✅ |
| 相材料物品（冰/黑曜石）点宿主 | `BlockItemMixin.place` HEAD (a) | 全宿主；交互型宿主需潜行（§2.4） | ✅ |
| 红石粉点宿主 | `BlockItemMixin.place` HEAD (a0) | 全宿主；交互型宿主需潜行（§2.4） | ✅ |
| 宿主放进红石线格 | `RedstoneWireReplaceMixin`（状态级 `canBeReplaced`）+ `BlockItemMixin.placeBlock` | 状态级，覆写遮不住；脚手架自搜索落点有自检 | ✅ |
| 下落方块落进线格 | `FallingBlockIntoWireMixin` | 相容宿主特判；沙子等保持原版 | ✅（活塞路径除外，§2.6） |
| 空桶左键收取 | `ClientLiquidMineMixin` + `ServerPlayerGameModeMixin` → `PhaseTargeting.mineLiquid` | 全宿主（熔岩相） | ✅ |
| 潜行灌入 | `ServerPlayerGameMode.useItemOn` 的 `isSecondaryUseActive()` | 6 版本 guardian 均存在 | ✅（无自检断言，§2.4） |
| 液体物品右键对宿主 | `BucketItemUseMixin`（`use` HEAD，功能三开） | 全宿主（熔岩→`fillWithPhase`、水→容器 `placeLiquid`） | ✅ |

---

## 3. 填充相渲染：逐链路结论

**本轮未发现缺口。** 证据：

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
  固相完全看不见——0.1.2/0.1.11/0.1.13 三次修的都是这一类）；
- **没有"带 rotation 但几何铺满整格"的宿主模型**（这类会被 rotation 拒绝判成内缩 → 藏进宿主）；
- `barrier`/`light` 的模型**没有 elements**（无 parent 链）→ 落到内缩是**安全默认**：
  这两个方块原生 `RenderShape.INVISIBLE`，模型本来就不画，强制 MODEL 后只有覆盖层可见。

### 3.2 图层

- 1.19.4–1.21.11：`PhaseRenderLayerMixin` 注入 `getChunkRenderType`（SOLID→CUTOUT）
  与 `getMovingBlockRenderType`（下落方块/活塞推动）两条；TRANSLUCENT 宿主
  （玻璃板/染色玻璃板）保持 TRANSLUCENT。
- **26.3**：没有 `ItemBlockRenderTypes`，图层由**每个 quad 的材质透明度**决定
  （`BakedQuad.MaterialInfo.of` → `ChunkSectionLayer.byTransparency`，guardian d5822ca2a1 实证）。
  覆盖层/外壳的透明像素 quad 自动进 CUTOUT——不需要任何 mixin，
  也就不存在"漏接某族方块"的问题。

### 3.3 RenderShape / 方块实体宿主

`PhaseRenderShapeMixin` 挂在状态级 `getRenderShape` RETURN（任何块级覆写都遮不住）；
自检 `liquidphase$assertNonModelHostsRenderPhases` 全量扫描全部非 MODEL 原生宿主
（逐个断言"带相 → MODEL、不带相 → 原值"，并断言扫描命中数 > 0）。

### 3.4 已知表现（不是缺口，但容易被当成"渲染不出"）

- **整格宿主的熔岩相没有覆盖层**（设计：熔岩由流体渲染画出来）。树叶/铜格栅有贴图孔洞，
  熔岩透孔可见；**红树根（mangrove_roots）的外皮近乎封闭**，熔岩相在视觉上基本不可见，
  只能靠 15 级光照/实体接触感知。
- 混合模型宿主（台阶）`type=double` 走整格替换、`type=bottom/top` 走内缩——回归断言
  `checkSlabVariantRendering` 在（0.1.13）。
- 含相状态的**遮挡**仍按宿主原形状（刻意决策）：极近视角有 1px 级接缝——
  与 AGENTS §3.2"遮挡/透光仍按宿主原形状"一致。

### 3.6 26.3 追加发现（用户实测定位，0.1.14 修复）

用户实测（栅栏/树叶）：充入红石/冰相不可见、熔岩相正常。26.3 实机探针复现：
**全部 505 宿主的含相状态烘焙模型与 phase=none 完全相同**（1010 项 BROKEN），
日志有 639,600 条 `Unable to bake model` + `IllegalArgumentException: Cannot compute
translucency out of bounds: [-1, -1, 17, 17] in 16x16 image`。

根因链（guardian d5822ca2a1 逐层实证）：
1. 26.3 的 `FaceBakery.bakeQuad` 对每个 quad 调 `computeMaterialTransparency`：
   显式 `uv` 缺失时按**元素包围盒**取 UV（`defaultFaceUV`），传给
   `SpriteContents.computeTransparency(u0,v0,u1,v1)`；
2. `SpriteContents` 侧只有 UV 恰为整图 (0,0,1,1) 时走缓存快速路径，否则
   `floor/ceil` 展开到像素并调 `NativeImage.computeTransparency(x0,y0,x1,y1)`，
   `x0<0 || x1>width` 直接抛 `IllegalArgumentException`；
3. 红石外壳模型为防 z-fighting 采用 **-0.01..16.01 外扩几何**，自动 UV 落到
   [-1,17] → 抛异常；26.3 上**任一 state 的 quad 失败使整个 blockstate bake 失败**，
   该宿主全部状态（含 phase=none）一起回退 → 相覆盖层与红石外壳全部不渲染，
   宿主本体照旧、熔岩相照旧（流体渲染不依赖 blockstate）；
4. 1.19.4–1.21.11 没有这条按 UV 计算透明度的路径，不受影响。

**修复（0.1.14）**：`tools/gen_redstone_assets.py` 给外壳模型每个面补显式
`uv: [0,0,16,16]`——命中整图快速路径，几何保持外扩，其余版本视觉不变。
修复后 26.3 实机探针 0 BROKEN、bake 警告 0 条；1.21.11/1.20.1/1.19.4/1.21.10
回归探针 0 BROKEN。服务端侧核对：用户场景（栅栏/树叶放进线格、点冰块吸收、
直接点红石粉/冰、放进熔岩源格）在 6 个版本全部正确写相——"放置失败"的观感
来自渲染缺失（以及放进**无电**线格得到 redstone_0、0 档外壳按设计近乎熄灭）。

**教训**：本审计的静态扫描（几何整格判定/UV 在 jar 内存在/验证器）都测不出
"几何出 0..16 边界 + 新版本按 UV 算透明度"这类**版本新增的烘焙期约束**；
渲染探针必须在**每个 API 时代**各跑一遍。

### 3.5 待实机取证

代码与资源层面绿灯不等于视觉验收通过。AGENTS §7.4 的手测清单仍然待做
（固定视角目视含相剔除接缝、0.1.12 补全宿主的红石相外观、双台阶固相外观、
0/15 档外壳色差等）。本轮没有运行真实客户端，这一条只能由 `runClient` 取证完成。

---

## 4. 其余功能逐条核对

| 功能 | 挂载点 | 层级 | 结论 |
| --- | --- | --- | --- |
| 熔岩相流体态/发光/火蔓延/凝固 | `BlockStateBaseMixin`（getFluidState/getLightEmission/randomTick/handleNeighborChanged） | 状态级 | 全宿主 |
| **熔岩相重流** | `PhaseHelper.scheduleLavaFlowTick` | **无调用点（死代码）** | **缺口 C（§2.3）** |
| 破坏保留/红石相实体化 | 状态级 `onRemove`/`affectNeighborsAfterRemoval`；offsetType guard 已删（0.1.13） | 状态级 | 全宿主（自检全量扫描，含 offset 宿主逐个） |
| 固相整格碰撞 | `ShapeFallbackMixin` 两参（缓存路径）+三参（实体路径）RETURN | 状态级 | 全宿主（0.1.13 修，自检全量扫描） |
| 形状表兜底 | `ShapeFallbackMixin`（null→phase=none 兄弟）+ `BlockBehaviourMixin` 块级兜底 + 类级形状 mixin（CrossCollision/Wall/BigDripleaf/Multiface/Fence 遮挡） | 状态级+块级+类级 | 全宿主 |
| 冰相随机刻融化/霜裂推进 | `BlockStateBaseMixin`（isRandomlyTicking/randomTick/tick/onPlace） | 状态级 | 全宿主 |
| 霜冰相邻融化 | `frostedNeighborChanged` | 状态级 | 原版霜冰之间 ✅；跨含霜冰宿主不联动（§2.6） |
| 冻结（低温降水冻结水相宿主） | `ServerLevelMixin` redirect `Biome.shouldFreeze`（tickChunk/tickPrecipitation 按版本） | 全局 | 全水相宿主 |
| 铜氧化随机刻（含相铜） | 原版 `WeatheringCopperBlock.randomTick` 的 `setValue`/`cycle` 保留其余属性 → phase 不丢；mod 的冰融随机刻与氧化并行 | 状态级 | ✅（行为记录） |
| 树叶衰减随机刻（含相树叶） | mod 的 randomTick 注入不取消原版逻辑 | 状态级 | 衰减破坏时相实体化（§2.6 行为记录） |
| 红石相信号/重算/挖掘 | `RedstoneSignalMixin`（状态级 getSignal/getDirectSignal/isSignalSource）+ `RedstoneBlocks.exposed`（按 phase=none 兄弟形状判暴露面） | 状态级 | 全宿主 |
| 炼药锅转化/混凝土/细雪锅 | `CauldronBehaviors`/`CauldronBehaviorRegistration`/`ConcreteMappings`（邻居更新 + 行为表注册，26.3 走 DispatcherAccessor） | 块/注册表 | 只涉及锅，不涉及宿主 |
| 发射器/漏斗 | `LiquidInteractions`/`CauldronHopperTransfer` | — | 锚锅路径 ✅；**宿主路径缺口 A/B（§2.1/§2.2）** |
| 双端同步 | 所有会改世界的客户端路径都有 `PhaseSyncState.canMutateWorld` 门控（BlockItem/Placement/BucketItem/BucketItemUse/RedstoneWireReplace/FallingBlockIntoWire/ClientLiquidMine/EntityPick） | — | 8 处齐全；握手 JOIN 发送/断开复位 |
| 功能三合成残留 | `ItemMixin`（getCraftingRemainder，26.3 改名 getCraftingRemainder 分支） | 类级 | 液体物品不返空桶 |

---

## 5. 文档核对

**README 功能一节残留 4 条 0.1.12 时代的过时 ⚠️**（与同文件后部已更新的"兼容性注意"
自相矛盾——0.1.13 改了兼容性注意，没回头改功能一节）：

| 位置 | 过时表述 | 实际状态 |
| --- | --- | --- |
| "含熔岩"条目 | "'被相邻熔岩灌入'对台阶/蜡烛/营火/干化恶魂不成立" | 0.1.13 已由 `SlabLiquidMixin`/`HostPlaceLiquidMixin` 接管，自检 `checkAdjacentLavaFilling` 全量在 |
| "破坏保留"条目 | "带 offsetType 的 4–5 个宿主例外：破坏时不留下相" | 0.1.13 已删 guard，自检 `checkBreakRetainEverywhere` 全量在 |
| "碰撞"条目 | "实测只有一部分宿主成立……固相碰撞仍是宿主形状" | 0.1.13 已移到状态级，自检 `checkSolidPhaseCollision` 全量在 |
| "整格宿主的相"条目 | "判定是方块级的……双台阶含固相看不见" | 0.1.13 已改到变体粒度，自检 `checkSlabVariantRendering` 在 |

建议把这四条 ⚠️ 改为指向 v1 审计 §6 的"已修复"记录（或在 0.1.14 的 README 清理里统一处理）。
其余承诺（熔岩桶右键交互优先 + 潜行、整格碰撞全宿主、外壳全宿主、双端要求、
26.x 实验性红石评估器不保证）与实现一致。

---

## 6. 静态校验与自检实跑结果（全部绿灯）

| 校验 | 结果 |
| --- | --- |
| `audit_host_coverage.py`（A/B/C/S 差集 + 遮蔽/放置吸收清单） | 6 版本 A-B/B-C/C-B/C-S 全零；遮蔽只剩有意保留的 barrier；"放置吸收未覆盖"为空 |
| `validate_blockstates.py` | 6 版本 0 错误 |
| `check_mixin_targets.py` | 6 版本 OK |
| `verify_jars.py`（dist/ 六个 0.1.13 jar） | 全 OK（blockstate 数、坏条件 0、关键 mixin 在包、外壳/清单一致） |
| **服务端自检（本轮实跑）** | 1.19.4=147/147、1.20.1=147/147、1.21.1=147/147、1.21.10=148/148、1.21.11=147/147、26.3=147/147；注入数 308/310/337/410/410/505 与清单一致 |
| **mixin 注入目标存在性（新增核对）** | 全部 mixin 的 @Inject/@Redirect/@ModifyVariable/@ModifyExpressionValue/@Invoker 方法目标逐版本在 guardian 有声明（含 `BlockBehaviour$Cache`/`BlockStateBase` 内部类、26.3 Dispatcher）；`Block.getStateForPlacement` 6 版本均由 Block 声明，`PlacementMixin` 的 Block.class 目标合法 |
| 潜行绕过存在性 | 6 版本 `ServerPlayerGameMode.useItemOn` 均含 `isSecondaryUseActive()` 守卫 |
| 26.3 分层机制 | `BakedQuad.MaterialInfo.of` 逐 quad 透明度 → CUTOUT（实证） |

---

## 7. 对"已知的两个症状"的直答

**"部分宿主方块仍然无法正常处理填充相放置"**——当前代码里：
- 手持熔岩桶/水桶右键（含潜行）、相邻流体流入、放置吸收、左键收取、宿主落线格
  这些**主链路对全部宿主成立**（§2.7）；
- 真正失效的是**发射器两条路径**（§2.1/§2.2），默认配置即触发，影响全部宿主；
  以及**熔岩相的重流缺失**（§2.3，"流出一次之后不再流"，容易被感知为"灌进去了但不正常"）；
- 非发射器场景下"灌不进"的宿主应当全部落在 §2.4 的交互型全集里（没潜行）；
  逐版本精确清单见那张表。若实测到的"灌不进"宿主不在该表内，请给出
  版本 + 具体方块 + 手势，那将指向本轮静态分析未覆盖的新链路。

**"部分宿主仍然无法正常渲染填充相"**——静态与资源层面**未复现出缺口**：
整格判定零误判（§3.1）、26.3 自动分层（§3.2）、隐形宿主强制 MODEL（§3.3）、
双台阶变体拆分有回归断言。两种可能：
1. 症状来自 0.1.13 之前的包（双台阶是 0.1.13 刚修的最后一例，发布前务必按
   AGENTS §6 核对 jar 里真的是新代码——曾发生"装了新 jar 但 bug 依旧"的教训）；
2. 症状在真实客户端的视觉层（剔除接缝/半透明排序/红树根熔岩相不可见这类 §3.4 表现），
   需要按 §3.5 跑 `runClient` 取证。请提供具体方块与版本，以便归类。

---

## 8. 复现命令

```bash
# 第一轮四集合差集 + 遮蔽/放置吸收清单（应保持 A-B/B-C/C-B/C-S 全零）
python tools/audit_host_coverage.py <guardian 仓库路径> .

# 仓库自带静态校验
python tools/validate_blockstates.py
python tools/check_mixin_targets.py
python tools/verify_jars.py

# 服务端自检（每版本一条；1.21.10 为 148 项，其余 147 项）
LIQUIDPHASE_SELFTEST=true LIQUIDPHASE_SELFTEST_STOP=true ./gradlew :<版本>:runServer

# 本轮新增的独立扫描脚本（工作区 _audit_tmp/，尚未收编进 tools/）：
#   scan_shadow.py         独立重算 6 版本方法声明矩阵（canPlaceLiquid/placeLiquid/pickupBlock/
#                          use/useItemOn/useWithoutItem/getStateForPlacement/canBeReplaced × 宿主闭包）
#   scan_render.py         生成 blockstate 逐 id 覆盖层/外壳完整性 + full/inset/mixed 分类
#   scan_inset_full.py     反向验证：内缩宿主里模型其实是整格的（应为 0）
#   scan_rot.py            反向验证：带 rotation 的整格模型 + 无 elements 宿主清单
#   check_mixin_decls.py   全部 mixin 注入方法目标逐版本存在性核对（应无输出问题）
```
