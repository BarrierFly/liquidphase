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
| 瞄准与直接挖相 | `phase/PhaseTargeting.java` |
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
```

`CompositeFactories.java` 目前是**手写**文件（生成器仍在完善），`regen_all.sh` 不会覆盖它。

`tools/phase-blocks/<版本>.ids` 是相容方块 id 清单；`<版本>.closure.txt` 是"直接实现 SimpleWaterloggedBlock 的类 + 继承闭包"。

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
| 启动服务端 | 日志出现 `injected phase property into … blocks`，无 mixin 报错 |
| 熔岩桶 → 楼梯 | 出现含熔岩相；整格发光 15；可流出/被流入 |
| 水 → 含熔岩 | 变为含黑曜石相 + 嘶声 |
| 破坏含冰楼梯 | 掉楼梯 + 原地留冰 |
| 含水楼梯 + 低温降水 | 冻结成含冰相；光照够时融回水相 |
| 空桶左键水源 | 掉"水"物品；右键放流体；无桶返还 |
| 熔岩锅 + 水 / 水锅 + 熔岩 | 掉黑曜石 / 石头·圆石·台阶 |
| 混凝土粉末 + 水锅 | 混泥土 + 水位 -1 |
| 发射器/漏斗 | 填锅、抽锅、液体物品进出 |
| 红石粉点楼梯 | 变含红石相组合方块；线按规则衰减；破坏留红石线 |
| 存档保存/重载 | 相保持 |
| 关闭 `phaseEnabled` | 旧存档的相丢失（预期，README 已写明） |

## 6. 发布流程

1. 6 版本全量 `build` 通过；
2. 启动服务端冒烟（`./gradlew :<版本>:runServer`）；
3. 收集 `versions/*/build/libs/liquidphase-*.jar`（不含 sources）到 `dist/`；
4. 更新 `README.md` 的版本支持与已知限制，然后提交。

## 7. 已知限制（待办）

1. **1.21.1 不注册红石相组合方块**：组合方块（有 `power` 无 `phase`）在 1.21.1 的宿主形状表里仍会返回 null，
   `Cache` 构造时 NPE。已在 `LiquidPhase` 用 `//? if !(1.21.1)` 跳过注册；修好形状兜底后去掉该守卫即可。
2. **组合方块覆盖面**：当前仅楼梯/台阶/墙/栅栏共 41 个宿主（手写 `CompositeFactories`）。
   `tools/gen_redstone_composites.py` 的批量生成还有版本守卫/构造参数问题，稳定后再切生成物。
3. **§3.5 元件相**（中继器/比较器/侦测器）未实现，方案见 `docs/milestone-2.5-repeater-proposal.md`。
4. 行为层只做了服务端冒烟（初始化 + 世界生成）；游戏内逐条手测（冻结、凝固、破坏保留、桶挖、炼药锅转化）仍待做。
