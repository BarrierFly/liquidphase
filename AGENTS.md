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
| 相枚举 | `phase/Phase.java` |
| 相行为总控（充入/实体化/凝固/冻结/融化/霜裂/火蔓延） | `phase/PhaseHelper.java` |
| 相容宿主标记接口 + 桶/流体默认方法 | `phase/PhaseCapable.java` |
| 瞄准与直接挖相（含整格宿主/形状表宿主的命中判定） | `phase/PhaseTargeting.java` |
| 状态级核心 mixin（流体态/光照/随机刻/破坏保留/邻居） | `mixin/BlockStateBaseMixin.java` |
| 自带状态注入器（1.21.1+） | `state/PhaseStateRefresher.java` + `mixin/state/*` + `state/*Duck` |
| 桶/放置/交互 mixin | `mixin/BucketItem*Mixin.java`、`mixin/BucketUseMixin.java`、`mixin/PlacementMixin.java`、`mixin/BlockItemMixin.java` |
| 冻结接线 | `mixin/ServerLevelMixin.java`（`tickChunk` ≤1.20.1 / `tickPrecipitation` 1.21.1+） |
| 炼药锅 | `cauldron/CauldronBehaviors.java`、`CauldronBehaviorRegistration.java`、`CauldronHopperTransfer.java`、`ConcreteMappings.java` |
| 发射器/漏斗 | `interact/LiquidInteractions.java`、`mixin/HopperTransferMixin.java` |
| 红石相（§3.4） | `redstone/RedstoneBlocks.java`、`redstone/RedstonePhaseBlock.java`、`redstone/CompositeBlocks.java`、`redstone/composites/*`（生成） |
| 配置 | `LiquidPhaseConfig.java` |

## 3. 生成脚本（改动后必须重跑）

```bash
# 1) 相容方块 id 清单（从 guardian 反编译源码解析 Blocks.java）
#    ⚠️ 这份清单必须与"运行时真的拿到 phase 属性的方块"逐版本严格一致：
#    运行时给每一个 `instanceof PhaseCapable` 的方块注入 phase，而 PhaseCapable 挂在
#    PhaseCapableMixin 的一组基类上、全部子类都会继承。清单少一个 → 那个方块含红石相
#    时没有任何 part 可画（0.1.12 的根因）；多一个 → 原版解析器报 Unknown property 'phase'，
#    整份 blockstate 加载失败。脚本用 EXPECTED_COUNTS（各版本运行时注入数）硬校验。
python tools/gen_phase_blocks.py "<guardian 仓库路径>" tools/phase-blocks tools/phase-blocks

# 2) （0.1.5 起不再有组合方块生成步骤；红石相走 phase 取值）

# 3) 相覆盖 blockstate（构建时自动执行，也可单独跑）
./gradlew :<版本>:convertPhaseBlockstates

# 4) 液体物品贴图
python tools/gen_item_textures.py src/main/resources/assets/liquidphase/textures/item

# 5) 红石相外壳贴图 + 模型（16 个功率档位各一份，按原版红石线亮度曲线着色）
#    外壳的 blockstate part 由第 3 步统一追加，这里只出资源。
python tools/gen_redstone_assets.py src/main/resources/assets/liquidphase
```

`tools/redstone-hosts.txt` 是 0.1.1–0.1.8 时期"外壳只覆盖 41 个代表宿主"的历史清单，
**已经不再参与构建**（`build.gradle.kts` 不再把它声明为输入），只作参考。
0.1.9 起外壳对全部相容宿主生成；0.1.11 起 blockstate 生成也只剩一条路径。

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
`injected phase property into N blocks` 的实测值（309 / 311 / 337 / 410 / 410 / 505），
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
> 已实测：把 1.21.11 的清单改成"删掉 `barrier` + 加一个不存在的 id"（总数仍是 410），
> 两条断言都立刻失败并点名 `barrier`。

生成器还必须处理这些批量注册形式（历史上逐个踩过）：
`register` / `registerSlab` / `registerLegacyStair` / `registerStair` / `registerWall`、
`ColorCollection.registerBlocks`、`ColorCollection.zipMap`（id 在**第二个**参数位）、
`WeatheringCopperCollection.registerBlocks`、`WeatheringCopperBlocks.create`。
`ColorCollection` 展开成 16 色，`WeatheringCopper*` 展开成 8 个氧化/打蜡变体。

生成器会**删除本次未产出的旧 blockstate**。不清理的话，从清单里移除某个 id 后旧文件仍在
输出目录、继续被打进 jar，那个方块若拿不到 `phase` 属性就会让客户端报
`Unknown property 'phase'`、整份 blockstate 退成 missing model。

### 2.1 发布前静态校验（0.1.7 起）

```bash
# 1) 生成出来的 blockstate 是否都能被原版 multipart 解析器接受
#    （按 KeyValueCondition/CombinedCondition 的真实语义，覆盖全部 6 个版本的构建产物）
python tools/validate_blockstates.py

# 2) 每个版本 jar 里 PhaseCapableMixin 的 @Mixin 目标是否对得上该版本的多面体/石钟乳家族
#    （1.21.10+ 是 MultifaceBlock / SpeleothemBlock，之前是 GlowLichen+SculkVein / PointedDripstone）
python tools/check_mixin_targets.py

# 3) dist/ 里的 jar 内部资源抽查（版本号、坏条件数、关键 mixin 是否真的在包里）
python tools/verify_jars.py

# 4) 相容宿主覆盖面审计（A/B/C/S 四个集合的差集；改 mixin 目标、ids 清单、
#    生成器门控、或做版本升级后各跑一次）
python tools/audit_host_coverage.py "<guardian 仓库路径>" .
```

`tools/audit_host_coverage.py` 把"哪些方块应当能含相"和"实际覆盖了哪些"拆成四条链路，
差集必须全空（详见 `docs/host-coverage-audit.md`）：

| 记号 | 含义 | 差集含义 |
| --- | --- | --- |
| A | `Blocks.java` 里类（或祖先）实现 `SimpleWaterloggedBlock` 的方块 | `A-B`：应能含相但拿不到 `phase` |
| B | `PhaseCapableMixin` 的 `@Mixin` 目标闭包 ∩ 注册表 | `B-C`：有属性但没有 blockstate（含相看不见） |
| C | `tools/phase-blocks/<版本>.ids` | `C-B`：有 blockstate 但拿不到属性（`Unknown property 'phase'`） |
| S | 真正生成红石外壳的方块 | `C-S`：有清单但没外壳 |

它还会顺带报出"能力被宿主覆写遮蔽"的三类缺口（`PhaseCapable` 的
`canPlaceLiquid`/`placeLiquid`/`pickupBlock`、块级 `getCollisionShape`、
`getStateForPlacement`）——这些不是清单问题，只按清单生成的断言测不出来。
`--history` 会逐提交重算，用于核对历史补丁是否真的覆盖了全部宿主。

`tools/validate_blockstates.py` 会在构建时被 `gen_phase_blockstates.py` 的
`validate_result()` 再查一遍（非法条件直接让 `convertPhaseBlockstates` 失败），
但单独跑一次可以对任意产物（含已经拷进 `dist/` 的 jar）复核。

### 3.1 相覆盖层的两类宿主

`tools/gen_phase_blockstates.py` 会**按模型几何自动判定**整格宿主（不再手写白名单）：
宿主 blockstate 能引用的**每一个**模型都满足「无 rotation + 包围盒恰好 0..16 + 六个边界面
被面片段完全覆盖」时，它含固相时**整格换成相材质**，宿主原版 part 的 when 合并
`phase: none|lava|<redstone>`；其余宿主保持"原版模型 + 内缩 0.25px 覆盖层"。

> ⚠️ **不能退回手写 `FULL_CUBE_HOSTS` 白名单**。26.3 新增的白杨树叶
> （`orange/red/yellow_poplar_leaves`）是整格模型，手写清单漏登记 → 含冰相时用的是内缩
> 覆盖层、藏在完整立方体内部 → **完全看不见相**。这正是 0.1.2 以来反复出现的同类 bug。
>
> ⚠️ **也不能只按包围盒判定**。台阶/楼梯的包围盒也是整格（两个长方体拼满对角），
> 但六个边界面远没有被铺满；只按包围盒判会把所有楼梯误判成整格宿主。
> 原版 `mangrove_roots` 则是"外皮 + 内部十字"：包围盒整格且六面被 0.002 薄板铺满，
> 所以它**应该**走整格路径（旧白名单恰好是对的）。

新增整格型宿主不需要改代码；跑一次生成器就会自动识别。要复核识别结果，看
`convertPhaseBlockstates` 打印的 `full-cube hosts:` 列表。

### 3.2 红石相的表示与渲染

**红石相 = 宿主方块上的 `phase` 取值**（0.1.5 起）：`redstone_0` … `redstone_15` 共 16 个
枚举值直接编码功率，**不再注册任何新方块**。楼梯还是 `minecraft:oak_stairs`，只是多了一个
phase 取值；形状、碰撞、掉落、破坏保留全部沿用宿主原有逻辑。

| 项目 | 位置 |
| --- | --- |
| 枚举（16 个 redstone 值 + `redstonePower()` / `Phase.redstone(n)`） | `phase/Phase.java` |
| 信号输出/接收/重算/生成/破坏 | `redstone/RedstoneBlocks.java` |
| `getSignal`/`getDirectSignal`/`isSignalSource` 状态级注入 | `mixin/RedstoneSignalMixin.java` |
| 红石线连接判定 | `mixin/RedStoneWireBlockMixin`（≤1.21.11）/ `BlockStateBaseMixin.shouldRedstoneWireConnectTo`（26.3） |
| 红粉点宿主 / 宿主放进线格 | `mixin/BlockItemMixin` + `mixin/RedstoneWireReplaceMixin` |
| 下落方块落进线格 | `mixin/FallingBlockIntoWireMixin` |
| 含相状态强制 MODEL 渲染（隐形/方块实体宿主也能显示相） | `mixin/PhaseRenderShapeMixin` |
| 相渲染谓词（是否带相 / 宿主状态） | `phase/PhaseRenderSupport.java` |
| 渲染层（带相状态强制 CUTOUT，含移动方块） | `mixin/client/PhaseRenderLayerMixin` + `liquidphase.layer.mixins.json` |
| 双端能力握手 | `sync/PhaseHandshake.java` + `sync/PhaseHandshakeClient.java` + `sync/PhaseSyncState.java` |

**代价**：`phase` 从 8 个取值变成 24 个（×3），1.21.11 的状态数从 175,607 涨到 **509,175**，
启动注入时间相应变长。

**渲染**：`tools/gen_phase_blockstates.py` 除相覆盖层外，会给**全部**相容宿主追加：
- 普通宿主的 part **完全不带 `phase` 条件**（覆盖层与外壳都是叠加层，宿主照常渲染）；
  整格宿主的 part 带 `phase: none|lava|<16 个 redstone>`（固体相下换成整格相材质）；
- 16 条 `when{phase: redstone_N}` 的 part，各自指向**按功率着色**的外壳模型
  （`redstone_phase_0` … `redstone_phase_15`，**叠加**在宿主之上）。

> ⚠️ **宿主 part 不要补"全部 24 个相"的条件**（0.1.10 及以前的做法）。
> 原版 `KeyValueCondition.instantiate` 发现条件覆盖了属性的全部取值时，会直接返回
> 恒真谓词并打 `Condition ... is always true` 警告——**每个方块状态一条**，
> 1.21.10+ 加载日志直接被刷屏。普通宿主本来就不需要这个条件。

> ⚠️ **外壳必须按功率着色**（0.1.11 修）。之前 16 个档位指向同一个恒定亮红外壳，
> 于是 `redstone_0`（未通电）看起来和 `redstone_15` 一模一样，直觉上是"这格通着电"。
> 现在生成 16 份贴图，亮度沿用原版 `RedStoneWireBlock.COLORS` 的曲线
> （`f = p/15`，`g = f*0.6 + (f>0 ? 0.4 : 0.3)`）：0 档 (76,26,22)、
> 15 档 (255,86,72)。
>
> **为什么不用 `tintindex` + 客户端 BlockColors**：tint 是按"模型的贴图索引"生效的，
> 而宿主自己的模型大量使用 `tintindex 0`（树叶的植被着色就是 0），外壳如果也用 0，
> 树叶的绿色会给粉尘染色；用 1 又要求每个宿主都有第二个 tint source，
> 1.19.4–1.21.1 的 `BlockColor` 接口（单 source、按 index 分派）表达不了这个区分。
> 静态生成 16 份调色板贴图在任何版本上一致，也没有 API 差异。

> ⚠️ **外壳覆盖面 = 运行时真的拿到 `phase` 属性的方块**（0.1.12 起硬校验）。
> 0.1.9 把"只给 41 个代表宿主生成"改成"全部宿主生成"，但当时的 id 清单本身漏登记了
> 一批方块（1.21.10/11 漏 24 个铜方块、26.3 漏 197 个、1.19.4/1.20.1 各漏 `barrier`），
> 于是那些方块**依然**没有外壳 part、含相了却什么都看不到——这就是 0.1.12 修的根因。
> 现在 `tools/gen_phase_blocks.py` 用各版本运行时注入数（309/311/337/410/410/505）硬校验清单，
> 少一个直接让构建失败。回归检查见 `tools/verify_jars.py` 的 `empty_phase_states`：
> 它扫描 jar 内**全部**生成产物，逐个断言 `phase=redstone_0/15` 都有对应档位的外壳 part，
> 并反向断言清单里每个 id 都有产物（`unrendered_ids` 列）。

> ⚠️ **红石相是"宿主 + 外壳叠加"，不是"外壳取代宿主"**（0.1.7 的实机 bug）。
> 曾经把宿主 part 限制成 `phase != redstone_*`，结果含红石相时**宿主根本不渲染**，
> 整格只剩一个 16³ 粉尘外壳：楼梯/台阶的形状消失、整格变成不透明粉尘面——
> 玩家看到的就是"红石相表面按不透明渲染"。回归检查：`python tools/check_redstone_render.py`
> （断言红石相状态下 `host parts > 0`）。

> ⚠️ **multipart 的 `when` 没有 `"a&b"` 这种语法**（0.1.6 的实机 bug）。
> 原版解析器（`KeyValueCondition` / `CombinedCondition`）的规则是：
> 一个 object 内的多个键 = AND，值里的 `a|b` = 同一属性的 OR，
> 跨属性组合必须显式写成 `{"AND": [...]}` / `{"OR": [...]}`，且一个 object 里
> **不能**同时出现 `AND`/`OR` 与属性键。写成 `"(a)&(b)"` 会被当成一个属性取值去查，
> 抛 `Unknown value`，**整份 blockstate 加载失败**，该方块所有状态退化成 missing model
> （紫黑方块 + `Missing model for variant` 刷屏）。合并条件一律走
> `and_condition()`；生成器结尾有 `validate_result()` 自检，非法条件直接让构建失败。
> 相覆盖层（`when` 里已带 `phase`）不要参与"排除红石相"的合并，否则条件取交集后永远不成立、
> 覆盖层不渲染。

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
绘制（`ModelBlockRenderer` 在方向循环之后单独处理 `null` 方向）。`phase_*_full`
（0..16 贴边）保留 `cullface`，因为它的面确实在边界上、语义正确。
回归：`tools/verify_jars.py` 的 `cullface=` 列 + 自检的模型断言。

**根因 B（0.1.9 的判断是错的，0.1.12 已删除对应 mixin）：`skipRendering` 与本问题无关。**
`BlockState.skipRendering` 只在**带 `cullface` 的 quad** 上参与判定
（`ModelBlockRenderer` 只对 `getQuads(direction)` 里的 culled quad 问
`Block.shouldRenderFace`；`getQuads(null)` 的无剔除 quad 直接绘制）。
相覆盖层与红石外壳从根因 A 起**全部不带 `cullface`**，所以它们根本不会进入这条路径。
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
> 正确做法：`mixin/client/PhaseRenderLayerMixin` 直接注入 `ItemBlockRenderTypes.getChunkRenderType`，
> **带相且原图层为 SOLID → 返回 CUTOUT**；CUTOUT/CUTOUT_MIPPED 保持；
> TRANSLUCENT（玻璃板/染色玻璃）保持——改 cutout 会让半透明像素变实。
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
**带相且原值不是 MODEL → 返回 MODEL**。

- 注入点必须是 `RETURN`：`HEAD` 里再问 `getRenderShape()` 会无限递归，而块级
  `getRenderShape(state)` 是 `protected`、mixin 拿不到。
- 不会让宿主凭空多出方块：这些方块的可见几何来自方块实体渲染器，那条路径
  （`hasBlockEntity` → `BlockEntityRenderDispatcher`）与 RenderShape 无关；它们自己的
  blockstate 模型只有 particle 贴图、没有 `elements`，所以最终多出来的只有本 mod 的
  相覆盖层 / 红石外壳。
- 不带相的状态原值返回，完全走原版。

#### 双端同步（0.1.9 修）

功能三（流体射线、本地瞬挖、液体物品放置）是**客户端行为 + 服务端权威**。客户端必须
**先确认服务端支持相**，否则连原版服务器时会单方面预测出服务端不存在的状态
（把线格改成含红石相宿主、把桶换成液体物品），表现为闪一下又回弹、物品数目对不上。

| 情形 | 握手 | 客户端行为 |
| --- | --- | --- |
| 单人游戏 | 集成服务器照常发包 | 到达前按"不支持"处理，之后正常 |
| 连有本 mod 的服务器 | 收到 `liquidphase:handshake` | 启用功能三 |
| 连原版服务器 | 永远收不到 | 全部退回原版行为 |

- 服务端 `ServerPlayConnectionEvents.JOIN` 时发 `PhaseHandshakePayload`（1.19.4/1.20.1 用裸
  `FriendlyByteBuf` 通道，1.21.1+ 用 `CustomPacketPayload`；注意 26.3 的
  `PayloadTypeRegistry` 改名为 `clientboundPlay()`）。
- 客户端收到就置 `PhaseSyncState.serverSupportsPhases()`；`DISCONNECT`/`JOIN` 都复位。
- 所有**会改世界状态**的客户端路径都用 `PhaseSyncState.canMutateWorld(level)` 门控
  （`BlockItemMixin`、`PlacementMixin`、`BucketItemMixin`、`BucketItemUseMixin`、`BucketUseMixin`、
  `RedstoneWireReplaceMixin`、`FallingBlockIntoWireMixin`、`ClientLiquidMineMixin`、`EntityPickMixin`）。
  服务端侧永远为真（自己的配置就是权威），所以**单人/多人/原版服务器都不影响方块状态同步**。
- 新增 `selftest` 断言：握手掩码、默认不支持、渲染剔除边界。

### 3.3 红石相的两向生成

- 红石粉点宿主 → 该格写入 `phase=redstone_15`（`BlockItemMixin` 分支 a0）。
- 相容宿主方块落到红石线所在格 → 宿主写进该格、功率沿用原线。

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
真正写方块前，如果**目标格此刻仍是红石线**，就把宿主状态写成
`phase=redstone_<原线功率>`。用"目标格现在是线"而不是"上下文里带标记"，
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
   落位写方块时也要在这里补 `phase=redstone_<原线功率>`，因为 `BlockItem.place` 那条钩子
   这条路径根本不触发。

3. **`@Redirect` 用 `require = 1`，不要用 `require = 0`。**
   两个注入点原版每个版本都有；`require = 0` 会让"某版本没命中"变成静默通过，
   表现为"构建成功、实机不生效"。


### 3.3.1 信号结算时机

`RedstoneBlocks.neighborChanged` 是**即时**重算（`updatePower`），不排计划刻——
排计划刻会让开关/拆线延迟两刻才反应。防递归靠“只有功率真的变了才 `setBlock`”，
与原版 `DefaultRedstoneWireEvaluator` 同一手法。

### 3.4 自动化自检

`-Dliquidphase.selftest=true`（或环境变量 `LIQUIDPHASE_SELFTEST=true`）启动服务端会在
`ServerLifecycleEvents.SERVER_STARTED` 跑 `selftest/PhaseSelfTest`，断言数按版本为
**111（1.19.4–1.21.11）/ 110（26.3）**——
各版本已经收敛到几乎相同，因为断言改成**全量扫描**而不是写死几个代表方块：
凡是"原生 RenderShape 不是 MODEL 的 phase 宿主"都会被逐个验证，各版本有几个就查几个
（实测 1.20.1 有 **51** 个，1.21.11 只有 **2** 个（`barrier`/`light`）——原版 1.21.10 起
把告示牌/箱子改成了 MODEL）。写死候选清单时只查了 6 个，漏掉的就是这类。覆盖内容：
相枚举/相位属性全覆盖、宿主方块身份、选择框/碰撞/遮挡、面露出判定、
向红石线 -1 / 向固相满功率 / DOWN 不输出 / 被覆盖面不输出、供电即时性（邻居变化同 tick 生效）、
红石线格放置（**两层**：无玩家直接 `BlockItem.place` + FakePlayer 走
`ServerPlayerGameMode.useItemOn`）、统一用例，脚手架堆叠（物品自己搜索落点那一类）、
下落方块落位（相容宿主落进线格带相；沙子仍被弹开）、
红石线可替换性的精确边界（宿主放行、石头/红石粉仍拒绝）、炼药锅满锅取水门槛、
**每个 phase 方块都画得出红石外壳**（遍历全部 `PhaseCapable`，断言它的 blockstate
里含 `liquidphase:block/redstone_phase_`——这是 0.1.12 根因的回归断言，
清单漏登记时立刻失败并打印漏掉的方块 id）、
**随包清单 == 运行时集合**（双向集合差，防止"清单两边各错一个、数量仍相等"）、
**blockstate 渲染资源**（楼梯宿主可见 + 外壳；整格宿主/透明宿主都有外壳）、
**模型几何**（内缩覆盖层/外扩外壳不带 `cullface`，贴边 `phase_*_full` 保留）、
**外壳按功率分档**（16 份模型/贴图互不相同，0 档与 15 档不同）、
**宿主 part 不带恒真 phase 条件**（否则 `Condition ... is always true` 刷屏）、
**整格宿主自动判定**（白杨树叶走整格、台阶走内缩）、
**含相状态强制 MODEL**（barrier 这类 INVISIBLE 宿主带相后能渲染；1.19.4–1.21.1 的
告示牌/箱子家族逐个验证）、
**相剔除**（相覆盖层/外壳模型都不带 `cullface`，原版不会剔除它们；
   **不要**再加"含相状态不参与 `skipRendering`"那类 mixin——0.1.12 已删除）、
**渲染层 mixin 配置**（仅非 26.3 接线）、
**双端同步**（客户端默认不支持、握手掩码正确）。
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
| 启动服务端 | 日志出现 `injected phase property into … blocks` 与 `redstone phase uses phase=redstone_0..15 (no extra blocks registered)`，无 mixin 报错 |
| 熔岩桶 → 楼梯 | 出现含熔岩相；整格发光 15；可流出/被流入 |
| 水 → 含熔岩 | 变为含黑曜石相 + 嘶声 |
| 破坏含冰楼梯 | 掉楼梯 + 原地留冰；准星瞄楼梯就按楼梯速度挖，瞄相才按冰算 |
| 破坏含黑曜石楼梯 | 瞄露出的黑曜石部分需钻石镐；瞄楼梯本体正常速度；破坏后留黑曜石 |
| 树叶含水/含冰 | 冰相整格显示冰；相=none 时显示树叶原貌 |
| 含水楼梯 + 低温降水 | 冻结成含冰相；光照够时融回水相 |
| 空桶左键水源 | 掉"水"物品；右键放流体；无桶返还 |
| 空桶左键水锅/熔岩锅 | 锅保留为空锅、掉 1 个液体物品；**未满水锅不响应**；右键锅不再取水、桶不消失 |
| 液体物品倒空锅 / 未满水锅 | 照常填锅，消耗 1 个、不返还空桶 |
| 熔岩锅 + 水 / 水锅 + 熔岩 | 掉黑曜石 / 石头·圆石·台阶 |
| 混凝土粉末 + 水锅 | 混泥土 + 水位 -1 |
| 发射器/漏斗 | 填锅、抽锅（水锅满锅才抽）、液体物品进出 |
| 红石粉点楼梯 | 该格写入 `phase=redstone_15`；方块仍是原版楼梯、宿主模型可见、疏松红石碎屑铺满整面；破坏留红石线 |
| 楼梯放进红石线格 | 该格换成楼梯并带红石相，朝向/半砖跟随点击面、功率沿用原线 |
| 脚手架堆叠到线格 | 线格能被替换成脚手架（物品自己搜索落点那条路） |
| 相容宿主下落 | 钟乳石等 `Fallable` + `PhaseCapable` 方块落到线格，变成含红石相的宿主 |
| 普通下落方块 | 沙子/砂砾仍像原版一样弹成物品，红石线不被覆盖 |
| 点线本身（不相容物品以外） | 原版先切换点/十字形状，属于预期行为 |
| 非宿主方块进线格 | 仍然放不进去（石头/红石粉等保持原版行为） |
| 红石线自身可替换性 | `canBeReplaced()` 仍为 false；只对相容宿主物品放行上下文重载 |
| 红石相输出 | 指向相邻红石线时 -1；指向灯/活塞时满功率；被宿主覆盖的面不输出 |
| 红石相信号延迟 | 开关/拆线后**同一 tick** 就更新（不走计划刻） |
| 瞄准红石外壳挖掘 | 掉红石粉、原地留宿主；瞄准宿主露出部分则正常破坏并留下对应功率红石线 |
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
   ③ `BlockBehaviourMixin`（块级兜底，带"仅带相状态/组合方块才兜底"的递归保护）。
   另 `PhaseStateRefresher` 对 `initCache()` 加了 try/catch，单个宿主异常只记日志不中断启动。
1. **红石相不再是独立方块**（0.1.5 起）：改成 `phase = redstone_0..15`，宿主方块本身不变。
   不再有组合方块注册、`COMPOSITE_TO_HOST` 表、`RedstonePhaseBlock` 标记接口。
   代价是 `phase` 取值 8 → 24，状态数约 ×3（1.21.11：175,607 → 509,175）。
2. **红石相宿主覆盖面**：渲染外壳对**全部**相容宿主生成——口径是"运行时真的拿到 `phase`
   属性的方块"，各版本为 **309 / 311 / 337 / 410 / 410 / 505 个**
   （0.1.12 起由 `tools/gen_phase_blocks.py` 的 `EXPECTED_COUNTS` 硬校验；
   0.1.9–0.1.11 的清单实际少登记了一批，见 §2.2 与 README 0.1.12 条目）。
   含树叶/格栅/玻璃板/铁栏杆/梯子/锁链/铜栏杆/铜锁链/铜灯笼/墙告示牌/羊毛楼梯等。
   整格宿主（树叶/铜格栅/红树根/白杨树叶）也带外壳。
   透明底走 cutout：客户端只把**默认图层为 SOLID** 的宿主升级成 CUTOUT，
   半透明宿主（玻璃板/染色玻璃）保持 TRANSLUCENT；下落方块与活塞推动走
   `getMovingBlockRenderType`，同样从 solid 升级成 cutout。
   1.19.4–1.21.11 由 `PhaseRenderLayerMixin` 直接注入 `ItemBlockRenderTypes`；
   26.3 没有该类，按贴图透明度自动推导。
   注意 1.21.11 的原版 blockstate/模型 JSON **不支持** `render_type`。
3. **§3.5 元件相**（中继器/比较器/侦测器）未实现。方案见
   `docs/milestone-2.5-repeater-proposal.md`，**状态预算与替代方案的实测核对见
   `docs/state-budget-analysis.md`**。
   > ⚠️ **元件参数只能并进 `phase` 一个属性的取值域，不许新增属性。**
   > 状态数是**相乘**的：`24 × 33 × 17 × 13` = 36.7 亿，越过 `int` 状态 id 空间。
   > 口径：`总状态数 = (N − B) + B × V`，1.21.11 实测 `N = 29,671`、`B = 20,976`、
   > 每个新增状态 ≈ 779 B、注入 ≈ 43.5 µs。
   > 1.21.11 现状（`V=24`）= 512,119 状态 / 506 MiB 堆 / 注入 ≈21 s；
   > 并集 `V=84` 全宿主 = 1,770,679 / ≈1.4 GiB；并集 `V=84` + 按方块裁剪
   > （白名单 = 楼梯+台阶 5,012 基础状态）= 812,839 / 730 MiB。
   > **扩取值前先确认白名单，墙（26 个方块、8,424 基础状态）必须排除。**
4. 行为层已做服务端自检（6 版本 111/111/111/111/111/110 项断言全过）+ 启动冒烟 + 6 版本编译；
   **渲染层与剔除面已在真实客户端（1.21.11 `runClient`）取证修复**：
   带相状态图层为 CUTOUT（半透明宿主保持 TRANSLUCENT）、内缩覆盖层/外扩外壳的
   quad 进入不可剔除列表、几何坐标在宿主外侧；
   游戏内逐条手测（冻结、凝固、破坏保留、桶挖、炼药锅转化、**固定视角目视含相剔除接缝**、
   客户端取水不发本地挖掘包、**连原版服务器时的客户端回退**、
   **隐形宿主/告示牌箱子含相后的外观**、**0 档与 15 档外壳的颜色差异**、
   **0.1.12 补全后的铜栏杆/铜锁链/铜灯笼/ wool 楼梯/墙告示牌等新补宿主的红石相外观**）仍待做。
5. **相瞄准的判定口径不能改成"合并轮廓 vs 相区域"**：相区域的外表面就落在方块边界上，与合并轮廓（=整格）的命中点重合，
   比较恒成立 → 任何瞄准都算挖相（历史 bug：含固相方块"怎么都挖不掉"）。现在用宿主轮廓（`phase=none` 兄弟状态）
   与相区域比距离，并保证形状表型宿主不会因查不到含相状态而退化成空形状。
6. **红石相与固相共用 `phase` 属性**：`isFrosted()` / `isSolid()` / `redstonePower()` 都用显式区间判断，
   不能再用 `ordinal()` 与 `FROSTED_0` 简单比较（红石取值排在后面）。新增相取值时务必同步这三个方法，
   以及 §7 第 3 条的状态预算口径。
   > ⚠️ 追加元件取值后，这条**一定会踩**：现在 `isRedstone()` 是
   > `ordinal() >= REDSTONE_0.ordinal()`（无上界），元件取值追加在 `redstone_15` 之后，
   > 于是 `repeater_*` 会被判成红石相（`redstonePower()` 返回 16 以上）。
   > **一律用"显式区间 + 上界"或 `switch`，不要用无上界的相对序关系。**
7. **`Unknown property 'phase'`（已修复）**：1.21.10+ 的多面体家族
   （glow_lichen / sculk_vein / resin_clump）统一成 `MultifaceBlock`，早期
   `PhaseCapableMixin` 的 stonecutter 分支仍写着旧的 `GlowLichenBlock`/`SculkVeinBlock`，
   这些方块拿不到 `phase` 属性，而生成器又给它们的 blockstate 加了 `phase` 条件，
   客户端 `BlockStateModelLoader` 于是报 `Unknown property 'phase'` 并退化成 missing model。
   已把 1.21.10+ 分支改成 `MultifaceBlock`（注入数 409 → 410），
   `tools/check_mixin_targets.py` 会拦住这类回归。
8. **相容宿主覆盖面仍有 7 处缺口**（清单层面已全绿，但"能力层"未覆盖）：
   详见 `docs/host-coverage-audit.md`，逐版本数字由
   `python tools/audit_host_coverage.py <guardian> .` 复现。摘要：
   - **P0** `barrier` 在 1.19.4 / 1.20.1 上拿到了 `phase`（`PhaseCapableMixin` 列了
     `BarrierBlock`），但这两个版本的 `BarrierBlock` 既没实现 `SimpleWaterloggedBlock`
     也没有 `WATERLOGGED` 属性，于是 `PhaseCapable` 默认方法里的
     `blockState.getValue(WATERLOGGED)` 会抛 `IllegalArgumentException`：
     水桶倒、空桶取、水流灌入三条路径都能触发。0.1.12 给这两个版本补了 `barrier` 的
     blockstate，反而让它变得"看得见、一碰就崩"。
   - **P1** 台阶的 `type=double` 变体：方块级 `all(...)` 整格判定必然判否（其余变体是半砖），
     而 double 变体引用的是 0..16 整块模型 → 内缩覆盖层完全藏在宿主内部，
     **含固相看不见**。57 / 57 / 60 / 62 / 62 / 101 个宿主。
     双台阶靠"已有半砖的 `getStateForPlacement` 保留 `phase` 再叠一块"获得相。
   - **P1** `SlabBlock` / `CandleBlock` / `CampfireBlock` / `DriedGhastBlock` 自己覆写了
     `canPlaceLiquid` / `placeLiquid`，把 `PhaseCapable` 的熔岩分支整个遮住 →
     **相邻熔岩灌不进这些宿主**（76–122 个）。桶/发射器/放置吸收仍可用。
   - **P2** 固相"整格碰撞"（D7）只对不覆写块级 `getCollisionShape` 的宿主成立：
     栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架、`sculk_shrieker`、`big_dripleaf`
     共 66–88 个宿主的固相碰撞仍是宿主形状。与 README 的"冰/黑曜石整格实心"表述冲突。
   - **P2** 带 `offsetType` 的 4–5 个宿主（`hanging_roots` / `mangrove_propagule` /
     `pointed_dripstone` / `small_dripleaf` / 26.3 `sulfur_spike`）被
     `!state.hasOffsetFunction()` 排除在"破坏保留"和"整格碰撞"之外 →
     破坏它们**不留下相**。这条限制没有写进 README。
   - **P3** `PlacementMixin` 的 `MultifaceBlock.class` 被包在
     `//? if 1.21.10 || 1.21.11 || 26.3` 里，但 1.19.4–1.21.1 的 `glow_lichen` /
     `sculk_vein` 也继承它的 `getStateForPlacement` → 这三个版本上放进熔岩源格不吸收相。
   - **P3** 1.21.1+ 起活板门/箱子/末影箱/光源方块的 `useWithoutItem` 会消费掉右键
     （`BucketUseMixin` 只挂了 `useItemOn`）→ 熔岩桶右键这些宿主不再灌入，
     ≤1.20.1 则可以。行为跨版本不一致，需明确取舍。
