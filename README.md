# 液相 Liquid Phase

**中文** | [English](#english)

让"可含水方块"（楼梯、台阶、墙、栅栏、告示牌、箱子、树叶、蜡、锁链……）能额外持有一个**相**：
熔岩、黑曜石、冰、霜冰，以及红石线。一格 = 固相（方块本身）+ 至多一个被含相。

支持 **Fabric** 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3。

---

## 这个 mod 做了什么

### 功能一：更多 logged 方块（双端必装）

- **含熔岩**：熔岩桶（或液体熔岩物品）右键可含水方块、发射器装填、熔岩源流进该格，都会让它变成"含熔岩方块"。
  内部熔岩是真实熔岩源：按宿主形状从没被固相挡住的方向流出，也会被相邻熔岩按形状灌入；
  整格发光 15；处于其中的实体会着火；随机刻复刻原版火蔓延。
- **凝固**：含熔岩方块旁边出现水 → 相变为**含黑曜石**，伴随嘶声粒子。
- **含冰 / 含霜冰**：雨天低温下"含水的方块"会冻结成含冰相（原版只会冻水源方块，本 mod 把判定扩展到宿主）；
  冰相在光照足够时融化回水相；下界/水蒸发维度融化时直接消失。
  霜冰相按原版 AGE 0–3 的裂纹推进与相邻融化规则演化（`frosted_0`…`frosted_3`）。
- **破坏保留**：破坏含相方块 → 掉落固相战利品，该格留成真实熔岩源 / 黑曜石 / 冰 / 霜冰。
  **含相方块本身可以像原版方块一样正常破坏**：准星落在宿主露出的部分就按宿主材质挖，落在相露出的部分才按相材质挖
  （`phase=none` 的兄弟状态参与命中判定，形状表型宿主也不会误判）。
- **收走相**：功能三开启时空桶左键；关闭时空桶右键。固体相（冰/霜冰/黑曜石）不响应右键收取。
- **放置吸收**：拿冰/黑曜石物品指向可含水方块 → 写入该格；把可含水方块放进冰/黑曜石/熔岩源所在的格子 → 吸收该相。
- **碰撞**：冰/黑曜石相按整格实心（与"相填满宿主空隙"的视觉一致）；熔岩相不额外加碰撞。
- **直接挖相**：准星指向相露出的部分时，挖掘速度与掉落按相材质计算（黑曜石需钻石镐；冰/霜冰不精准采集则化水，霜冰无物品）。
- **整格宿主的相**：树叶、铜格栅、红树根这类"模型就是完整 16³ 立方体"的宿主，含相时整格替换为相材质
  （否则覆盖层藏在完整宿主模型内部，玩家看不到相）；`phase=none`/`lava` 仍显示宿主本体。

### 功能二：炼药锅（仅服务端，原版方块可表达的部分）

- 熔岩锅 + 水 → 掉落 1 个黑曜石、锅清空。
- 水锅 + 熔岩 → 按水位 3/2/1 掉落 石头 / 圆石 / 圆石台阶、锅清空。
- 空锅收流体：**只收六向直接接触的源**（水→水锅，熔岩→熔岩锅）。
- 混凝土粉末 + 水锅 → 消耗 1 水位，1 个粉末换成对应颜色的混凝土（16 色）。
- 细雪锅遇水/熔岩 → 满水锅。
- 发射器：液体物品填锅、空桶抽锅/抽液体、玻璃瓶从水锅装水瓶；漏斗：流体锅 ↔ 液体物品。
- **桶与锅**（功能三开启时）：手持空桶**左键**水锅/熔岩锅 → 整锅一次拆空并掉落对应液体物品，
  **锅本身保留**；只有满锅才能取（水锅要求 3 级水位）。右键取水被禁用（桶不再被换成液体物品，
  也不再凭空消失）；拿液体物品往空锅/未满水锅倾倒照常填锅，消耗 1 个且不返还空桶。
  发射器空桶抽锅、漏斗抽锅同样按满锅口径。功能三关闭时全部回到原版桶语义。
- **没有**药水锅、染色锅、装黑曜石的锅——这些需要方块实体，本版不做。

### 功能三：玩家与液体互动（**强制双端**）

- 手持**空桶**左键液体（水源、熔岩源、含熔岩方块的熔岩相）→ 瞬间"挖掉"并**掉落液体物品**（不直接进包）。
- 手持水/熔岩"液体物品"右键 → 像放方块一样放液体：对可含水方块写入相/含水，对空气放流体，
  **消耗 1 个、不返还空桶**。合成/其他消耗也不再返还空桶。
- 装了 mod 的客户端手持空桶时，准星会对液体启用流体射线（原版射线打不中液体）。
- 创造模式：仍只有空桶能挖，挖不掉落；放置不消耗。

> ⚠️ **功能三必须客户端与服务端都装**。只装服务端的原版客户端：桶的装/倒会被禁用，且因为原版射线打不中液体，无法挖液体（这是不装客户端功能时的固有限制）。

---

## 必须双端装 / 只要服务端装

| 功能 | 双端必装 | 仅服务端 |
| --- | --- | --- |
| 功能一（相属性、含熔岩/冰/黑曜石/霜冰、红石相） | ✅ | — |
| 功能二（炼药锅转化、发射器、漏斗） | — | ✅ |
| 功能三（液体物品、桶左键挖掘、准星射线） | ✅ | — |

**关闭双端功能以兼容原版客户端**：编辑 `config/liquidphase.json`，把 `phaseEnabled` 与 `liquidInteraction` 置 `false`。
关闭后服务端跳过状态注入与新方块注册，所有产生新相的路径返回拒绝；**旧存档里已有的相会直接丢失**（原版状态回退机制决定，无法补救）。

---

## 怎么玩

1. 装 Fabric Loader + Fabric API，把 jar 放进 `mods/`。
2. 拿熔岩桶点楼梯侧面 → 楼梯变含熔岩；用水桶点它 → 变含黑曜石，破坏楼梯掉楼梯 + 留下黑曜石。
3. 雨天放一个含水楼梯（水桶点楼梯）→ 低温会冻成含冰楼梯；光照够了会化回水相。
4. 空桶左键水源 → 掉落"水"物品；拿着它右键空气 → 放一个水源，物品消失、没有桶。
5. 熔岩锅旁边倒水 → 掉黑曜石；水锅上倒熔岩 → 按水位掉石头/圆石/圆石台阶。
6. 拿红石粉点楼梯 → 楼梯变成**含红石线的楼梯**：方块本身还是那格楼梯，只是多了 `phase` 取值
   （`redstone_0`…`redstone_15` 编码功率）。外面罩一层 16³ 红石粉尘外壳
   （透明底 cutout，贴图是**短碎屑铺满整面**的疏松红石纹理），形状/碰撞/掉落仍是楼梯自己的。
   按红石线规则衰减传递信号（对相邻红石线 -1，对灯/活塞等元件满功率），被宿主遮住的面不输出；
   破坏它 → 留下对应功率的红石线。
   也可以反过来：把楼梯/台阶等可含水方块**放进红石线所在的那一格**，那条线就被吸进宿主、
   变成含红石相的方块（功率沿原线）。这一步走的是普通方块放置流程，和谁在放无关：
7. 拿空桶**左键**水锅/熔岩锅 → 整锅拆空、掉落"水"/"熔岩"物品；**锅本身保留**。
   只有满锅才能取（水锅必须是 3 级）；右键对着锅不再取水。

---

## 兼容性注意

- **移除本 mod 会丢失所有相**（含熔岩/冰/黑曜石/红石相）。降级前请先清场。
- 本 mod 独占"相"语义，**不与 Fluidlogged 这类通用 fluidlogged mod 互通**（共存不崩，但互相看不见对方的数据）。
- 相位渲染用 blockstate JSON 覆盖（multipart + 内缩模型），与其他**重写过原版 blockstate JSON 的资源包/模组**会冲突。
- 26.x 的实验性红石评估器（`REDSTONE_EXPERIMENTS`）下，含红石相方块**不保证**与原版红石线行为一致。
- 红石相是**宿主方块上的 `phase` 取值**（`redstone_0`…`redstone_15`，功率直接编码在取值里），
  不注册任何新方块；方块仍是原版楼梯/台阶/墙/栅栏。渲染外壳只对 41 种宿主生成。
- **状态数会明显增加**：`phase` 从 8 个取值变成 24 个，1.21.11 加载后的总状态数约 51 万
  （0.1.4 约 17.6 万），启动注入时间相应变长；低内存环境请留意。
- 含固相方块的**相覆盖层会让方块看起来像整格黑曜石/冰**（内缩 0.25px 的立方体），但碰撞与挖掘仍按宿主：
  准星落在宿主露出的部分就挖宿主，落在相露出的部分就挖相。黑曜石相需要钻石镐才能挖动相本身
  （没有钻石镐时按黑曜石速度计算，非常慢——这是有意的设计，不是卡死）。
- 整格宿主（树叶/铜格栅/红树根）含黑曜石/冰相时整格显示相材质，不再显示宿主贴图。
- 状态数会随宿主数量增长（每宿主 ×23 相取值，其中 16 个 redstone 值编码功率）；
  已在各版本实测编译与加载，1.19.4 这类低内存环境请留意内存占用。
- 1.19.4/1.20.1 的状态注入使用 **Statement** 依赖（会自动随 mod 提供）。
- **依赖下限**：`fabricloader` 按各版本实测最小需求声明（1.19.4 ≥0.15.0、1.20.1 ≥0.16.10、
  1.21.1 ≥0.15.11、1.21.10 ≥0.17.0、1.21.11 ≥0.17.3、26.3 ≥0.19.3），
  `minecraft` 仍是补丁级匹配（`~x.y.z`），`fabric-api` 与 `statement` 为必需依赖。
- ⚠️ **旧 loader + 新 JDK 会启动失败**：loader 0.15.x/0.16.x 内置的 Mixin 读不了 JDK 25 的类
  （`Unsupported class file major version 69`），这不是本 mod 的问题。用 JDK 25 时请把 loader
  升到较新版本，或改用 JDK 17/21 启动。

---

## 变更记录

- **未发布（0.1.8：修 0.1.7 的两个实机问题）**：
  - **红石相表面按不透明渲染 / 楼梯形状消失**：根因不在渲染层注册（1.21.10/1.21.11 实测 `ItemBlockRenderTypes.getChunkRenderType` 已是 `CUTOUT`），而是 blockstate 把**宿主模型整个排除了**——宿主 part 的 `phase` 条件被限制成`none|lava|obsidian|ice|frosted_0..3`，于是含红石相时宿主根本不渲染，整格只剩一个 16³ 粉尘外壳。视觉上就是"整格变成不透明粉尘面、楼梯形状没了"。
    改为：宿主 part 的 `phase` 条件覆盖**全部 24 个取值**（任何相下都照常渲染），红石外壳只是**额外叠加**一层——与旧版组合方块"宿主 + 粉尘外壳同时渲染"的行为一致。
  - **可含水方块放不进红石线所在格（修法推翻重做）**：上一版的做法是放宽 `BlockBehaviour.canBeReplaced(state, context)`，让红石线在"手持相容宿主"时算作可替换。这解决了一半问题，但代价是**把红石线对全世界的可替换语义改掉了**——水流、活塞、邻居更新、其它模组都会把红石线当可替换方块，属于改坏底层语义。
    本版改为**只在放置上下文里放行**，见下文"放置入口"。
  - **放置入口（最终方案）**：能力属于"方块放置流水线"，与玩家、手、`InteractionHand`、潜行**完全无关**。
    根因只有一个：`BlockItem.place` 第一步 `context.canPlace()` 只问"目标格能不能被替换"，红石线不可替换 → 直接 FAIL。
    现在新增 `redstone/WirePlaceContext`：`canPlace()` 恒真（仅限该上下文）、`getClickedPos()` 指向线格；
    `BlockItemMixin` 在 `place` 入口用 `@ModifyVariable` 换上下文（**仅当要放的方块是 `PhaseCapable`**），
    在 `placeBlock` 入口补 `phase=redstone_<原线功率>`。其余流程原封不动
    （`updatePlacementContext → getStateForPlacement → placeBlock → setPlacedBy →` 音效/统计/扣物品），
    朝向/半砖/连接态/含水全部由宿主自己的 `getStateForPlacement` 按线格算出。
  - **撤回前两轮的两种错误做法**：删除 `RedstoneWireUseMixin`（接管红石线 `use`/`useWithoutItem`，
    把玩家点击手势写进逻辑，只对"点线本身"这一种姿势生效）与 `RedstoneWireReplaceMixin`
    （全局放宽 `canBeReplaced`，改坏方块语义）。
  - 自检改为 **48 项，六版本实测 48/48**：放置类断言**不再用 FakePlayer**，改成直接驱动
    `BlockItem.place`（无玩家、无手），并新增三条反向断言——非宿主方块仍放不进线格、
    红石线自身仍不可替换、普通红石粉放置不受影响。这样才能区分"精确放行"和"全局放宽"。
  - 新增 `tools/check_redstone_render.py`：按原版 multipart 语义模拟状态命中，断言红石相下 `host parts > 0`。
  - 新增 `tools/verify_jars.py`：发布前抽查 dist 里每个 jar 的版本号、非法 condition 数，
    并断言 `WirePlaceContext` 在包里、两个已删除的 mixin 确实不在包里。

- **未发布（修复 0.1.6 的两个实机问题）**：
  - **可含水方块整片变成紫黑"错误模型"**：`tools/gen_phase_blockstates.py` 合并条件时
    拼出了 `phase: "(obsidian)&(none|lava|...)"` 这种伪语法。原版 multipart 的 `when`
    没有 `&` 运算符——值里只有 `a|b`（同一属性的 OR），跨属性必须写成
    `{"AND": [...]}` / `{"OR": [...]}`。解析器把这个字符串当成一个属性取值去查，
    抛 `Unknown value`，**整份 blockstate 加载失败**，于是该方块全部状态都没有模型
    （日志刷 `Missing model for variant`），只有内缩的相覆盖层还渲染得出来。
    受影响的是 41 个红石相宿主（楼梯/台阶/墙/栅栏）的所有状态，六个版本都有。
    改为正确的嵌套条件合并（`and_condition()`），并给生成器加了产出前自检
    （`validate_result()`，非法条件直接让构建失败）。
  - **`resin_clump` 等方块报 `Unknown property 'phase'`**：1.21.10+ 的多面体家族
    （glow_lichen / sculk_vein / resin_clump）统一成 `MultifaceBlock`，但
    `PhaseCapableMixin` 的 stonecutter 分支把 live 分支写成了旧的
    `GlowLichenBlock`/`SculkVeinBlock`，导致这些方块拿不到 `phase` 属性，
    而生成器又给它们的 blockstate 加了 `phase` 条件。已把 1.21.10+ 分支改为
    `MultifaceBlock`（注入数 409 → 410）。
  - **向红石线占用的方块放置宿主"还是没反应"**：最常见的真实手势是线铺在地上、玩家拿着楼梯去点**线下面支撑它的方块**。此时原版 `BlockPlaceContext` 已经把`getClickedPos()` 算成"线所在那一格"，但那一格是红石线（不可替换）→ `canPlace()` 为 false；而 `RedstoneWireUseMixin` 只在**直接右键红石线**时触发，于是右键毫无反应。`BlockItemMixin` 新增 (b1) 分支显式接管这条路径：把宿主写进红石线所在格、功率沿用原线。
  - **自检补上"真实右键入口"**：新增 9 项断言用 `FakePlayer` 驱动
    `ServerPlayerGameMode.useItemOn` 把楼梯放进红石线格，覆盖客户端真正走的那条路；
    旧自检只调内部方法，mixin 失效也会 PASS。另加"每个 PhaseCapable 都有 phase 属性"
    与"生成器产出可被原版解析"两条守护断言。共 42 项，六版本实测 42/42。
  - 自检新增 `-Dliquidphase.selftest.stop=true`：跑完自动关服，便于脚本刷六版本。

- **0.1.6**：放宽 `fabric.mod.json` 的依赖声明——`fabricloader` 从构建用的 `>=0.20.1` 改为
  各版本实测最小需求（`max(Fabric API 要求, 0.15.0)`，0.15.0 起 loader 自带 MixinExtras）；
  `minecraft`（补丁级）、`fabric-api`、`statement` 经核实都是必需项，保留原样。
- **0.1.5**：红石相改为**真正的 phase 取值**（按你的要求重做架构）——
  ① 不再注册"组合方块"：`phase` 新增 `redstone_0`…`redstone_15` 共 16 个取值，功率直接编码在取值里；
     楼梯还是原来的楼梯，形状/碰撞/掉落/破坏保留全部沿用宿主逻辑，**没有新方块、没有新物品**；
  ② 信号语义随之重写：向相邻红石线/红石相宿主 -1 衰减、指向灯/活塞等元件满功率、
     被宿主覆盖的面既不输出也不连接、邻居变化**即时**重算；
  ③ 渲染：16 条 `when{phase: redstone_N}` 的 part 指向同一个 16³ 宽松红石外壳，
     贴图是短碎屑铺满整面的疏松纹理；
  ④ 红石粉点宿主、宿主右键点红石线格两条生成路径都保留；
  代价：`phase` 取值 8 → 24，1.21.11 状态数 175,607 → **509,175**，启动注入更慢。
  自检增至 35 项并全部通过（含"红石相仍是原方块""没有额外方块注册"）。
- **0.1.4**：修复 0.1.3 的四个实机问题——
  ① 红石相贴图每面只有两根线：改为短碎屑均匀铺满整面（16×16、约 29% 覆盖、跨格可拼贴）；
  ② 向红石线所在格放置可含水方块无效：之前把逻辑挂在 `BlockItem.place`，但红石线自己的
     点/十字切换会先吃掉右键，分支永远走不到；现在直接挂在红石线的右键入口（`RedstoneWireUseMixin`）；
  ③ 信号变化不是瞬时的：邻居变化原先排了 2 tick 计划刻，改为即时重算（只有功率真的变化才传播）；
  自检新增"供电即时性"与"向红石线格放置"两组断言（25 → 33 项）。
- **0.1.3**：修复五个实机问题——
  ① 红石相组合方块的**准星选择框只有宿主形状**（楼梯/台阶上点不中"红石外壳"）：选择框改为完整 16³，
     碰撞仍按宿主、不额外加碰撞；遮挡/透光同步按宿主（不会挡邻面渲染、不吃天光）；
  ② 红石相贴图只有正中一点：恢复 0.1.1 的**疏松红石线图案铺满正脸**（16×16、透明底、三档红色、
     四边接缝对称可拼贴），不再使用原版 `redstone_dust_dot`；
  ③ 红石相向四周**红石线输出未衰减**：组合方块 → 相邻原版红石线/其他组合方块按线规则 -1，
     指向灯/活塞/比较器等元件时仍输出满功率；被宿主覆盖的面既不输出也不连接；
  ④ **无法向红石线所在格放置可含水方块**：红石线自身的右键（点/十字切换）会先吃掉交互，
     现在手持可组合宿主时放过右键，并按线所在格计算宿主放置状态（朝向/半砖/连接态），功率沿用原线；
  ⑤ 炼药锅取水：空桶左键取水后**锅不再被一起破坏**（只取走水、留下空锅），
     且**只有满锅（水锅 LEVEL=3）才能取**；漏斗/发射器抽锅统一按满锅口径。
  一并修复：**瞄准红石外壳挖掘**走不到 `mineRedstonePhase`（服务端守卫只认固相）——
  现在瞄准红石外壳掉红石粉、原地留宿主；瞄准宿主露出部分则正常破坏宿主并留下对应功率红石线。
  组合方块的战利品表显式指向宿主（否则 `liquidphase:blocks/redstone_*` 不存在 → 破坏不掉落）。
  **1.21.1 重新启用红石相组合方块**（形状改为显式按宿主回答），六个版本现在都可用。
  新增**自动化自检**：`-Dliquidphase.selftest=true`（或环境变量 `LIQUIDPHASE_SELFTEST=true`）启动服务端，
  会在启动完成后跑 25 项断言（注册表、选择框/碰撞/遮挡、信号衰减与覆盖面、炼药锅取水门槛）并打 `PASS/FAIL`；
  六个版本实测 25/25 通过。
  版本号 0.1.2 → 0.1.3。
- **0.1.2**：修复四个实机问题——
  ① 含固相（黑曜石/冰）的整格宿主（树叶、铜格栅、红树根）看不到相：改为整格渲染相材质，
     `phase=none`/`lava` 仍显示宿主；
  ② 含固相方块"怎么都挖不掉"：瞄准判定原先用"合并轮廓（=整格）"与相区域比距离，两者外表面重合导致**恒判为相目标**
     （挖掘速度永远按黑曜石/冰算），且形状表型宿主对含相状态返回 null 进一步放大误判。现在改用**宿主轮廓**与相区域比距离，
     并用 `phase=none` 兄弟状态查询宿主形状——瞄准宿主就按宿主挖，瞄准相才按相挖；
  ③ 炼药锅桶交互：功能三开启时空桶左键整锅拆空并掉落液体物品；右键取水禁用（桶不再凭空消失）；
     液体物品往锅倾倒消耗 1 个且不返还空桶；
  ④ 红石相组合方块贴图与红石线不符、且遮挡宿主：贴图换成原版 `redstone_dust_dot` 按满功率染色，
     模型改为"宿主原版模型 + 完整 16³ 粉尘外壳"（cutout，透明像素透出宿主）。
  后续修复：瞄准红石相组合方块时崩溃（`Cannot set property phase as it does not exist`）——
  组合方块没有 `phase` 属性，`withPhase` 现在对无该属性的状态原样返回，宿主轮廓改走 `hostStateOf`。
  版本号 0.1.1 → 0.1.2。
- **0.1.1**：补齐 41 个红石相组合方块的 blockstate、完整方块模型与疏松红石线贴图（透明底 cutout，
  1.19.4/1.20.1/1.21.1/1.21.10/1.21.11 显式注册渲染层，26.3 按贴图透明度自动推导）；
  中英文名称补全；组合方块不再注册物品形态（玩法不变，仍由红石粉/液相方块交互生成）；
  消除方块缺模型产生的 missing model 警告。版本号 0.1.0 → 0.1.1。

---

## 许可

WTFPL v2（见 `LICENSE`）。引用的开源项目与许可：

- [Lavalogged](https://github.com/JimiIT92/Lavalogged)（MIT）：状态注入器与桶/放置 mixin 的方法论。
- [Statement](https://github.com/Virtuoel/Statement)（MIT）：1.19.4/1.20.1 的运行时方块状态属性注入。
- [cauldronfix](https://github.com/Ryan100c/cauldronfix)（CC0）：炼药锅行为对照（混凝土映射、水位掉落表、发射器/漏斗映射）。
- [Fluidlogged](https://github.com/Leximon/Fluidlogged)（MIT）：旁路流体与流体交互的参考。
- cauldron-expansion（CC-BY-NC-SA）：**仅作行为对照**，未复制代码或资源。
- 反编译源码来自 [guardian](https://github.com/hibiii/guardian)，仅用于本地比对，不随仓库分发。

---

## English

Let waterloggable blocks (stairs, slabs, walls, fences, signs, chests, leaves, chains, candles, …) hold a **second phase**:
lava, obsidian, ice, frosted ice, and redstone wire. One block = solid phase (the block itself) + at most one contained phase.

**Supported:** Fabric 1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11 / 26.3.

### Feature 1 — more logged blocks (required on both sides)

- **Lava inside**: a lava bucket (or a liquid-lava item), a dispenser, or a lava source flowing into a waterloggable block fills it.
  The contained lava is a real source: it flows out through the faces the solid phase leaves open, is fed by neighbouring lava using
  vanilla shape checks, emits light 15, ignites entities inside, and reproduces vanilla fire spread on random ticks.
- **Solidification**: water next to a lava-containing block turns its phase into **obsidian** (with the vanilla fizz).
- **Ice / frosted ice**: during cold precipitation, water-containing blocks freeze (vanilla only freezes water source blocks;
  this mod extends the check to hosts). Ice melts back to water when lit; in water-evaporating dimensions it simply disappears.
  Frosted ice follows the vanilla AGE 0–3 crack progression and neighbour rules (`frosted_0`…`frosted_3`).
- **Breaking keeps the phase**: breaking a phased block drops the host loot and leaves the real lava/obsidian/ice/frosted ice block behind.
  The phased block itself breaks like a vanilla block: aim at a part where the host is exposed and it mines at host speed,
  aim at an exposed phase and it mines as the phase material.
- **Removing the phase**: empty bucket with left-click when feature 3 is on (right-click when it is off). Solid phases cannot be collected.
- **Placement absorption**: point an ice/obsidian item at a waterloggable block to fill it; place a waterloggable block into a tile holding
  ice/obsidian/a lava source to absorb that phase.
- **Collision**: ice and obsidian phases are full-cube (matching the "phase fills the host's gaps" visuals); lava adds no collision.
- **Mining the phase directly**: when the crosshair points at an exposed phase, mining speed and drops follow the phase material
  (obsidian needs a diamond pickaxe; ice melts to water without silk touch; frosted ice has no item).
- **Full-cube hosts**: leaves, copper grates and mangrove roots (whose vanilla model already fills the whole 16³ cube) render as
  the phase material when phased — otherwise the overlay would be hidden inside the host model. `phase=none`/`lava` still show the host.

### Feature 2 — cauldron behaviour (server-side only, vanilla-representable part)

Lava cauldron + water drops one obsidian and empties; water cauldron + lava drops stone/cobblestone/cobblestone slab by level 3/2/1;
empty cauldrons collect only **directly adjacent** source fluid; concrete powder + water cauldron hardens into 16 coloured concretes
(one level each); powder-snow cauldron + water/lava becomes a water cauldron; dispensers fill/empty cauldrons and bottles;
hoppers move liquid items in and out of fluid cauldrons.

With feature 3 on, buckets interact with cauldrons the "liquid item" way: **left-click** a water/lava cauldron with an empty bucket
to empty the whole cauldron and drop the matching liquid item **without breaking the cauldron itself**; only a full cauldron
(water cauldron at level 3) can be emptied. Right-click filling is disabled (the bucket is never swapped away);
pouring a liquid item into an empty/partially filled cauldron still fills it but consumes one item with no bucket returned.
**Not** included: potion cauldrons, dyed-water cauldrons, obsidian-block cauldrons — all need block entities.

### Feature 3 — player ↔ liquid interaction (**required on both sides**)

Left-click liquids with an **empty bucket** to instantly mine them and **drop the liquid item**; right-click with a water/lava liquid item
to place liquid like a block (**one consumed, no bucket returned**, and no remainder in recipes). A modded client enables fluid rays for the
crosshair while holding an empty bucket. Creative mode keeps bucket-only mining with no drops and free placement.

> ⚠️ Feature 3 needs the mod on **both** sides. With a vanilla client on a modded server, bucket fill/place is disabled and liquids cannot be
> mined (the vanilla crosshair ray never hits fluids).

### Both sides vs server only

| Feature | Both sides | Server only |
| --- | --- | --- |
| Feature 1 (phases, composites) | ✅ | — |
| Feature 2 (cauldrons, dispensers, hoppers) | — | ✅ |
| Feature 3 (liquid items, mining, raycast) | ✅ | — |

Turn the two-sided features off for vanilla clients via `config/liquidphase.json` (`phaseEnabled`, `liquidInteraction` = `false`).
Existing phases in old worlds are then **lost permanently** (vanilla drops unknown states).

### Compatibility notes

Removing this mod loses every phase. It does not interoperate with generic fluidlogged mods (coexistence is crash-free, data is not shared).
Phase rendering overrides vanilla blockstate JSONs, so resource packs/mods that rewrite those files conflict.
Under the 26.x experimental redstone evaluator (`REDSTONE_EXPERIMENTS`) redstone-phase blocks are not guaranteed to match vanilla wire behaviour.
Redstone-phase composites are available on **all six versions** (41 stair/slab/wall/fence hosts). 1.21.1 used to skip
them because host shape tables returned null for composite states; the composite now answers outline/collision/support/
occlusion explicitly from its host, and all six versions pass startup + self-test. Composites render as **the vanilla host
model with a full-cube redstone-dust shell on top**:
the shell uses a porous redstone-wire texture that fills each face, transparent pixels let the host show through (cutout), the crosshair outline
covers the whole cube (collision stays with the host), and they have **no item form**; they are only created through redstone-dust/phase-block
interactions (either dust on a host, or a host block item placed into the redstone-wire tile through the normal block placement pipeline).
Signals decay by 1 into neighbouring wires/composites
but stay at full power for lamps, pistons and other components.
Phased blocks with a solid phase look like a full cube of that material, but collision and mining still follow the host: aim at the exposed host and
it mines at host speed, aim at the exposed phase and it mines as the phase material (obsidian still needs a diamond pickaxe — that slowness is intended).
Full-cube hosts (leaves, copper grates, mangrove roots) show the phase material instead of the host texture while phased.
1.19.4/1.20.1 use **Statement** for state injection (shipped as a dependency).
Dependency floors follow each version's real minimum (`fabricloader` 0.15.0 / 0.16.10 / 0.15.11 /
0.17.0 / 0.17.3 / 0.19.3 respectively), `minecraft` stays patch-level (`~x.y.z`), and `fabric-api` plus
`statement` remain required. Note: loader 0.15.x/0.16.x bundle an old Mixin that cannot read JDK 25 class
files (`Unsupported class file major version 69`) — use a newer loader or JDK 17/21 in that case.

### Licence

WTFPL v2 — see `LICENSE`. Credits: Lavalogged (MIT), Statement (MIT), cauldronfix (CC0), Fluidlogged (MIT);
cauldron-expansion (CC-BY-NC-SA) used for **behaviour comparison only**, no code or assets copied.
Decompiled sources via guardian, used locally for verification only.
