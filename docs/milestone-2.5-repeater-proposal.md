# 规划 §3.5（中继器/比较器/侦测器相）的轻量实现方案

> 本文是 §3.5 的**设计提案**，尚未实现（本版只交付 §3.4 红石相）。
> 目标：在不复制 3×~300 个组合方块类、不做逐像素烘焙的前提下，拿到 §3.5 的核心玩法
> （元件按"露出红石相的面"输入/输出、元件贴在宿主表面）。
>
> **本版修订要点（相对上一版）**：
> 1. **取消 `HOST_SHAPE` 属性**。杆/红条的高度不存状态，改为**渲染期逐列实时查询宿主判定箱**。
> 2. 元件与宿主**同格共存**：宿主方块原样保留，元件状态以属性形式挂在宿主状态上
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

不新增方块，只给**已注册的相容宿主**追加 3 个属性（`PhaseStateRefresher` + `StateDefinitionDuck`
同一套注入机制；1.19.4/1.20.1 由 Statement 完成同等工作，两端追加的 state id 按注册表顺序一致）：

| 属性 | `NONE` 之外的取值 | 取值数 |
| --- | --- | --- |
| `liquidphase:repeater_phase` | `FACING`(4) × `DELAY`(4) × `POWERED`(2) | 32 + 1 = **33** |
| `liquidphase:comparator_phase` | `FACING`(4) × `MODE`(2) × `POWERED`(2) | 16 + 1 = **17** |
| `liquidphase:observer_phase` | `FACING`(6) × `POWERED`(2) | 12 + 1 = **13** |

**没有 `HOST_SHAPE`。** 宿主形状不入状态：宿主方块就在同一格里，渲染期直接问它自己的形状表（§2.5），
所以一个档位枚举都不需要。

**状态数怎么算——必须说清代价。** BlockState 属性是**相乘**的，和 `phase` 一模一样：

```
每宿主状态数 = 原宿主状态数 × 33   （只做中继器）
每宿主状态数 = 原宿主状态数 × 63   （三个元件全开：33+17+13 个非默认值）
```

即：**仅中继器 ≈ 现有 `phase` 开销的 4.1 倍（33/8），三个元件全开 ≈ 7.9 倍（63/8）。**
这比"每宿主 ×16 的组合方块"（§3.4 红石相）只重 2–4 倍，却省掉 41×3 个生成类与逐宿主重写方法——
**这正是选属性方案的交换条件，必须写进 README 的已知代价。**

对几个典型宿主（原版状态数为准）：

| 宿主 | 原状态数 | 现有 `phase`(×8) | 仅中继器(×33) | 三元全开(×63) |
| --- | --- | --- | --- | --- |
| 楼梯 `StairBlock`（`FACING`4×`HALF`2×`SHAPE`5×`WATERLOGGED`2） | 80 | 640 | 2640 | 5040 |
| 台阶 `SlabBlock`（`TYPE`3×`WATERLOGGED`2） | 6 | 48 | 198 | 378 |
| 栅栏 `FenceBlock`（`NORTH/EAST/SOUTH/WEST`2⁴×`WATERLOGGED`2） | 32 | 256 | 1056 | 2016 |
| 墙 `WallBlock`（`UP`2×四面 `WallSide`3⁴×`WATERLOGGED`2） | 324 | 2592 | 10692 | 20412 |

> 墙是状态数大户（四面各 3 档 × 上下），单独 ×33 就要 1.07 万状态。
> **强烈建议墙不要全量注入**：要么先跳过 `WallBlock`，要么只注入 `UP=false` 的子集，
> 或者干脆让元件相只支持"平顶宿主"（台阶/楼梯/完整方块/泥土类）——墙的横向落差本来就靠
> §2.5 的逐列采样表达，不吃 `HOST_SHAPE`，但状态代价是另一回事。

> 省状态的两个抓手：
> ① **按元件单独开关**（配置项 `repeaterPhaseEnabled` / `comparatorPhaseEnabled` / `observerPhaseEnabled`），
> 默认只开中继器 → 只吃 ×33；
> ② 只注入到**手写选定的 41 个宿主**（现有 `CompositeFactories` 的同一份清单），
> 不要无脑铺满 386 个 PhaseCapable 实例。
>
> 否则再考虑把三元压进**一个** `element` 枚举（`1+32+16+12 = 61` 值，×61）：只能省 2 个状态，
> 却让"中继器延迟"在 `element=observer` 时变成废值、语义脏。**建议仍分三个属性。**

### 2.2 位置：同格共存

元件相**不占额外的格、也不替换宿主**：

- 宿主方块原样留在本格，元件状态是**宿主自己那个 BlockState** 上的属性
  → `state.getCollisionShape(level, pos)` 拿到的天然就是宿主形状，这就是 §2.5 的采样基础。
- 不需要方块实体、不需要旁路表、不需要存"宿主状态"字段。
- 破坏元件 = 把对应属性置回 `NONE`，宿主不受影响（不存在"换回宿主状态"这一步，因为宿主一直在）。
  **注意：**若像 §3.4 那样"破坏元件也要掉宿主战利品"，那是原版破坏方块本身的行为，不用额外接线。
- 与 §3.4 红石相是**两套表示**：红石相要 0–15 功率（×16 且要逐宿主继承类）才走组合方块；
  元件相取值固定几档，直接吃属性相乘即可。两者互不干扰，但**同一格只能有一个相**
  （`phase` / `*_phase` 互斥，见 §2.4）。

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
  前置门控换成"背面 `FACING` 面是否露出"；`MODE` 的 `compare/subtract` 直接用原版
  `shouldTurnOn` / `calculateOutputSignal` 的算式。
- 侦测器相：`FACING` 方向邻居变化 → 计划刻 2 tick 翻转 `POWERED`
  （对齐原版 `ObserverBlock.updateShape` 的 `!POWERED` 分支）；输出口 `FACING.getOpposite()` 露出时输出 15。

**实现在哪（决定要不要 41×3 个类）**：**不要**给每个宿主覆写方法。信号逻辑统一注入到
**状态级 mixin**（沿用 `mixin/BlockStateBaseMixin.java` 这套锚点，新增一个
`ElementStateMixin` 更清晰），按 `state.getValue(*_PHASE) != NONE` 分派：

| 目标方法 | 注意 |
| --- | --- |
| `BlockStateBase.getSignal(BlockGetter, BlockPos, Direction)` | 状态级，全覆盖 |
| `BlockStateBase.getDirectSignal(...)` | 比较器强信号 |
| `BlockStateBase.isSignalSource()` | 块级/状态级签名随版本变，按 §3.4 已有的版本守卫处理 |
| `RedStoneWireBlock.shouldConnectTo` | 让线连到有元件相且该面露出的格（同 §3.4 注入点） |

这样一套代码覆盖全部宿主，与 §3.4 的"逐宿主生成类"路线彻底分开。

### 2.4 与 §3.4 红石相的互操作

- 红石线的"元件连接"判定复用 `RedStoneWireBlock.shouldConnectTo` 注入：
  把带元件相的状态当信号源，但只有朝向该格的那一面露出时才算连接。
- 放置：宿主方块上用中继器/比较器/侦测器物品右键 → 属性置为非 `NONE`，
  `FACING` 取玩家朝向（中继器/比较器水平四向，侦测器按 `getNearestLookingDirection().getOpposite()` 归一）。
- 互斥：写入任一 `*_phase` 时，把其余两个 `*_phase` 与 `phase` 一并清回 `NONE`。

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
  `(宿主形状引用, 元件属性三元组, 逐列高度数组)` 作缓存键——宿主形状一变键就变，缓存自动失效。
- 26.3 的 `BlockStateModel#collectParts(random, output)` **拿不到世界**，
  所以必须走 FRAPI 的 `emitQuads` 重载，不能用原生 `collectParts`。

**"立即重画"怎么保证**：元件状态和宿主状态是**同一个 BlockState**，
所以宿主变化（楼梯形状换向、栅栏连上另一段、台阶上半/下半切换）本身就会
`level.setBlock(..., UPDATE_ALL)` → 客户端标脏该 section → 重画，与元件变化走同一条路径，天然同步。
唯一要补的边角：**只改邻居、本格 BlockState 没变**的路径（例如栅栏因邻居栅栏变化而改 `north/south` 属性
——这其实也改本格状态，所以已覆盖；但墙体 `UP` 之类由邻居高度决定的，需确认宿主自身 `updateShape`
确实产生了新状态，否则）在宿主 `updateShape` 注入里补一次
`level.sendBlockUpdated(pos, old, new, Block.UPDATE_CLIENT)`。

> 说明：`setValue` 只改一个属性、其余原样带过，所以宿主的 `updateShape` 返回新状态时
> **元件属性自动保留**，不需要额外搬运逻辑。

### 2.6 用 `getShape` 还是 `getCollisionShape`

两者在本方案里几乎总相等，**建议 `getCollisionShape`**：

- 语义是"玩家站得上去的面"，与 §3.4 `exposed()` 用的是同一个形状表，两处判定不会打架。
- 差异出现在栅栏/墙（`CrossCollisionBlock` 里 `shapes` 与 `collisionShapes` 是两张表）：
  用碰撞形状元件会略宽地贴上去，视觉上比"浮在碰撞面之上"更自然。
- 日后若要升级到"逐像素贴模型"，把这一处换成 `getShape` 即可，其余逻辑（信号、放置、互斥）全不动
  ——渲染与语义解耦。

## 3. 交付顺序建议

1. **中继器相**（最小闭环）：`repeater_phase` 注入（33 值）+ 状态级信号 mixin + 逐列采样渲染；
2. **比较器**：多一个 `MODE` 与容器读取（17 值），几何多两根红条 + 前端火把；
3. **侦测器**：`FACING` 6 向 + 计划刻翻转（13 值），渲染位置按原稿**固定**、不做逐列采样；
4. 每步在 `AGENTS.md` 测试矩阵加一行，6 版本各跑一次服务端冒烟 + 客户端目视（重点：楼梯上的杆是否贴合、
   两根杆是否一高一低、换台阶后是否立即重画）。

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
