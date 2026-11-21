# 方案 C（类别进状态 + 参数旁路表）与元件相实施说明

> 本文是实现口径，不是提案。旧存档不兼容（属性取值域变更）。

## 1. 表示法

### 1.1 状态里的类别（`filling`，8 值）

| 值 | 含义 | 为什么必须留在状态里 |
| --- | --- | --- |
| `none` | 无相 | 默认值 |
| `lava` | 熔岩相 | `getFluidState()` / `getLightEmission()` / `isRandomlyTicking()` 都是**无坐标**入口 |
| `obsidian` | 黑曜石相 | 固相整格碰撞（状态级缓存）、破坏保留 |
| `ice` | 冰相 | 随机刻融化（无坐标入口） |
| `frosted_0..3` | 霜冰相 | 裂纹档位是**材质档位**，保留为状态值可继续走静态 JSON 覆盖层 |

**红石相与元件相（中继器/比较器/侦测器）不再占用状态值**：它们在旁路表里，
取值域只有上面 8 个（相对原版新增 7 个）。

### 1.2 旁路表（参数）

每格一条 `PhaseExtra`（包进一个 `int`）：

| 字段 | 位宽 | 说明 |
| --- | --- | --- |
| kind | 3 | 0=无、1=红石、2=中继器、3=比较器、4=侦测器 |
| power | 4 | 红石功率 0–15 / 比较器输出 0–15 |
| facing | 3 | `Direction.ordinal()` |
| delay | 2 | 中继器延迟 0–3（渲染/计划刻都 +1） |
| mode | 1 | 比较器 0=比较、1=减法 |
| locked | 1 | 中继器锁存（逻辑锁定，渲染暂不显示锁形） |
| powered | 1 | 元件输出状态 |

`kind == 0` 的条目不入表（删除条目）。

**存储位置（与文档 §3.4/§6 的偏差，见 §5）**：每个 `Level` 挂一张
`PhaseStorage`：`Map<Long /*chunkPos*/, Long2IntOpenHashMap /*BlockPos.asLong -> extra*/>`。
服务端权威、按区块分组（客户端请求/下发按区块），持久化走 mod 自己的
`world/data/liquidphase_<dimension>.dat`（NBT），不碰原版区块存档格式。

### 1.3 一致性

- 写 `filling != none` 时清掉旁路条目；写旁路条目时要求 `filling == none`
  （同一格只表达一个相，与文档 §2.2 一致）。
- 方块被替换/移除时清掉旁路条目（`BlockStateBaseMixin` 的 onRemove / 放置路径）。
- 任何旁路条目变化都必须：服务端广播给追踪该格的玩家 + 客户端标脏该 section。

## 2. 信号语义

- `getSignal` / `getDirectSignal` 是**带坐标**的状态级入口：读旁路表。
- `isSignalSource()` **没有坐标**：不再对红石相返回 true。
  红石线连接判定改为在 `RedStoneWireBlock.getConnectingSide(...)`（带 Level/BlockPos）
  注入，只有"该格有红石条目且该面露出"才算连接。功率读取走
  `level.getBestNeighborSignal` → `getSignal`，天然正确。
- 中继器/比较器/侦测器沿用原版算式，输入面 = `facing`，输出面 = `facing`（中继器/比较器）
  或 `facing.getOpposite()`（侦测器）；被宿主遮住的面不输入也不输出。
- 侦测器：观察 `facing` 邻居变化 → 计划刻 2 tick 翻转 `powered`。

## 3. 放置与破坏

- 手持中继器/比较器/侦测器右键**宿主方块的上表面** → 写入对应元件条目
  （朝向取玩家水平朝向 / 侦测器取 `getNearestLookingDirection().getOpposite()`）。
  放置前提：足迹内存在顶面高度 `h < 1` 的空腔列（文档 §2.2）。
- 破坏：准星命中的是元件几何且宿主本身未被破坏 → 只清条目 + 掉落对应物品。
- 破坏宿主时：元件条目随宿主一起消失（不额外掉落，与原版"元件是方块"不同，写进 README）。

## 4. 渲染

- 静态 JSON：8 个类别值的覆盖层（黑曜石/冰/霜冰）保持现状。
- 动态模型（Fabric Rendering API）：每个宿主 blockstate 追加一条**无条件** part
  `liquidphase:block/dynamic`。该模型：
  - 按坐标查旁路表；
  - 红石条目 → 发 `liquidphase:block/redstone_phase_<power>` 子模型的 quad（16 档颜色）；
  - 元件条目 → 发对应（kind × facing × 变体）子模型，并用 `QuadTransform` 把每个顶点
    抬到**宿主顶面所在列的高度**（逐列采样，`VoxelShape.max(Y, x, z)`），
    保证五官/输出口/杆都画在宿主表面**上层**；
  - 无条目 → 一个 quad 都不发。
- 子模型由 `tools/gen_element_models.py` 从客户端 jar 的原版模型生成
  （复用原版贴图与 UV，按朝向预旋转），版本无关。
- 图层：由 Fabric Rendering API 的 material 决定（红石外壳是 cutout，
  元件贴图不透明）；不再依赖状态级 `ItemBlockRenderTypes` 注入来区分红石相。
- `getRenderShape`：把 `PhaseCapable` 方块在**块级**强制为 `MODEL`
  （替代原按状态的 `PhaseRenderShapeMixin`；状态里已经看不出有没有元件）。

## 5. 与文档 `state-budget-analysis.md` 的偏差（必须写进 README）

1. 类别集取 8 个**材料类别**，红石相与元件相整个进旁路表（文档 C 的原意）。
   `(N − B) + B × 8` ⇒ 1.21.11 约 176,503 状态（文档 C 的 257 MiB 档）。
2. 旁路表按 **Level 分组（chunk → cells）**，不是 `LevelChunkSection` 内的表：
   省掉 `LevelChunkSection` / `ChunkSerializer` / `SerializableChunkData` 三条
   版本差异最大的存档路径，持久化改用 mod 自己的 NBT 文件。稀疏性、按区块同步粒度
   与文档一致；代价是区块卸载后条目仍驻留内存（本来就要求持久）。
3. 渲染走 **Fabric Rendering API 的按坐标发 quad**，而不是
   `RenderChunk` / `RenderChunkRegion` / `RebuildTask` 快照链：
   FRAPI 的 `emitQuads` / `emitBlockQuads` 本身就带 `BlockAndTintGetter + BlockPos`，
   并且按 quad 决定图层，跨 6 个版本只需要两代适配。
4. Sodium/光影：FRAPI 模型由 Indigo（Sodium 下由 Indium）接管；不承诺 Sodium 兼容，
   README 写成已知限制。

## 6. 验收

- 6 个版本编译通过；
- 服务端自检全过（新增：旁路表读写、元件信号、渲染资源、状态数上限断言）；
- `python tools/validate_blockstates.py` / `check_mixin_targets.py` / `verify_jars.py` /
  `audit_host_coverage.py` 全绿；
- 1.21.11 客户端 gametest（栅栏红石相 + 元件几何 quad 断言）。
