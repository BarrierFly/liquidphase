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
  > ⚠️ "被相邻熔岩灌入"对台阶/蜡烛/营火/干化恶魂不成立（它们自己覆写了 `placeLiquid`）：
  > 熔岩桶右键、放置吸收照常可用。见 `docs/host-coverage-audit.md` §4.3。
- **凝固**：含熔岩方块旁边出现水 → 相变为**含黑曜石**，伴随嘶声粒子。
- **含冰 / 含霜冰**：雨天低温下"含水的方块"会冻结成含冰相（原版只会冻水源方块，本 mod 把判定扩展到宿主）；
  冰相在光照足够时融化回水相；下界/水蒸发维度融化时直接消失。
  霜冰相按原版 AGE 0–3 的裂纹推进与相邻融化规则演化（`frosted_0`…`frosted_3`）。
- **破坏保留**：破坏含相方块 → 掉落固相战利品，该格留成真实熔岩源 / 黑曜石 / 冰 / 霜冰。
  **含相方块本身可以像原版方块一样正常破坏**：准星落在宿主露出的部分就按宿主材质挖，落在相露出的部分才按相材质挖
  （`phase=none` 的兄弟状态参与命中判定，形状表型宿主也不会误判）。
  > ⚠️ 带 `offsetType` 的 4–5 个宿主（`hanging_roots`、`mangrove_propagule`、`pointed_dripstone`、
  > `small_dripleaf`、26.3 的 `sulfur_spike`）例外：破坏时**不留下相**。
- **收走相**：功能三开启时空桶左键；关闭时空桶右键。固体相（冰/霜冰/黑曜石）不响应右键收取。
- **放置吸收**：拿冰/黑曜石物品指向可含水方块 → 写入该格；把可含水方块放进冰/黑曜石/熔岩源所在的格子 → 吸收该相。
- **碰撞**：冰/黑曜石相按整格实心（与"相填满宿主空隙"的视觉一致）；熔岩相不额外加碰撞。
  > ⚠️ 实测只有一部分宿主成立：栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架、`sculk_shrieker`、
  > `big_dripleaf` 的固相碰撞仍是宿主形状（它们自己覆写了块级 `getCollisionShape`）。
  > 逐版本数字见 `docs/host-coverage-audit.md` §4.4。
- **直接挖相**：准星指向相露出的部分时，挖掘速度与掉落按相材质计算（黑曜石需钻石镐；冰/霜冰不精准采集则化水，霜冰无物品）。
- **整格宿主的相**：树叶、铜格栅、红树根这类"模型就是完整 16³ 立方体"的宿主，含相时整格替换为相材质
  （否则覆盖层藏在完整宿主模型内部，玩家看不到相）；`phase=none`/`lava` 仍显示宿主本体。
  > ⚠️ 判定是**方块级**的：台阶的 `type=double` 变体引用整块模型，但同一个方块的
  > `bottom`/`top` 变体是半砖，所以整块判定为否 → 双台阶的相覆盖层藏在宿主里，**含固相看不见**
  > （57–101 个宿主）。见 `docs/host-coverage-audit.md` §4.1。

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
6. 拿红石粉点楼梯 → 楼梯变成**含红石线的楼梯**：方块本身还是那格楼梯，只是多了 `filling` 取值
   （`redstone_0`…`redstone_15` 编码功率）。外面罩一层 16³ 红石粉尘外壳
   （透明底 cutout，贴图是**短碎屑铺满整面**的疏松红石纹理，
   **亮度随功率变化**——0 档接近熄灭、15 档满亮，和原版红石线一致），
   形状/碰撞/掉落仍是楼梯自己的。
   按红石线规则衰减传递信号（对相邻红石线 -1，对灯/活塞等元件满功率），被宿主遮住的面不输出；
   破坏它 → 留下对应功率的红石线。
   也可以反过来：把楼梯/台阶等可含水方块**放进红石线所在的那一格**，那条线就被吸进宿主、
   变成含红石相的方块（功率沿原线）。这一步走的是普通方块放置流程，和谁在放无关：
7. 拿空桶**左键**水锅/熔岩锅 → 整锅拆空、掉落"水"/"熔岩"物品；**锅本身保留**。
   只有满锅才能取（水锅必须是 3 级）；右键对着锅不再取水。

---

## 兼容性注意

- **移除本 mod 会丢失所有相**（含熔岩/冰/黑曜石/红石相）。降级前请先清场。
- **0.1.15 起属性名为 `filling`**（原 `phase`）：跨 0.1.14 ↔ 0.1.15 升降级都会丢相，请先清场。
- 本 mod 独占"相"语义，**不与 Fluidlogged 这类通用 fluidlogged mod 互通**（共存不崩，但互相看不见对方的数据）。
- 相位渲染用 blockstate JSON 覆盖（multipart + 内缩模型）。**0.1.17 起与第三方资源包自动兼容**：
  资源包覆盖宿主 blockstate（`assets/minecraft/blockstates/<宿主>.json`——栅栏/树叶/告示牌类包
  很常见）时，客户端会把相部件**合并进包的定义**，包只生效于宿主 part、相照常显示；
  成功与降级各有一条日志（`merged phase overlays into …` / `overlay could not be merged …`）。
  仅当包把宿主 part 的条件重排到无法精确匹配（罕见）时该宿主退回旧行为——
  **症状是"某些宿主的所有相完全不显示"**（宿主本体正常、熔岩相因走流体渲染也正常）。
  验证方法：F3 看该方块的 `filling` 属性是否已写入——已写入但不显示即触发了降级，
  可用配置 `packMerge=false` 复现旧行为进一步定位；针对本 mod 的资源包
  （引用 `liquidphase:` 模型）不受合并影响，仍可整体覆盖。
- 26.x 的实验性红石评估器（`REDSTONE_EXPERIMENTS`）下，含红石相方块**不保证**与原版红石线行为一致。
- 红石相是**宿主方块上的 `filling` 取值**（`redstone_0`…`redstone_15`，功率直接编码在取值里），
  不注册任何新方块；方块仍是原版楼梯/台阶/墙/栅栏。渲染外壳对**全部**相容宿主生成
  （含树叶/格栅/玻璃板/铁栏杆/梯子/锁链等透明方块），**外壳亮度随功率变化**。
  外壳跟着"运行时真的拿到 `phase` 属性的方块"走：清单由 `tools/gen_phase_blocks.py`
  从原版 `Blocks.java` 推导，并用运行时注入数硬校验，漏一个就会让那个方块含红石相时
  什么都不显示（0.1.12 修的就是这个）。
- **吸收按"可替换填充相"规则判定**（0.1.17 起）：向宿主写填充材料时只看**现有填充相**——
  空格 / 含水 / 含熔岩相可替换（冰替换水=冻结、熔岩或黑曜石替换水、黑曜石覆盖熔岩相、
  红石粉也能替换水/熔岩），冰/霜冰/黑曜石/红石相不可替换、材料按不可放置处理。
  与 0.1.16 的差别：含水/含熔岩的宿主**不再拒绝**填充材料。水的流体路径不变：
  水桶/水流仍只进无相宿主，水不能直接替换熔岩相（含熔岩相宿主遇水邻居走凝固成黑曜石相）。
- **告示牌/箱子/屏障/光源方块等原生"不渲染方块模型"的宿主也能显示相**：带相状态一律按
  方块模型渲染，宿主本体仍由各自的原版渲染路径绘制。
- **状态数会明显增加**：`phase` 从 8 个取值变成 24 个，1.21.11 加载后的总状态数约 51 万
  （0.1.4 约 17.6 万），启动注入时间相应变长；低内存环境请留意。
- 含固相方块的**相覆盖层会让方块看起来像整格黑曜石/冰**，碰撞也是**整格实心**（对所有宿主一致，
  0.1.13 起"整格碰撞"挂在状态级，栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架这些
  自己覆写碰撞的宿主也生效）；挖掘按双口径：准星落在宿主露出的部分就挖宿主，
  落在相露出的部分就挖相。黑曜石相需要钻石镐才能挖动相本身
  （没有钻石镐时按黑曜石速度计算，非常慢——这是有意的设计，不是卡死）。
- 整格模型宿主（树叶/铜格栅/红树根）与台阶的 `type=double` 变体含黑曜石/冰相时整格显示相材质，
  不再显示宿主贴图；台阶的半砖变体仍是"宿主 + 内缩覆盖层"。哪些变体算"整格"由构建时按
  模型几何自动判定（不再手写清单），新版本新增的树叶会被自动识别。
- **熔岩桶右键按原版倒水优先级**（0.1.13 起，与原版"水桶点方块"完全同规则）：
  原版交互不消费右键的宿主（楼梯/台阶/栅栏/玻璃板/树叶等）→ 右键直接灌入；
  原版交互消费右键的宿主（**活板门开关、箱子/末影箱开箱、光源方块调亮度、告示牌开编辑、
  装饰陶罐收纳、搁物架/铜傀儡雕像交互**）→ 原版行为优先、不灌入，
  和"水桶点活板门只会开关它"一样。要给这些宿主灌相：**潜行右键**（原版绕过方块交互）、
  发射器、或放置吸收（拿方块放进熔岩源格）。各版本行为一致（0.1.12 及以前 ≤1.20.1
  会无视原版交互强制灌入）。
- **覆盖面缺口已在 0.1.13 全部收口**（审计与修复过程见 `docs/host-coverage-audit.md`）：
  双台阶含固相看不见、台阶/蜡烛/营火/干化恶魂灌不进相邻熔岩、固相整格碰撞只覆盖一半宿主、
  带 `offsetType` 的宿主破坏不留相、`PlacementMixin` 在 1.19.4–1.21.1 漏 `MultifaceBlock`、
  1.19.4/1.20.1 `barrier` 的 `IllegalArgumentException` 崩溃面（这两个版本的 `barrier`
  不再是相容宿主，本就不含水）、熔岩桶右键的版本不一致——全部按上文两条说明处理。
  1.21.1+ 的 `barrier` 仍是相容宿主（红石相正常显示），但桶/放置吸收/相邻熔岩充不进相：
  原版对 barrier 的流体接受是"创造模式限定"，而相充入的探测调用不携带玩家身份
  （0.1.12 起如此，非本次改动；熔岩桶点 barrier 只是拒绝，不会崩）。
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

- **0.1.17（可替换填充相 + 资源包自动合并 + 吸收门控防漂移）**：
  - **吸收机制改为"可替换填充相"规则**：向宿主格写入填充相材料（冰/霜冰/黑曜石/熔岩/红石粉）
    时只判定**现有填充相是否可替换**——空格、含水（水即"现有填充相"）与含熔岩相可替换，
    冰/霜冰/黑曜石/红石相不可替换、按不可放置处理。0.1.16 及以前一律要求"无相且未含水"，
    所以含水/含熔岩的宿主现在能被吸收（冰替换水=冻结、熔岩替换水、黑曜石覆盖熔岩相等），
    且该规则由 `Phase.isReplaceableFilling()` 统一维护，未来加可替换相只改一处。
    **边界**：水的流体容器路径不变——水桶/水流仍只进无相宿主（含熔岩相宿主遇水邻居
    仍走"凝固成黑曜石相"，水桶不能直接替换熔岩相）；熔岩桶对熔岩相宿主幂等重灌成功。
  - **吸收门控防漂移**：接受层（`canBeReplaced`）/改写层（`Level.setBlock`）/交互层
    （`BlockItem.place` 落点吸收）的判定全部收敛到唯一判定源 `PhaseHelper.canAcceptPhase`，
    不再各自复写内联门控；自检新增 `checkAbsorptionGateConsistency`——楼梯 ×
    现有填充相 {空/含水/熔岩/冰/黑曜石/红石} × 材料 {冰/黑曜石/红石粉/熔岩/水}
    全矩阵对四条路径逐一对账（A==B==C==D，含终态一致性），并对**全部相容宿主**
    扫描判定源与接受层一致。自检总数 162→295（1.21.10 为 296）。
  - **资源包冲突自动合并（0.1.16"实锤冲突"的完整解法）**：第三方资源包覆盖宿主
    blockstate 时，客户端在 blockstate 加载点把 mod 的相部件（覆盖层/红石外壳/整格
    相材质）**合并进获胜定义**，使包只生效于宿主 part、相外观不再丢失——不需要再
    手动编辑资源包。合并核心 `PhasePackMergeCore`（纯 JSON）：包若为 variants 形式
    先转等价 multipart；整格宿主（树叶/铜格栅/红树根）统一门控宿主 part 到
    "宿主可见"的相（第三方包把树叶拆成 per-distance 变体也能全部命中）；
    混合宿主（台阶）按变体精确匹配门控 `type=double`。降级约定：包把宿主 part 条件
    重排到无法精确匹配时，该宿主退回旧行为（相被宿主模型挡住），不崩溃；
    合并成功/降级各有一条日志。针对本 mod 的包（引用 `liquidphase:` 模型）不受影响，
    仍可整体覆盖。可用配置 `packMerge`（默认开）关闭。客户端注入点 ≤1.21.1 挂
    `ModelManager.loadBlockStates`、1.21.10+ 挂 `BlockStateModelLoader.loadBlockStates`，
    一处代理覆盖两条管线。已在 1.21.11 实机验证：含 oak_stairs/stone_slab/oak_leaves
    blockstate 的第三方包 → 日志 `merged phase overlays into 3 foreign blockstate(s)`，
    无 `Unknown property 'filling'`、无 missing model。
  - 片段资源：生成器为每个宿主另出 `assets/liquidphase/phase_parts/<id>.json`
    （纯相部件 + 门控），与烘焙 blockstate 由同一份逻辑产出并走同一套条件校验。

- **0.1.16（填充相放置手势补全：任意来源落进宿主格都写相）**：
  - **新增"落点吸收"**：点**旁边**的方块让红石粉/冰/黑曜石/宿主落进相容宿主格——
    原版会因落点格不可替换直接 FAIL 的那条路径——现在同样写相
    （红石粉→红石相、冰→冰相、宿主落进冰块格→吸收冰相）。此前只支持
    直接点宿主本身，这条路径从未被接管（用户实测缺口）。
  - **通用改写层**：`Level.setBlock` 对"无相未含水的相容宿主格"写入填充相材料
    （冰/霜冰/黑曜石/红石线）时统一改写为"宿主 + 填充相"——玩家、发射器、命令、
    下落方块、任意 mod 的写入全部覆盖；接受层挂在状态级 `canBeReplaced`
    （与线格接受宿主同一架构，方向互补）。水/熔岩不在改写口径（走流体容器路径）。
  - **资源包冲突实锤**（用户实测定位）：资源包只要包含宿主的 blockstate
    （`assets/minecraft/blockstates/<宿主>.json`——栅栏/树叶/告示牌类包很常见），
    用户资源包优先级高于 mod 内置资源，该宿主**所有相的覆盖层与外壳都会消失**
    （宿主本体与熔岩相不受影响）。实机复现：1.21.11 + 只含 oak_fence/oak_leaves
    blockstate 的包 → 探针 4 项 BROKEN。**解法：编辑资源包移除这些条目**（mod 无法
    盖过用户资源包，这是原版资源栈设计）。

- **0.1.15（发射器灌宿主 / 空桶保宿主 / 熔岩相重流：三处缺口修复，audit v2 §2）**：
  - **发射器液体桶对宿主灌入**：0.1.14 及以前，自动化配置（默认全开）下发射器对前方
    相容宿主把整桶弹射；现在按原版桶语义灌入（熔岩写相、水含水），宿主拒绝（已有相/
    含水/barrier）时仍按原版弹射。功能三开启消耗 1 个不返桶，关闭返还空桶。
  - **发射器空桶对含水宿主只收水**：0.1.14 及以前直接 `setBlock(AIR)` 连方块一起毁；
    现在走 `BucketPickup.pickupBlock` 口径——只收水、保宿主。
  - **熔岩相重流**：`scheduleLavaFlowTick` 此前从未被调用（死代码），流出部分被移走后
    源不再重流；现在邻居变化时重排流体 tick，与原版源块语义对齐。
  - 自检新增 15 项回归断言（147→162；1.21.10 为 163）。
- **0.1.15（填充相口径：属性名 `phase` → `filling`，破坏性变更）**：
  - 方块状态属性由 `phase=lava/ice/.../redstone_N` 改为 **`filling=...`**——填充相的
    "填充"语义直接体现在属性名上（`filling=lava`、`filling=redstone_15`）。
  - **破坏性**：属性名变更使旧存档（0.1.14 及以前）里的相丢失，与"移除 mod 丢相"同口径；
    取值枚举不变（lava / obsidian / ice / frosted_0..3 / redstone_0..15），仅属性名变。
  - 资源重生成：全部相容宿主的 blockstate 覆盖层条件键同步改为 `filling`。

- **0.1.14（26.3 相覆盖层/红石外壳不显示：修复 bake 出界）**：
  - **根因**：26.3 的 `FaceBakery` 会按每个 quad 的 UV 区域调用
    `SpriteContents.computeTransparency` 计算贴图透明度；红石外壳模型为了防 z-fighting
    采用 -0.01..16.01 的外扩几何，自动 UV（=元素包围盒）落到像素 [-1,17] 出界，
    `NativeImage.computeTransparency` 直接抛 `IllegalArgumentException`。26.3 上任一
    state 的 quad 失败会让**整个 blockstate bake 失败**，该宿主全部状态回退——
    相覆盖层与红石外壳全部不渲染（宿主本体与熔岩相不受影响：前者是原版模型，
    后者走流体渲染）。1.19.4–1.21.11 没有这条按 UV 计算透明度的路径，不受影响。
  - **修复**：外壳模型的每个面补显式 `uv: [0,0,16,16]`——命中
    `SpriteContents.computeTransparency` 的整图快速路径（不逐像素扫描），几何保持外扩，
    其余版本视觉与行为不变。由 `tools/gen_redstone_assets.py` 生成时写入。
  - **验证**：26.3 实机探针（全部 505 宿主逐相：固相覆盖层 quad、红石外壳 quad、
    RenderShape、逐 quad 图层）修复前 1010 项 BROKEN、bake 警告 639,600 条；
    修复后 0 BROKEN、0 条。1.21.11/1.20.1 回归探针 0 BROKEN。

- **0.1.13（相容宿主覆盖面：审计 + 7 处缺口全部收口）**：
  - **审计**：新增 `tools/audit_host_coverage.py` 与 `docs/host-coverage-audit.md`，把
    "哪些方块应当能含相"与"实际覆盖了哪些"拆成四条链路（A 原版真值 / B 注入 phase /
    C 生成清单 / S 红石外壳）逐版本、逐提交做差集，并检查"能力被宿主覆写遮蔽"的三类缺口。
    历史结论：`B-C`（有 `phase` 属性、无 blockstate）从 0.1.0 到 0.1.11 一直是
    1 / 1 / 0 / 24 / 24 / 197，**0.1.9、0.1.10、0.1.11 三次"显示修复"都建立在同一份错误清单上**；
    `C-S`（无红石外壳）0.1.5–0.1.8 为 267 / 269 / 296 / 345 / 345 / 267，0.1.9 起归零。
  - **修复 ①（1.19.4/1.20.1 `barrier` 崩溃面，audit §4.2）**：这两个版本的 `BarrierBlock`
    没有 `WATERLOGGED` 属性，注入 `PhaseCapable` 后水桶/空桶/水流一碰就在默认方法里抛
    `IllegalArgumentException`。`BarrierBlock` 从这两个版本的 mixin 目标与 `.ids` 清单移除
    （运行时注入数 309→308、311→310，`EXPECTED_COUNTS` 同步更新）。
  - **修复 ②（双台阶含固相看不见，audit §4.1）**：整格判定从方块粒度改到**变体（part）粒度**
    ——台阶是"混合模型"宿主（`type=double` 引用整块模型），方块级 `all(...)` 必然判否，
    覆盖层整个藏在宿主模型里。现在整格变体换 `phase_*_full` 相材质、半砖变体保持
    "宿主 + 内缩覆盖层"（条件用 `!double` 取反）。62 个台阶（1.21.11）受益。
  - **修复 ③（台阶/蜡烛/营火/干化恶魂灌不进相邻熔岩，audit §4.3）**：这四类宿主自己
    覆写 `canPlaceLiquid`/`placeLiquid`，遮住了 `PhaseCapable` 的熔岩分支。新增
    `SlabLiquidMixin`（台阶：两个方法都接管）与 `HostPlaceLiquidMixin`（蜡烛/营火/
    干化恶魂：`placeLiquid`），只接管熔岩分支，水仍走宿主原版逻辑。
  - **修复 ④（固相整格碰撞只覆盖一半宿主，audit §4.4）**：整格碰撞从块级
    `BlockBehaviour.getCollisionShape`（被栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架、
    `sculk_shrieker`、`big_dripleaf` 自己覆写而失效）移到**状态级**
    `BlockStateBase.getCollisionShape` 的 RETURN（三参 + 走 `Cache` 的两参都挂），
    对全部宿主一致生效，与"整格碰撞（D7）"的文档口径终于一致。
  - **修复 ⑤（offsetType 宿主破坏不留相，audit §4.5）**：删掉 `!state.hasOffsetFunction()`
    保护——`hanging_roots` / `mangrove_propagule` / `pointed_dripstone` / `small_dripleaf`
    （26.3 另有 `sulfur_spike`）破坏后同样留下相实体化的方块。
  - **修复 ⑥（放置吸收漏多面体，audit §4.6）**：`PlacementMixin` 的 `MultifaceBlock`
    改为全版本目标（它的 `getStateForPlacement` 全版本由它声明），1.19.4–1.21.1 的
    `glow_lichen` / `sculk_vein` 放进熔岩源格重新吸收相。
  - **修复 ⑦（熔岩桶右键按原版倒水优先级，audit §4.7）**：删除 `BucketUseMixin`——
    它在块级 `use`/`useItemOn` 上强制灌入，会凌驾原版方块交互（≤1.20.1 连箱子/告示牌都灌），
    且审计文档对"1.21.1+ 不再灌入"的推断有误（`BlockBehaviour.useItemOn` 基类钩子
    本就覆盖未声明交互的宿主）。现在熔岩桶右键与原版水桶完全同规则：
    原版交互消费右键的宿主（活板门/箱子/末影箱/光源/告示牌/装饰陶罐/搁物架/雕像）不灌入，
    其余宿主经 `useItem → emptyContents → BucketItemMixin` 灌入；潜行右键绕过交互照常灌入，
    发射器/放置吸收不受影响。
  - **顺带修复（潜伏 CCE）**：`BucketItemUseMixin` 在 ≤1.21.1 的 `CallbackInfoReturnable`
    类型写成了 1.21.10+ 的 `InteractionResult`（宿主方法返回 `InteractionResultHolder`），
    `setReturnValue` 后强转必崩。以前被 `BucketUseMixin` 提前消费右键而很少走到，
    修复 ⑦ 后桶走原版路径，故按版本分支修正返回类型。
  - **自检**：新增 6 组覆盖面回归断言（固相整格碰撞全量扫描两参+三参、offset 宿主破坏保留
    全量扫描、遮蔽方法熔岩灌入、多面体放置吸收、真实玩家姿势的熔岩桶交互优先级、
    台阶变体渲染资源），自检断言数 **147（1.19.4/1.20.1/1.21.1/26.3）/ 148（1.21.10）**，
    六版本实测全过；`tools/audit_host_coverage.py` 的覆写遮蔽清单会标注已被新 mixin
    接管的条目。
  - 版本号 0.1.12 → 0.1.13，六版本重新构建并替换 dist。
- **0.1.12（部分含红石方块不显示红石相：真正根因）**：
  - **根因：`tools/phase-blocks/<版本>.ids`（生成 blockstate 用的 id 清单）漏登记了运行时
    真的会被注入 `phase` 的方块。**
    运行时 `LiquidPhase.injectPhaseProperty` 遍历 `BuiltInRegistries.BLOCK`，给**每一个**
    `instanceof PhaseCapable` 的方块加 `phase` 属性；标记接口挂在 `PhaseCapableMixin` 的
    一组原版基类上，**这些基类的全部已注册子类都会继承到它**。而 blockstate（相覆盖层 +
    红石外壳）是按 `.ids` 逐 id 生成的。两边不一致时，那些"拿到了 `phase` 却不在清单里"的
    方块含相后仍用原版 blockstate——里面没有任何 `phase` 条件、也没有外壳 part，
    于是**红石相完全不显示**（固相也只剩原版外观）。
    实测缺口（运行时注入数 − 生成数）：

    | 版本 | 注入 | 生成 | 缺口 | 漏掉的代表方块 |
    | --- | --- | --- | --- | --- |
    | 1.19.4 | 309 | 308 | 1 | `barrier`（closure 清单漏 `BarrierBlock`） |
    | 1.20.1 | 311 | 310 | 1 | 同上 |
    | 1.21.1 | 337 | 337 | 0 | — |
    | 1.21.10 | 410 | 386 | 24 | 铜栏杆/铜锁链/铜灯笼全家 |
    | 1.21.11 | 410 | 386 | 24 | 同上 |
    | 26.3 | **505** | 308 | **197** | 染色玻璃板、墙告示牌、墙珊瑚扇、羊毛/混凝土楼梯与台阶、氧化铜全家…… |

    三处解析缺陷（`tools/gen_phase_blocks.py`）：
    1. 只认 `public static final Block X = register(...)`，于是
       `WeatheringCopperBlocks.create("copper_bars", ...)`（1.21.10/1.21.11）整类漏掉；
    2. 26.3 改成 `ColorCollection.registerBlocks` / `WeatheringCopperCollection.registerBlocks`
       批量注册（id 常量在 `references/BlockItemIds.java`），旧解析器只覆盖了前者；
    3. 26.3 的 `ColorCollection.zipMap(VALUES, BlockItemIds.WOOL_STAIRS, ...)` 里 id 常量在
       **第二个**参数位，而不是第一位。
    生成器现在会展开本地静态工厂、解析 id 常量（含 `createSimpleColored` /
    `createSimpleCopper`）、按"注册形式 × 颜色/氧化态"展开真实 id，并用
    `EXPECTED_COUNTS`（= 各版本运行时日志里的注入数）**硬校验**，数量不符直接让构建失败。
  - **配套修掉"输出目录不清理"**：生成器原先只写不删，从清单里移除某个 id 后旧 blockstate
    仍会留在输出目录、继续被打进 jar。那个方块若拿不到 `phase` 属性，客户端就会报
    `Unknown property 'phase'`，整份 blockstate 加载失败、该方块所有状态退成 missing model。
    现在每次生成后删掉本次未产出的 blockstate。
  - **撤回 `PhaseSkipRenderingMixin`**（0.1.9 加的）：`skipRendering` 只作用于带
    `cullface` 的 quad，而相覆盖层与红石外壳从 0.1.10 起**全部不带 `cullface`**，
    它们根本不会进入那条判定。这个 mixin 唯一还能起作用的地方，是让**宿主自己**的
    带 `cullface` 面（楼梯 29 面、栅栏 4 面……）在含相时不再被邻居剔除——
    那是改了原版剔除语义，与本问题无关，也已由去 `cullface` 的模型几何修掉。
    随之删除 `PhaseRenderSupport.allowVanillaCulling` 及其断言。
  - **新增根因级回归断言**：自检遍历所有 `PhaseCapable` 方块，断言每一个都有
    "含 `liquidphase:block/redstone_phase_` 的 blockstate"；`tools/verify_jars.py`
    不再抽查几个代表方块，而是扫描 jar 内**全部**生成产物，逐个断言
    `phase=redstone_0/15` 都有对应档位的外壳 part，并反向断言清单里每个 id 都有产物
    且产物集合与清单**完全相等**（多一个少一个都判 BROKEN）。
    已实测：把 1.21.11 的清单回退到 0.1.11 的 386 条，自检立刻报出那 24 个铜方块。
  - 自检断言数 **111（1.19.4–1.21.11）/ 110（26.3），六版本实测全过**。
    三处"抽查"都改成了**全量扫描**（原先只查几个代表方块，正是漏块能长期存在的原因）：
    1. 逐个检查清单里**每个** blockstate 都有 16 档外壳 part、宿主 part 条件正确；
    2. 双向集合比对：随 jar 发布的 `phase-blocks.ids` vs 运行时所有 `instanceof
       PhaseCapable` 的方块（只比数量不够——清单两边各错一个、数量仍相等，漏掉的照样不显示）；
    3. 遍历**全部** phase 方块里"原生 RenderShape 不是 MODEL"的那些，逐个断言带相后
       变 MODEL（实测 1.20.1 有 51 个，原先只写死了 6 个）。
    已实测：把 1.21.11 清单改成"删掉 `barrier` + 加一个假 id"（总数仍是 410），
    自检立刻同时报出两条失败并点名 `barrier`。
  - 版本号 0.1.11 → 0.1.12，六版本重新构建并替换 dist。
    （26.3 的 jar 从 322 KB 涨到 433 KB：多生成 197 份 blockstate 属于必要开销。）
- **0.1.11（含红石相方块的显示问题全面收口）**：
  这一版把"某些宿主含相后看不见"归结到的四个独立根因全部修掉。
  - **原生不渲染方块模型的宿主，含相后什么都不显示**：
    根因是 `RenderShape`。区块构建器只在"该渲染方块模型"时才请求模型——1.19.4/1.20.1 用
    `!= INVISIBLE`，1.21.1 起用 `== MODEL`。而 `barrier`、`light`、告示牌/悬挂告示牌全家
    （全版本 `INVISIBLE`）、`decorated_pot`（1.20.1–1.21.1）、
    `chest`/`trapped_chest`/`ender_chest`/`conduit`（1.19.4–1.21.1 为 `ENTITYBLOCK_ANIMATED`）
    都不满足条件。给它们写入 `phase=redstone_15` 或冰相，数据在、模型从没被请求过，
    界面上当然什么都没有。
    新增 `PhaseRenderShapeMixin`：**带相且原值不是 MODEL → 返回 MODEL**。
    宿主本体仍由方块实体渲染器绘制（那条路径与 RenderShape 无关），
    它们自己的模型只有 particle 贴图、没有 `elements`，所以多出来的只有相覆盖层/外壳。
  - **红石外壳 16 个功率档位长得一模一样**：之前都指向同一个恒定亮红的外壳，
    于是 `redstone_0`（未通电）看起来和满功率没区别。现在生成 **16 份调色板贴图**，
    亮度沿用原版 `RedStoneWireBlock.COLORS` 的曲线：0 档 (76,26,22)、15 档 (255,86,72)。
    没有用 `tintindex` + 客户端 `BlockColors`，因为宿主自己的模型大量使用 `tintindex 0`
    （树叶植被着色），外壳会跟着变绿；用 1 又要求每宿主有第二个 tint source，
    1.19.4–1.21.1 的单 source `BlockColor` 接口表达不了。
  - **整格宿主的判定从手写白名单改成按模型几何自动判定**：
    26.3 新增的白杨树叶（`orange/red/yellow_poplar_leaves`）是整格模型却没登记，
    含冰相时走的是内缩覆盖层——藏在完整立方体内部，**完全看不见**（与 0.1.2 同一类 bug）。
    现在按「无 rotation + 包围盒 0..16 + 六个边界面被完全覆盖」自动识别；
    台阶/楼梯虽然包围盒也是整格，但边界面没铺满，仍走内缩路径。
  - **宿主 part 不再带恒真 `phase` 条件**：
    以前每个宿主的原版 part 都补上"全部 24 个相"的条件，原版
    `KeyValueCondition.instantiate` 会判定它覆盖了属性全部取值、返回恒真谓词并打
    `Condition ... is always true` 警告——**每个方块状态一条**，1.21.10+ 加载日志被刷屏。
    普通宿主的覆盖层与外壳本来就是叠加层，根本不需要这个条件（整格宿主保留
    `none|lava|<redstone>` 的必要子集）。
  - **下落/被活塞推动的含相方块渲染成不透明方块**：
    `getMovingBlockRenderType` 走的是原始图层，SOLID 时同样丢弃透明像素。
    `PhaseRenderLayerMixin` 现在同时注入 `getChunkRenderType` 与 `getMovingBlockRenderType`。
  - **顺带清理**：
    - 删掉 0.1.1 时代生成、永远不会被加载的 41 个 `assets/liquidphase/blockstates/*.json`
      与 41 个指向不存在方块的 lang 键（0.1.5 起红石相不再注册方块）；
    - `gen_redstone_assets.py` 收敛成"只出贴图 + 外壳模型"，blockstate 统一由
      `gen_phase_blockstates.py` 生成；`redstone-hosts.txt` 不再参与构建；
    - 删掉已无对应实现的 `gen_redstone_composites.py` / `clean_composites.py` / `regen_all.sh`；
    - 修复 `assets/minecraft/lang/zh_cn.json` 里被截断的两个中文译名。
  - 自检增至 **113 / 116 / 116 / 97 / 97 / 96 项（1.19.4 → 26.3），六版本实测全过**；
    新增：外壳按功率分档、宿主无恒真条件、整格宿主自动判定、含相状态强制 MODEL
    （含 1.19.4–1.21.1 的告示牌/箱子家族逐个验证）。
  - `tools/verify_jars.py` 增加 `always_true=` 与 `shell=` 两列，并断言
    `barrier`/`oak_sign` 的 `redstone_15`、`oak_leaves` 的 `ice` 都至少命中一个 part。
- **0.1.10（修 0.1.9 未解决的两个实机问题，已在真实客户端取证）**：
  - **整格宿主（树叶/铜格栅/红树根）看不到红石外壳**：
    根因是**渲染层**。红石外壳是透明底 cutout 贴图（约 29% 不透明像素），
    而带相状态的 `getChunkRenderType` 仍是 `SOLID`——SOLID 层直接丢弃透明像素，
    外壳等于没画。
    0.1.9 在 `ClientModInitializer` 里用 `BlockRenderLayerMap.putBlock` 登记，
    但**那张表会被原版资源重载重建**，初始化写入被覆盖回 SOLID（真实客户端实测：
    初始化时确实识别为 SOLID 并写入 CUTOUT，进世界后仍读回 SOLID）。
    改为 `PhaseRenderLayerMixin` 直接注入 `ItemBlockRenderTypes.getChunkRenderType`：
    **带相且原图层为 SOLID → CUTOUT**；CUTOUT 保持；**半透明宿主（玻璃板/染色玻璃）
    保持 TRANSLUCENT**。26.3 由贴图透明度自动推导，不接线。
  - **含相剔除面不正确**：
    根因是**模型几何带 `cullface`**。`cullface` 的语义是"邻居盖住方块边界上的这一整面"，
    但相覆盖层是 0.25 内缩立方体、红石外壳是 -0.01..16.01 外扩，**面都不在方块边界上**。
    对台阶/楼梯这类部分覆盖的邻居，原版会把本该保留的面剔掉
    （实测 `oak_stairs+obsidian` 对 slab/stairs 邻居 `dir=UP` 返回 false，而 y=15.75 的
    覆盖层顶面并没被挡住）。
    改为**内缩覆盖层与红石外壳全部去掉 `cullface`**（变成不可剔除面，照常绘制）；
    贴边的 `phase_*_full` 保留 `cullface`（语义正确）。
    当时还加/保留过 `PhaseSkipRenderingMixin`（让含相状态不参与"同种方块共面剔除"），
    但去掉 `cullface` 之后它已无事可做，**0.1.12 已删除**。
  - 自检增至 **87 项（26.3 为 85 项），六版本实测全过**，新增：模型 `cullface` 断言、
    渲染层 mixin 配置断言。
  - `tools/verify_jars.py` 增加 `cullface=` 列：内缩覆盖层/外扩外壳一旦带回 `cullface`
    直接判定 BROKEN。
  - **验证方式**：在真实 1.21.11 客户端（`runClient`）用 `BlockModelShaper` 烘焙带相状态，
    确认（a）`oak_leaves/copper_grate/mangrove_roots/oak_stairs/oak_slab/oak_fence`
    红石相与冰相图层均为 CUTOUT、`glass_pane` 保持 TRANSLUCENT；
    （b）内缩覆盖层/外壳的 quad 进入不可剔除列表，坐标在宿主外侧。
- **0.1.9（修 0.1.8 的三个实机问题 + 双端同步复核）**：
  - **部分透明方块所含红石相看不到**：渲染外壳原先只对 `tools/redstone-hosts.txt` 里的
    41 个宿主（楼梯/台阶/墙/栅栏）生成。其余宿主——玻璃板、铁栏杆、梯子、锁链、树叶等
    **透明方块最容易看出来**——也能被写入 `phase=redstone_*`，但 blockstate 里没有外壳 part，
    于是"含相了却看不到红石"。现在**外壳对全部相容宿主生成**；整格宿主（树叶/铜格栅/红树根）
    同样带外壳（它们原版 part 已放开到全部相，但相本身需要一层粉尘才可见）。
  - **含相状态的剔除面不正确**（当时的判断）：相覆盖层/红石外壳是叠加层，当时认为原版
    `BlockState.skipRendering` 的"同种方块省一面"会把两块相邻含相方块贴合处各剔一面。
    于是加了 `PhaseSkipRenderingMixin`（只要本状态或邻居带相就不走原版剔除）。
    > ⚠️ 这条判断是错的：`skipRendering` 只作用于**带 `cullface`** 的 quad，而真正的
    > 问题是相覆盖层/外壳自己带了 `cullface`（0.1.10 才查出）。0.1.10 把它们全部去掉
    > `cullface` 之后，这个 mixin 已无事可做、只会改坏原版剔除语义，**0.1.12 已删除**。
  - **渲染层升级口径**：红石外壳是透明底 cutout。客户端现在只把**默认图层为 SOLID** 的
    相容宿主升级成 CUTOUT，**半透明宿主（玻璃板/染色玻璃）保持 TRANSLUCENT**——
    之前统一 `putBlock(cutout)` 会把半透明像素画成半不透明。
  - **双端同步（单人 / 连有 mod 服务器 / 连原版服务器）**：功能三是"客户端行为、服务端权威"。
    之前客户端会无条件启用（流体射线、本地瞬挖、液体物品放置拦截），
    连原版服务器时会单方面预测出服务端不存在的状态（把红石线格改成含相宿主、把桶换成液体物品），
    表现为闪一下又回弹、物品数目对不上。现在服务端在 JOIN 时发 `liquidphase:handshake`，
    客户端收到才启用；**连原版服务器永远收不到 → 自动退回原版行为**。
    所有会改世界状态的客户端路径都用同一个门控；服务端侧永远为真，
    所以单人/多人/原版服务器三种情形都不影响方块状态同步。
  - 自检增至 **69 项，六版本实测 69/69**，新增：整格宿主与透明宿主的 blockstate 渲染断言、
    含相剔除边界断言、双端同步握手断言。
  - `tools/verify_jars.py` 增加 `render_sync` 与 `empty_phase_states` 断言，
    防止"外壳只给 41 个宿主生成"这类回归。
- **0.1.8（修 0.1.7 的两个实机问题）**：
  - **红石相表面按不透明渲染 / 楼梯形状消失**：根因不在渲染层注册（1.21.10/1.21.11 实测 `ItemBlockRenderTypes.getChunkRenderType` 已是 `CUTOUT`），而是 blockstate 把**宿主模型整个排除了**——宿主 part 的 `phase` 条件被限制成`none|lava|obsidian|ice|frosted_0..3`，于是含红石相时宿主根本不渲染，整格只剩一个 16³ 粉尘外壳。视觉上就是"整格变成不透明粉尘面、楼梯形状没了"。
    改为：宿主 part 的 `phase` 条件覆盖**全部 24 个取值**（任何相下都照常渲染），红石外壳只是**额外叠加**一层——与旧版组合方块"宿主 + 粉尘外壳同时渲染"的行为一致。
  - **可含水方块放不进红石线所在格（修法推翻重做，两次）**：
    根因只有一个：`BlockItem.place` 第一步 `context.canPlace()` 只问"目标格能不能被替换"，红石线不可替换 → 直接 FAIL。
    - 第一版（0.1.7）挂红石线自己的 `use`/`useWithoutItem`：接管右键、把点击路径写进逻辑，只对"点线本身"一种姿势生效。
    - 第二版（0.1.8 初稿）在 `BlockItem.place` 入口替换放置上下文（`WirePlaceContext`）：看起来能用，但**漏掉了"物品自己搜索落点"那一类**——`ScaffoldingBlockItem.updatePlacementContext` 会在自己的循环里调 `canBeReplaced`，红石线格被直接跳过、方块落到别处（打包成脚手架堆叠时暴露）。
    - **本版（最终）**：在 `canBeReplaced(BlockState, BlockPlaceContext)` 里放行——这一格是红石线、且这个放置上下文要放的方块是相容宿主 → 返回 `true`。这是**唯一**能同时覆盖 `BlockItem.place` 入口和物品自行搜索落点的位置。红石相由 `BlockItemMixin.placeBlock` 补：写方块前若目标格仍是红石线，就写成 `phase=redstone_<原线功率>`。
  - **下落方块（`FallingBlockEntity`）落到线格也不行（同轮修）**：这条路径**既不走 `BlockItem.place`、也不走任何“手里的物品”**——`tick()` 里用 `landing.canBeReplaced(new DirectionalPlaceContext(level, pos, DOWN, ItemStack.EMPTY, UP))` 判一次，然后直接 `level.setBlock(pos, this.blockState, flags)`（26.3 是 `setBlockAndUpdate`）。那个上下文装的是 `ItemStack.EMPTY`，所以"按待放物品判断"的钩子对这条路径永远返回 false。新增 `FallingBlockIntoWireMixin`：仅当**下落方块本身是 `PhaseCapable`** 时，把空物品上下文换成用下落方块自己的物品构造的；落位写方块时补 `phase=redstone_<原线功率>`。沙子/砂砾/混凝土粉末保持原版，仍会在红石线上弹成物品。
  - **注入位置踩过的坑**：块级 `BlockBehaviour.canBeReplaced(state, context)` 会被宿主覆写遮蔽（`ScaffoldingBlock`、`SlabBlock`、`SnowLayerBlock`、`VineBlock` 等十几个类都自己声明了），脚手架堆叠就是这么坏的。现在统一挂**状态级** `BlockStateBase.canBeReplaced(BlockPlaceContext)`，那是唯一不会被遮蔽的分发点。
  - **顺带纠正一个错误的判断**：曾以为放宽 `canBeReplaced(state, context)` 会让水流/活塞也把红石线当可替换方块。实际存在**两个重载**——`(BlockState, BlockPlaceContext)` 只服务方块放置，`(BlockState, Fluid)` 才是流体流动（`FluidState.canBeReplacedWith`）。改前者不影响水流与活塞。
  - **判定里没有"手持"**：用的是 `context.getItemInHand()`，即**放置上下文自己携带的待放物品**（玩家右键时来自手上，其它来源来自被投放的物品，同一个字段）；`getPlayer()` 为 `null` 时行为完全一样，不读 `InteractionHand`、不读潜行状态。
  - **点红线本身仍会被原版挡下**（`useWithoutItem` 先切换点/十字形状）。这是原版方块交互，按玩家要求保留。
  - **撤回**：`RedstoneWireUseMixin`（接管右键）与 `WirePlaceContext`（只覆盖单个入口）都已删除。
  - 自检改为 **57 项，六版本实测 57/57**，放置类断言分两层：无玩家直接 `BlockItem.place`、FakePlayer 走真实 `ServerPlayerGameMode.useItemOn`；并新增脚手架堆叠、下落方块落位（相容宿主带相 / 沙子仍弹开）、以及可替换性边界（宿主放行 / 石头与红石粉仍拒绝）的断言。
  - 新增 `tools/check_redstone_render.py`：按原版 multipart 语义模拟状态命中，断言红石相下 `host parts > 0`。
  - 新增 `tools/verify_jars.py`：发布前抽查 dist 里每个 jar 的版本号、非法 condition 数，并断言放行 mixin 在包里、已删除的两个 mixin 确实不在包里。

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
Phase rendering overrides vanilla blockstate JSONs. **Since 0.1.17 resource packs are merged automatically**: when a pack replaces a host's
blockstate JSON, the client merges the phase parts (overlays / redstone shells / full-cube swaps) into the winning definition, so the pack
only affects the host part and the phase visuals survive. A log line reports every merge (`merged phase overlays into …`) and every
degradation (`overlay could not be merged …`). Packs made for this mod (referencing `liquidphase:` models) still override freely, and
`packMerge=false` in the config restores the old behaviour.
Filling absorption (0.1.17) judges **the existing filling's replaceability**: empty / waterlogged / lava-filled hosts accept any filling
material (ice replaces water, obsidian covers lava, redstone dust replaces water or lava); ice/frosted/obsidian/redstone fillings are not
replaceable. The water fluid path is unchanged — buckets and flowing water still only enter empty hosts.
Under the 26.x experimental redstone evaluator (`REDSTONE_EXPERIMENTS`) redstone-phase blocks are not guaranteed to match vanilla wire behaviour.
Redstone-phase composites are available on **all six versions** (every phase-capable host, not just stairs/slabs/walls/fences).
The shell is generated for **all** phase hosts, including the transparent ones (leaves, grates, glass panes, iron bars, ladders,
chains) that used to show no redstone at all, and full-cube hosts now render host + shell under redstone phases too.
Composites render as **the vanilla host model with a full-cube redstone-dust shell on top**:
the shell uses a porous redstone-wire texture that fills each face, transparent pixels let the host show through (cutout),
the crosshair outline covers the whole cube (collision stays with the host), and they have **no item form**; they are only created
through redstone-dust/phase-block interactions (either dust on a host, or a host block item placed into the redstone-wire tile
through the normal block placement pipeline).
Signals decay by 1 into neighbouring wires/composites
but stay at full power for lamps, pistons and other components.
Phased states never take part in vanilla same-block face culling, so two adjacent phased blocks do not lose the shared face.
The inset phase overlay and the redstone shell carry **no** `cullface`: their faces are not on the block boundary, so vanilla
culling would drop faces against partial neighbours (slabs/stairs) that the overlay actually covers. The full-cube overlays stay on
the boundary and keep `cullface`.
Phased states are forced onto the CUTOUT layer via `ItemBlockRenderTypes.getChunkRenderType` (only when the original layer is
SOLID); translucent hosts (glass panes, stained glass) keep TRANSLUCENT. The same mixin also fixes
`getMovingBlockRenderType`, so falling blocks and piston-moved phased blocks are not drawn on the opaque layer.
This must be a mixin: the `BlockRenderLayerMap` table is rebuilt by the resource reload and silently loses mappings
registered during client init.
Phased blocks with a solid phase look like a full cube of that material, and the full-cube collision applies to **every** host
(state-level since 0.1.13, including fences, panes, bars, walls, wall hanging signs and scaffolding that override the block-level
shape); mining is two-lane: aim at the exposed host and it mines at host speed, aim at the exposed phase and it mines as the phase
material (obsidian still needs a diamond pickaxe — that slowness is intended).
Full-cube hosts (leaves, copper grates, mangrove roots, poplar leaves) show the phase material instead of the host texture while
phased, and since 0.1.13 the decision is **per variant**: a slab's `type=double` swaps to the full phase material while its half-slab
variants keep the host + inset overlay (previously double slabs hid the phase completely, 57–101 hosts).
Lava buckets follow the vanilla water-bucket interaction priority (0.1.13): hosts whose vanilla interaction consumes the click
(trapdoors toggle, chests/ender chests open, light blocks cycle, signs open the editor, decorated pots store the item, shelves/statues
act) are not filled by a plain right-click — sneak right-click, dispensers and placing a block into a lava source still fill them;
every other host fills on right-click through the vanilla `useItem` path, identical on all six versions.
`barrier` is no longer a phase host on 1.19.4/1.20.1 (those versions have no `WATERLOGGED` on it — the old crash is gone); on 1.21.1+
it stays a host, but bucket/placement filling is refused there (vanilla only accepts fluids on barriers in creative mode, unchanged).
Slabs/candles/campfires/dried ghasts accept lava from neighbouring lava sources again (their shadowed `placeLiquid`/`canPlaceLiquid`
lava branch is restored by dedicated mixins), and `offsetType` hosts leave their phase behind when broken.
Known host-coverage invariants are audited by `python tools/audit_host_coverage.py <guardian> .` and documented with the 0.1.13
fixes in `docs/host-coverage-audit.md` (§4 analysis, §6 resolution).
The redstone shell's brightness follows its power level (0 = nearly dark, 15 = full bright), matching the vanilla wire colour ramp.
Hosts whose vanilla `RenderShape` is not `MODEL` (barriers, light blocks, all sign blocks, and chest/pot families before 1.21.10) still render
the phase: phased states are forced back to `MODEL`, while the host itself keeps being drawn by its own vanilla render path.
Feature 3 is client driven but server authoritative, so the server announces itself with a `liquidphase:handshake` packet on join and the client
only enables its phase features after receiving it. A vanilla server never sends it, so the client falls back to vanilla behaviour there
(single player works because the integrated server still sends the handshake).
1.19.4/1.20.1 use **Statement** for state injection (shipped as a dependency).
Dependency floors follow each version's real minimum (`fabricloader` 0.15.0 / 0.16.10 / 0.15.11 /
0.17.0 / 0.17.3 / 0.19.3 respectively), `minecraft` stays patch-level (`~x.y.z`), and `fabric-api` plus
`statement` remain required. Note: loader 0.15.x/0.16.x bundle an old Mixin that cannot read JDK 25 class
files (`Unsupported class file major version 69`) — use a newer loader or JDK 17/21 in that case.

### Licence

WTFPL v2 — see `LICENSE`. Credits: Lavalogged (MIT), Statement (MIT), cauldronfix (CC0), Fluidlogged (MIT);
cauldron-expansion (CC-BY-NC-SA) used for **behaviour comparison only**, no code or assets copied.
Decompiled sources via guardian, used locally for verification only.
