# AGENTS.md — 工程说明（面向 AI/协作者）

本仓库是 **液相 Liquid Phase**：多版本（1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3）Fabric mod，
用 **stonecutter** 维护单棵 `src/` 源码树，版本差异用 `//? if … {` / `//?} else {` / `//?} elif` / `//?}` 预处理注释表达。

## 1. 版本切换与构建

```bash
# 切换"原始源码"对应的目标版本（raw 源码必须对该版本可编译）
./gradlew stonecutterSwitchTo1.21.1        # 切换
# 或直接编辑 stonecutter.gradle.kts: stonecutter active "1.21.11"
./gradlew "Reset active project"           # 回到 vcsVersion（1.21.11）

# 逐版本构建（产物：versions/<版本>/build/libs/liquidphase-<ver>+<mc>.jar）
./gradlew :1.19.4:build :1.20.1:build :1.21.1:build :1.21.10:build :1.21.11:build :26.3:build
```

要点：
- `active` 版本直接编译 **原始** `src/`（预处理注释只是注释）；其余版本由 `stonecutterGenerate` 预处理后再编译。
  因此 **原始源码必须对 1.21.11 合法**：老版本专属代码要写在 `//? if 1.19.4 || 1.20.1 { /* … *///?}` 的注释分支里。
- 26.3 在 `versions/26.3/gradle.properties` 里开 `fabric.loom.disableObfuscation=true`；该模式下 Loom 不创建
  `modImplementation` 等 remap 配置，依赖一律用 `implementation`。
- 生成的 blockstate 资源与 Java 生成类都属于构建产物，被 `tasks.matching { name == jar/sourcesJar }.configureEach { dependsOn(...) }` 约束依赖顺序。

## 2. 关键实现位置

| 主题 | 文件 |
| --- | --- |
| 相枚举（方案 C，8 个材料类别） | `phase/Phase.java`（属性名 0.1.15 起为 `filling`，Java 侧沿用 Phase 命名；红石/元件不再是枚举值） |
| 相行为总控（充入/实体化/凝固/冻结/融化/霜裂/火蔓延） | `phase/PhaseHelper.java`（属性常量 `FILLING`） |
| 吸收门控唯一判定源（可替换填充相规则，0.1.17） | `PhaseHelper.canAcceptPhase` + `Phase.isReplaceableFilling()`；接受层/改写层/交互层一律委托它（防漂移，见 §3.7） |
| 相容宿主标记接口 + 桶/流体默认方法 | `phase/PhaseCapable.java`（只实现熔岩分支）+ `phase/WaterloggedPhaseCapable.java`（含水宿主走原版含水默认实现）；传送门三件套由 `mixin/PortalPhaseCapableMixin.java` 接入基接口 |
| 瞄准与直接挖相（含整格宿主/形状表宿主的命中判定） | `phase/PhaseTargeting.java` |
| 状态级核心 mixin（流体态/光照/随机刻/破坏保留/邻居） | `mixin/BlockStateBaseMixin.java` |
| 自带状态注入器（1.21.1+） | `state/PhaseStateRefresher.java` + `mixin/state/*` + `state/*Duck` |
| 桶/放置/交互 mixin | `mixin/BucketItem*Mixin.java`、`mixin/PlacementMixin.java`、`mixin/BlockItemMixin.java`（含落点格吸收）；`mixin/FillingReplaceMixin`（接受层）+ `mixin/FillingPlacementMixin`（`Level.setBlock` 通用改写层）；遮蔽宿主的熔岩分支在 `mixin/SlabLiquidMixin.java` + `mixin/HostPlaceLiquidMixin.java`（0.1.13，audit §4.3）。原 `BucketUseMixin` 已删除（0.1.13，audit §4.7：桶交互完全走原版路径） |
| 冻结接线 | `mixin/ServerLevelMixin.java`（`tickChunk` ≤1.20.1 / `tickPrecipitation` 1.21.1+） |
| 炼药锅 | `cauldron/CauldronBehaviors.java`、`CauldronBehaviorRegistration.java`、`CauldronHopperTransfer.java`、`ConcreteMappings.java` |
| 发射器/漏斗 | `interact/LiquidInteractions.java`、`mixin/HopperTransferMixin.java` |
| 方案 C 表示法（旁路表参数） | `phase/PhaseExtra.java`（kind/power/facing/delay/mode/locked/powered 压进一个 int）+ `phase/PhaseExtras.java`（关卡级读写、写入即通知）+ `docs/plan-c-elements.md`（现行口径） |
| 旁路表存储与持久化 | `phase/PhaseStorage.java`（每个 `Level` 一张 `chunkPos -> (BlockPos -> int)` 稀疏表，内层不可变）+ `phase/PhaseStorageHolder.java` + `mixin/LevelStorageMixin.java` + `phase/PhasePersistence.java`（`world/data/liquidphase_<dimension>.dat`，30 s 自动保存 + 关服保存） |
| 旁路表同步 | `sync/PhaseExtraSync.java`（`extra_request` / `extra_chunk` / `extra_update`）+ `sync/PhaseExtraClientSync.java`（区块加载/握手/Level 更换请求 + 标脏） |
| 红石信号与放置 | `redstone/RedstoneBlocks.java`；`mixin/RedstoneSignalMixin.java`；红石线连接 `mixin/RedStoneWireBlockMixin`（≤1.21.11）/ `BlockStateBaseMixin.shouldRedstoneWireConnectTo`（26.3）；点粉/宿主进线格 `mixin/BlockItemMixin` + `mixin/RedstoneWireReplaceMixin`；下落方块 `mixin/FallingBlockIntoWireMixin` |
| 元件相（中继器/比较器/侦测器） | `element/ElementBlocks.java`（放置/破坏/信号/计划刻/锁存）+ `element/ElementRenderSupport.java`（子模型 id / 旋转 / 逐列抬升）+ `mixin/ElementShapeUpdateMixin.java`（状态级 `updateShape`） |
| 旁路表一致性 | `mixin/LevelChunkExtraMixin.java`（方块被换成另一个方块时清条目）；`BlockStateBaseMixin` 的 onRemove/放置路径（写 `filling != none` 时清条目） |
| 动态渲染（两代 API） | `client/PhaseDynamicModelLegacy.java`（≤1.21.1，接管 `liquidphase:block/dynamic`）/ `client/PhaseDynamicModelModern.java`（1.21.10+，包装宿主 `BlockStateModel`） |
| 资源包兼容合并（§3.7，0.1.17） | `client/PhasePackMerge.java`（ResourceManager 代理 + 栈顶合并）+ `client/PhasePackMergeCore.java`（纯 Gson 合并核心）+ `mixin/client/PhaseLoaderMixin.java`（≤1.21.1 挂 `ModelManager.loadBlockStates`，1.21.10+ 挂 `BlockStateModelLoader.loadBlockStates`）+ 片段 `assets/liquidphase/phase_parts/<id>.json`（生成器产出） |
| 配置 | `LiquidPhaseConfig.java`（`packMerge` 控制资源包合并，默认开） |

## 3. 生成脚本（改动后必须重跑）

```bash
# 1) 相容方块 id 清单（从 guardian 反编译源码解析 Blocks.java）
#    ⚠️ 这份清单必须与"运行时真的拿到 filling 属性的方块"逐版本严格一致：
#    运行时给每一个 `instanceof PhaseCapable` 的方块注入 filling，而 PhaseCapable 挂在
#    PhaseCapableMixin 的一组基类上、全部子类都会继承。清单少一个 → 那个方块含相
#    时没有 blockstate/动态 part（0.1.12 的根因）；多一个 → 原版解析器报 Unknown property 'filling'，
#    整份 blockstate 加载失败。脚本用 EXPECTED_COUNTS（各版本运行时注入数）硬校验。
python tools/gen_phase_blocks.py "<guardian 仓库路径>" tools/phase-blocks tools/phase-blocks

# 2) （0.1.5 起不再有组合方块生成步骤；红石相/元件相改走旁路表）

# 3) 相覆盖 blockstate（构建时自动执行，也可单独跑）
#    0.2.0 起每个宿主只追加一条无条件 part liquidphase:block/dynamic；
#    不再生成 16 条 when{filling: redstone_N} part。
./gradlew :<版本>:convertPhaseBlockstates

# 4) 液体物品贴图
python tools/gen_item_textures.py src/main/resources/assets/liquidphase/textures/item

# 5) 红石相外壳贴图 + 模型（16 个功率档位各一份，按原版红石线亮度曲线着色）
#    只作为动态模型的子模型资源；blockstate 由第 3 步追加无条件 dynamic part。
python tools/gen_redstone_assets.py src/main/resources/assets/liquidphase

# 6) 元件相子模型（方案 C：从客户端 jar 复制原版几何与贴图）
#    产出 22 个 element_*.json + dynamic.json（共 23 个文件）；改了元件几何/贴图后必须重跑。
python tools/gen_element_models.py "<client-jar>" src/main/resources
```

`tools/redstone-hosts.txt` 是 0.1.1–0.1.8 时期"外壳只覆盖 41 个代表宿主"的历史清单，
**已经不再参与构建**（`build.gradle.kts` 不再把它声明为输入），只作参考。
0.1.9 起外壳对全部相容宿主生成；0.1.11 起 blockstate 生成也只剩一条路径；
0.2.0 起外壳改由动态模型按坐标提供，`gen_phase_blockstates.py` 只追加一条无条件
`liquidphase:block/dynamic` part，16 份外壳模型/贴图仍保留为子模型。

`fabric_loader_version`（共享 `gradle.properties`）是**构建用** loader，`depends` 里声明的是
各版本 `versions/<版本>/gradle.properties` 的 `fabric_loader_min`（= max(Fabric API 自身要求, 0.15.0)，
0.15.0 起 loader 自带 MixinExtras，`CacheShapeGuardMixin` 依赖它）。两者别混用；
改 `fabric_loader_min` 时 `processResources` 已把它声明为输入，不会因增量判定漏更新。

`tools/phase-blocks/<版本>.ids` 是相容方块 id 清单；`<版本>.closure.txt` 是 `PhaseCapableMixin`
的 `@Mixin` 目标类清单（标记接口就是挂在这些类上，其全部已注册子类都会继承）。
**两者都要与 mixin 源码保持一致**：`closure.txt` 漏一个类名，那个族就等于没登记
（0.1.12 的 `barrier` 就是这么漏的：`BarrierBlock` 在 mixin 里、却不在 1.19.4/1.20.1 的
closure 清单里）。

### 2.2 id 清单完整性（0.1.12 起硬校验）

`tools/gen_phase_blocks.py` 结尾用 `EXPECTED_COUNTS` 对比各版本
`injected phase property into N blocks` 的实测值（0.2.0：311 / 313 / 340 / 413 / 413 / 508；
新增传送门三件套 `nether_portal` / `end_portal` / `end_gateway`。0.1.13 起 1.19.4/1.20.1
不含 `barrier`——那两个版本的 `BarrierBlock` 没有 `WATERLOGGED`，
见 §7 第 8 条与 `docs/host-coverage-audit.md` §4.2），
数量不符直接失败。**做版本升级时这两个数就是验收口径**：先跑一次服务端拿到新的注入数，
把 `EXPECTED_COUNTS` 改掉，再重跑生成器。它同时会打印"closure 里没有任何注册方块的类"，
那说明闭包清单写进了不存在的类名（或解析器又漏了一种注册形式）。

> ⚠️ **数量相等 ≠ 集合相等**。清单两边各错一个时数量一样，漏掉的那个方块仍然不显示
> 红石相。所以真正的验收是**双向集合比对**，有两处：
> 1. 自检 `checkPhaseBlockListMatchesRuntime`：把随 jar 发布的 `phase-blocks.ids`
>    （`build.gradle.kts` 的 `copyPhaseBlockIds` 任务打进资源根目录）与运行时
>    `BuiltInRegistries.BLOCK` 里所有 `instanceof PhaseCapable` 的方块做双向差集，
>    任一方向非空即失败并直接打印差集（`phased but not listed` / `listed but not phased`）。
> 2. `tools/verify_jars.py`：断言 jar 内的 blockstate 集合与 `tools/phase-blocks/<版本>.ids`
>    完全相等（多一个少一个都 BROKEN），并核对 jar 里随包的 `phase-blocks.ids` 与仓库清单一致。
>
> 已实测：把 1.21.11 的清单改成"删掉 `barrier` + 加一个不存在的 id"（总数不变，0.2.0 为 413），
> 两条断言都立刻失败并点名 `barrier`。

生成器还必须处理这些批量注册形式（历史上逐个踩过）：
`register` / `registerSlab` / `registerLegacyStair` / `registerStair` / `registerWall`、
`ColorCollection.registerBlocks`、`ColorCollection.zipMap`（id 在**第二个**参数位）、
`WeatheringCopperCollection.registerBlocks`、`WeatheringCopperBlocks.create`。
`ColorCollection` 展开成 16 色，`WeatheringCopper*` 展开成 8 个氧化/打蜡变体。

生成器会**删除本次未产出的旧 blockstate**。不清理的话，从清单里移除某个 id 后旧文件仍在
输出目录、继续被打进 jar，那个方块若拿不到 `filling` 属性就会让客户端报
`Unknown property 'filling'`、整份 blockstate 退成 missing model。

### 2.1 发布前静态校验（0.1.7 起）

```bash
# 1) 生成出来的 blockstate 是否都能被原版 multipart 解析器接受
#    （按 KeyValueCondition/CombinedCondition 的真实语义，覆盖全部 6 个版本的构建产物）
python tools/validate_blockstates.py

# 2) 每个版本 jar 里 PhaseCapableMixin 的 @Mixin 目标是否对得上该版本的多面体/石钟乳家族
#    （1.21.10+ 是 MultifaceBlock / SpeleothemBlock，之前是 GlowLichen+SculkVein / PointedDripstone）
python tools/check_mixin_targets.py

# 3) dist/ 里的 jar 内部资源抽查（版本号、坏条件数、关键 mixin 是否真的在包里、
#    每个宿主 blockstate 是否有无条件 dynamic part、16 份外壳模型/贴图是否在包里、
#    phase_write 探针是否为 liquidphase$captureWirePower）
python tools/verify_jars.py

# 4) 相容宿主覆盖面审计（A/B/C/S 四个集合的差集；改 mixin 目标、ids 清单、
#    生成器门控、或做版本升级后各跑一次）
python tools/audit_host_coverage.py "<guardian 仓库路径>" .
```

`tools/audit_host_coverage.py` 把"哪些方块应当能含相"和"实际覆盖了哪些"拆成四条链路，
差集必须全空（详见 `docs/host-coverage-audit.md`）：

| 记号 | 含义 | 差集含义 |
| --- | --- | --- |
| A | `Blocks.java` 里类（或祖先）实现 `SimpleWaterloggedBlock` 的方块；显式加入传送门三件套 | `A-B`：应能含相但拿不到 `filling` |
| B | `PhaseCapableMixin` 的 `@Mixin` 目标闭包 ∩ 注册表 | `B-C`：有属性但没有 blockstate（含相看不见） |
| C | `tools/phase-blocks/<版本>.ids` | `C-B`：有 blockstate 但拿不到属性（`Unknown property 'filling'`） |
| S | 真正生成 blockstate（含无条件 `dynamic` part）的方块 | `C-S`：有清单但没产物 |

它还会顺带报出"能力被宿主覆写遮蔽"的三类缺口（`PhaseCapable` 的
`canPlaceLiquid`/`placeLiquid`/`pickupBlock`、块级 `getCollisionShape`、
`getStateForPlacement`）——这些不是清单问题，只按清单生成的断言测不出来。
`--history` 会逐提交重算，用于核对历史补丁是否真的覆盖了全部宿主。

`tools/validate_blockstates.py` 会在构建时被 `gen_phase_blockstates.py` 的
`validate_result()` 再查一遍（非法条件直接让 `convertPhaseBlockstates` 失败），
但单独跑一次可以对任意产物（含已经拷进 `dist/` 的 jar）复核。

### 3.1 相覆盖层：所有宿主统一"内缩叠加"（0.1.18 起）

`tools/gen_phase_blockstates.py` 仍**按模型几何自动判定**整格宿主（保留判定，用于断言与
日志），但**渲染方式已经统一**：无论整格、非整格还是混合宿主，相一律是
**内缩 0.25px 的覆盖层**（`phase_obsidian` / `phase_ice` / `phase_frosted_*`），
宿主 part 一律**不带**任何 `filling` 条件、始终渲染。

| 宿主类型 | 0.1.17 及以前 | 0.1.18 起 |
| --- | --- | --- |
| 整格（树叶/铜格栅/红树根） | 宿主 part 被 `filling` 条件屏蔽 + `phase_*_full` 整格相材质 | 宿主常驻 + 内缩覆盖层 |
| 混合（台阶） | `type=double` 走整格替换并屏蔽宿主；bottom/top 用内缩 | 三种变体统一内缩、宿主常驻 |
| 非整格（栅栏/楼梯/墙…） | 内缩覆盖层（未变） | 内缩覆盖层（未变） |

> ⚠️ **为什么必须内缩、不能再用 `phase_*_full`**：整格宿主的模型是 0..16，`_full` 也是
> 0..16，两者完全共面 → z-fighting，树叶与相随机互相遮挡。内缩 0.25px 后宿主稳定在外层：
> 不透明树叶遮住相、贴图缝隙里露出相。
>
> ⚠️ **为什么不再屏蔽宿主**（0.1.18 的核心修正）：屏蔽会让"树叶含冰"变成一整块冰、
> 树叶消失，而**含熔岩时树叶照常可见**（熔岩走原版流体渲染、不进 blockstate）。
> 同一个方块两种相给出完全不同的观感，用户实测就是这个差异。现在两者一致。
>
> ⚠️ **薄宿主的宿主会看不见，这是预期**：栅栏柱子 x/z 只有 6..10、横杆 7..9，而相是
> 0.25..15.75 的实心立方体——柱子的每个顶点都在相**内部**，不存在"表面重叠"可以让宿主
> 占优的位置，从任何外部视角相的面都先被命中。这是"相就是整块材质"的直接后果，不是缺陷。
> 想要栅栏可见必须改相的形状（挖空/贴合），那是另一个设计决策。
>
> ⚠️ **判定粒度仍是 part（变体）**（0.1.12 及以前的教训，audit §4.1）：台阶的
> `type=bottom/top` 引用半砖模型、`type=double` 引用整块模型，方块级 `all(...)` 必然判否。
> 现在三种变体都走内缩，判定只用于分类统计与断言，不再影响产物形状。

新增整格型宿主不需要改代码；跑一次生成器就会自动识别。要复核识别结果，看
`convertPhaseBlockstates` 打印的 `full-cube hosts:`（全部整格）与
`mixed-model hosts:`（按变体拆分）列表。

### 3.2 方案 C：旁路表与动态渲染

**0.2.0 起 `filling` 只有 8 个材料类别**（`none` / `lava` / `obsidian` / `ice` / `frosted_0..3`），
红石功率与元件相（中继器/比较器/侦测器）的全部参数压进旁路表 `PhaseExtra`（一个 15 bit 的 int）。
完整口径（位布局、信号语义、与旧方案的偏差）见 `docs/plan-c-elements.md`。

| 项目 | 位置 |
| --- | --- |
| 旁路条目（kind/power/facing/delay/mode/locked/powered） | `phase/PhaseExtra.java` |
| 关卡级读写 + 写入通知 | `phase/PhaseExtras.java` |
| 每 `Level` 一张稀疏表（内层不可变，渲染线程无锁读） | `phase/PhaseStorage.java` + `phase/PhaseStorageHolder.java` + `mixin/LevelStorageMixin.java` |
| 服务端持久化 | `phase/PhasePersistence.java`（`world/data/liquidphase_<dimension>.dat`，30 s 自动保存 + 关服保存） |
| 双端同步 | `sync/PhaseExtraSync.java` + `sync/PhaseExtraClientSync.java` |
| 元件放置/信号/计划刻/锁存 | `element/ElementBlocks.java`；状态级 `updateShape` 在 `mixin/ElementShapeUpdateMixin.java` |
| 动态渲染 ≤1.21.1 | `client/PhaseDynamicModelLegacy.java`（自定义 `UnbakedModel` 接管 `liquidphase:block/dynamic`） |
| 动态渲染 1.21.10+ | `client/PhaseDynamicModelModern.java`（`ModelLoadingPlugin.modifyBlockModelOnLoad` 包装宿主 `BlockStateModel`） |
| 子模型 id / 旋转 / 逐列抬升 | `element/ElementRenderSupport.java` |
| 红石线连接判定 | `mixin/RedStoneWireBlockMixin`（≤1.21.11）/ `BlockStateBaseMixin.shouldRedstoneWireConnectTo`（26.3） |
| 全部相宿主状态强制 MODEL | `mixin/PhaseRenderShapeMixin`（状态级 `RETURN`，见下） |
| 双端能力握手 | `sync/PhaseHandshake.java` + `sync/PhaseHandshakeClient.java` + `sync/PhaseSyncState.java` |

**一致性（同格只表达一个相）**：

- 写 `filling != none` 一律走 `PhaseHelper.setPhase`，先清旁路条目；
- 写旁路条目走 `canAcceptExtra`：条目必须为空，且 `filling == none` 或可替换（水/熔岩），
  写入时把 `filling` 清回 `none`；
- 方块被换成**另一个方块**时由 `mixin/LevelChunkExtraMixin` 清条目（活塞推动走这里，
  所以条目不随动）；换成空气不在那里清，留给 onRemove 读取红石功率以留下红石线。

**状态数**：1.21.11 注入 **413** 个方块、总状态 **176,531**（方案 C 预算档）。

**渲染**：`tools/gen_phase_blockstates.py` 除相覆盖层外，给每个宿主 blockstate 追加一条
**无条件** part `liquidphase:block/dynamic`（不再生成 16 条 `when{filling: redstone_N}`）：

- 模型按坐标查旁路表：红石条目发 `liquidphase:block/redstone_phase_<power>` 子模型
  （16 档颜色）；元件条目发 `liquidphase:block/element_*` 子模型。
- 元件几何按逐顶点采样宿主碰撞形状顶面高度（`VoxelShape.max(Y, x, z)`）抬升，
  再按 facing 做 y 轴旋转；五官/输出口/杆因此画在宿主表面上层。
- ≤1.21.1 的 `emitBlockQuads` 自带 `BlockAndTintGetter + BlockPos`，可直接发几何；
  1.21.10+ 的 `BlockModelPart.emitQuads` 拿不到坐标，所以改为包装宿主 `BlockStateModel`，
  并在 `createGeometryKey` 里带上 extra/pos（否则不同格会共用缓存）。
- 子模型由 `tools/gen_element_models.py` 从客户端 jar 复制原版模型（23 个文件：
  22 个元件模型 + `dynamic.json`）；红石外壳 16 份模型/贴图保留为子模型。
- 1.19.4–1.21.11：所有相宿主方块（**含 `filling=none` 状态**）的 `ItemBlockRenderTypes`
  图层从 SOLID 升为 CUTOUT（红石外壳是透明底贴图）；26.3 由贴图透明度自动推导。
  ⚠️ 这是整块方块的取舍：失去原版 SOLID 的 mipmap，已写进 README 已知限制。

> ⚠️ **宿主 part 不要补"全部 8 个相"的条件**（0.1.10 及以前的做法）。
> 原版 `KeyValueCondition.instantiate` 发现条件覆盖了属性的全部取值时，会直接返回
> 恒真谓词并打 `Condition ... is always true` 警告——**每个方块状态一条**，
> 1.21.10+ 加载日志直接被刷屏。普通宿主本来就不需要这个条件。

> ⚠️ **外壳必须按功率着色**（0.1.11 修，0.2.0 保留）。16 个功率档位各有一份贴图，
> 亮度沿用原版 `RedStoneWireBlock.COLORS` 的曲线（`f = p/15`，
> `g = f*0.6 + (f>0 ? 0.4 : 0.3)`）：0 档 (76,26,22)、15 档 (255,86,72)。
> 现在子模型 id 由旁路表的 power 选择，但调色板仍是这 16 份。
>
> **为什么不用 `tintindex` + 客户端 BlockColors**：tint 是按"模型的贴图索引"生效的，
> 而宿主自己的模型大量使用 `tintindex 0`（树叶的植被着色就是 0），外壳如果也用 0，
> 树叶的绿色会给粉尘染色；用 1 又要求每个宿主都有第二个 tint source，
> 1.19.4–1.21.1 的 `BlockColor` 接口（单 source、按 index 分派）表达不了这个区分。
> 静态生成 16 份调色板贴图在任何版本上一致，也没有 API 差异。

> ⚠️ **覆盖面 = 运行时真的拿到 `filling` 属性的方块**（0.1.12 起硬校验）。
> 0.1.9 把"只给 41 个代表宿主生成"改成"全部宿主生成"，但当时的 id 清单本身漏登记了
> 一批方块（1.21.10/11 漏 24 个铜方块、26.3 漏 197 个、1.19.4/1.20.1 各漏 `barrier`），
> 那些方块含相了却什么都看不到——这就是 0.1.12 修的根因。0.2.0 起每组生成清单为
> **311/313/340/413/413/508**，新增传送门三件套；`tools/gen_phase_blocks.py` 用
> 运行时注入数硬校验，少一个直接让构建失败。回归检查见 `tools/verify_jars.py`：
> 扫描 jar 内全部产物，断言每个宿主 blockstate 都有无条件 `dynamic` part、
> 16 份外壳模型/贴图存在，并反向断言清单里每个 id 都有产物。

> ⚠️ **红石相/元件是"宿主 + 动态部件叠加"，不是"动态部件取代宿主"**（0.1.7 的实机 bug）。
> 曾经把宿主 part 限制成 `filling != redstone_*`，结果含红石相时**宿主根本不渲染**，
> 整格只剩一个 16³ 粉尘外壳：楼梯/台阶的形状消失、整格变成不透明粉尘面。
> 0.2.0 的动态模型只追加几何，宿主 part 保持常驻；回归检查：
> `python tools/check_redstone_render.py`（断言红石相下"宿主 part + 动态 part"都在，默认相 `ice`）。

> ⚠️ **multipart 的 `when` 没有 `"a&b"` 这种语法**（0.1.6 的实机 bug）。
> 原版解析器（`KeyValueCondition` / `CombinedCondition`）的规则是：
> 一个 object 内的多个键 = AND，值里的 `a|b` = 同一属性的 OR，
> 跨属性组合必须显式写成 `{"AND": [...]}` / `{"OR": [...]}`，且一个 object 里
> **不能**同时出现 `AND`/`OR` 与属性键。写成 `"(a)&(b)"` 会被当成一个属性取值去查，
> 抛 `Unknown value`，**整份 blockstate 加载失败**，该方块所有状态退化成 missing model
> （紫黑方块 + `Missing model for variant` 刷屏）。合并条件一律走
> `and_condition()`；生成器结尾有 `validate_result()` 自检，非法条件直接让构建失败。
> 0.2.0 的 `dynamic` part 不带条件，不受这条影响；材料相覆盖层仍按 `filling` 取值生成条件。

> ⚠️ **外壳子模型必须带显式 `uv: [0,0,16,16]`（0.1.14 修，0.2.0 保留）**：26.3 的
> `FaceBakery` 按 quad 的 UV 区域计算贴图透明度，自动 UV（=元素包围盒）会因
> -0.01..16.01 的外扩几何出界抛 `IllegalArgumentException`，整个 blockstate bake
> 失败、该宿主全部状态回退，相覆盖层与红石外壳全部不渲染（26.3 实测 639,600 条
> bake 警告）。显式整图 UV 命中 `SpriteContents.computeTransparency` 的快速路径。
> 渲染探针必须逐 API 时代实跑（见 docs/host-coverage-audit-v2.md §3.6）。

外壳模型/贴图由 `tools/gen_redstone_assets.py` 生成（16³ 外壳外扩 0.01px，贴图是
**短碎屑均匀铺满整面**的疏松红石纹理，16×16 透明底、约 29% 覆盖、跨格可拼贴；
每个功率档位一份调色板）。

#### 剔除面（0.1.10 修，两处根因）

**根因 A：内缩覆盖层/外扩外壳不该带 `cullface`。**
原版 `cullface` 的语义是"邻居把**方块边界上的这一整面**盖住了 → 我省掉这面"，
它用 `Block.shouldRenderFace(self, neighbour, dir)` 实现，比较的是**方块边界**上的面。
相覆盖层是 0.25 内缩的立方体（面在 y/z=0.25..15.75），红石外壳是 -0.01..16.01 外扩；
它们的面**都不在方块边界上**。对台阶/楼梯这类**部分覆盖**的邻居，原版精细化判断会把
本不该剔的面剔掉——实测 `oak_stairs+obsidian` 对 slab/stairs 邻居 `dir=UP` 返回 false，
而 y=15.75 处的覆盖层顶面根本没被邻居挡住。这就是"剔除面不正确"的物理根因。

做法：`phase_obsidian/ice/frosted_*`（内缩）与 `redstone_phase_*`（外扩）模型**全部去掉
`cullface`**。去掉后 quad 进入 `getQuads(null)`（不可剔除面），原版分块构建器会照常
绘制（`ModelBlockRenderer` 在方向循环之后单独处理 `null` 方向）。0.2.0 的动态模型
直接从旁路表发 quad，同样不参与静态 `cullface`。`phase_*_full`（0..16 贴边）仍保留
`cullface`，但 0.1.18 起**没有任何 blockstate 再引用 `_full`**——它只作为旧存档/旧包的
兼容资源保留。回归：`tools/verify_jars.py` 的 `cullface=` 列 + 自检的模型断言。

**根因 B（0.1.9 的判断是错的，0.1.12 已删除对应 mixin）：`skipRendering` 与本问题无关。**
`BlockState.skipRendering` 只在**带 `cullface` 的 quad** 上参与判定
（`ModelBlockRenderer` 只对 `getQuads(direction)` 里的 culled quad 问
`Block.shouldRenderFace`；`getQuads(null)` 的无剔除 quad 直接绘制）。
相覆盖层、红石外壳与动态元件几何从根因 A 起**全部不带 `cullface`**，所以它们根本不会进入这条路径。
0.1.9 曾加 `PhaseSkipRenderingMixin` 强制"含相状态不参与剔除"，那唯一的效果是让
**宿主自己**的带 `cullface` 面（楼梯 29 面、栅栏 4 面……）在含相时不再被邻居剔除，
属于改坏原版语义且与本问题无关——**0.1.12 已删除该 mixin 与
`PhaseRenderSupport.allowVanillaCulling`**。不要重新加回来。
回归：自检断言所有相覆盖层/外壳模型都没有 `cullface`
（`checkPhaseRenderCulling`），这才是"相不会被错误剔除"的唯一前提。

> ⚠️ **遮挡/透光仍按宿主原形状**，不要把含相状态的遮挡换成整格：覆盖层内缩 0.25px、
> 外壳只有约 29% 像素，换成整格会让邻居的面凭空消失、还会吃天光。固相（冰/霜冰/黑曜石）
> 的碰撞已经是整格（决策点 D7），那是碰撞口径，与遮挡形状分开。

#### 渲染层（0.1.10 修；0.1.11 补移动方块）

红石外壳是透明底 cutout 贴图（约 29% 不透明像素）。**带相状态在 SOLID 层会被直接丢弃
透明像素**，整格宿主（树叶/铜格栅/红树根）看起来就是"看不到红石外壳"——这是
0.1.9 修复无效的真正原因。

> ⚠️ **不能在 `ClientModInitializer` 里用 `BlockRenderLayerMap.putBlock`。**
> 那张表由原版资源重载重建；实测初始化时 `oak_leaves` 被识别为 SOLID 并写入 CUTOUT，
> 但进入世界后 `getChunkRenderType` 仍返回 SOLID（初始化写入被覆盖）。
> 正确做法：`mixin/client/PhaseRenderLayerMixin` 直接注入 `ItemBlockRenderTypes.getChunkRenderType`。
> **0.2.0 的规则是：只要状态带 `filling` 属性（含 `filling=none`）且原图层为 SOLID → CUTOUT**，
> 因为红石/元件是否存在已经不能从状态里看出来，不能再按"带相"筛选。
> CUTOUT/CUTOUT_MIPPED 保持；TRANSLUCENT（玻璃板/染色玻璃）保持——改 cutout 会让半透明像素变实。
> 代价是全部相宿主的所有状态失去原版 SOLID 的 mipmap（README 已知限制）。
> 26.3 没有 `ItemBlockRenderTypes`，由贴图透明度自动推导，故 `liquidphase.layer.mixins.json`
> 只在 1.19.4–1.21.11 接线（`build.gradle.kts` 的 `extra_mixins_layer`）。

> ⚠️ **`getMovingBlockRenderType` 必须一起改**（0.1.11 修）。下落方块
> （`FallingBlockEntity`）和活塞推动中的方块走那条路径，它按 `TYPE_BY_BLOCK` 的原始图层
> 返回 `RenderType.solid()` / `RenderTypes.solidMovingBlock()`，同样丢弃透明像素，
> 于是"含红石相的下落方块"渲染成一坨不透明方块。两条注入点都在同一个 mixin 里。

#### 隐形 / 方块实体宿主（0.1.11 修）

区块构建器只在"该渲染方块模型"时才请求模型，判据随版本不同：
1.19.4 / 1.20.1 用 `!= RenderShape.INVISIBLE`（所以 `ENTITYBLOCK_ANIMATED` 的箱子本来就会渲染），
1.21.1 起改成 `== RenderShape.MODEL`。默认返回非 "可渲染" 的宿主全都能含相，
但它们的相**永远不会被绘制**：

| 宿主 | 原生 RenderShape | 版本 |
| --- | --- | --- |
| `barrier`、`light` | `INVISIBLE` | 全版本 |
| 告示牌 / 悬挂告示牌全家 | `INVISIBLE` | 全版本 |
| `decorated_pot` | `INVISIBLE` | 1.20.1–1.21.1 |
| `chest`、`trapped_chest`、`ender_chest`、`conduit` | `ENTITYBLOCK_ANIMATED` | 1.19.4–1.21.1（1.21.10 起是 MODEL） |

做法：`mixin/PhaseRenderShapeMixin` 在状态级 `getRenderShape` 的 `RETURN` 上判定，
**只要方块带 `filling` 属性且原值不是 MODEL → 返回 MODEL**（0.2.0 起不再区分"带相状态"：
红石/元件存在性在状态里看不出来）。

- 注入点必须是 `RETURN`：`HEAD` 里再问 `getRenderShape()` 会无限递归，而块级
  `getRenderShape(state)` 是 `protected`、mixin 拿不到。
- 不会让宿主凭空多出方块：这些方块的可见几何来自方块实体渲染器，那条路径
  （`hasBlockEntity` → `BlockEntityRenderDispatcher`）与 RenderShape 无关；它们自己的
  blockstate 模型只有 particle 贴图、没有 `elements`，所以最终多出来的只有本 mod 的
  相覆盖层 / 动态几何。
- 没有 `filling` 属性的状态不命中，完全走原版。

#### 双端同步（0.1.9 修）

功能三（流体射线、本地瞬挖、液体物品放置）是**客户端行为 + 服务端权威**。客户端必须
**先确认服务端支持相**，否则连原版服务器时会单方面预测出服务端不存在的状态
（把线格改成含红石相宿主、把桶换成液体物品），表现为闪一下又回弹、物品数目对不上。

| 情形 | 握手 | 客户端行为 |
| --- | --- | --- |
| 单人游戏 | 集成服务器照常发包 | 到达前按"不支持"处理，之后正常 |
| 连有本 mod 的服务器 | 收到 `liquidphase:handshake` | 启用功能三 |
| 连原版服务器 | 永远收不到 | 全部退回原版行为 |

**旁路表同步（0.2.0）** 与握手是两套独立通道：

- 客户端 `CHUNK_LOAD` / 握手到达 / `Level` 更换 时发 `extra_request`，服务端回
  `extra_chunk`（**合并**语义；空表也回，用来清掉客户端残留）。握手到达时已经加载的
  区块不会再有 `CHUNK_LOAD`，所以按视距补一轮请求。
- 服务端每次写表把 `extra_update` 发给该维度**全部**玩家，**不要**改成
  `PlayerLookup.tracking`：它只覆盖"已追踪该区块"的情况，而写入可能发生在玩家刚开始
  追踪、服务端追踪表尚未更新的窗口里；区块请求只发一次，丢了就永久丢失。
- 客户端渲染期拿到的是 `RenderChunkRegion` 快照而不是 `Level`，`PhaseExtras`
  用 `storageResolver` 解析到当前 `ClientLevel`；应用完数据要标脏该 section。
- 客户端 `LevelChunkExtraMixin` 在客户端侧**不清表**：纯镜像，清理由服务端广播的
  `extra_update(0)` 驱动，否则区块数据重放顺序会把条目抹掉。

- 服务端 `ServerPlayConnectionEvents.JOIN` 时发 `PhaseHandshakePayload`（1.19.4/1.20.1 用裸
  `FriendlyByteBuf` 通道，1.21.1+ 用 `CustomPacketPayload`；注意 26.3 的
  `PayloadTypeRegistry` 改名为 `clientboundPlay()`）。
- 客户端收到就置 `PhaseSyncState.serverSupportsPhases()`；`DISCONNECT`/`JOIN` 都复位。
- 所有**会改世界状态**的客户端路径都用 `PhaseSyncState.canMutateWorld(level)` 门控
  （`BlockItemMixin`、`PlacementMixin`、`BucketItemMixin`、`BucketItemUseMixin`、
  `RedstoneWireReplaceMixin`、`FallingBlockIntoWireMixin`、`ClientLiquidMineMixin`、`EntityPickMixin`）。
  `SlabLiquidMixin`/`HostPlaceLiquidMixin` 不需要门控：它们接的是
  `FlowingFluid.spreadTo` 这条服务端专属引擎路径，客户端预测不会走到。
  服务端侧永远为真（自己的配置就是权威），所以**单人/多人/原版服务器都不影响方块状态同步**。
- 新增 `selftest` 断言：握手掩码、默认不支持、渲染剔除边界。

### 3.3 红石相的两向生成

- 红石粉点宿主 → 该格写入旁路表条目 `PhaseExtra.redstone(0)`（"单独放置"口径，
  `BlockItemMixin` 分支 a0），并把 `filling` 清回 `none`；0.1.21 及以前直接写 15。
- 相容宿主方块落到红石线所在格 → 宿主写进该格、条目功率沿用原线。
- 元件相的放置不是"把物品放进线格"，而是**潜行右键宿主上表面**：
  `BlockItemMixin` 在 `place` 之前拦截，`ElementBlocks.hasCavity` 通过后写
  `PhaseExtra.element(...)`；普通右键保持原版"元件放在宿主上面一格"。

**关键：放置能力属于"方块放置流水线"，与玩家、手、`InteractionHand`、潜行都无关。**

根因只有一个：`BlockItem.place` 的第一步

```java
if (!context.canPlace()) return FAIL;
```

`canPlace()` 只问"目标格现在能不能被替换"，而红石线不是可替换方块 → 直接 FAIL。
**而且这个问题不止出现在 `place` 入口**：`ScaffoldingBlockItem.updatePlacementContext`
会自己沿方向搜索落点、搜索时问的是同一个 `canBeReplaced`，红石线格会被它直接跳过、
方块落到别处。只在 `BlockItem.place` 入口上做手脚覆盖不到这类物品。
（曾经这样做过，`WirePlaceContext` 是那条错误路线的残留，已删除。）

**两个 `canBeReplaced` 重载别搞混**（这是 0.1.9 之前的判断失误）：

| 重载 | 谁在用 | 作用 |
| --- | --- | --- |
| `canBeReplaced(BlockState, BlockPlaceContext)` | `BlockItem.place` / `ScaffoldingBlockItem` / `SlabBlock` / `SnowLayerBlock` / `VineBlock` 等 | 「这个方块能不能被**要放的物品**替换」 |
| `canBeReplaced(BlockState, Fluid)` | 水/熔岩流动（`FluidState.canBeReplacedWith`） | 「流体能不能**流进**这一格」 |

**改前一个不会影响水流和活塞。** 曾经误以为会，于是绕开它去改放置上下文，
结果脚手架这类"自己搜索落点"的物品漏掉了。

正确做法是在 `canBeReplaced(BlockState, BlockPlaceContext)` 里回答：

```java
if (state.is(REDSTONE_WIRE) && context.getItemInHand() 是 PhaseCapable 方块物品) return true;
```

`context.getItemInHand()` 读的是**放置上下文自己携带的待放物品**（玩家右键时来自手上、
其它来源来自被投放的物品，是同一个字段），**与玩家实体、手、潜行无关**——
`getPlayer()` 为 `null` 时行为完全一样。

红石相本身由 `BlockItemMixin.placeBlock` 的 `@ModifyVariable` 补：
真正写方块前，如果**目标格此刻仍是红石线**，就把宿主状态写成无相、并写入
`PhaseExtra.redstone(<原线功率>)`。用"目标格现在是线"而不是"上下文里带标记"，
所以无论是直接进 `place` 还是物品先搜索落点再进来，判定都成立。

其余流程原封不动：`updatePlacementContext → getStateForPlacement → placeBlock →
setPlacedBy →` 音效/统计/扣物品；朝向、半砖、连接态、含水全部由宿主自己的
`getStateForPlacement` 按线格算出来。

**不要**接管红石线自身的 `use`/`useWithoutItem`：那是方块交互，会把点击路径写进逻辑。
玩家点线本身时原版本来就会先切换点/十字形状，这个行为要保留（见 §5 测试矩阵）。

#### 三个容易踩的坑

1. **注入必须挂在状态级 `BlockStateBase.canBeReplaced(BlockPlaceContext)`，不能挂块级。**
   块级 `BlockBehaviour.canBeReplaced(state, context)` 会被宿主的覆写遮蔽：
   `ScaffoldingBlock`、`SlabBlock`、`SnowLayerBlock`、`VineBlock`、`SeaPickleBlock`、
   `CandleBlock`、`MultifaceBlock`、`BedBlock`、`DoorBlock`、`TurtleEggBlock`、
   `MossyCarpetBlock`、`FlowerBedBlock`、`LeafLitterBlock`、`SegmentableBlock`、
   `PitcherCropBlock`、`GrowingPlantBodyBlock`、`DoublePlantBlock`、`WallSkullBlock`
   都自己声明了这个方法。块级钩子对它们完全不生效（脚手架堆叠就是这么坏掉的）。

2. **下落方块（`FallingBlockEntity.tick()`）不经过 `BlockItem.place`。**
   它自己调
   ```java
   landing.canBeReplaced(new DirectionalPlaceContext(level, pos, DOWN, ItemStack.EMPTY, UP))
   ```
   然后直接 `level.setBlock(pos, this.blockState, flags)`（26.3 是 `setBlockAndUpdate`）。
   注意上下文里是 **`ItemStack.EMPTY`**——没有"手里的物品"可读，所以按物品判断的钩子
   对这条路径永远返回 false。处理方式见 `mixin/FallingBlockIntoWireMixin`：
   仅当**下落方块本身是 `PhaseCapable`** 时，把那个空物品上下文换成用下落方块自己的
   物品构造的上下文；其它下落方块（沙子/砂砾/混凝土粉末）保持原样，仍会在线上弹成物品。
   落位写方块时也要在这里补 `PhaseExtra.redstone(<原线功率>)`，因为 `BlockItem.place`
   那条钩子这条路径根本不触发。

3. **`@Redirect` 用 `require = 1`，不要用 `require = 0`。**
   两个注入点原版每个版本都有；`require = 0` 会让"某版本没命中"变成静默通过，
   表现为"构建成功、实机不生效"。


### 3.3.1 信号结算时机

`RedstoneBlocks.neighborChanged` 是**即时**重算（`updatePower`），不排计划刻——
排计划刻会让开关/拆线延迟两刻才反应。防递归靠“只有功率真的变了才 `PhaseExtras.set`”，
与原版 `DefaultRedstoneWireEvaluator` 同一手法。

元件按原版计划刻节奏：中继器 `delay+1` 红石刻、比较器 2 tick、侦测器观察面变化后
2 tick 翻转 `powered`、再 2 tick 复位；中继器侧向邻居变化只重算 `locked`。
因为元件不占状态，`updateShape` 必须挂在状态级分发点（`ElementShapeUpdateMixin`），
否则宿主自己的覆写会把元件更新整个遮掉。

### 3.7 资源包兼容合并（0.1.17）

第三方包覆盖宿主 blockstate 时，客户端把片段（`assets/liquidphase/phase_parts/<id>.json`，
生成器与烘焙 blockstate 同源产出）合并进**获胜定义**，包只生效于宿主 part。
要点与踩过的坑：

- **两条管线、一个注入点**：≤1.21.1 是 `ModelManager.loadBlockStates`（私静）、
  1.21.10/1.21.11/26.3 是 `BlockStateModelLoader.loadBlockStates`（公静），
  两者都收 `(ResourceManager, Executor)` 且只经 `listResourceStacks("blockstates", …)`
  枚举资源栈（栈序低→高、"最后者覆盖"）。`PhaseLoaderMixin` 在入口把参数换成
  `PhasePackMerge.wrap` 的 `Proxy`（按实参/结果形状分派、其余透传）——26.3 把
  `listResourceStacks` 第二参从 `Predicate` 换成 `Selector`、`getResource` 返回
  `Optional`，代理分派不看签名细节，全部免分支。
- ⚠️ **动态代理的分派绝不能按 `method.getName()`**（0.1.20 修，实机 bug）：方法名是
  开发环境的 Yarn 名（`listResourceStacks`），生产环境是 intermediary 名
  （1.21.11 实测 `method_41265`），loom 只重映射 mixin 注解里的目标、**不会重映射普通
  Java 字符串**。0.1.19 及以前按名比较，生产（launcher）环境必然失配——表现：日志有
  "pack merge active" 却永远没有 "blockstate merge scan"，第三方包覆盖宿主 blockstate
  时相部件整体丢失（熔岩相走原版流体渲染不受影响）。dev 环境所有 gametest 都复现不了，
  因为 dev 跑的是 named 映射。现在分派只看**运行时实参**（首参为字符串 `"blockstates"`
  的两参调用）与**结果形状**（值是 `List` 的 Map，区别于 `listResources` 的单资源表），
  两者都是映射无关的运行时值。同类隐患：`HopperTransferMixin` 反射按字段名含 "ooldown"
  找冷却字段——生产环境 intermediary 字段名（`field_xxxx`）匹配不上，会静默退化为
  "无冷却重试"（有降级语义、非本次症状根因，待专门处理）。
- ⚠️ **stonecutter 分支的 live 代码必须是 1.21.11 的**：active 版本直接编译原始源码、
  `//?` 分支只是注释。`PhaseLoaderMixin` 的 live 分支写错成 `ModelManager` 时，
  1.21.11 客户端启动即 `Critical injection failure: could not find any targets matching
  'loadBlockStates'`（实机踩过）。
- ⚠️ **资源栈的 key 是文件 id**（`minecraft:blockstates/oak_stairs.json`），不是方块 id——
  直接拿它查 BLOCK 注册表恒为 null、全部宿主静默跳过（实机踩过）。先剥
  `blockstates/` 前缀与 `.json` 后缀再查注册表/取片段。
- **合并语义**（`PhasePackMergeCore`，纯 Gson、无 MC 依赖，服务端自检可直接跑）：
  获胜定义是 variants → 转等价 multipart；片段 `gates` 给获胜宿主 part 合并
  `filling` 条件。0.1.18 起**生成器不再产出任何 gate**（所有宿主都保持常驻），
  所以正常路径下 gates 为空；Java 侧仍保留 gate 匹配实现（含空 when gate 命中
  全部 part、非空 gate 精确匹配），以便旧 jar/旧片段或针对本 mod 的包继续工作
  （0.1.21 起生成器侧的同名 `and_condition` 已随 gate 产出退役，条件合并逻辑只此一份）。
- **跳过条件**：栈顶定义引用 `liquidphase:` 模型（= mod 烘焙的或针对本 mod 的包）→
  不合并（重复追加部件会把覆盖层画两遍）；宿主无片段（第三方 mod 的相容宿主）→
  静默跳过。
- **排查入口**：每次资源重载都会打一行
  `blockstate merge scan: N stacks, M phase hosts (A merged, B already-baked, C no-fragment, D bad-json, E unmergeable)`。
  合并没生效时先看这一行：`M=0` 说明没有任何资源包覆盖宿主 blockstate；`C=M` 说明片段缺失；
  完全看不到这一行则是 ResourceManager 没被包装（`packMerge=false` 会单独打一条说明）。
  该行 0.1.19 起才有；此前只打"成功/失败"两组列表，全部静默跳过时一条日志都没有。

- **性能**：只在资源重载时对"相容宿主且栈顶无 liquidphase 引用"的栈读一次栈顶 JSON，
  正常路径零写入。配置 `packMerge=false` 关闭。
- **验证**：服务端自检 `checkPackMergeFragments`（片段完整性全量扫描 + fixtures 合并
  断言 + 代理幂等）；1.21.11 实机：含 oak_stairs/stone_slab/oak_leaves 的第三方包 →
  `merged phase overlays into 3 foreign blockstate(s)`，无 `Unknown property 'filling'`。

### 3.4 自动化自检

#### 3.4.0 客户端渲染回归（1.21.11 `runClientGameTest`）

服务端自检只能看 jar 里的 blockstate JSON，看不到"真实客户端烘焙出来的模型"。栅栏是最典型的
"形状表型宿主"（`FenceBlock` 自建 occlusion 形状表、1.21.10+ 在 `getOcclusionShape` 里手工查表），
所以单开一个**真实客户端**回归：

```bash
./gradlew :1.21.11:runClientGameTest
```

- 代码：`src/main/java/org/liquidphase/client/FenceRedstoneRenderTest.java`（`fabric-client-gametest`
  入口，由 `build.gradle.kts` 的 `extra_gametest_entrypoint` 只对 1.21.11 注入 `fabric.mod.json`）。
- run config：`build.gradle.kts` 的 `loom.runs.create("clientGameTest")`，运行目录
  **`versions/1.21.11/run-clientgametest/`**（刻意与 `run/` 分开：新目录里没有 `resourcepacks/`，
  等于"无资源包"的干净客户端）。日志与截图都在该目录下，已加进 `.gitignore`。
- 必须带 `-Dfabric.client.gametest.disableNetworkSynchronizer=true`：本 mod 的握手包走自定义通道，
  gametest 的网络同步器会判成 `interfacing with packets at a lower level` 并让 run 失败。
- 场景一：地板 + 一列含相栅栏（先写红石条目 0 档，再由紧邻的真实红石线算出功率）+ 两段无相栅栏对照，
  另有一处 0 档（无红石线）对照。断言逐格检查：
  1. **宿主 quad 和外壳 quad 都在**（红石相是叠加，不能让宿主消失，也不能没有外壳）；
  2. 区块图层不是 `SOLID`（外壳透明底，SOLID 会丢像素）；
  3. 外壳 quad 顶点落在本格内（方块空间 0..1，宽松量 0.01px 外扩）。
- 场景二（元件）：在下半砖/楼梯上放中继器、比较器、侦测器，断言：
  1. 三类元件的动态 quad 都存在；
  2. 元件顶点 y ∈ [0.5, 1.0]（抬到宿主顶面之上，不埋进方块）；
  3. 中继器能被紧邻的真实红石块点亮（`powered` 翻转）。
- 每次会落三张截图到 `screenshots/`：斜视、近垂直俯视、0 档对比。

> ⚠️ 只在 **1.21.11** 接线：`BakedQuad.position(int)` 是这一版才有的 API（1.21.10 的顶点还打包在
> `int[] vertices` 里），其余版本的渲染覆盖仍靠服务端自检 + `tools/verify_jars.py`。
>
> ⚠️ 截图里**地面上的红色碎屑属于原版红石线**，不是本 mod 的外壳几何。判定方法（本轮实测）：
> 把旁路条目写成 0 档后外壳变暗红、栅栏表面随之变暗，而地面碎屑亮度不变；而且"只放线不放栅栏"
> 与"有栅栏但隔一格无线"两种场景下地面都没有碎屑。

#### 3.4.1 服务端自检

`-Dliquidphase.selftest=true`（或环境变量 `LIQUIDPHASE_SELFTEST=true`）启动服务端会在
`ServerLifecycleEvents.SERVER_STARTED` 跑 `selftest/PhaseSelfTest`，断言数为
**约 341（0.2.0；1.21.11 实测 341，其余版本可能 ±1，以实测日志为准）**。
含 0.1.20 的分派识别回归、0.1.22 的组装初始状态与组合碰撞断言，以及 0.2.0 新增的
旁路表读写、红石 updatePower、元件放置空腔判定、dynamic part/外壳资源存在性断言。
各版本已经收敛到几乎相同，因为断言改成**全量扫描**而不是写死几个代表方块：
凡是"原生 RenderShape 不是 MODEL 的 phase 宿主"都会被逐个验证，各版本有几个就查几个
（实测 1.20.1 有 **51** 个，1.21.11 只有 **2** 个（1.21.11 起是 `barrier`/`light`；1.19.4/1.20.1
用 `light` 顶上，这两个版本的 barrier 不再是宿主，见 §7 第 8 条）——原版 1.21.10 起
把告示牌/箱子改成了 MODEL）。写死候选清单时只查了 6 个，漏掉的就是这类。覆盖内容：
相枚举/相位属性全覆盖、宿主方块身份、选择框/碰撞/遮挡、面露出判定、
向红石线 -1 / 向固相满功率 / DOWN 不输出 / 被覆盖面不输出、供电即时性（邻居变化同 tick 生效）、
**红石条目组装初始状态与结算**（0.1.22，按"是不是重新放置"区分初始状态）：
  新放置（点粉、改写层写线状态）初始功率 0，吸收（宿主落进线格）初始功率沿用原线；
  以初始状态落地后由 `BlockStateBaseMixin.liquidphase$onPlace` 一律立即按邻居重算
  （镜像原版红石线 onPlace → updatePowerStrength），不重算状态就错了；
红石线格放置（**两层**：无玩家直接 `BlockItem.place` + FakePlayer 走
`ServerPlayerGameMode.useItemOn`）、统一用例，脚手架堆叠（物品自己搜索落点那一类）、
下落方块落位（相容宿主落进线格带相；沙子仍被弹开）、
红石线可替换性的精确边界（宿主放行、石头/红石粉仍拒绝）、炼药锅满锅取水门槛、
**每个 phase 方块都有动态渲染入口**（遍历全部 `PhaseCapable`，断言 blockstate
里含无条件 `liquidphase:block/dynamic`，且 16 份 `redstone_phase_*` 模型/贴图存在——
这是 0.1.12 根因在方案 C 下的回归断言，清单漏登记时立刻失败并打印漏掉的方块 id）、
**随包清单 == 运行时集合**（双向集合差，防止"清单两边各错一个、数量仍相等"）、
**旁路表**（`PhaseStorage` 的 put/get/remove/区块替换/快照、`PhaseExtras` 关卡级读写、
  写入清 `filling`、`canAcceptExtra` 与 `canAcceptPhase` 的互斥边界）、
**元件放置空腔判定**（下半砖/下半楼梯接受，整格方块/上半砖拒绝）、
**blockstate 渲染资源**（楼梯宿主可见 + 动态 part；整格宿主/透明宿主都有动态 part；
  元件子模型 23 个文件存在）、
**模型几何**（内缩覆盖层/外壳子模型不带 `cullface`；`phase_*_full` 保留但不再被引用）、
**外壳按功率分档**（16 份模型/贴图互不相同，0 档与 15 档不同）、
**宿主 part 不带恒真 filling 条件**（否则 `Condition ... is always true` 刷屏）、
**整格宿主自动判定**（白杨树叶仍被识别为整格，但改用内缩覆盖层：`phase_ice` 不含 `_full`）、
**全部相宿主状态强制 MODEL**（所有带 `filling` 属性的状态都返回 MODEL；
  告示牌/箱子家族逐个验证）、
**相剔除**（相覆盖层/外壳模型都不带 `cullface`，原版不会剔除它们；
   **不要**再加"含相状态不参与 `skipRendering`"那类 mixin——0.1.12 已删除）、
**渲染层 mixin 配置**（仅非 26.3 接线）、
**双端同步**（客户端默认不支持、握手掩码正确；旁路表 `extra_request/chunk/update` 通道注册）、
**传送门宿主**（`nether_portal` / `end_portal` / `end_gateway` 拿到 `filling`；
  含固相时整格碰撞成立）、
**覆盖面回归（0.1.13，对应 audit §4）**：
  固相**组合碰撞**（0.1.22 起 = 宿主 ∪ 整格；全量扫描逐点比对，两参缓存路径 + 三参实体路径，
  栅栏的方块外碰撞柱有专门回归断言）、
  offsetType 宿主破坏保留（全量扫描）、台阶/蜡烛/营火/干化恶魂的遮蔽方法熔岩灌入
  （直接调 `canPlaceLiquid`/`placeLiquid`，与引擎同参）、
  glow_lichen/sculk_vein 放置吸收、熔岩桶右键优先级（FakePlayer 走
  `useItemOn`→（PASS 时）`useItem`：楼梯灌入、活板门被原版开关、光源方块保持原版交互）、
  台阶 blockstate 的变体拆分资源断言。
默认关闭，不进正式玩法路径。**改动红石相形状、渲染或信号语义后请先跑它。**

> 加 `-Dliquidphase.selftest.stop=true`（或 `LIQUIDPHASE_SELFTEST_STOP=true`）会在跑完后
> 直接关服，方便脚本一键跑 6 个版本（返回码仍以日志里的 `result: N passed, 0 failed` 为准）。

> 自检场地要放真实电源：信号即时化之后，手动 `setValue(POWER, 15)` 会被立刻重算回真实值。

> ⚠️ **放置类断言必须覆盖"物品自己搜索落点"那一类**（目前唯一实例是脚手架堆叠）。
> 只测 `BlockItem.place` 会漏掉 `updatePlacementContext` 里对 `canBeReplaced` 的调用，
> 表现为"自检全过、实机脚手架堆不进去"（0.1.8 踩过）。
>
> ⚠️ **反向断言同样必须存在**：非宿主方块仍放不进线格、红石线自身 `canBeReplaced()` 仍为 false、
> 红石粉放置不受影响。否则无法区分"精确放行"和"全局放宽"。
>
> **点线本身仍会被原版挡下**（`useWithoutItem` 切换点/十字形状），这是预期行为，不是 bug。

## 4. mixin 目标核对流程

mixin **只能注入目标类自己声明的方法**。每次升版本必须重跑核对（方法论照搬 Lavalogged 的 `docs/static-check.md`）：

1. 用 `git -C <guardian> ls-tree -r <commit> --name-only | ugrep '<关键词>'` 定位源码；
2. 对闭包内每个类检查是否声明 `updateShape` / `getStateForPlacement` / `useItemOn`（1.21.1+）/ `use`（≤1.20.1）/ `neighborChanged`；
3. 生成"实现类闭包 + 方法声明矩阵"，据此增删 `@Mixin({...})` 里的类；
4. 对同名重载（如 `shouldConnectTo(BlockState)` 与 `shouldConnectTo(BlockState, Direction)`）使用**完整描述符**指定目标。

各版本 commit：1.19.4=`37641fc907`、1.20.1=`41c32f0060`、1.21.1=`d47305d7cf`、1.21.10=`5d7d486906`、1.21.11=`192e7132b4`、26.3=`d5822ca2a1`。

## 5. 测试矩阵（每版本一遍）

| 用例 | 期望 |
| --- | --- |
| 启动服务端 | 日志出现 `injected phase property into 413 blocks (total states: 176531)`（0.2.0 / 1.21.11，其他版本以实测为准；各版本注入数 311/313/340/413/413/508），有存档时出现 `loaded N side-table entries`，无 mixin 报错 |
| 熔岩桶 → 楼梯 | 出现含熔岩相；整格发光 15；可流出/被流入 |
| 熔岩桶 → 活板门 / 箱子 / 光源方块 | **原版交互优先**（开关活板门/开箱/调亮度），不灌入——与原版水桶同规则；潜行右键灌入；各版本一致（0.1.13 起，audit §4.7） |
| 熔岩贴着台阶/蜡烛/营火 | 按形状灌入（0.1.13 起，audit §4.3）；含水的也灌入并清掉水（0.1.17 可替换填充相规则） |
| 水 → 含熔岩 | 变为含黑曜石相 + 嘶声（邻居凝固路径不变；水桶直接浇含熔岩相宿主仍拒绝，0.1.17 决策） |
| 破坏含冰楼梯 | 掉楼梯 + 原地留冰；准星瞄楼梯就按楼梯速度挖，瞄相才按冰算 |
| 破坏含黑曜石楼梯 | 瞄露出的黑曜石部分需钻石镐；瞄楼梯本体正常速度；破坏后留黑曜石 |
| 树叶含水/含冰 | 冰相显示内缩覆盖层、树叶宿主仍可见；`filling=none` 时显示树叶原貌 |
| 含水楼梯 + 低温降水 | 冻结成含冰相；光照够时融回水相 |
| 空桶左键水源 | 掉"水"物品；右键放流体；无桶返还 |
| 空桶左键水锅/熔岩锅 | 锅保留为空锅、掉 1 个液体物品；**未满水锅不响应**；右键锅不再取水、桶不消失 |
| 液体物品倒空锅 / 未满水锅 | 照常填锅，消耗 1 个、不返还空桶 |
| 熔岩锅 + 水 / 水锅 + 熔岩 | 掉黑曜石 / 石头·圆石·台阶 |
| 混凝土粉末 + 水锅 | 混泥土 + 水位 -1 |
| 发射器/漏斗 | 填锅、抽锅（水锅满锅才抽）、液体物品进出 |
| 红石粉点楼梯 | 该格写入红石旁路条目（新放置：初始功率 0、落地即按邻居重算）；有电源时即 15。方块仍是原版楼梯、宿主模型可见、疏松红石碎屑铺满整面；破坏留红石线 |
| 楼梯放进红石线格 | 该格换成楼梯并写入旁路条目，朝向/半砖跟随点击面；初始功率**沿用原线**（吸收已存在的线不是重新放置），落地立即按邻居重算（多数情况与原线一致；电源在被遮蔽面上等分歧行结算为真实值） |
| 脚手架堆叠到线格 | 线格能被替换成脚手架（物品自己搜索落点那条路） |
| 相容宿主下落 | 钟乳石等 `Fallable` + `PhaseCapable` 方块落到线格，变成含红石相的宿主 |
| 普通下落方块 | 沙子/砂砾仍像原版一样弹成物品，红石线不被覆盖 |
| 点线本身（不相容物品以外） | 原版先切换点/十字形状，属于预期行为 |
| 非宿主方块进线格 | 仍然放不进去（石头/红石粉等保持原版行为） |
| 红石线自身可替换性 | `canBeReplaced()` 仍为 false；只对相容宿主物品放行上下文重载 |
| 红石相输出 | 指向相邻红石线时 -1；指向灯/活塞时满功率；被宿主覆盖的面不输出 |
| 红石相信号延迟 | 开关/拆线后**同一 tick** 就更新（不走计划刻） |
| 瞄准红石外壳挖掘 | 掉红石粉、原地留宿主；瞄准宿主露出部分则正常破坏并留下对应功率红石线 |
| 潜行右键放元件 | 手持中继器/比较器/侦测器，潜行右键下半砖/下半楼梯上表面 → 元件贴在宿主表面；普通右键仍是原版放在宿主上面一格 |
| 元件放置前提 | 整格方块/上半砖拒绝；已有填充相或旁路条目的格子拒绝 |
| 破坏元件 | 左键命中元件：清条目 + 掉落对应物品，宿主保留；破坏宿主时元件随宿主消失、不额外掉落 |
| 元件信号 | 中继器/比较器 FACING = 输入面，输出 = `facing.getOpposite()`；侦测器 FACING = 观察面，观察面邻居变化 2 tick 后翻 powered、再 2 tick 复位；被宿主覆盖的面不输入不输出 |
| 中继器锁存 | 侧向红石信号锁定后停止翻转（逻辑锁定；锁形视觉未实现） |
| 活塞推动含元件宿主 | 条目不随活塞移动（该格条目被清掉，元件丢弃） |
| 传送门宿主 | 下界门/末地门/末地折跃门可含材料相；含冰/霜冰/黑曜石时整格碰撞堵住传送门（预期） |
| 旁路表持久化 | 30 s 自动保存 + 关服保存；重启后红石功率/元件参数保持；`world/data/liquidphase_<dimension>.dat` 存在 |
| 旁路表同步 | 进入世界/加载区块/切维度后客户端能看到红石/元件；服务端写表后同维度所有玩家收到更新（含尚未开始追踪的玩家） |
| 旧存档 | 不兼容：`filling` 取值域变更，旧状态无法映射（预期，README 已写明） |
| 存档保存/重载 | 相保持 |
| 关闭 `phaseEnabled` | 旧存档的相丢失（预期，README 已写明） |

## 6. 发布流程

1. 6 版本全量 `build` 通过；
2. 启动服务端冒烟（`./gradlew :<版本>:runServer`）；红石相/炼药锅相关改动建议带自检跑一遍：
   `LIQUIDPHASE_SELFTEST=true ./gradlew :<版本>:runServer`（日志里应有
   `[selftest] result: N passed, 0 failed`，N 以当前断言数为准）；批量跑用
   `powershell -File tools/run_selftest_all.ps1`；
3. 收集 `versions/*/build/libs/liquidphase-*.jar`（不含 sources）到 `dist/`；
4. 更新 `README.md` 的版本支持与已知限制，然后提交。

> ⚠️ **发布前必须核对 jar 里真的是新代码**。踩过的坑：`versions/<版本>/build/libs/` 下可能残留旧版本的
> jar（同名同版本号、时间戳更早），Gradle 增量判定会因为输入未变而跳过 `jar`/`remapJar`，结果新 dist 拷的
> 还是旧包——表现为"装了新 jar 但 bug 依旧"。核对方法（以 1.21.11 为例）：
>
> ```bash
> ls -la --time-style=+%H:%M versions/1.21.11/build/libs/*.jar   # 看时间戳
> python -c "import zipfile;print(len(zipfile.ZipFile('dist/liquidphase-X+1.21.11.jar').read('org/liquidphase/phase/PhaseHelper.class')))"
> # 或直接反编译确认改动在：javap -p -c <解包后的 PhaseHelper.class> | less
> ```
>
> 必要时先 `rm` 掉 `build/libs/liquidphase-<版本>.jar` 再 `./gradlew :<版本>:build`。
> `build/devlibs/` 里的是**未 remap 的开发包**，不能作为发布物。

## 7. 已知限制（待办）

0. **启动崩溃已修**：宿主类覆写了块级 `getCollisionShape` / 状态级两参版本时，`BlockStateBase$Cache`
   构造器会拿到 null 形状并 NPE。现在有三道防线：
   ① `CacheShapeGuardMixin`（`Cache.<init>` 里把 null 换成空形状，`require = 0`）；
   ② `ShapeFallbackMixin`（状态级 outline/collision/occlusion/support/visual 兜底）；
   ③ `BlockBehaviourMixin`（块级兜底，带"仅带相状态/旁路条目所在格才兜底"的递归保护）。
   另 `PhaseStateRefresher` 对 `initCache()` 加了 try/catch，单个宿主异常只记日志不中断启动。
1. **红石相/元件相不再是状态取值**（0.2.0，方案 C）：`filling` 只保留 8 个材料类别
   （`none` / `lava` / `obsidian` / `ice` / `frosted_0..3`），红石功率（0–15）与元件相
   （中继器/比较器/侦测器）的全部参数压在旁路表 `PhaseExtra` 里。宿主方块本身不变，
   不再有组合方块注册、`COMPOSITE_TO_HOST` 表、`RedstonePhaseBlock` 标记接口。
   **旧存档不兼容**（属性取值域变更）。1.21.11 实测注入 413 个方块、总状态 176,531。
   完整表示法见 `docs/plan-c-elements.md`。
2. **红石相/元件宿主覆盖面**：动态渲染入口对**全部**相容宿主生成——口径是"运行时真的拿到
   `filling` 属性的方块"，0.2.0 各版本为 **311 / 313 / 340 / 413 / 413 / 508 个**
   （`tools/gen_phase_blocks.py` 的 `EXPECTED_COUNTS` 硬校验；0.2.0 新增传送门三件套）。
   含树叶/格栅/玻璃板/铁栏杆/梯子/锁链/铜栏杆/铜锁链/铜灯笼/墙告示牌/羊毛楼梯等。
   整格宿主（树叶/铜格栅/红树根/白杨树叶）也带动态 part。
   透明底走 cutout：0.2.0 起**所有相宿主方块（含 `filling=none` 状态）**在默认图层为 SOLID 时
   升级成 CUTOUT，半透明宿主（玻璃板/染色玻璃）保持 TRANSLUCENT；下落方块与活塞推动走
   `getMovingBlockRenderType`，同样从 solid 升级成 cutout。
   代价是失去原版 SOLID 的 mipmap（视觉/性能取舍，README 已知限制）。
   1.19.4–1.21.11 由 `PhaseRenderLayerMixin` 直接注入 `ItemBlockRenderTypes`；
   26.3 没有该类，按贴图透明度自动推导。
   注意 1.21.11 的原版 blockstate/模型 JSON **不支持** `render_type`。
3. **元件相（中继器/比较器/侦测器）已实现**（0.2.0，方案 C）：**潜行右键**宿主上表面放置，
   普通右键保持原版。放置前提是足迹内存在顶面空腔（`top < 1`）：下半砖/下半楼梯可以，
   整格方块/上半砖拒绝。信号语义、几何与宿主形状采样见 `docs/plan-c-elements.md`；
   状态预算与旧方案（`docs/state-budget-analysis.md`、`docs/milestone-2.5-repeater-proposal.md`）
   只作历史记录。
   > ⚠️ **不要重新引入"每个元件参数一个属性"或"元件参数并进 `filling` 取值域"的方案。**
   > 状态数是**相乘**的：`24 × 33 × 17 × 13` = 36.7 亿，越过 `int` 状态 id 空间；
   > 并进取值域则 1.21.11 至少 81.3 万状态（V=84）。现行实现把参数放进旁路表，
   > `filling` 固定 8 值，总状态 176,531。
   > 新增元件种类时只加 `PhaseExtra.Kind` 与子模型，**不要动 `filling` 取值域**。
4. 行为层已做服务端自检（0.2.0 约 341 项断言/版本，1.21.11 实测 341，其余可能 ±1）+ 启动冒烟 + 6 版本编译；
   **渲染层与剔除面已在真实客户端（1.21.11 `runClient`）取证修复**：
   带相状态图层为 CUTOUT（半透明宿主保持 TRANSLUCENT）、内缩覆盖层/外扩外壳的
   quad 进入不可剔除列表、几何坐标在宿主外侧；
   **栅栏的红石相已在真实 1.21.11 客户端取证并自动回归**（`./gradlew :1.21.11:runClientGameTest`，
   见 §3.4.0）：宿主 quad + 外壳 quad 同在、图层 CUTOUT、外壳顶点不越出本格；
   顺手核清了"地面红碎屑属于原版红石线"这一易误判点。
   游戏内逐条手测（冻结、凝固、破坏保留、桶挖、炼药锅转化、**固定视角目视含相剔除接缝**、
   客户端取水不发本地挖掘包、**连原版服务器时的客户端回退**、
   **隐形宿主/告示牌箱子含相后的外观**、**0 档与 15 档外壳的颜色差异**、
   **0.1.12 补全后的铜栏杆/铜锁链/铜灯笼/ wool 楼梯/墙告示牌等新补宿主的红石相外观**）仍待做。
5. **相瞄准的判定口径不能改成"合并轮廓 vs 相区域"**：相区域的外表面就落在方块边界上，与合并轮廓（=整格）的命中点重合，
   比较恒成立 → 任何瞄准都算挖相（历史 bug：含固相方块"怎么都挖不掉"）。现在用宿主轮廓（`filling=none` 兄弟状态）
   与相区域比距离，并保证形状表型宿主不会因查不到含相状态而退化成空形状。
6. **`filling` 与旁路表的一致性**：一格只表达一个相。
   - 写 `filling != none` 一律走 `PhaseHelper.setPhase`，**先清旁路条目**；
   - 写旁路条目走 `canAcceptExtra`：条目必须为空，且 `filling == none` 或可替换（水/熔岩），
     写入时把 `filling` 清回 `none`；`canAcceptPhase` 反向也检查条目为空；
   - 方块被换成**另一个方块**时由 `LevelChunkExtraMixin` 清条目；换成空气留给
     `onRemove` 读取红石功率以留下红石线；
   - 元件几何/信号/连接判定一律按坐标查表，不能只看 `BlockState`（状态里没有元件信息）。
   > ⚠️ `Phase.isFrosted()` / `isSolid()` 仍必须用**显式区间**，不要用无上界的
   > `ordinal()` 相对序关系。红石/元件已不在枚举里，但霜冰取值仍排在中间。
7. **`Unknown property 'filling'`（已修复）**：1.21.10+ 的多面体家族
   （glow_lichen / sculk_vein / resin_clump）统一成 `MultifaceBlock`，早期
   `PhaseCapableMixin` 的 stonecutter 分支仍写着旧的 `GlowLichenBlock`/`SculkVeinBlock`，
   这些方块拿不到属性（0.1.15 前叫 `phase`，之后叫 `filling`），而生成器又给它们的
   blockstate 加了条件，客户端 `BlockStateModelLoader` 于是报 `Unknown property` 并退化成
   missing model。已把 1.21.10+ 分支改成 `MultifaceBlock`（当时注入数 409 → 410），
   `tools/check_mixin_targets.py` 会拦住这类回归。
8. **相容宿主覆盖面 7 处缺口已在 0.1.13 全部收口**（清单与能力层都绿）：
   详见 `docs/host-coverage-audit.md`（§4 逐项 + §6 修复记录），逐版本数字由
   `python tools/audit_host_coverage.py <guardian> .` 复现。要点与余留：
   - `barrier`（1.19.4/1.20.1）：从 mixin 目标与 `.ids` 移除（0.2.0 运行时注入数 311/313），
     崩溃面消失；1.21.1+ 的 `barrier` 仍是宿主，但原版"创造模式限定接受流体"的语义
     使相充入路径（探测时拿不到玩家身份）对它一律拒绝——红石相不受影响。
   - 台阶 `type=double`：整格判定改到变体粒度，混合模型宿主按变体拆分
     （见 §3.1）；`tools/gen_phase_blockstates.py` 打印 `mixed-model hosts:`。
   - 台阶/蜡烛/营火/干化恶魂：`SlabLiquidMixin` + `HostPlaceLiquidMixin` 接管
     遮蔽的 `canPlaceLiquid`/`placeLiquid` 熔岩分支（只接管熔岩，水走原版）。
     1.21.1+ 的 `barrier` 也遮蔽这两个方法，但原版语义是创造模式限定 → **有意不接管**。
   - 固相整格碰撞：移到状态级 `ShapeFallbackMixin`（两参缓存路径 + 三参），与 D7 一致；
     块级只留 null 兜底（`Cache.<init>` 直接调块级方法，兜底必须留在那）。
   - offsetType 宿主：删除 `!hasOffsetFunction()` guard，破坏保留/整格碰撞对它们照常生效。
   - `PlacementMixin` 的 `MultifaceBlock` 改为全版本目标（`getStateForPlacement` 全版本由它声明）。
   - 熔岩桶右键：删除 `BucketUseMixin`，桶交互完全走原版路径（`useItemOn` 通过 →
     `useItem` → `emptyContents` → `BucketItemMixin`）——原版交互消费右键的宿主
     （活板门/箱子/末影箱/光源/告示牌/装饰陶罐/搁物架/雕像）不灌入，与原版水桶同规则；
     审计文档对"1.21.1+ 不再灌入"的推断是错的（基类钩子本就覆盖未声明交互的宿主），
     §4.7 的真实问题是"强制灌入凌驾原版交互"，不是"灌不进"。
     顺带修复 `BucketItemUseMixin` 在 ≤1.21.1 的 CCE（CIR 类型写成了 1.21.10+ 的）。
   - 传送门三件套（`nether_portal` / `end_portal` / `end_gateway`）：0.2.0 起
     `audit_host_coverage.py` 的 A/B 集合显式包含它们（它们不是 `SimpleWaterloggedBlock`）。
   - 验收口径固定为四个差集全零（A-B / B-C / C-B / C-S），提交后跑
     `python tools/audit_host_coverage.py <guardian> .` 复核；"覆写遮蔽"清单里
     已被新 mixin 接管的条目会标为"覆写已接管"。

9. **0.2.0 已知限制（README 与 §7 必须保持一致）**：
   - **旧存档不兼容**：`filling` 取值域变更，旧状态无法映射。
   - **元件只能放在有顶面空腔的宿主**：下半砖/下半楼梯等（足迹内存在 `top < 1` 的列）可以，
     整格方块/上半砖拒绝。
   - **元件参数与红石功率在旁路表里，不随活塞移动**：推动宿主时该格条目被清掉（元件丢弃）。
   - **中继器 `locked` 只做逻辑锁定**，不显示原版锁形。
   - **1.19.4–1.21.11 所有相宿主方块（所有状态）都走 CUTOUT**，失去原版 SOLID 的 mipmap
     （视觉/性能取舍）；26.3 由贴图透明度自动推导。
   - **含固相的传送门会被整格碰撞堵住**：下界门/末地门/末地折跃门含冰/霜冰/黑曜石时，
     固相整格碰撞是通用规则。
   - **Sodium/光影**：渲染走 Fabric Rendering API（Indigo），未承诺 Sodium 兼容。
   - **`getRenderShape` 对全部相宿主状态强制 `MODEL`**：红石/元件存在性在状态里看不出来；
     隐形宿主（barrier/light/告示牌）的 blockstate 模型本身没有 `elements`，不会凭空多出几何。
   - 红石相没有弱充能方块能力；破坏宿主时元件随宿主消失、不额外掉落。
