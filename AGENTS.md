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
python tools/gen_phase_blocks.py "<guardian 仓库路径>" tools/phase-blocks tools/phase-blocks

# 2) （0.1.5 起不再有组合方块生成步骤；红石相走 phase 取值）

# 3) 相覆盖 blockstate（构建时自动执行，也可单独跑）
./gradlew :<版本>:convertPhaseBlockstates

# 4) 液体物品贴图
python tools/gen_item_textures.py src/main/resources/assets/liquidphase/textures/item

# 5) 红石相外壳资源（贴图 + 16³ 外壳模型 + 41 个宿主的 blockstate + 中英文名）
#    第 1 个参数是宿主清单 tools/redstone-hosts.txt，第 3 个是客户端 jar
python tools/gen_redstone_assets.py tools/redstone-hosts.txt \
    src/main/resources/assets/liquidphase "<GRADLE_USER_HOME>/caches/fabric-loom/1.21.11/minecraft-client.jar"
```

`tools/redstone-hosts.txt` 是红石外壳覆盖的 41 个宿主清单（生成器与客户端共用）。

`fabric_loader_version`（共享 `gradle.properties`）是**构建用** loader，`depends` 里声明的是
各版本 `versions/<版本>/gradle.properties` 的 `fabric_loader_min`（= max(Fabric API 自身要求, 0.15.0)，
0.15.0 起 loader 自带 MixinExtras，`CacheShapeGuardMixin` 依赖它）。两者别混用；
改 `fabric_loader_min` 时 `processResources` 已把它声明为输入，不会因增量判定漏更新。

`tools/phase-blocks/<版本>.ids` 是相容方块 id 清单；`<版本>.closure.txt` 是"直接实现 SimpleWaterloggedBlock 的类 + 继承闭包"。

### 3.1 相覆盖层的两类宿主

`tools/gen_phase_blockstates.py` 的 `FULL_CUBE_HOSTS` 列出"原版模型本来就填满整格"的宿主
（树叶、铜格栅、红树根）：它们含固相时**整格换成相材质**，宿主原版 part 的 when 合并
`phase: none|lava`；其余宿主保持"原版模型 + 内缩 0.25px 覆盖层"。
新增整格型宿主时把 id 加进这个集合，并确认 `phase_*_full.json` 六个模型存在。

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
| 红粉点宿主 / 宿主放进线格 | `mixin/BlockItemMixin` / `mixin/RedstoneWireUseMixin` |
| cutout 图层的宿主清单 | `redstone/RedstoneRenderBlocks.java` + `tools/redstone-hosts.txt` |

**代价**：`phase` 从 8 个取值变成 24 个（×3），1.21.11 的状态数从 175,607 涨到 **509,175**，
启动注入时间相应变长。

**渲染**：`tools/gen_phase_blockstates.py` 除了相覆盖层，还会对 `tools/redstone-hosts.txt`
里的 41 个宿主额外追加：
- 宿主原 part 的 `when.phase` 限制为 `none|lava|obsidian|ice|frosted_0..3`；
- 16 条 `when{phase: redstone_N}` 的 part，全部指向同一个外壳模型。

外壳模型/贴图由 `tools/gen_redstone_assets.py` 生成（16³ 外壳外扩 0.01px，贴图是
**短碎屑均匀铺满整面**的疏松红石纹理，16×16 透明底、约 29% 覆盖、跨格可拼贴）。

### 3.3 红石相的两向生成

- 红石粉点宿主 → 该格写入 `phase=redstone_15`（`BlockItemMixin` 分支 a0）。
- 宿主方块右键点红石线格 → 宿主写进该格、功率沿用原线
  （`RedstoneWireUseMixin` + `RedstoneBlocks.placeHostIntoWire`）。
  **必须挂在红石线自己的右键入口上**：红石线的“点/十字切换”会先吃掉交互，
  `BlockItem.place` 那条分支根本走不到（踩过的坑）。位置换算走 `WirePlaceContext`
  （`replaceClicked = true`，否则 `getClickedPos()` 会是线旁边那格）。

### 3.3.1 信号结算时机

`RedstoneBlocks.neighborChanged` 是**即时**重算（`updatePower`），不排计划刻——
排计划刻会让开关/拆线延迟两刻才反应。防递归靠“只有功率真的变了才 `setBlock`”，
与原版 `DefaultRedstoneWireEvaluator` 同一手法。

### 3.4 自动化自检

`-Dliquidphase.selftest=true`（或环境变量 `LIQUIDPHASE_SELFTEST=true`）启动服务端会在
`ServerLifecycleEvents.SERVER_STARTED` 跑 `selftest/PhaseSelfTest`，33 项断言覆盖：
组合方块注册/反向表/属性、选择框整格、碰撞=宿主（含缓存）、遮挡非整格、面露出判定、
向红石线 -1 / 向固相满功率 / DOWN 不输出 / 被覆盖面不输出、供电即时性（邻居变化同 tick 生效）、
向红石线格放置宿主、炼药锅满锅取水门槛。
默认关闭，不进正式玩法路径。**改动红石相形状或信号语义后请先跑它。**

> 自检场地要放真实电源：信号即时化之后，手动 `setValue(POWER, 15)` 会被立刻重算回真实值。

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
| 启动服务端 | 日志出现 `injected phase property into … blocks` 与 `registered … redstone-phase composite blocks`，无 mixin 报错 |
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
| 楼梯右键红石线格 | 该格换成楼梯并带红石相，朝向/半砖跟随点击面、功率沿用原线 |
| 红石相输出 | 指向相邻红石线时 -1；指向灯/活塞时满功率；被宿主覆盖的面不输出 |
| 红石相信号延迟 | 开关/拆线后**同一 tick** 就更新（不走计划刻） |
| 瞄准红石外壳挖掘 | 掉红石粉、原地留宿主；瞄准宿主露出部分则正常破坏并留下对应功率红石线 |
| 存档保存/重载 | 相保持 |
| 关闭 `phaseEnabled` | 旧存档的相丢失（预期，README 已写明） |

## 6. 发布流程

1. 6 版本全量 `build` 通过；
2. 启动服务端冒烟（`./gradlew :<版本>:runServer`）；红石相/炼药锅相关改动建议带自检跑一遍：
   `LIQUIDPHASE_SELFTEST=true ./gradlew :<版本>:runServer`（日志里应有 `[selftest] result: 25 passed, 0 failed`）；
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
2. **红石相宿主覆盖面**：渲染外壳只对 `tools/redstone-hosts.txt` 里的 41 个宿主
   （楼梯/台阶/墙/栅栏）生成；其他宿主也能被写入 `phase=redstone_*`（逻辑正常），但没有外壳贴图。
   透明底走 cutout：1.19.4/1.20.1/1.21.1 用旧版 `BlockRenderLayerMap.INSTANCE`，
   1.21.10/1.21.11 用新版 `client.rendering.v1.BlockRenderLayerMap` + `ChunkSectionLayer.CUTOUT`，
   26.3 按贴图透明度自动推导。注意 1.21.11 的原版 blockstate/模型 JSON **不支持** `render_type`。
3. **§3.5 元件相**（中继器/比较器/侦测器）未实现，方案见 `docs/milestone-2.5-repeater-proposal.md`。
4. 行为层已做服务端自检（6 版本 25 项断言全过）+ 启动冒烟 + 6 版本编译；
   游戏内逐条手测（冻结、凝固、破坏保留、桶挖、炼药锅转化、树叶相渲染、红石外壳渲染、
   客户端取水不发本地挖掘包）仍待做。
5. **相瞄准的判定口径不能改成"合并轮廓 vs 相区域"**：相区域的外表面就落在方块边界上，与合并轮廓（=整格）的命中点重合，
   比较恒成立 → 任何瞄准都算挖相（历史 bug：含固相方块"怎么都挖不掉"）。现在用宿主轮廓（`phase=none` 兄弟状态）
   与相区域比距离，并保证形状表型宿主不会因查不到含相状态而退化成空形状。
6. **红石相与固相共用 `phase` 属性**：`isFrosted()` / `isSolid()` / `redstonePower()` 都用显式区间判断，
   不能再用 `ordinal()` 与 `FROSTED_0` 简单比较（红石取值排在后面）。新增相取值时务必同步这三个方法。
7. **客户端资源加载会报 `Unknown property 'phase'`（既有问题，未在 0.1.3 处理）**：
   `gen_phase_blockstates.py` 会给 `resin_clump` 这类 `MultifaceBlock` 的 blockstate 追加 `phase` 条件，
   而客户端 `BlockStateModelLoader` 解析 multipart 时读的是**注册时**的属性表——
   phase 是之后注入的，于是报 `Unknown property 'phase' on 'Block{minecraft:resin_clump}'` 并产生 missing model 警告。
   服务端与游戏逻辑不受影响（只影响这些方块的渲染），后续要么给生成器加 white-list 跳过这类方块，
   要么改渲染层方案。
