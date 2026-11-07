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

# 2) 红石相组合方块类 + 实例工厂 + 清理已验证不兼容的类
bash tools/regen_all.sh

# 3) 相覆盖 blockstate（构建时自动执行，也可单独跑）
./gradlew :<版本>:convertPhaseBlockstates

# 4) 液体物品贴图
python tools/gen_item_textures.py src/main/resources/assets/liquidphase/textures/item

# 5) 红石相组合方块资源（贴图 + 宿主几何 + 粉尘外壳 + 41 个 blockstate + 中英文名）
#    第 3 个参数是客户端 jar（宿主原版 blockstate 与 redstone_dust_dot.png 都从里面取）
python tools/gen_redstone_assets.py src/main/java/org/liquidphase/redstone/composites/CompositeFactories.java \
    src/main/resources/assets/liquidphase "<GRADLE_USER_HOME>/caches/fabric-loom/1.21.11/minecraft-client.jar"
```

`CompositeFactories.java` 目前是**手写**文件（生成器仍在完善），`regen_all.sh` 不会覆盖它。

`tools/phase-blocks/<版本>.ids` 是相容方块 id 清单；`<版本>.closure.txt` 是"直接实现 SimpleWaterloggedBlock 的类 + 继承闭包"。

### 3.1 相覆盖层的两类宿主

`tools/gen_phase_blockstates.py` 的 `FULL_CUBE_HOSTS` 列出"原版模型本来就填满整格"的宿主
（树叶、铜格栅、红树根）：它们含固相时**整格换成相材质**，宿主原版 part 的 when 合并
`phase: none|lava`；其余宿主保持"原版模型 + 内缩 0.25px 覆盖层"。
新增整格型宿主时把 id 加进这个集合，并确认 `phase_*_full.json` 六个模型存在。

### 3.2 红石组合方块的渲染

`gen_redstone_assets.py` 从客户端 jar 提取宿主原版 blockstate，生成
"宿主原版 multipart part + 一整层 16³ 粉尘外壳（外扩 0.01px 避免共面闪烁）"。
外壳贴图 = 原版 `redstone_dust_dot` 按满功率 RGB(255,51,0) 逐像素染色，透明底 cutout；
透明像素后面直接是宿主模型，所以"原版贴图 + 透出宿主"两者都成立。
`power` 不出现在 when 里 → 16 个功率共用一套模型。

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
| 空桶左键水锅/熔岩锅 | 整锅拆空、掉 1 个液体物品；右键锅不再取水、桶不消失 |
| 液体物品倒空锅 / 未满水锅 | 照常填锅，消耗 1 个、不返还空桶 |
| 熔岩锅 + 水 / 水锅 + 熔岩 | 掉黑曜石 / 石头·圆石·台阶 |
| 混凝土粉末 + 水锅 | 混泥土 + 水位 -1 |
| 发射器/漏斗 | 填锅、抽锅、液体物品进出 |
| 红石粉点楼梯 | 变含红石相组合方块；宿主模型可见、粉尘外壳透明处透出宿主；线按规则衰减；破坏留红石线 |
| 存档保存/重载 | 相保持 |
| 关闭 `phaseEnabled` | 旧存档的相丢失（预期，README 已写明） |

## 6. 发布流程

1. 6 版本全量 `build` 通过；
2. 启动服务端冒烟（`./gradlew :<版本>:runServer`）；
3. 收集 `versions/*/build/libs/liquidphase-*.jar`（不含 sources）到 `dist/`；
4. 更新 `README.md` 的版本支持与已知限制，然后提交。

## 7. 已知限制（待办）

0. **启动崩溃已修**：宿主类覆写了块级 `getCollisionShape` / 状态级两参版本时，`BlockStateBase$Cache`
   构造器会拿到 null 形状并 NPE。现在有三道防线：
   ① `CacheShapeGuardMixin`（`Cache.<init>` 里把 null 换成空形状，`require = 0`）；
   ② `ShapeFallbackMixin`（状态级 outline/collision/occlusion/support/visual 兜底）；
   ③ `BlockBehaviourMixin`（块级兜底，带"仅带相状态/组合方块才兜底"的递归保护）。
   另 `PhaseStateRefresher` 对 `initCache()` 加了 try/catch，单个宿主异常只记日志不中断启动。
1. **1.21.1 不注册红石相组合方块**：组合方块（有 `power` 无 `phase`）在 1.21.1 的宿主形状表里仍会返回 null，
   `Cache` 构造时 NPE。已在 `LiquidPhase` 用 `//? if !(1.21.1)` 跳过注册；修好形状兜底后去掉该守卫即可。
3. **组合方块覆盖面**：当前仅楼梯/台阶/墙/栅栏共 41 个宿主（手写 `CompositeFactories`）。
   `tools/gen_redstone_composites.py` 的批量生成还有版本守卫/构造参数问题，稳定后再切生成物。
   41 个宿主的资源（blockstate + 宿主原版几何 + 整格粉尘外壳 + 满功率染色贴图）已由 `tools/gen_redstone_assets.py`
   补齐；组合方块**不注册 BlockItem**，只能由红石粉/液相方块交互生成。透明底走 cutout：
   1.19.4/1.20.1/1.21.1 用旧版 `BlockRenderLayerMap.INSTANCE`，1.21.10/1.21.11 用新版
   `client.rendering.v1.BlockRenderLayerMap` + `ChunkSectionLayer.CUTOUT`，26.3 按贴图透明度自动推导。
   注意 1.21.11 的原版 blockstate/模型 JSON **不支持** `render_type`，不要试图只靠资源声明图层。
3. **§3.5 元件相**（中继器/比较器/侦测器）未实现，方案见 `docs/milestone-2.5-repeater-proposal.md`。
4. 行为层只做了服务端冒烟（初始化 + 世界生成）与 6 版本编译；游戏内逐条手测（冻结、凝固、破坏保留、桶挖、炼药锅转化、
   树叶相渲染、红石外壳）仍待做。
5. **相瞄准的判定口径不能改成"合并轮廓 vs 相区域"**：相区域的外表面就落在方块边界上，与合并轮廓（=整格）的命中点重合，
   比较恒成立 → 任何瞄准都算挖相（历史 bug：含固相方块"怎么都挖不掉"）。现在用宿主轮廓（`phase=none` 兄弟状态）
   与相区域比距离，并保证形状表型宿主不会因查不到含相状态而退化成空形状。
6. **红石相组合方块没有 `phase` 属性**（宿主子类 + `POWER`）。`PhaseHelper.withPhase` 对没有该属性的状态原样返回
   （直接 `setValue` 会抛 `IllegalArgumentException`）；需要宿主轮廓时走 `RedstoneBlocks.hostStateOf` 换回宿主状态。
   `PhaseTargeting.isPhaseMineable` 同时涵盖固相与红石相，两条路径都必须能安全取到宿主形状。
