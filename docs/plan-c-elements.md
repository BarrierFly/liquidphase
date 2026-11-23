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

**传送门三件套**（`nether_portal` / `end_portal` / `end_gateway`）原版没有 `WATERLOGGED`。
0.2.0 在注入 `filling` 之前先给它们补上 `WATERLOGGED`（`LiquidPhase.injectPhaseProperty` 里
"没有该属性的宿主补一个"），于是它们和普通宿主一样走 `WaterloggedPhaseCapable` 的水分支：
水桶灌入/空桶取水/`getFluidState` 报告水源/带材料相时拒水。基接口 `PhaseCapable` 仍然
不读 `WATERLOGGED`，只作为"没有该属性时也不崩"的兜底（若注入失败，水分支直接拒绝）。

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

- 写 `filling != none` 时清掉旁路条目；写旁路条目时要求 `filling` 为空（含水会被清掉）
  或可替换（熔岩相被清回 `none`）——同一格只表达一个相（与文档 §2.2 一致）。
  0.2.3 起统一走 `PhaseHelper.canAcceptExtra`，元件装配与红石粉共用这一条门控。
- 方块被替换/移除时清掉旁路条目（`BlockStateBaseMixin` 的 onRemove / 放置路径）。
- 任何旁路条目变化都必须：服务端广播给追踪该格的玩家 + 客户端标脏该 section。

## 2. 信号语义

- `getSignal` / `getDirectSignal` 是**带坐标**的状态级入口：读旁路表。
- `isSignalSource()` **没有坐标**：不再对红石相返回 true。
  红石线连接判定改为在 `RedStoneWireBlock.getConnectingSide(...)`（带 Level/BlockPos）
  注入，只有"该格有红石条目且该面露出"才算连接。功率读取走
  `level.getBestNeighborSignal` → `getSignal`，天然正确。
- 中继器/比较器/侦测器沿用原版算式，输入面 = `facing`，输出面 = `facing.getOpposite()`
  （三类一致，与 `DiodeBlock`/`ObserverBlock` 的 FACING 语义一致）；**凡是参与输入/输出的面
  都受宿主相应面控制**：`RedstoneBlocks.exposed(host, face)` 为 false 时该面不输入也不输出。
  - 中继器/比较器：输入面、输出面；
  - 比较器的侧向 alternate 输入：两侧面分别判定；
  - 中继器的锁存：侧向二极管输入的两侧面分别判定；
  - 侦测器：观察面（`facing`）不露出时不排检测刻；输出面不露出时不出信号。
- 比较器侧向输入按原版 `SignalGetter.getControlInputSignal` 实现：中继器锁存
  `sideInputDiodesOnly=true`（只认中继器/比较器）；比较器 `false`（红石块 15、红石线读
  `POWER`、红石相按线规则读满功率、其余信号源读 `getDirectSignal`）。
- 红石线连接：新增"元件宿主"分支，规则对齐原版 `shouldConnectTo`——中继器/比较器的
  输入面与输出面都连，侦测器只在输出面连；连接同样要求宿主相应面露出
  （`RedStoneWireBlockMixin` / 26.3 的 `BlockStateBase.shouldRedstoneWireConnectTo`）。
  没有这个分支时，红石线对元件宿主返回 0（线自己的 `getSignal` 要求"朝接收方那一侧已连接"）。
- 中继器 tick 镜像原版 `DiodeBlock#tick`：已排队的刻即使输入已消失也会点亮并按延迟再排一次，
  短脉冲因此仍能输出一个完整延迟的脉冲。中继器放置时按原版补初始 `locked`。
- 侦测器：观察 `facing` 邻居变化 → 计划刻 2 tick 翻转 `powered`。

## 3. 放置与破坏

- **普通右键**宿主方块，手持中继器/比较器/侦测器 → 写入对应元件条目
  （**潜行**右键保留原版"放在宿主上面一格"）；写入时清掉宿主的水/熔岩相。
- 真实元件方块也是"相材料格"：宿主放进/`setBlock` 写进/落进中继器·比较器·侦测器所在格
  → 吸收成元件相，参数从该方块状态继承（`ElementBlocks.extraOfBlock`）。
  （朝向取玩家水平朝向 / 侦测器取 `getNearestLookingDirection()`，与原版
  `ObserverBlock.getStateForPlacement` 的双 getOpposite 抵消结果一致）。
  放置前提：足迹内存在顶面高度 `h < 1` 的空腔列（文档 §2.2）。
- 破坏：准星命中的是元件几何且宿主本身未被破坏 → 只清条目 + 掉落对应物品。
- **右键调档（0.2.5）**：宿主没有使用键交互时，右键它调所含元件档位
  （中继器延迟 1→2→3→4→1、比较器比较↔减法）。注入点在服务端
  `ServerPlayerGameMode#useItemOn` 的 RETURN，只在"这次点击没有被消费"时才动作，
  因此放置/装配/开箱都不会被抢。客户端不预测，服务端广播 `extra_update`。
- **充水冲落（0.2.5）**：水会冲掉**可冲落**的条目（红石相掉红石粉、元件掉对应物品），
  然后正常含水。可冲落判定是实时的（`PhaseExtra.isWashable`，目前 = 红石 + 全部元件）；
  材料相（冰/霜冰/黑曜石/熔岩）仍按"水不能直接替换材料相"处理。
- 破坏宿主时（普通破坏）：元件条目随宿主一起消失（不额外掉落，与原版"元件是方块"不同，写进 README）。
- 活塞路径（0.2.3）：黑曜石填充相一律推不动；相不随活塞移动——推动覆盖/破坏列表摧毁弹出物品，
  粘性拉动/剩余清格实体化留在原位（详见 README"活塞与相"）。

## 3.1 吸收（0.2.3 统一）

宿主放进**任何相材料格**（红石线 / 冰 / 霜冰 / 黑曜石 / 熔岩源）都走同一条机制：
`PhaseCellReplaceMixin` 让该格在放置管线里算可替换，`BlockItemMixin` 的 placeBlock 捕获
按格子内容写相（线格继承原线功率、材料格走 `canAcceptPhase`）。原 `PlacementMixin`
（熔岩源 `getStateForPlacement` 改写）与 place 头部落点拦截已删除。

## 4. 渲染

- 静态 JSON：0.2.5 起**材料相覆盖层也不再写进 blockstate**，blockstate 里只有宿主 part +
  一条无条件 `liquidphase:block/dynamic`。材料相覆盖层由动态模型按状态发射，从而可以
  **逐 quad 选渲染图层**（冰/霜冰 → TRANSLUCENT、黑曜石 → CUTOUT）；静态 part 只能整块状态
  一个图层，会把半透明冰画成死白、把玻璃宿主上的黑曜石拖进排序层。
- 动态模型（Fabric Rendering API）：每个宿主 blockstate 追加一条**无条件** part
  `liquidphase:block/dynamic`。该模型：
  - 按坐标查旁路表、按状态取 `filling`；
  - 材料相条目 → 发内缩 0.25px 的 `phase_<obsidian|ice|frosted_0..3>` 子模型，逐 quad 设图层；
  - 红石条目 → 发 `liquidphase:block/redstone_phase_<power>` 子模型的 quad（16 档颜色）；
  - 中继器/比较器条目 → 发对应（kind × facing × 变体）子模型。子模型**只有杆与红条**：
    原版模型里的 2px 平滑石底板、锁存条都不生成；杆（红石火把，含点亮光晕）整体下移
    2px 让杆底落在 y=0，红条是从原版顶面贴图里裁出的 2×8 / 8×2 贴图。
    几何在生成时按 **1/16 像素列**切开，运行时每个小面只取**该列**的宿主顶面高度
    （`VoxelShape.max(Y, z, x)`——⚠️ 两个坐标参数是 (z, x)，0.2.5 修）**整面刚性平移**——
    "每一平方像素都搭在宿主相应像素的最高处，同一根杆/红条内部允许落差"。旋转时连法线与
    `nominalFace` 一起转，否则光照/剔除仍按模型空间方向算。
  - 侦测器条目 → 发对应（facing × powered）子模型。**6 个朝向各自预烘焙**，16×16 正常比例，
    固定在方块内（运行时不旋转、不抬升）；只生成"侦测面五官"和"输出面输出口"两块平面，
    贴图是原版 `observer_front` / `observer_back` 的裁切（其余像素透明），不显示任何一个整面。
    平面放在对应方块边界**外侧 0.01**，所以下半砖/楼梯的侧脸不会把五官/输出口挡住。
  - 无条目/无材料相 → 该部分一个 quad 都不发。
- 子模型由 `tools/gen_element_models.py` 从客户端 jar 的原版模型生成
  （33 个模型：16 中继器 + 4 比较器 + 12 侦测器 + `dynamic.json`；另有 7 份元件贴图），
  版本无关。比较器/中继器的 `_locked` 变体只保留逻辑锁定，不渲染锁形。
- 图层：由 Fabric Rendering API 的 material 决定（红石外壳与侦测器裁切贴图是 cutout，
  杆/红条贴图不透明）；不再依赖状态级 `ItemBlockRenderTypes` 注入来区分红石相。
- `getRenderShape`：把 `PhaseCapable` 方块在**块级**强制为 `MODEL`
  （替代原按状态的 `PhaseRenderShapeMixin`；状态里已经看不出有没有元件）。

## 5. 与文档 `state-budget-analysis.md` 的偏差（必须写进 README）

1. 类别集取 8 个**材料类别**，红石相与元件相整个进旁路表（文档 C 的原意）。
   `(N − B) + B × 8` ⇒ 1.21.11 约 176,503 状态（文档 C 的 257 MiB 档）。
   实际值为 **176,563**：传送门三件套额外补注入 `WATERLOGGED`（+32 状态），
   这样它们和普通宿主一样能含水（水桶灌/取、流体态、带相拒水）。
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
- 服务端自检全过（0.2.5 实测：1.19.4/1.20.1/1.21.1 **510**、1.21.10 **511**、26.3 **509**、
  1.21.11 **511**，均 0 failed；含旁路表读写、元件 I/O 露出门控、右键调档、充水冲落、
  红石线连接、元件资源形状/贴图、材料相覆盖层不再写进 blockstate 的断言）；
- `python tools/validate_blockstates.py` / `check_mixin_targets.py` / `verify_jars.py` /
  `audit_host_coverage.py` 全绿；
- 1.21.11 客户端 gametest（栅栏红石相 + 元件几何 quad 断言 + 下半楼梯逐像素落差 +
  侦测器五官/输出口 + **材料相覆盖层逐 quad 图层**：冰 6 quad 进 TRANSLUCENT、
  黑曜石 6 quad 进 CUTOUT）。
