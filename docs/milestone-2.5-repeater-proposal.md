# 规划 §3.5（中继器/比较器/侦测器相）的轻量实现方案

> 本文是 §3.5 的**设计提案**，尚未实现（本版只交付 §3.4 红石相）。
> 目标：在不复制 3×~300 个组合方块类、不做逐像素烘焙的前提下，拿到 §3.5 的核心玩法
> （元件按"露出红石相的面"输入/输出、元件贴在宿主表面）。
>
> ⚠️ **表示法已于修订时变更（2026-11）**：原稿"追加 `repeater_phase` / `comparator_phase` /
> `observer_phase` 三个独立属性"会连乘到 **36.7 亿状态**（`int` 状态 id 溢出），**不可行**。
> 现在改为**把全部元件参数并进 `phase` 一个属性的取值域**（24 → 84 值，互斥），
> 代价与替代方案见 [`state-budget-analysis.md`](./state-budget-analysis.md)。
> 下文若仍出现三个属性的写法，按"并集取值"理解，§2.1 已给出对照。
>
> **本版修订要点（相对上一版）**：
> 1. **取消 `HOST_SHAPE` 属性**。杆/红条的高度不存状态，改为**渲染期逐列实时查询宿主判定箱**。
> 2. 元件与宿主**同格共存**：宿主方块原样保留，元件状态是宿主 `phase` 属性的一个取值
>    （与含水方块的 `phase` 完全同一机制），**不用方块实体、不新增方块**。
> 3. 两根杆**各自独立**取高，同一根杆内部也允许逐列落差。
> 4. 宿主方块或方块状态一变，元件**立即重画**（走原版自己的区块脏标记路径）。

## 1. 为什么不用规划里的原方案

规划要求"中继器/比较器每一平方像素搭在固相表面最高处"，即**烘焙期自定义网格**：读取宿主模型的顶面高度图，
把杆/红条逐像素贴上去。实测（§3.4 生成器）每个版本有 386 个可含相实例、每族组合方块状态 ~13.6 万——
若对 3 个元件再各生成一族，状态与类数量呈三倍增长，且逐像素网格要为每个版本维护一套烘焙管线。

烘焙期网格还有个更硬的障碍：**烘焙结果不随世界状态变化**。宿主顶面高度是"每个模型一份"的量，
同一族里不同坐标处的宿主形状本来就不同，缓存粒度对不上；宿主一变就得重烘整块模型。
放到渲染期实时算反而绕开了这个不一致（见 §2.5）。

## 2. 轻量方案：**元件相属性 + 渲染期宿主形状采样**

### 2.1 表示

不新增方块、**也不新增属性**：把元件参数并进现有的 `phase` 一个属性（`PhaseStateRefresher` +
`StateDefinitionDuck` 同一套注入机制；1.19.4/1.20.1 由 Statement 完成同等工作，
两端追加的 state id 按注册表顺序一致）。一格只表达一个相，取值天然互斥：

| `phase` 取值段 | 编码 | 取值数 |
| --- | --- | --- |
| `none` | 默认值 | 1 |
| 固相 / 熔岩 | `lava` + `obsidian` + `ice` + `frosted_0..3` | 7 |
| 红石相（§3.4，已实现） | `redstone_0..15`，功率编码在取值里 | 16 |
| 中继器相 | `facing` 4 × `delay` 4 × `powered` 2 | 32 |
| 比较器相 | `facing` 4 × `mode` 2 × `powered` 2 | 16 |
| 侦测器相 | `facing` 6 × `powered` 2 | 12 |
| **合计** | | **84** |

**没有 `HOST_SHAPE`。** 宿主形状不入状态：宿主方块就在同一格里，渲染期直接问它自己的形状表（§2.5），
所以一个档位枚举都不需要。`facing`/`mode` 也没有单独属性：它们只对所属元件有意义，
放进取值域后"中继器延迟"不会在 `observer` 相里变成废值（那是拆分方案的语义污染）。

> 若要把中继器的 `locked`（1.21.1+ 的锁存视觉）也做进来，`repeater` 段变 64，合计 **116**。
> 只做逻辑锁定、不显示锁形也是可接受的降级，但要写进 README 已知限制。

**状态数怎么算——必须说清代价。** BlockState 属性是**相乘**的，而且并集方案已经乘在现有
`phase` 的代价里（`V` 从 24 换成 84，不是再乘一个系数）：

```
现状（§3.4 红石相）    = (N − B) + B × 24
并集 84 值（本方案）   = (N − B) + B × 84
```

按 1.21.11 实测反解口径（`N = 29,671`、全部 410 个相容宿主的 `B = 20,976`、779 B/状态）：

| 口径 | 总状态 | 堆 | 注入耗时（相对原版） |
| --- | --- | --- | --- |
| 原版（`phaseEnabled=false`） | 29,671 | 148 MiB | — |
| 现状 `×24` | 512,119 | 506 MiB | ≈21 s |
| 并集 `×84`，**全宿主统一** | 1,770,679 | ≈1.4 GiB | ≈76 s |
| 并集 `×84`，**按方块裁剪**（楼梯+台阶 5,012 用 84 值，其余仍 24 值） | 812,839 | **730 MiB** | **≈34 s** |

> 裁剪行的语义：只有元件宿主注入 84 个取值，其余宿主保持 `phase` 的 24 个固/液/红石取值。
> 这是推荐组合——**总状态 51.2 万 → 81.3 万（+59%）、堆 506 → 730 MiB（+44%）**，
> 与"只做红石相"同一量级。代价是把 `PhaseHelper.PHASE` 单例改成按方块查属性
> （属性名仍叫 `phase`，只是取值子集不同；详见 `state-budget-analysis.md` §3.4）。
> 宿主收窄不是妥协：元件要贴在上表面、需要足迹内有空腔列，
> 而栅栏/墙/栏杆/玻璃板/锁链的顶面是破碎顶点云，树叶顶面被自身模型占满。

#### 被否决的原稿：三个独立属性

原稿给每个元件追加一个独立属性（`repeater_phase` 33 / `comparator_phase` 17 / `observer_phase` 13），
这在语义上允许三者共存（一格两个相），与"一格只有一个相"矛盾，状态数也失控：

| 口径 | 新增状态 | 堆 | 注入耗时 |
| --- | --- | --- | --- |
| `×24×33`（只做中继器） | 16,592,016 | ≈12.2 GiB | ≈12 min |
| `×24×33×17×13`（三个属性） | **36.7 亿** | — | **越过 `int` 状态 id，不可用** |

> ⚠️ 原稿的算术有两处硬错，别照它开工：
> ① 它把 `63 = 33+17+13` 当成"三元全开"的倍数，但那是"三者合并进**同一个**互斥属性"的加法，
>    三个独立属性的组合数必须**连乘**（`33×17×13 = 7,293`）；
> ② 它把"现有 `phase` 开销"当成 `×8`，而 `phase` 自 0.1.5 起是 **24 值**。
> 因此"仅中继器 ≈ 现有开销 4.1 倍"这个结论是错的：正确倍数是 **33 倍**（相对新增状态）。
> 完整推导与其余方案（类别进状态 + 稀疏旁路表 / 红石相语义降档）见
> [`state-budget-analysis.md`](./state-budget-analysis.md)。

对几个典型宿主（原版状态数为准，`×84` 为并集口径）：

| 宿主 | 原状态数 | 现有 `phase`(×24) | 提案（三个独立属性） | **并集(×84)** |
| --- | --- | --- | --- | --- |
| 楼梯 `StairBlock`（`FACING`4×`HALF`2×`SHAPE`5×`WATERLOGGED`2） | 80 | 1,920 | 80 × 174,096 = 13,927,680 | **6,720** |
| 台阶 `SlabBlock`（`TYPE`3×`WATERLOGGED`2） | 6 | 144 | 1,044,576 | **504** |
| 栅栏 `FenceBlock`（`NORTH/EAST/SOUTH/WEST`2⁴×`WATERLOGGED`2） | 32 | 768 | 5,571,072 | **2,688** |
| 墙 `WallBlock`（`UP`2×四面 `WallSide`3⁴×`WATERLOGGED`2） | 324 | 7,776 | 56,407,104 | **27,216** |

> 墙是状态数大户（四面各 3 档 × 上下），26 个墙方块的基础状态就占全部宿主的 40%。
> **强烈建议墙不注入元件取值**（只保留固相 + 红石相）：要么跳过 `WallBlock`，
> 要么只注入 `UP=false` 的子集，或者干脆让元件相只支持"平顶宿主"
> （台阶/楼梯/完整方块/泥土类）。墙的横向落差本来就靠 §2.5 的逐列采样表达，
> 不吃 `HOST_SHAPE`，但状态代价是另一回事。

> 省状态的三个抓手：
> ① **按元件单独开关**（配置项 `repeaterPhaseEnabled` / `comparatorPhaseEnabled` / `observerPhaseEnabled`）：
> 并集编码下每关一个就少 32/16/12 个取值（不是乘一个系数）；
> ② **按宿主收窄**：只给"顶面存在 `h < 1` 空腔列"的宿主注入元件取值
> （楼梯/台阶/完整方块；栅栏/墙/栏杆/玻璃板的顶面是破碎顶点云，本来就不该放元件）；
> ③ 若仍不够，可给不同方块建**同名但取值子集不同**的属性实例
> （`EnumProperty.create("phase", Phase.class, List<Phase>)`；代价见 `state-budget-analysis.md` §3.4）。
>
> 按推荐的"并集 84 值 + 按方块裁剪（楼梯 58 + 台阶 62 = 5,012 基础状态）"：
> 新增 783,168 状态 / 堆 730 MiB / 注入 ≈34 s（相对原版；比现状多 ≈13 s），
> 相对今天的 506 MiB 是 +44%，**比三个独立属性的 36.7 亿小三个数量级**。

### 2.2 位置：同格共存

元件相**不占额外的格、也不替换宿主**：

- 宿主方块原样留在本格，元件状态是**宿主自己那个 BlockState** 上的属性
  → `state.getCollisionShape(level, pos)` 拿到的天然就是宿主形状，这就是 §2.5 的采样基础。
- 不需要方块实体、不需要旁路表、不需要存"宿主状态"字段。
- 破坏元件 = 把对应属性置回 `NONE`，宿主不受影响（不存在"换回宿主状态"这一步，因为宿主一直在）。
  **注意：**若像 §3.4 那样"破坏元件也要掉宿主战利品"，那是原版破坏方块本身的行为，不用额外接线。
- 与 §3.4 红石相同属**一个** `phase` 属性（不是两套表示）：红石相是 `redstone_0..15`，
  元件相是 `repeater_*` / `comparator_*` / `observer_*`，**天然互斥**，不需要额外的清理逻辑。
  同一个 `state` 既是宿主又是相，`getCollisionShape` 拿到的就是宿主形状（§2.5 的采样基础）。

**放置前提（重要）**：元件要占用宿主之上那部分空腔，所以顶面必须留得出空间。
宿主在该列顶面高度为 `h`，元件几何从 `h` 向上长；`h ≥ 1` 的列没有空腔，该列不画（§2.5）。
因此**顶面被完全盖住的宿主（如上半砖、倒挂台阶）不能放元件**——放置时用
"足迹内是否存在 `h < 1` 的列"做校验，不通过就拒绝（对齐原版中继器"必须有可依附表面"的直觉）。

### 2.3 信号语义

- **输入/输出面判定**沿用 §3.4 的 `RedstoneBlocks.exposed(state, direction)`
  —— 它也是用**碰撞形状**判定该面是否被固相覆盖，与本方案的采样口径一致（§2.6）。
- 中继器相：`getSignal(dir)` 仅当 `dir == FACING` 且该面露出时返回 15；
  输入从背面露出面读 `level.getSignal`（原版 `DiodeBlock.getInputSignal` 的 `FACING` 语义照抄）。
- 比较器相：`getInputSignal` 照抄原版 `ComparatorBlock.getInputSignal`
  （`hasAnalogOutputSignal` → 容器/信标信号；`isRedstoneConductor` 时隔一格再读 + 物品展示框），
  前置门控换成"背面 `FACING` 面是否露出"；`mode`（compare/subtract）的取值段直接用原版
  `shouldTurnOn` / `calculateOutputSignal` 的算式。
- 侦测器相：`FACING` 方向邻居变化 → 计划刻 2 tick 翻转 `POWERED`
  （对齐原版 `ObserverBlock.updateShape` 的 `!POWERED` 分支）；输出口 `FACING.getOpposite()` 露出时输出 15。

**实现在哪（决定要不要 41×3 个类）**：**不要**给每个宿主覆写方法。信号逻辑统一注入到
**状态级 mixin**（沿用 `mixin/BlockStateBaseMixin.java` 这套锚点，新增一个
`ElementStateMixin` 更清晰），按 `PhaseHelper.getPhase(state)` 的取值段分派：

| 目标方法 | 注意 |
| --- | --- |
| `BlockStateBase.getSignal(BlockGetter, BlockPos, Direction)` | 状态级，全覆盖（已有 `RedstoneSignalMixin` 可扩展） |
| `BlockStateBase.getDirectSignal(...)` | 比较器强信号 |
| `BlockStateBase.isSignalSource()` | 块级/状态级签名随版本变，按 §3.4 已有的版本守卫处理 |
| `RedStoneWireBlock.shouldConnectTo` | 让线连到有元件相且该面露出的格（同 §3.4 注入点） |
| `BlockStateBase.tick` / `handleNeighborChanged` | 中继器延迟与侦测器翻转（已有 `BlockStateBaseMixin` 锚点） |

这样一套代码覆盖全部宿主，与 §3.4 的"逐宿主生成类"路线彻底分开。

### 2.4 与 §3.4 红石相的互操作

- 红石线的"元件连接"判定复用 `RedStoneWireBlock.shouldConnectTo` 注入：
  把带元件相的状态当信号源，但只有朝向该格的那一面露出时才算连接。
- 放置：宿主方块上用中继器/比较器/侦测器物品右键 → `phase` 写成对应的元件取值，
  朝向取玩家朝向（中继器/比较器水平四向，侦测器按 `getNearestLookingDirection().getOpposite()` 归一）。
- **互斥由表示法自带**：一个 `phase` 只能是一个值，写入元件相必然覆盖 `redstone_*`/固相，
  不需要"把其余属性清回 `NONE`"这类搬运逻辑（这是并集编码相对三属性的额外好处）。

### 2.5 渲染：渲染期逐列采样宿主形状（本次核心）

元件不预烘焙，几何在区块/模型构建期按**当前世界状态**生成：

```java
// 1) 取宿主自身形状（同格共存，state 就是宿主状态，只是多了元件属性）
VoxelShape host = state.getCollisionShape(level, pos);      // 口径见 §2.6

// 2) 对每个部件的每个水平列逐列取顶面高度
for (int cx = 0; cx < 16; cx++) {
    for (int cz = 0; cz < 16; cz++) {
        double h = host.max(Direction.Axis.Y, (cx + 0.5) / 16.0, (cz + 0.5) / 16.0);
        // 空列返回 NEGATIVE_INFINITY；
        // h >= 1 → 该列没有空腔，不画（见 §2.2 放置前提）
    }
}
```

- `VoxelShape.max(Direction.Axis axis, double x, double z)` 是**原版官方"某列固相最高点"问法**，
  在 **1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3 六个版本全部存在**，
  原版自用在 `WaterDropParticle`（雨滴落点）、`WeatherEffectRenderer`、`ComposterBlock`（堆肥高度）。
  **不要自己遍历 `toAabbs()` 求交**——那是 O(n) 且要处理重叠盒。
- 内部实现是一次二分查下标 + 一次 `isFull` 判定（`DiscreteVoxelShape.lastFull`），
  且 `BlockStateBase.Cache.collisionShape` 在构造期已算成常量引用，256 次查询是纯内存操作。
- **两根杆各自独立**：中继器两杆足迹是 x∈[7,9]、z∈[6,8] 与 z∈[2,4]（原版 `repeater_1tick.json`），
  比较器两杆是 x∈[4,6] 与 x∈[10,12]、z∈[11,13]（`comparator_on.json`）。
  按列采样天然让两杆一高一低，同杆内部也允许逐列落差——正是规划要的
  "同一杆/红条内部允许落差"。
- **底板**（`0,0,0`→`16,2,16` 那层）不参与逐列采样，按固定 2px 从宿主本列顶面起算。
- **贴图与 UV** 全部沿用原版：`block/repeater`、`block/repeater_on`、`block/comparator*`、
  `block/observer*`，按列给 `(u,v)` 区间即可，**不需要重画贴图**。

**几何产出路径**（两代 FRAPI，签名均已核对）：

| 版本 | API |
| --- | --- |
| 1.19.4 / 1.20.1 / 1.21.1 | `FabricBakedModel#emitBlockQuads(BlockAndTintGetter, BlockState, BlockPos, Supplier<RandomSource>, RenderContext)` |
| 1.21.10 / 1.21.11 / 26.3 | `FabricBlockStateModel#emitQuads(QuadEmitter, BlockAndTintGetter, BlockPos, BlockState, RandomSource, Predicate<Direction>)` |

- 后一代还应实现 `createGeometryKey(blockView, pos, state, random)`，返回
  `(宿主形状引用, phase 取值, 逐列高度数组)` 作缓存键——宿主形状一变键就变，缓存自动失效。
- 26.3 的 `BlockStateModel#collectParts(random, output)` **拿不到世界**，
  所以必须走 FRAPI 的 `emitQuads` 重载，不能用原生 `collectParts`。

**"立即重画"怎么保证**：元件相和宿主状态是**同一个 BlockState**，
所以宿主变化（楼梯形状换向、栅栏连上另一段、台阶上半/下半切换）本身就会
`level.setBlock(..., UPDATE_ALL)` → 客户端标脏该 section → 重画，与元件变化走同一条路径，天然同步。
唯一要补的边角：**只改邻居、本格 BlockState 没变**的路径（例如栅栏因邻居栅栏变化而改 `north/south` 属性
——这其实也改本格状态，所以已覆盖；但墙体 `UP` 之类由邻居高度决定的，需确认宿主自身 `updateShape`
确实产生了新状态，否则）在宿主 `updateShape` 注入里补一次
`level.sendBlockUpdated(pos, old, new, Block.UPDATE_CLIENT)`。

> 说明：`setValue` 只改一个属性、其余原样带过，所以宿主的 `updateShape` 返回新状态时
> **`phase` 取值自动保留**，不需要额外搬运逻辑。

### 2.6 用 `getShape` 还是 `getCollisionShape`

两者在本方案里几乎总相等，**建议 `getCollisionShape`**：

- 语义是"玩家站得上去的面"，与 §3.4 `exposed()` 用的是同一个形状表，两处判定不会打架。
- 差异出现在栅栏/墙（`CrossCollisionBlock` 里 `shapes` 与 `collisionShapes` 是两张表）：
  用碰撞形状元件会略宽地贴上去，视觉上比"浮在碰撞面之上"更自然。
- 日后若要升级到"逐像素贴模型"，把这一处换成 `getShape` 即可，其余逻辑（信号、放置、互斥）全不动
  ——渲染与语义解耦。

## 3. 交付顺序建议

1. **先把 `phase` 扩到 84 值，只实现取值与持久化**（`Phase` 枚举 + `FILLED` + 生成器 + 自检），
   确认总状态数、内存、启动时间落在 `state-budget-analysis.md` 的预算内；
2. **中继器相**（最小闭环）：`repeater` 取值段 + 状态级信号 mixin + 逐列采样渲染；
3. **比较器**：`comparator` 取值段 + 容器读取（`hasAnalogOutputSignal`），几何多两根红条 + 前端火把；
4. **侦测器**：`observer` 取值段 + 计划刻翻转，渲染位置按原稿**固定**、不做逐列采样；
5. 每步在 `AGENTS.md` 测试矩阵加一行，6 版本各跑一次服务端冒烟 + 客户端目视
   （重点：楼梯上的杆是否贴合、两根杆是否一高一低、换台阶后是否立即重画、总状态数是否超预算）。

> ⚠️ 第 1 步是**强制前置**：取值域一旦定下，后面每一步都只是往同一个枚举里加段，
> 不会再出现"再加一个属性"的诱惑。若第 1 步实测超预算，退回方案 A/C（稀疏旁路表），
> 而不是缩小取值域后再加属性。

## 4. 明确不做（与规划一致）

- 活塞推动元件相（§8.3）。
- 26.x 实验性红石评估器下的一致性保证（§8.2）。
- 元件相与红石相在同一格内叠加（一格只表达一个相）。
- 烘焙期逐像素网格（本方案以渲染期逐列采样替代；日后要换只动 §2.6）。

## 5. 已核对的源码锚点（guardian）

| 结论 | 依据 |
| --- | --- |
| `VoxelShape.max(Axis, double, double)` 六版本都在 | 1.19.4 `37641fc907:.../shapes/VoxelShape.java:127`、1.20.1 `41c32f0060:127`、1.21.1 `d47305d7cf:140`、1.21.10 `5d7d486906:149`、1.21.11 `192e7132b4:150`、26.3 `d5822ca2a1:150` |
| 逐列顶面是官方用法 | `WaterDropParticle`（`.max(Y, x-bx, z-bz)`）、`WeatherEffectRenderer`（`.max(Y, d, e)`）、`ComposterBlock`（`.max(Y, 0.5, 0.5)`） |
| 空列返回 -∞ | `lastFull(...)<=0 → NEGATIVE_INFINITY`（`VoxelShape.max` 实现） |
| 碰撞形状构造期常量 | `BlockBehaviour$BlockStateBase$Cache.collisionShape`（1.21.11 `BlockBehaviour.java:931-938`）；两参 `getCollisionShape(level,pos)` 返回缓存，三参直达方块 |
| 栅栏/墙视觉形状≠碰撞形状 | `CrossCollisionBlock.getShape` 用 `shapes`、`getCollisionShape` 用 `collisionShapes` |
| 两代 BER/模型 API 分界 | `BlockEntityRenderer<T>`（≤1.21.1：`render(T, float, PoseStack, MultiBufferSource, int, int)`）→ `BlockEntityRenderer<T,S extends BlockEntityRenderState>`（1.21.10+：`createRenderState`/`extractRenderState`/`submit`） |
| FRAPI 两代模型接口 | ≤1.21.1 `FabricBakedModel#emitBlockQuads(...)`；1.21.10+ `FabricBlockStateModel#emitQuads(QuadEmitter, BlockAndTintGetter, BlockPos, BlockState, RandomSource, Predicate)` |
| 26.3 原生模型拿不到世界 | `dispatch/BlockStateModel#collectParts(RandomSource, List)` 无世界参数 → 必须走 FRAPI |
| 中继器两杆足迹 | `assets/minecraft/models/block/repeater_1tick.json`：`[7,2,6]-[9,7,8]`、`[7,2,2]-[9,7,4]` |
| 比较器两杆足迹 | `.../comparator_on.json`：`[4,2,11]-[6,7,13]`、`[10,2,11]-[12,7,13]` |
| 元件信号原版算式 | `DiodeBlock.getInputSignal`/`getSignal`/`checkTickOnNeighbor`；`ComparatorBlock.getInputSignal`/`calculateOutputSignal`；`ObserverBlock.updateShape`/`tick` |
