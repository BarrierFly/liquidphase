# 元件相（中继器 / 比较器 / 侦测器）与独立方块的功能一致性审计

> 审计对象：`liquidphase` 0.2.6（`src/` 单树，基线提交 `13858e8`）。
> 对照源码：1.21.11 guardian `192e7132b4`、26.3 guardian `d5822ca2a1`，
> 历史语义差异另行标注（1.19.4 `37641fc907`、1.20.1 `41c32f0060`、1.21.1 `d47305d7cf`、1.21.10 `5d7d486906`）。
>
> **口径**：只比较"同一元件作为**旁路表里的填充相**"与"作为**原版独立方块**"时的功能差异。
> 按用户要求，**宿主相应面被完整表面挡住 → 该面不输入/不输出**（`RedstoneBlocks.exposed`）不计入差异；
> 已在 README / AGENTS 记录的设计取舍（锁存不画锁形、只画杆+红条、普通破坏不额外掉落、
> 相不随活塞移动、只能放在有顶面空腔的宿主）也不重复列为缺陷，只在 §9 复核一遍。
>
> **方法**：① 逐函数对照原版 `DiodeBlock` / `RepeaterBlock` / `ComparatorBlock` / `ObserverBlock` /
> `SignalGetter` / `RedStoneWireBlock` / `Level.setBlock` / `updateNeighbourForOutputSignal` /
> `LevelChunkTicks` / `ServerPlayerGameMode`；② 反查本 mod 的全部元件写入/清除/通知点
> （`ElementBlocks`、`PhaseAbsorption`、`BlockItemMixin`、`FillingPlacementMixin`、
> `BlockStateBaseMixin`、`PhaseExtras`）；③ 脚本扫描 1.21.11 的 413 个相容宿主，
> 找出"自身也会排方块 tick"的宿主类（25 类），用于量化 tick 槽位冲突。

---

## 0. 结论速览

| # | 现象 | 与原版独立方块的差异 | 严重度 | 根因 |
| --- | --- | --- | --- | --- |
| A1 | **比较器相读不到相邻容器的内容变化**（漏斗进/出、玩家存取、唱片机换碟、讲台翻页、物品展示框转动、命令方块输出、探测铁轨上有矿车都不刷新） | 原版比较器由 `Level.updateNeighbourForOutputSignal` 主动通知 | **P1** | 根因 A |
| A2 | **观察者相观测不到**红石相宿主与其它元件宿主的任何变化 | 原版观察者能观测红石线/二极管的状态变化（`setBlock` 带形状更新） | **P1** | 根因 A |
| A3 | 元件旁边**放第二个二极管不锁定中继器**，锁存只能靠以后偶然的形状更新补上 | 原版并排放置会立即重算 `LOCKED` | **P1** | 根因 A |
| A4 | 装配/拆除元件后，**相邻红石线不重新判定连接**（不弯向元件、拆掉后仍连着） | 原版放置/破坏会触发邻居的 `updateShape` | P2（视觉 + 连接态陈旧；元件的信号读取有 POWER 兜底，所以不表现为"不工作"） | 根因 A |
| B1 | **侦测器相的脉冲时机被宿主 tick 劫持**（珊瑚、大垂滴叶、钟乳石尖、横向避雷针、干尸壳、探测铁轨等"自身会排刻且能放元件"的宿主） | 原版侦测器只占自己的 tick | **P1** | 根因 B |
| B2 | 中继器/比较器的**延迟会被宿主抢走**（被压缩到宿主那次 tick，或反之宿主 tick 被推迟数 tick） | 原版两者互不影响 | **P1** | 根因 B |
| C | **吸收真实元件方块**后不补初始结算：比较器输出恒 0、中继器有输入却不点亮、正在脉冲的侦测器永远亮着 | 原版放置时 `setPlacedBy → scheduleTick(1)`、`ObserverBlock.onPlace` 复位 | **P1** | 根因 C |
| K | **比较器的模拟量输入不受"宿主覆盖面不输入"约束**（输入面被完全盖住时仍读容器/炼药锅/唱片机的模拟量） | 与 AGENTS/README 声明的 I/O 露出门控不一致 | P2 | 独立 |
| L | **客户端**在形状更新里会自己写旁路表（重算 `locked`），原版该重算被 `!isClientSide` 挡住 | 客户端可能用不完整数据算出与服务器不同的 `locked` | P3 | 独立 |
| D | 计划刻**不区分优先级**，也没有 `shouldPrioritize` | 原版中继器 `HIGH/VERY_HIGH/EXTREMELY_HIGH`、比较器 `NORMAL/HIGH` | P2 | 独立 |
| E1 | 红石线对**比较器宿主**只在轴向两面连接；原版比较器 `isSignalSource()`，任意面都连 | 连接态与线形差异（信号路径不受影响） | P2 | 独立 |
| E2 | 红石线**不会向上"爬到"元件宿主**（斜上方一格） | 原版 `getConnectingSide` 的 `canConnectUp` 分支 | P2 | 独立 |
| F | 元件宿主 `isSignalSource()` 为 false | 原版三种元件都是 true；受影响的原版逻辑：格内怪物生成、铁轨朝向更新、第三方 mod 判定 | P3（方案 C 固有） | 独立 |
| G1 | 拆元件**不是瞬挖**（按宿主硬度算进度） | 原版中继器/比较器 `instabreak` | P2 | 独立 |
| G2 | 拆元件**不看瞄准**：点宿主任何位置都先拆元件，要拆宿主得点两次 | 原版元件占整格 | P2 | 独立 |
| G3 | **创造模式拆元件仍掉物品** | 原版创造不掉落 | P2 | 独立 |
| H1 | **80 个宿主在 `CollisionContext.empty()` 下碰撞为空**（告示牌 36、珊瑚 30、铁轨 4、传送门 3、glow_lichen/sculk_vein/resin_clump 3、垂滴叶茎/悬挂根/红树胎生苗 4），元件却能被放上去，并整根留在 y=0 嵌在宿主几何里 | 原版这些方块的顶面不接受元件 | P2 | 独立 |
| H2 | 细柱型宿主（铁链、朝上的避雷针、钟乳石尖、蜡烛、海泡菜）上，杆/红条被拆成两截：有表面的列抬到顶面、无表面的列留在 y=0 | 原版元件完整落在整块顶面上 | P2 | 独立 |
| I | 元件**不提供碰撞**；点亮的中继器没有粒子 | 原版中继器/比较器是 16×2 碰撞、侦测器是整格；只有点亮的中继器有红石粒子（`RepeaterBlock.animateTick`），比较器/侦测器没有 | P3（建议补进 README） | 固有 |
| J | 爆炸等"非玩家非活塞"破坏**不掉元件** | 原版中继器/比较器被爆炸破坏会掉落 | P3 | 既有取舍 |

已复核、**不算缺陷**的项见 §9；**需要拍板的取舍与选项集中在 §12**。

---

## 1. 根因 A：旁路表写入/清除只走"邻居变化"通道，缺"形状更新"与"模拟量通知"两个通道

### 1.1 原版一次状态变化做三件事

`Level.setBlock(pos, state, flags, limit)`（1.21.11 `Level.java:225..256`）：

```java
if ((i & 1) != 0) {                        // UPDATE_NEIGHBORS
    this.updateNeighborsAt(blockPos, blockState2.getBlock());          // → handleNeighborChanged
    if (!this.isClientSide() && blockState.hasAnalogOutputSignal()) {
        this.updateNeighbourForOutputSignal(blockPos, block);          // → 通知相邻比较器
    }
}
if ((i & 16) == 0 && j > 0) {              // 不是 UPDATE_KNOWN_SHAPE
    blockState2.updateIndirectNeighbourShapes(...);
    blockState.updateNeighbourShapes(...);                            // → updateShape(direction, ...)
    blockState.updateIndirectNeighbourShapes(...);
}
```

三条通道用途完全不同，不能互相替代：

| 通道 | 原版用它做什么（元件相关） |
| --- | --- |
| `handleNeighborChanged` | `DiodeBlock.checkTickOnNeighbor`（重算是否要排刻）、`RedStoneWireBlock.updatePowerStrength`（重算功率） |
| `updateShape` | `RedStoneWireBlock.getConnectingSide`（连接态）、`RepeaterBlock.isLocked`（`LOCKED` 状态位）、`ObserverBlock.startSignal`（**观察者唯一的触发点**）、`LeavesBlock`/`ScaffoldingBlock` 的距离/稳定性 tick |
| `updateNeighbourForOutputSignal` | 容器/唱片机/展示框变化时**主动戳相邻比较器**（`Level.java:894..910` 只找 `Blocks.COMPARATOR`） |

### 1.2 本 mod 的现状

元件的全部参数都在旁路表里，写表走 `PhaseExtras.set`，它只做两件事
（`phase/PhaseExtras.java:101..112`）：客户端标脏 section、服务端广播 `extra_update`。
**没有任何一次表写入会走 `updateNeighbourShapes` 或 `updateNeighbourForOutputSignal`。**

现有通知点（全部只到 `updateNeighborsAt`）：

| 位置 | 场景 | 通知 |
| --- | --- | --- |
| `ElementBlocks.place`（`element/ElementBlocks.java:187..212`） | 装配元件 | **无** |
| `ElementBlocks.mine`（`:224..232`） | 拆除元件 | `notifyOutput` → `updateNeighborsAt`（`pos` 与输出面） |
| `ElementBlocks.absorbBlock`（`:115..126`） | 吸收真实元件方块 | `updateNeighborsAt` |
| `ElementBlocks.tick` / `neighborChanged` / `refreshLock` / `cycleSetting` | `powered`/`power`/`locked`/`delay`/`mode` 变化 | `notifyOutput` → `updateNeighborsAt` |
| `RedstoneBlocks.updatePower`（`redstone/RedstoneBlocks.java:138..150`） | 红石相功率变化 | `updateNeighborsAt` |

`BlockItemMixin` / `FillingPlacementMixin` 的"宿主进元件格"路径也一样：先 `setBlock`
（此时表里还没有条目，形状更新看到的是"没有元件"），再写表，之后只补 `updateNeighborsAt`。

### 1.3 症状

**(a) 比较器相读不到容器变化（最影响玩法）**

原版：容器内容变化 → `BlockEntity.setChanged` / `Containers.updateNeighboursAfterDestroy` /
`ItemFrame.setRotation` 等 → `Level.updateNeighbourForOutputSignal`
→ 相邻 `Blocks.COMPARATOR` 收到 `neighborChanged` → 2 tick 后刷新输出（`ComparatorBlock.checkTickOnNeighbor`）。

**注意"开箱"本身不触发这条链**：开箱只发 `blockEvent`（`ContainerOpenersCounter.scheduleRecheck`），
比较器读的是容器内容量（`AbstractContainerMenu.getRedstoneSignalFromContainer`），
所以原版开/关空箱也不会变。真正触发的是**内容量变化**：玩家存取物品、漏斗进/出、
唱片机换碟、讲台翻页、物品展示框转动、命令方块输出、探测铁轨上的矿车。

本 mod：`updateNeighbourForOutputSignal` 只找 `Blocks.COMPARATOR`，而档位在旁路表里 →
**比较器相完全收不到这类通知**，必须等旁边有别的方块变化才"自己想起来"。

**(b) 观察者相观测不到只写表的变化**

`ObserverBlock` 只有 `updateShape` 一个启动点（1.21.11 `ObserverBlock.java:70..90`），没有 `neighborChanged`。
- 观察者相 **观测不到红石相宿主**：原版红石线改功率用 `setBlock(..., 2)`，仍会走形状更新；
  我们的 `RedstoneBlocks.updatePower` 只写表 + `updateNeighborsAt`。
- 观察者相 **观测不到其它元件相** 的 `powered` 翻转（同样只写表）。

**(c) 锁存/侧向输入不重算**

原版 `RepeaterBlock.updateShape` 在"侧轴方向变化"时重算 `LOCKED`；本 mod 的等价物是
`ElementShapeUpdateMixin → ElementBlocks.onUpdateShape`（`mixin/ElementShapeUpdateMixin.java:48..54`）。
放一个**真实**中继器在旁边会触发形状更新 → 能锁；放一个**元件相**中继器在旁边只写表 → 不触发。
比较器的侧向 alternate 输入同理（它每个 tick 现算，所以只影响"是否需要排刻"）。

**(d) 红石线的连接态陈旧**

线先放、再在宿主上装配元件：线的 `getConnectingSide` 仍然返回 `NONE`（装配时没有形状更新），
所以线不弯向元件；拆元件后线仍然弯着。**注意这一条不导致元件不工作**：
`DiodeBlock.getInputSignal` / `ComparatorBlock.getInputSignal` 在 `level.getSignal(wire, dir)` 之外
还有 `Math.max(i, wire.POWER)` 兜底，本 mod 的 `ElementBlocks.inputSignal` 完整镜像了这一点
（`element/ElementBlocks.java:406..420`）；红石相一侧 `computeTargetPower` 也直读 `POWER`。
所以症状是"线形/连接态不对"，不是"中继器不亮"（这一点容易误判，实测核对过）。

### 1.4 建议修法（一处、通用）

在 `PhaseExtras` 里把"写表"和"按状态变化口径通知"绑定，删掉现在散落的 `updateNeighborsAt`：

```java
/** 服务端：一次表值变化后，按原版 Level.setBlock 的三条通道补通知。 */
public static void notifyStateLikeChange(LevelAccessor level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    level.updateNeighborsAt(pos, state.getBlock());                  // ① neighborChanged
    state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);       // ② updateShape
    level.updateNeighbourForOutputSignal(pos, state.getBlock());     // ③ 相邻比较器
}
```

另外单独补 ③ 的**反向**（我们是被通知方）：`Level.updateNeighbourForOutputSignal` 只认
`Blocks.COMPARATOR`，需要在 `Level` 上挂一个 HEAD/TAIL 注入，对"有 COMPARATOR 条目"的格子
执行与 `ElementBlocks.neighborChanged` 相同的 2 tick 重排（并且要照原版一起看"相邻格是一格导体时
再往里看一格"）。

要点与坑：

- **必须防递归/去重**：形状更新会回调 `ElementShapeUpdateMixin` → `refreshLock` → 可能再写表。
  `PhaseExtras.set` 已经只在值真的变化时 `fireChanged`，把通知挂在 `fireChanged` 上天然满足；
  但红石相的 `updatePower` 递归链每一步都会发 6 次形状更新，需要按"条目语义是否变化"过滤
  （功率 / `powered` / `facing` / `delay` / `mode` / `locked` 变化才通知；单纯重复写同值不通知）。
- 只在**服务端**做（客户端是镜像，形状更新会在本地预测里打架）。
- `updateNeighbourShapes(LevelAccessor, BlockPos, int)` 六版本签名一致；
  `updateNeighbourForOutputSignal` 在 `Level` 上六版本都是 public（1.19.4:896 / 1.20.1:810 /
  1.21.1:905 / 1.21.11:894 / 26.3:1015）。
- 注意 `Block.UPDATE_ALL` 含 `UPDATE_NEIGHBORS`，形状更新自身不会重复触发邻居变化；
  真正需要小心的是我们在 `fireChanged` 里同时调 ① 与 ② 时产生的双重回调，
  用"每个 tick 每格最多通知一次"的集合去重最稳。

---

## 2. 根因 B：元件计划刻与宿主自身的方块 tick 共用 `(pos, block)` 槽位

### 2.1 机制

原版方块 tick 容器是 `LevelChunkTicks<Block>`，按 **`(BlockPos, Block)`** 唯一定位，
重复 `scheduleTick` 直接丢弃（`LevelChunkTicks.java:56..66`）：

```java
public void schedule(ScheduledTick<T> scheduledTick) {
    if (this.ticksPerPosition.add(scheduledTick)) { this.scheduleUnchecked(scheduledTick); }
}
```

执行时 `ServerLevel.tickBlock(pos, block)` 只判 `blockState.is(block)` 然后调 `blockState.tick(...)`
（`ServerLevel.java:808..814`）。而本 mod 的元件 tick 正挂在 `BlockStateBaseMixin.tick` 的 HEAD
（`mixin/BlockStateBaseMixin.java:128..140`），排刻一律用 `level.scheduleTick(pos, host.getBlock(), n)`
（`element/ElementBlocks.java:209/307/308/327/334/361/386`）。**元件与宿主共用一个槽位。**

### 2.2 影响面（1.21.11，`tools/phase-blocks/1.21.11.ids` 共 413 个宿主）

自身会 `scheduleTick(pos, this, …)` 的相容宿主共 **25 类**：

- 树叶全家（`oak/…/pale_oak_leaves`，`LeavesBlock.updateShape` 距离变化即排 1 tick）；
- 脚手架（**每次** `updateShape` 都排 1 tick）；
- 钟乳石（`updateShape` 排 1–2 tick）；
- 大垂滴叶 / 茎（倾斜推进 1 tick）；
- 避雷针（雷击后 8 tick 复位）；
- 探测铁轨（20 tick 冷却）；
- 死珊瑚 15 个（60–100 tick 死亡）；
- 干尸壳（水位推进）。

**但其中只有一部分真的能放上元件**：`hasCavity` 要求"足迹内存在 `top < 1` 的列"。
按这一条逐类核对（`CollisionContext.empty()` 下的顶面）：

| 宿主类 | `empty()` 下的顶面 | 能放元件？ |
| --- | --- | --- |
| 树叶 | 1.0（整格 16×16） | **不能**（顶面不缺，被拒） |
| 脚手架 | `SHAPE_STABLE` 顶面 1.0（整格 16×16） | **不能** |
| 探测铁轨 `BaseRailBlock` | 无碰撞（`noCollision()`）→ 被 `isEmpty()` 放行 | **能（误放行，见 §8.1）** |
| 死珊瑚 / 珊瑚扇 | 无碰撞 → 误放行 | **能（误放行）** |
| 大垂滴叶 | `SHAPE_LEAF`（11..15）→ 0.9375，14×14 | **能** |
| 大垂滴叶茎 | 无碰撞 → 误放行 | **能（误放行）** |
| 朝上的钟乳石尖 | `SHAPE_TIP_UP` 6×6，顶 0.6875 | **能** |
| 横向避雷针（`RodBlock` 4×4 柱） | 顶面 1.0，但足迹是 4×4 的柱 | **能**（柱外无表面，见 §8.2） |
| 干尸壳 | `Block.column(10, 10, 0, 10)` → 10×10、顶 0.625 | **能**（10×10 之外无表面，见 §8.2） |

所以 B 的真实影响面是**能放元件的那一批**：珊瑚 / 大垂滴叶（含茎）/ 钟乳石 / 横向避雷针 /
干尸壳 / 探测铁轨。探测铁轨之所以"能"放，本身是 §8.1 的 `isEmpty()` 误放行——修掉那个
之后它会退出 B 的影响面。树叶、脚手架是"整格顶面到顶"，放不上元件，不构成症状。
**不要**用"树叶上中继器延迟变成 1 tick"这种描述去复现——那是错的（本轮核对纠正）。

### 2.3 症状

**(a) 侦测器相不脉冲（最严重）**

`ElementBlocks.onUpdateShape` 的守卫是
`if (!serverLevel.getBlockTicks().hasScheduledTick(pos, host.getBlock())) { scheduleTick(..., 2); }`
（`:304..309`），`neighborChanged` 的守卫是 `willTickThisTick(pos, host.getBlock())`（`:317`）。
在珊瑚 / 大垂滴叶（含茎）/ 钟乳石 / 横向避雷针 / 干尸壳 / 探测铁轨这类**能放元件**的宿主上，
宿主自己的 tick 一旦在队列里（例如珊瑚每 60–100 tick 的死亡推进、大垂滴叶的倾斜推进、
探测铁轨 20 tick 的压车检测）：

- 观察面变化 → `hasScheduledTick(pos, hostBlock)` 为真 → 放弃排刻 → 侦测器这一拍不翻 `powered`；
- 反过来元件先排了 2 tick → 宿主的推进 tick 排不进去 → 珊瑚死亡/垂滴叶倾斜被推迟。

**(b) 中继器/比较器的延迟被宿主抢走或抢占宿主**

- 宿主先占槽位 → 中继器想排 `delay*2`（2–8 tick）被丢弃 → 到点后 `tickBlock` 执行**宿主的** tick，
  顺带跑了我们的 HEAD → 中继器按宿主那次 tick 推进（延迟被压缩）；
- 元件先占 2–8 tick → 宿主的 tick（珊瑚死亡、钟乳石下落、避雷针复位、垂滴叶倾斜）
  排不进去 → 行为被推迟或停住。

原版里"元件是方块、tick 是自己的槽位"，两者互不影响。

> **一个更普遍的隐患（不依赖宿主）**：`ElementBlocks.neighborChanged` 的守卫
> `if (level.getBlockTicks().willTickThisTick(pos, host.getBlock())) return;`（`:317`）
> 在原版里对应 `!level.getBlockTicks().willTickThisTick(pos, this)`（`DiodeBlock.checkTickOnNeighbor`）。
> 因为 tick 类型是**宿主方块**，同格有两条不同的逻辑会互相"以为对方已经排了刻"
> ——包括红石相的 `scheduleTick`（`RedstoneBlocks` 用同一个 `pos` 但从不排刻，影响小）
> 与"宿主的自然 tick"（影响见上）。改成哨兵类型后这条守卫才真正表达"元件自己已经排了刻"。

### 2.4 建议修法

给元件一条**不共享**的 tick 通道。推荐方案 1：

1. **专用哨兵类型**：`scheduleTick(pos, SENTINEL, n)`（一个稳定注册、玩家拿不到的方块），
   并在 `ServerLevel.tickBlock` 的 HEAD 注入里识别 `type == SENTINEL` → 直接跑元件 tick，
   跳过 `blockState.is(type)` 判断。好处：复用原版容器（持久化 / 区块卸载 / `hasScheduledTick`
   全部免费），六版本 `tickBlock` 都是私有但同名同描述符，可注入。
   `ElementBlocks` 里所有 `hasScheduledTick`/`willTickThisTick` 的守卫同步改成哨兵类型。
2. **自建队列**：`PhaseStorage` 里维护 `pos -> endTick`，由 `ServerTickEvents.END_SERVER_TICK`
   推进。要自己处理持久化与区块卸载，工作量明显大于方案 1。

无论哪种，**不能继续用 `host.getBlock()` 当 tick 类型**：`tickBlock` 的 `blockState.is(type)`
在宿主自己换了状态（`DISTANCE`/`TILT`/`AGE`）后仍然为真，看起来"能用"，直到与宿主自己的 tick
撞车才暴露，正是现在这种"某些宿主上偶发"的形态。

---

## 3. 根因 C：吸收真实元件方块时不补"放置初始结算"

`ElementBlocks.extraOfBlock`（`element/ElementBlocks.java:86..112`）把真实元件方块状态翻译成条目，
其中 **`power` 一律写 0**：

```java
if (block instanceof ComparatorBlock) {
    return PhaseExtra.encode(COMPARATOR, 0, FACING, 0, MODE==SUBTRACT, false, POWERED);
}
```

`absorbBlock`（`:115..126`）只做"剥材料相/含水 → 写表 → `updateNeighborsAt`"，
缺少原版放置路径里的三件初始结算：

| 原版 | 位置 | 作用 |
| --- | --- | --- |
| `setPlacedBy → shouldTurnOn → scheduleTick(pos, this, 1)` | `DiodeBlock` | 有输入时 1 tick 后点亮 |
| `getStateForPlacement → isLocked` | `RepeaterBlock` | 初始 `LOCKED`（靠继承状态，勉强等价） |
| `onPlace`：`POWERED && 没有排刻` → 强制 `POWERED=false` + 通知前方 | `ObserverBlock` | 防止吸收一个正在脉冲的侦测器后永远亮着 |

### 症状

- 吸收**有输出的比较器** → 条目 `powered=true, power=0` → `ElementBlocks.getSignal` 对比较器返回
  `PhaseExtra.power` → **输出 0**，且静默（要等邻居变化才恢复）。
- 吸收**有输入未点亮的中继器** → 没有 1 tick 初始刻 → 不点亮，直到邻居变化。
- 吸收**正在脉冲的侦测器** → `powered=true`，没有复位刻；`onUpdateShape` 在 `powered` 时直接
  return（`:297`）→ **永远亮着**。

### 建议修法

`absorbBlock` 末尾按 `place` 的口径补一次（复用同一份代码，不要再写第二遍）：

```java
switch (PhaseExtra.kind(extra)) {
    case REPEATER  -> { refreshLock(...); if (inputSignal(...) > 0) scheduleTick(pos, host.getBlock(), 1); }
    case COMPARATOR -> { if (shouldTurnOn(...) || comparatorOutput(...) != 0) scheduleTick(pos, host.getBlock(), 1); }
    case OBSERVER -> { if (PhaseExtra.powered(extra)) PhaseExtras.set(level, pos, PhaseExtra.withPowered(extra, false)); }
}
```

比较器的 `power` 不要在吸收时写 0：要么吸收时用 `comparatorOutput` 现算，要么统一"排 1 tick 后
由 `tick` 写入"（后者更贴近原版"方块实体先为空、下一 tick 刷新"，且能顺带覆盖
`refreshOutputState` 的 compare 模式无条件刷新）。

---

## 4. 根因 D：计划刻优先级与 `shouldPrioritize`

原版（1.21.11，1.19.4–26.3 结构相同）：

| 位置 | 优先级 |
| --- | --- |
| `DiodeBlock.checkTickOnNeighbor` | `EXTREMELY_HIGH`（`shouldPrioritize`）/ `VERY_HIGH`（当前点亮）/ `HIGH` |
| `DiodeBlock.tick` 补排 | `scheduleTick(..., getDelay, TickPriority.VERY_HIGH)` |
| `ComparatorBlock.checkTickOnNeighbor` | `HIGH`（`shouldPrioritize`）/ `NORMAL` |
| `ComparatorBlock.tick` | `refreshOutputState`，不再排刻 |

本 mod 全部走 `scheduleTick(pos, block, n)`（默认 `NORMAL`），也**没有 `shouldPrioritize`**
（原版判定："正前方是朝向不同的二极管 → 提优先级"）。

影响：中继器链 / 相邻二极管互相指向 / 快速时钟，同一 tick 内的结算顺序与原版不同。
原版这两档优先级就是让链式二极管按方向收敛；缺了它会出现"某拍多算一次"的瞬态，
在环路上可稳定复现（不是收敛性问题）。

建议：`ElementBlocks` 补 `shouldPrioritize(level, pos, host, extra)`（看 `facing.getOpposite()`
方向的相邻条目 / 真实二极管是否朝向不同），并按上表传优先级。
`scheduleTick(pos, block, delay, priority)` 六版本都有（1.19.4 起）。

---

## 5. 根因 E：红石线↔元件宿主的连接规则

原版 `shouldConnectTo`（1.21.11 `RedStoneWireBlock.java:393..405`；1.19.4–1.21.10 同结构）：

```java
if (state.is(REDSTONE_WIRE))  return true;
else if (state.is(REPEATER))  return facing == direction || facing.getOpposite() == direction;
else return state.is(OBSERVER) ? direction == facing : state.isSignalSource() && direction != null;
```

**比较器没有专门分支**，落到最后一行；`ComparatorBlock extends DiodeBlock`、
`isSignalSource()` 为 `true` → **任意非 null 面都连接**。26.3 是等价改法
（`BlockBehaviour.shouldRedstoneWireConnectTo` 默认 `isSignalSource() && direction != null`，
只有 `RepeaterBlock` / `ObserverBlock` 各自覆写）——已逐版本确认。

### E1 比较器宿主只在轴向两面连接

本 mod `ElementBlocks.connectsOnFace`（`:271..278`）把比较器和中继器一起限定成轴向两面：

```java
case REPEATER, COMPARATOR -> direction == facing || direction == facing.getOpposite();
```

差异：比较器宿主侧面/上面的线不会连接（不弯、`getConnectingSide` 返回 `NONE`）。
**信号路径不受影响**：比较器只从 `facing` 读输入、只向 `facing.getOpposite()` 输出，
线的 `getSignal` 门在这一侧仍然满足。所以是"线形/连接态"差异（以及 `isCross`/`isDot` 的
形状判定随之不同）。

### E2 线不会向上爬到元件宿主

原版 `getConnectingSide(level, pos, direction, canConnectUp)` 会检查
`relativePos.above()` 上的方块是否 `shouldConnectTo`，从而让线"爬一格"接到上方元件。
本 mod 的 `RedStoneWireBlockMixin` 只在 HEAD 判"紧邻格是元件宿主"，宿主的 `isSignalSource()`
又是 false，所以**永不爬**。26.3 的 `shouldRedstoneWireConnectTo` 分支同样只处理方向本身。

### 建议

- `connectsOnFace` 对 `COMPARATOR` 放宽为"任意非 null 方向"（与 `isSignalSource()` 口径一致），
  中继器保持轴向两面；两侧都保留 `exposed` 判定。
- `RedStoneWireBlockMixin` 补 `canConnectUp` 分支：紧邻格上方是元件宿主且允许该面连接时返回
  `RedstoneSide.UP`（`exposed` 取宿主底面方向）；26.3 在 `BlockStateBaseMixin` 的
  `shouldRedstoneWireConnectTo` 注入里补同样判定。

> 这两处必须和 §6 一起决定口径：要么"元件宿主完全模拟信号源"，要么保持"只模拟带坐标入口"
> 并把差异写进 README。不要只改一半。

---

## 6. 根因 F：`isSignalSource()` 为 false 的连带影响

方案 C 里 `isSignalSource()` 没有坐标，所以元件宿主一律 false（`RedstoneSignalMixin` 注释写明）。
原版三种元件都是 true。非红石链路上依赖该判定的原版行为因此不一致：

| 原版调用点 | 原版行为 | 元件宿主 |
| --- | --- | --- |
| `NaturalSpawner.isValidEmptySpawnBlock` | 元件格**禁止**怪物生成 | 允许（宿主非满方块时） |
| `RailBlock.updateState` | 旁边是信号源方块时改朝向 | 不改 |
| 任意第三方 mod 的 `isSignalSource()` 判定 | 视为信号源 | 不是 |

建议至少写进 README 已知限制（"同一布局里铁轨朝向可能与原版不同"），
因为无坐标确实做不到完全等价；真正的修法需要引入"按坐标的信号源查询"接口，代价很大。

---

## 7. 根因 G：拆元件的挖掘语义

| 项目 | 原版独立元件 | 元件相 |
| --- | --- | --- |
| 硬度 | 中继器/比较器 `instabreak`（`strength(0)`）；侦测器 `strength(3.0)+requiresCorrectToolForDrops` | 随宿主（`BlockStateBaseMixin.getDestroyProgress` 只处理固相/红石相） |
| 瞄准 | 整格都是元件 | `isElementMineable` **不看瞄准**（`ServerPlayerGameModeMixin:66..72` 刻意跳过 `isPhaseTarget`） |
| 创造模式掉落 | 不掉落 | `ElementBlocks.mine` 无条件 `Block.popResource` |

说明：

- 现行实现对三者一视同仁：按宿主硬度挖、不看瞄准、创造也掉。与中继器/比较器（瞬挖、整格、
  创造不掉）明显不一致；与侦测器在硬度上偶然接近，但绕过了"需要正确工具才掉落"。
- "不看瞄准"有它的理由（元件几何只有顶面几像素，很难精确指），代价是**想拆宿主永远先拆元件**，
  两次点击；体验上容易被当成 bug。
- `RedstoneBlocks.mineRedstonePhase` 有同样的创造掉落问题（同源）。

建议：至少补 `!player.isCreative()` 守卫（低风险）；是否加瞄准判定需要产品决策，
建议连同"拆元件用宿主速度"一起写进 README。

---

## 8. 根因 H：顶面高度采样把"该列没有表面"当成 0

`ElementRenderSupport.columnTop`（`element/ElementRenderSupport.java:140..155`）：

```java
double top = shape.max(Direction.Axis.Y, sz, sx);
if (top == Double.NEGATIVE_INFINITY) {
    return 0.0;                       // ← "这一列没有表面"被当成 y=0
}
return Math.min(1.0, top);
```

`ElementBlocks.hasCavity`（`:154..174`）用的是同一个 `max(...)`，把 `NEGATIVE_INFINITY`
当成"有空腔"，并且在**碰撞形状整体为空**时直接放行：

```java
VoxelShape shape = host.getCollisionShape(level, pos, CollisionContext.empty());
if (shape == null || shape.isEmpty()) return true;     // ← 完全没碰撞 → 直接放行
for (16×16 采样) {
    double top = shape.max(Direction.Axis.Y, (j + 0.5) / 16.0, (i + 0.5) / 16.0);
    if (top == Double.NEGATIVE_INFINITY || top < MIN_CAVITY) return true;
}
```

### 8.1 碰撞在 `CollisionContext.empty()` 下为空的宿主：**80 个**

按 `Blocks.java` 的注册项（是否 `noCollision()`）+ 继承链上是否有 `getCollisionShape` 覆写
逐个核对 1.21.11 的 413 个宿主，**80 个**在 `empty()` 下碰撞为空：

| 家族 | 数量 | 说明 |
| --- | --- | --- |
| 告示牌/墙告示牌/悬挂告示牌（`*_sign` / `*_wall_sign` / `*_hanging_sign`） | 36 | `noCollision()` + 无覆写（12 种木材 × 3 类；`*_wall_hanging_sign` 有覆写，另计） |
| 珊瑚 / 珊瑚扇 / 墙珊瑚扇（含 dead 与活的） | 30 | `noCollision()`；`getShape` 是 `Block.column(12, 0, 4)` |
| 铁轨全家（`rail` / `powered_rail` / `activator_rail` / `detector_rail`） | 4 | `noCollision()`；形状在 `BaseRailBlock.getShape` |
| 传送门三件套 | 3 | `noCollision()` |
| `glow_lichen` / `sculk_vein` / `resin_clump`（`MultifaceBlock`） | 3 | `noCollision()` |
| `hanging_roots` / `small_dripleaf` / `mangrove_propagule` / `big_dripleaf_stem` | 4 | `noCollision()` |

这 80 个宿主**全部**被 `hasCavity` 的 `shape.isEmpty() → return true` 直接放行，
既不检查顶面高度、也不检查表面宽度。后果：

- `ElementRenderSupport.columnTop` 对空形状返回 0（`shape.isEmpty()` 提前返回），
  所以**整根杆/红条都留在模型原始 y（0..5px）**，也就是贴方块底面、完全嵌在宿主几何里；
- 原版里这些方块（告示牌、珊瑚、铁轨、垂滴叶茎……）顶面根本不接受方块/元件，
  我们在它们上面装配元件是**凭空产生**的行为。

### 8.2 有覆写、但"该列没有表面"的情况

73 个宿主覆写了 `getCollisionShape`，其中绝大多数是 `CrossCollisionBlock`/`WallBlock`/
`WallHangingSignBlock` 的形状表。它们有真实表面，但**表面只覆盖足迹的一部分**：

| 宿主 | `empty()` 下的表面 | 元件足迹（熄灭态 z 2..14、x 7..9）里的空列 |
| --- | --- | --- |
| 铁链 / 铜锁链 | `6.5..9.5` × `6.5..9.5` 全高柱（`Block.cube(3,3,16)`） | `z 2..6.5`、`z 9.5..14` |
| 朝上的避雷针（`RodBlock` 4×4 柱） | `6..10` × `6..10` | 同上 |
| 朝上的钟乳石尖 | `5..11` × `5..11`，顶 0.6875 | `z 2..5`、`z 11..14` |
| 蜡烛 / 海泡菜（单颗） | 2×2 / 4×4，顶 0.375 / 0.375 | 绝大部分列 |
| 栅栏 / 墙 / 玻璃板 | 碰撞是 1.0–1.5 格高的柱+臂（栅栏/墙臂 1.5，`columnTop` 截到 1.0）；视觉横杆不参与采样 | 连接方向决定足迹是否被覆盖；覆盖列抬到 1.0，未覆盖列留在 0 |

这些列走 `columnTop` 的 `-∞ → 0` 分支，于是同一根杆被拆成两截：中间那段被抬到宿主顶面、
两端几列留在 y=0。

### 8.3 澄清（本轮核对纠正的错误结论）

- **脚手架不是"空碰撞"**：`ScaffoldingBlock` 覆写了 `getCollisionShape`，在 `empty()` 下走
  `isAbove(Shapes.block(), pos, true)`（`EntityCollisionContext.Empty.isAbove` 直接返回 `bl`=true）
  → `SHAPE_STABLE`（完整 16×16、14..16），顶面 1.0 → `hasCavity` **拒绝**。
- 但**"空碰撞宿主为 0 个"是错的**：上面 §8.1 那 80 个就是。原因是 `noCollision()` 的方块
  通常不覆写 `getCollisionShape`，落在 `BlockBehaviour` 默认实现
  `hasCollision ? getShape() : Shapes.empty()` 的空分支。

建议：把三种情况分别处理。

1. **碰撞整体为空**（§8.1）：`hasCavity` 应退回 `getShape`（视觉形状）再判，或直接拒绝
   ——"没有碰撞"并不意味着"没有可以贴的表面"，但更不该无条件放行。
2. **无表面列**（§8.2）：`columnTop` 返回哨兵（`OptionalDouble`），没有表面的列**不参与抬升**
   （保持模型原始 y），不要当成 0。
3. `hasCavity` 的 `-∞` 分支改成"至少有一列存在表面且 `top < 1`"（或按覆盖率设阈值）。

改动会显著收窄"哪些宿主可以放元件"的既有边界（告示牌/珊瑚/铁轨/垂滴叶茎/细柱全部受影响），
必须同步 README 与自检，并在 `runClientGameTest` 里目视确认。

---

## 8.5 根因 K：比较器的模拟量输入绕过了宿主露出门控

`ElementBlocks.comparatorInput`（`:422..453`）先调 `inputSignal`（那里有 `exposed` 判定），
然后把结果**覆盖**成模拟量：

```java
int signal = inputSignal(level, pos, host, extra);   // 这里 exposed 为 false 时返回 0
Direction input = PhaseExtra.facing(extra);
BlockPos behind = pos.relative(input);
BlockState state = level.getBlockState(behind);
if (state.hasAnalogOutputSignal()) {
    return state.getAnalogOutputSignal(level, behind, input.getOpposite());   // ← 无条件
}
...
```

于是"输入面被宿主完全盖住 → 不输入"这条门控对**容器/炼药锅/唱片机/讲台/探测铁轨等
`hasAnalogOutputSignal()` 的方块无效**：即使输入面被完整挡住，比较器照旧读到模拟量。

原版这里也没有 exposed 概念（元件是整格方块），所以这**不是原版差异**，而是本 mod 自己声明的
门控没有被贯彻到模拟量分支——属于内部不一致，用户按 README 的"I/O 露出门控"推断会出错。

建议：在 `comparatorInput` 开头统一做一次 `if (!RedstoneBlocks.exposed(host, input)) return 0;`，
把门控提到最外层（后面的 `isRedstoneConductor` 二次深入也一并被覆盖）。

---

## 8.6 根因 L：客户端也会在形状更新里写旁路表

`ElementBlocks.onUpdateShape` 的中继器分支（`:287..293`）只要收到形状更新就调
`refreshLock(actualLevel, …)`，后者在锁存状态变化时 `PhaseExtras.set`。这个分支**没有**
`!level.isClientSide()` 守卫，而原版对应逻辑被 `!levelReader.isClientSide()` 挡住
（1.21.11 `RepeaterBlock.updateShape`）。

后果：客户端在本地预测/区块重放期间可能用不完整的邻居数据算出与服务器不同的 `locked`，
并把它写进客户端表；随后若没有对应的 `extra_update` 覆盖，就会一直保持错误值。
概率低（服务端会广播真实值），但这与"客户端是纯镜像"的设计前提矛盾。

建议：与 §1.4 的"只在服务端通知"一起处理——把 `refreshLock` 的写入包在
`if (!level.isClientSide())` 里；客户端的 `locked` 只由网络包驱动。

---

## 9. 设计内 / 已记录差异（复核后确认不算缺陷）

| 差异 | 依据 |
| --- | --- |
| 宿主相应面被完整表面覆盖时该面不输入/不输出 | 用户口径排除；`RedstoneBlocks.exposed`（比较器模拟量分支的漏网见 §8.5） |
| 中继器 `locked` 只做逻辑锁定、不画锁形（`_locked` 模型只有 1 根杆、无锁条） | README §已知限制 4；`gen_element_models.py` 的 `keep_rod_element` 过滤 `#lock` |
| 只渲染杆 + 红条 / 侦测器只渲染五官 + 输出口 | README §已知限制 10/11 |
| 普通破坏宿主时元件随宿主消失、不额外掉落 | README §已知限制 9 |
| 相（含元件）不随活塞移动 | README §已知限制 3 |
| 元件只能放在"有顶面空腔"的宿主上 | README §已知限制 2（"空碰撞宿主"与"无表面列"的子问题见 §8.1/§8.2） |
| 充水会把元件冲落成物品 | 0.2.5 设计（原版元件方块不进水流） |
| 装配永远贴宿主顶面（点侧面/底面也一样） | 设计：元件贴宿主表面；原版点侧面会放到旁边一格 |
| 装配音效是 `ITEM_FRAME_ADD_ITEM`、不走原版放置统计 | 体验差异 |
| 调档本身不通知邻居 | 与根因 A 同源；中继器延迟不改输出值，比较器已在 `cycleSetting` 里 `notifyOutput` |

补充几条**已记录但容易被漏**的：

- 爆炸/其它"非玩家、非活塞"破坏走 `affectNeighborsAfterRemoval` 的 ① 普通破坏分支 →
  元件**不掉落**（`BlockStateBaseMixin.liquidphase$materializePhase` 只在 `movedByPiston` 时掉元件）。
  原版中继器/比较器被爆炸破坏是掉落物品的。
- 原版中继器/比较器的 `POWERED` 变化用 `setBlock(..., 2)`（**不带** `UPDATE_NEIGHBORS`），
  形状更新（`updateNeighbourShapes`）不受 flag 控制，只是 `j > 0` 才做；本 mod 若按 §1.4 补形状更新，
  需要意识到"只改 `powered` 也会让邻居做一次形状更新"，这与原版一致（原版同样会）。
- 比较器相的 `power` 只通过 `getSignal` 暴露，不经过 `hasAnalogOutputSignal`/
  `getAnalogOutputSignal`。核对原版：`ComparatorBlock` 同样没有覆写这两个方法
  （`BlockBehaviour` 默认是 `false` / `0`），原版比较器链式传递靠 `getSignal`
  （`DiodeBlock#getSignal` → `getOutputSignal`，比较器覆写为读方块实体）。所以这一点
  **与原版一致，不是差异**。

---

## 10. 建议补的回归断言

现有自检（0.2.6：519/519/519/520/519/518）覆盖了装配、空腔、I/O 露出门控、调档、冲落，
但**没有覆盖上面任何一条**。建议补：

1. **比较器相 + 容器**：比较器元素宿主 + 前方箱子 → 向箱子里**放入/取出**物品
   （不是开箱；空箱开合不改变内容量，原版也不变）→ 断言条目 `power` 变化
   （现在会红：没有 `updateNeighbourForOutputSignal` 桥接）。
2. **观察者相 + 红石相/元件相**：只写表改功率/`powered` → 断言观察者宿主排刻并翻 `powered`。
3. **并排锁存**：中继器元素 + 侧向真实二极管/元件相二极管 → 断言 `locked` 立即为真。
4. **线连接刷新**：线先放、再装配/拆除 → 断言线的 `RedstoneSide` 变化。
5. **tick 槽位**：死珊瑚 / 大垂滴叶 / 朝上的钟乳石尖 上放侦测器 → 断言观察面变化后
   确实排了刻并脉冲；中继器 delay=4 → 断言 `triggerTick == gameTime + 8`
   （现在只能按 `(pos, hostBlock)` 查，改哨兵后按新类型查）。
6. **吸收初始结算**：`setBlock` 有输出的比较器到宿主格 → 断言 `power` 非 0；
   吸收 `powered=true` 的侦测器 → 断言被复位。
7. **优先级**：两个相邻二极管 → 断言排刻优先级（需要新 API 读取，或用"同 tick 只前进一格"
   的可观察行为间接断言）。
8. **比较器宿主任意面连接** / **线向上爬**：断言 `getConnectingSide` 的返回。
9. **创造模式不掉落**：创造玩家拆元件 → 断言没有 `ItemEntity`。
10. **空碰撞 / 无表面列**：告示牌、珊瑚、铁轨（碰撞为空，§8.1）→ 断言 `hasCavity` 为 false
    或拒绝装配；铁链、朝上的避雷针、蜡烛（有表面但未覆盖足迹，§8.2）→ 断言杆的顶点
    都落在有表面的列上（现在会红：`-∞ → 0` 会把两端压到 y=0）。
    反向断言：脚手架 / 树叶 / 整格方块**仍然**被拒（顶面是完整 16×16 且到顶）。
11. 六版本 `LIQUIDPHASE_SELFTEST=true ./gradlew :<版本>:runServer` 全绿 +
    1.21.11 `runClientGameTest`（几何/图层不受影响）。

---

## 11. 建议修复顺序

1. **根因 A（通知通道）**：一处通用出口 + `updateNeighbourForOutputSignal` 桥接 + 去重；
   一次修掉 A1–A4，且用户最容易误判为"元件坏了"的就是 A1。
2. **根因 C（吸收初始结算）**：局部、风险低、现象明确。
3. **根因 B（tick 槽位）**：结构性改动，需要六版本验证 + 持久化回归，建议单独一版。
4. **根因 D/E（优先级、连接规则）**：影响集中在二极管链与线连接，改动小但需实机对照。
5. **根因 G/H（挖掘语义、空碰撞宿主、无表面列）**：体验/边界，可同轮或延后；
   §8.1 那 80 个宿主最好在校验里显式列白名单/黑名单，避免"能放元件但视觉完全错位"。
6. 根因 F 与 I/J：写进 README 已知限制即可。

---

## 12. 需要拍板的取舍（选项）

> §1–§8.6 给的是"差异 + 建议修法"，但其中多处存在**几条都说得通的路线**，不是单纯的实现细节。
> 本节把它们集中列出：每条先写目标与现状，再列选项、代价与影响面，最后给倾向。
> **★** = 产品取向（不同选择都自洽，需要你定）；未标 = 工程取向（更贴近原版的答案比较明确，
> 选项的差别主要是范围与风险）。编号沿用 §0 / §11。
>
> **逐项结论见 §13**（裁定口径：与"同一元件作为原版独立方块"的行为一致）。

### 12.1 ★ 根因 A：通知做到哪一层

**目标**：让"只写旁路表"的变化对邻居表现为原版的一次 `setBlock`。
**现状**：只发 `updateNeighborsAt`；没有 `updateNeighbourShapes`，也没有
`updateNeighbourForOutputSignal` 的**反向桥接**（§1.2）。

| 选项 | 做什么 | 修掉 | 代价 / 风险 |
| --- | --- | --- | --- |
| **A-1 全量** | 在 `PhaseExtras.fireChanged`（仅服务端）统一发三条通道；红石相功率变化也算 | A1–A4 全部；观察者能看红石相 | 每次功率变化 6 次形状更新，与原版 `setBlock` 同量级；大线网是主要 TPS 风险；必须做每 tick 每格去重，否则递归链会放大 |
| **A-2 仅元件** | 同上，但只对**元件条目**的语义变化发形状更新；红石相保持现状 | A1、A3、A4；观察者能看元件相 | 观察者仍看不到红石相功率变化；成本低一档，风险集中在元件链 |
| **A-3 仅装配/拆除** | 只在 `place` / `mine` / `absorbBlock` 补形状更新 + 反向桥接 | A1；装配/拆除后的线形 | 运行期的 `powered`/`locked` 变化仍不通知：观察者看不到表变化，锁存也不即时 |
| **A-4 只文档** | — | 无 | 差异全部保留 |

**子选项（A-1/A-2 都要定）**

- **时机**：同 tick 同步（原版口径）还是每 tick 末合并一次（省扇出，但观察者脉冲整体 +1 tick）。
- **`locked` 算不算"语义变化"**：算 = 观察者能观测到锁存翻转（原版 `LOCKED` 是状态位，会触发形状更新）；不算 = 少一类通知，锁存只影响自己的 tick。
- **按种类区分"语义变化"**：比较器的模拟量 `power` 单独变化在原版只是方块实体变化（`refreshOutputState` 只在 `POWERED` 翻转时才 `setBlock`），**不应**触发形状更新/观察者脉冲，只走邻居变化让线/灯读到新值；`powered` 翻转才算。中继器的 `delay`/`locked` 在原版是状态位，算。
- **通知入口**：`PhaseExtras.fireChanged` 统一做（覆盖 `clearAt`、装配、吸收、tick 全路径），还是只在各调用点逐个补（更保守，但容易再漏）。
- **反向桥接**（容器内容变化 → 相邻比较器相）是独立的一块，A-1~A-3 都必须带；只加形状更新不加它，**A1 仍然坏**。
- 注意 `clearAt` 发生在 `LevelChunk.setBlockState` 之前，原版后续 `setBlock` 自己会发形状更新；这里要避免同一次方块替换发两遍。

**倾向**：A-2 + 同步 + `locked` 算 + 统一入口。线网性能优先时退到 A-3，并把"观察者看不到填充相"写进 README。

> **§13.2 改判为 A-1**：A-2 会保留"观察者看不到红石相功率变化"，而原版红石线的功率变化走
> `setBlock(...,2)`，形状更新与 flag 无关，相邻观察者必然收到。A-2 因此不是"少修一条"，
> 而是保留了一条原版不存在的差异。

### 12.2 根因 B：元件 tick 通道

**目标**：让元件的计划刻不再和宿主共用 `(pos, Block)` 槽位（§2）。
**现状**：`scheduleTick(pos, host.getBlock(), n)`。

| 选项 | 做法 | 优点 | 代价 / 风险 |
| --- | --- | --- | --- |
| **B-1 哨兵方块** | 注册一个不可获得、无掉落的方块，`scheduleTick(pos, SENTINEL, n)`；在 `ServerLevel.tickBlock` 的 HEAD 识别 `type == SENTINEL` → 直接跑元件 tick | 复用原版 tick 容器：持久化、区块卸载、`hasScheduledTick` 全部免费；六版 `tickBlock` 描述符一致 | 注册表多 1 个方块（状态 +1）；注入私有方法；**必须同时拆掉 `BlockStateBaseMixin.tick` 里"宿主 tick 顺带跑元件"的钩子**，否则宿主自己的 tick 仍会推进元件；旧存档里已排的"宿主型"元件 tick 会失效，正在脉冲的侦测器可能停在点亮态（需要一次迁移扫描，或接受"拆一下/重载"修复） |
| **B-2 自建队列** | `PhaseStorage` 维护 `pos -> dueTick`，由 `END_SERVER_TICK` 推进 | 不动方块注册表；逻辑完全自控 | 自己的持久化、区块卸载、与世界时间对齐、与 `extra_update` 的一致性都要写；回归面明显更大 |
| **B-3 保持现状** | 继续用 `host.getBlock()` | 零改动 | B1/B2 差异保留；且这个槽位冲突是"某些宿主上偶发"的形态，很难靠文档让用户避开 |

**两方案共同**：`hasScheduledTick` / `willTickThisTick` 的查询键必须一起换；`ElementBlocks.tick` 在条目已被清掉时要安全 no-op。
**倾向**：B-1，单独一版。若你不想动方块注册表，选 B-2；两者都不做就 B-3 + README。

### 12.3 根因 C：吸收真实元件方块时的初始结算

**目标**：吸收后的第一拍就与原版放置一致（§3）。
**现状**：`extraOfBlock` 把比较器 `power` 写 0；`absorbBlock` 不排刻、不复位侦测器。

| 选项 | 做法 | 效果 | 代价 |
| --- | --- | --- | --- |
| **C-1 立即结算** | 复用 `place` 的初始化：`refreshLock` + 现算 `comparatorOutput` / `shouldTurnOn` + 侦测器复位 + 有输入排 1 tick | 吸收瞬间输出正确 | 需要把 `place` 的初始化抽成共用函数；比较器要现算一次输入 |
| **C-2 排 1 tick 统一重算** | 只排 1 tick，`tick` 里写 `power` / `powered` | 最贴近原版"方块实体先为空、下一 tick 刷新" | 吸收后有一拍输出 0 / 不亮，玩家能看出来 |
| **C-3 只补最小项** | 只 `refreshLock` + 侦测器复位 | 修"永远亮" | 比较器/中继器仍要等邻居变化 |
| **C-4 只文档** | — | 无 | 差异保留 |

**注意**：不要直接把被吸收比较器方块实体的旧 `output signal` 抄进条目——它是上一次结算的结果，可能与当前输入不一致；要么现算，要么排刻重算。
**倾向**：C-1；若你更看重"吸收 = 原版放置"的时序，选 C-2（接受一拍 0）。

> **§13.4 改判为 C-2**：原版的"出现"只有 `setPlacedBy → scheduleTick(1)` 一条路径，
> 放置后第一拍输出仍是 0，下一拍才结算。C-1 比原版快一个红石刻。
> 但排刻条件必须复刻 `shouldTurnOn`（无条件排刻会给中继器注入假脉冲），
> 且吸收时不要把原方块的 `POWERED` 抄进条目。

### 12.4 根因 D：计划刻优先级

**目标**：同一 tick 内多个二极管结算时，顺序与原版一致（§4）。
**现状**：全部默认 `NORMAL`，没有 `shouldPrioritize`。

| 选项 | 做法 | 代价 / 说明 |
| --- | --- | --- |
| **D-1 完整镜像** | 实现 `shouldPrioritize`（看 `facing.getOpposite()` 方向的相邻真实二极管 / 元件条目是否朝向不同），按原版档位传 `scheduleTick(..., priority)` | 判定只看前方一格，改动不大；需要能同时识别真实方块与条目 |
| **D-2 只补档位** | 中继器 `powered ? VERY_HIGH : HIGH`，比较器 `NORMAL`；不做 `shouldPrioritize` | 简单；相邻二极管互指时仍差一档（原版是 `EXTREMELY_HIGH` / `HIGH`） |
| **D-3 跳过** | — | 差异只在链/环上可观察；文档注明 |

**倾向**：D-1（顺手做，和 A/B 同一版或紧随其后）；不想动判定就 D-2 + 文档注明"互指二极管差一档"。

### 12.5 ★ 根因 E+F：元件宿主算不算"信号源"

**目标**：红石线连接态（E）以及依赖 `isSignalSource()` 的原版逻辑（F）与独立元件一致。
**现状**：比较器宿主只在轴向两面连接；线不会向上爬；`isSignalSource()` 恒 false。

| 选项 | 做什么 | 修掉 | 代价 / 产品冲突 |
| --- | --- | --- | --- |
| **E-1 连接规则** | 比较器宿主放宽为任意非 null 面；`RedStoneWireBlockMixin` 补 `canConnectUp`（线向上爬到元件宿主） | E1、E2 | 只影响红石线连接态；改动自包含，风险低 |
| **E-2 E-1 + 原版两个调用点** | 额外给 `NaturalSpawner.isValidEmptySpawnBlock`、`RailBlock.updateState` 加"该格有元件条目"的旁路判定 | F 里的刷怪、铁轨朝向 | **产品冲突**：元件贴在半砖上时，那格大半是空的，原版 parity 会**禁止刷怪**；铁轨可能因为旁边一个元件相而改朝向。是"像原版"还是"按实际占用"要你定 |
| **E-3 只修连接面** | 只做比较器任意面，不做向上爬 | E1 | 最小；E2（向上爬）仍缺 |
| **E-4 保持现状** | — | 无 | 差异保留 |

**说明**：不能用"该方块类型的 `isSignalSource()` 返回 true"来省事——它没有坐标，会把**所有**同种方块（包括没装元件的）都变成信号源，还会改变红石线连接与刷怪。
**倾向**：E-1；F 默认只写文档。若要 E-2，请先确认"半砖上的元件也禁止刷怪"是不是你想要的效果。

> **§13.6 / §13.0 改判**：连接面取 **E-3**（不是 E-1）。原版 `getConnectingSide` 的
> `canConnectUp` 分支走的是**无方向**重载 `shouldConnectTo(state)`（`direction == null`），
> 三种元件在那里恒为 false——"线向上爬到元件"**不是原版行为**，E-1 的后半段会凭空造一条。
> F 部分改取 **E-2**（刷怪 + 铁轨按坐标补），但铁轨那条还受"恰好 3 个潜在连接"约束；
> 第三方 `isSignalSource()` 仍做不到，只能写进 README。

### 12.6 ★ 根因 G：拆元件的三条语义（可分别选）

**现状**：按宿主硬度挖、不看瞄准、创造模式也掉落（§7）。三条互相独立：

| 子项 | 选项 | 效果 / 代价 |
| --- | --- | --- |
| **G-掉落** | (a) 补 `!player.isCreative()` 守卫（`minePhase` 里已有 `player`，加参数即可） | 与原版创造一致；改动最小 |
| | (b) 保持创造也掉 + README | 保留"拆元件有产出"的现状 |
| **G-硬度** | (a) 随宿主（现状） | 拆元件永远要先挖穿宿主 |
| | (b) 中继器/比较器瞬挖；侦测器按 `3.0 + requiresCorrectToolForDrops` | 与三种独立方块一致；需要 `getDestroyProgress` 按条目种类返回伪状态的进度（状态级有 `pos`，客户端同样读表） |
| | (c) 三者都瞬挖 | 手感统一、实现最简；但与侦测器原版硬度不符 |
| **G-瞄准** | (a) 不看瞄准（现状：先拆元件，拆宿主要点两次） | 实现简单；容易被当成 bug |
| | (b) 看瞄准（元件命中盒/射线，拆宿主一次点击） | 需要给元件做命中几何；杆/红条很薄，容易点不中，"拆不掉元件"的投诉风险高 |
| | (c) 折中：瞄准宿主顶面 2px 内算元件，其余算宿主 | 介于两者之间，规则要写清楚 |

**说明**：硬度与瞄准都走 `getDestroyProgress` / 服务端破坏入口；客户端也读同一张表，能同步。侦测器的"正确工具才掉落"如果要镜像，还要在 `mine` 里加掉落条件（与 G-掉落是两件事）。
**倾向**：掉落 (a) + 硬度 (b) + 瞄准 (a)；瞄准 (b) 是纯 UX 取舍，建议先不做。

> **§13.7 对瞄准改判为 (c)**：原版中继器/比较器的轮廓是 **16×16、高 2px**，侦测器是**整格**。
> 现状 (a)"点宿主任何位置都先拆元件"比原版**更宽**（原版在 y=1 那层点到的是空气）。
> (c) 把 2px 落到宿主逐列顶面，才等于原版 16×2；侦测器按整格。
> 掉落 (a) 与硬度 (b) 与倾向一致。

### 12.7 ★ 根因 H1/H2：可装配宿主集合与"无表面列"

**现状是两条规则叠加**：

- `hasCavity`：碰撞形状整体为空 → 直接放行（§8.1，80 个宿主）；
- 采样循环：**整块 16×16** 里任一列 `-∞`（没有表面）或 `top < 1` → 放行（§8.2）。

所以"有空洞"本身就算通过，而 `columnTop` 又把 `-∞` 当 0，同一根杆被拆成两截。
H1 与 H2 其实是同一个判定问题，建议一起定：

| 选项 | 做法 | 结果 |
| --- | --- | --- |
| **R-1 只拒绝空碰撞** | `isEmpty()` → 不再放行 | 80 个宿主（告示牌 36、珊瑚 30、铁轨 4、传送门 3、多面体 3、垂滴叶茎等 4）不能装；但细柱/空洞宿主仍按 `-∞` 放行，H2 仍在 |
| **R-2 改覆盖判定** | 要求**元件足迹**（x 7..9、z 2..14）的每一列都有表面，且至少一列 `top < 1`；空形状按"无覆盖"拒绝；新装配不再走 `-∞ → 0`（历史条目见 L-1） | 一次解决 H1+H2：铁链/铜锁链、避雷针、蜡烛、单颗海泡菜、钟乳石尖、各类茎、红树胎生苗、多面体、墙告示牌等退出；直立珊瑚（12×12）、大垂滴叶（未完全倾斜，16×16）、小型垂滴叶（12×12）、平坦铁轨（16×16）、12×12 的海泡菜簇等仍可装（逐状态核对）；标准告示牌（8×16 板、顶面 1.0）退出；**悬挂告示牌按旋转不同**（部分旋转的 14×2 板覆盖足迹、顶面 0.625，会通过）；**栅栏/墙/玻璃板这类形状随连接变化的宿主变成"覆盖足迹才可装"**，同一格的结果取决于邻居连接 |
| **R-3 覆盖 + 最小面积** | 在 R-2 上加"覆盖面积 ≥ 阈值"或"顶面高度落在某区间" | 可以排除"只有中心一小块有表面"的极端宿主；阈值需要你定 |
| **R-4 黑名单/白名单** | 在现状上按家族裁决（保留珊瑚、拒绝铁轨/传送门/告示牌/细柱…） | 规则人工维护；新增宿主不会自动得到正确结论；但能保留个别装饰玩法 |
| **R-5 保持 + 文档** | — | 80 个宿主 + 空洞宿主继续"能装但贴底/断裂" |

**取舍点**：

- R-2 会保留"元件装在珊瑚/大垂滴叶/铁轨/小垂滴叶/海泡菜簇上"，但去掉"元件装在告示牌/铁链/蜡烛上"这类装饰玩法；是否保留其中某几类，按你的玩法定。
- R-2 的"足迹"是按**中继器/比较器的杆+红条**（x 7..9、z 2..14）定义的；侦测器贴在宿主某个面上、不占这块足迹。要么给侦测器单独定规则（例如"该面露出 + 宿主存在空腔"），要么继续用同一条粗判定。否则会出现"侦测器能装在平坦铁轨上、却装不上 3px 细柱"这种与它的几何无关的结果。
- R-2 对栅栏/墙的结论依赖邻居连接（形状是动态的）：同一格今天能装、拆掉旁边方块后就不能再装。这是可接受的动态规则，还是干脆用 R-4 拒绝这类宿主？
- **已存在的条目**（`PhasePersistence.load` 用 `putRaw` 直接灌入，不校验）：
  - **L-1 保留**：历史格继续工作；
  - **L-2 保留但不再接受新的**：数据不变，只拦新装配（改动最小）；
  - **L-3 读取时清除并掉落**：干净，但会"吃掉"玩家已有的元件。

> **§13.8 已按"与单独放置一致"改判为 R-3（阈值 = 原版 `SupportType.RIGID`）+ L-3**，
> 理由见该节（R-2 判的区域是渲染足迹的中心条带，与原版的边缘环不是同一件事）。以下倾向保留原文备查。

### 12.8 ★ 根因 H2：只在保留"有空洞宿主"时需要

若 12.7 选 R-1/R-2，**这一节基本不需要**（足迹被要求全覆盖）。只有选 R-4/R-5 保留细柱/空洞宿主时，才需要定渲染规则：

| 选项 | 做法 | 结果 |
| --- | --- | --- |
| **H2-1 保持** | 无表面列不抬升（=0） | 杆/红条被拆成两截（现状） |
| **H2-2 统一起抬** | 整个元件按足迹内的最高表面抬升 | 连续、不会断；无表面的列会**悬空**在宿主外侧 |
| **H2-3 裁剪几何** | 只渲染有表面的列 | 视觉"诚实"，但元件可能只剩中间一小段，不像元件 |

**倾向**：优先 12.7 R-2（这一节不需要）；若保留细柱宿主，H2-2 并接受悬空。

> **§13.9 裁定**：R-3 之后这一节**大幅收窄但没有完全消失**——`RIGID` 只保证最顶层一圈外环，
> 环形/中间空心且顶面到 1.0 的宿主仍可能留下无表面列。残留时取 **H2-2**；同时 `columnTop`
> 的 `-∞ → 0` 应改成哨兵（函数级问题，与放置规则无关）。

### 12.9 根因 K：比较器模拟量是否也受 I/O 露出门控

| 选项 | 做法 | 结果 |
| --- | --- | --- |
| **K-1 统一门控** | `comparatorInput` 最外层先判 `exposed`，再读模拟量 | 与 README 声明的"被完整盖住的面不输入"一致；与原版不同（原版没有门控概念） |
| **K-2 保持例外** | 模拟量分支绕过门控，README 写明 | 保留"容器隔着脸也能读"的现状 |

**倾向**：K-1（这是内部一致性问题，不是原版 parity 问题）。

> **§13.10 改判为 K-2**：用户指定的口径是"与单独放置一致"，而原版比较器**没有**
> "输入面被盖住就不读"的概念（`getInputSignal` 在 `hasAnalogOutputSignal` 时无条件读）。
> K-1 是比原版更严，只保住内部自洽。若更看重门控一处生效，仍可选 K-1，但那要写成主动偏离。

### 12.10 根因 L：客户端是否允许写 `locked`

| 选项 | 做法 | 结果 |
| --- | --- | --- |
| **CL-1 服务端守卫** | `onUpdateShape` 的 `refreshLock` 只在 `!level.isClientSide()` 写表；客户端 `locked` 只由 `extra_update` 驱动 | 客户端成为纯镜像；锁存没有视觉，不需要本地预测 |
| **CL-2 保持** | 客户端继续按邻居算 `locked` | 预测更快，但可能与服务器不一致并滞留 |

**倾向**：CL-1，随 A 一起做。

### 12.11 ★ 根因 I：碰撞与粒子

**碰撞**

| 选项 | 做法 | 代价 / 风险 |
| --- | --- | --- |
| **I-1 不加**（现状） | 元件不占碰撞 | 与原版不同；但方案 C 的元件是"贴在宿主表面的装饰"，不加碰撞是自洽的 |
| **I-2 只加 16×2 板** | 中继器/比较器在宿主顶面加 2px 碰撞；侦测器不加 | 走下半砖时多 2px 台阶；实体/路径/刷怪判定会变；状态级 `getCollisionShape` 有 `pos`，可查表 |
| **I-3 完整镜像** | 侦测器也加整格碰撞 | 半砖上的侦测器会变成一整格障碍，基本不可接受 |

**粒子**

| 选项 | 做法 | 说明 |
| --- | --- | --- |
| **PT-1 不加** | — | 现状 |
| **PT-2 只补点亮的中继器** | 宿主 `animateTick` 里查表，条目是点亮中继器时发 `DustParticleOptions.REDSTONE` | 与原版一致（只有 `RepeaterBlock.animateTick` 发；比较器/侦测器没有）；成本低 |

**倾向**：碰撞 I-1 + 粒子 PT-2；碰撞差异写进 README。

> **§13.12 对碰撞改判为 I-2**：原版中继器/比较器是 16×2 碰撞，I-2 才是 parity。
> 但 I-2 与 §13.6 的 E-2（元件格禁刷怪）耦合，要一起做。
> **侦测器仍是 I-1**：原版是整格碰撞（严格 parity 应 I-3），但那会让半砖上的侦测器变成
> 整格障碍、与"元件是宿主表面装饰"的表示法前提冲突，所以有意不照做，README 写明。
> 粒子 PT-2 与倾向一致。

### 12.12 ★ 根因 J：爆炸/非玩家破坏是否掉元件

| 选项 | 做法 | 结果 |
| --- | --- | --- |
| **J-1 任何破坏都掉** | 在移除路径补弹出元件物品（活塞路径已经这么做） | 更接近原版（独立元件被爆炸破坏会掉落）；TNT/苦力怕会产出元件 |
| **J-2 保持不掉**（现状） | 只有玩家左键拆元件才掉 | 规则简单；但"活塞掉、爆炸不掉"不统一 |
| **J-3 只对玩家破坏掉** | 爆炸/命令等不掉，玩家拆宿主时掉 | 中间路线；需要在移除路径区分来源 |

**倾向**：J-2 或 J-1 二选一，并把选择写进 README；不建议 J-3（来源判定容易漏）。

> **§13.13 取 J-1**：原版掉落表带 `survives_explosion`，且 `DiodeBlock.neighborChanged`
> 在 `!canSurvive` 时 `dropResources + removeBlock`——爆炸与"支撑消失"都掉。
> 连同 README 已知限制 9（"破坏宿主时元件随宿主消失、不额外掉落"）一起改写。

### 12.13 修复批次与配置开关

| 选项 | 内容 | 说明 |
| --- | --- | --- |
| **S-1 分批**（§11） | A+C 一版；B 单独一版；D/E/G/H 一版 | 每版可独立回滚；B 的结构风险不与行为改动混在一起 |
| **S-2 一次全做** | 一个大版本 | 只发一次；但 B 出问题时难定位 |
| **S-3 只做 A/B/C** | 其余只写文档 | 改动面最小；D/E/G/H 的差异保留 |

**开关**：是否为"瞬挖 / 瞄准 / 碰撞"这类手感改动加配置项？

- **不加**：行为统一、测试矩阵不膨胀；升级即变。
- **加**：用户可回退；但每个开关都要覆盖六版本与自检，组合爆炸。

**倾向**：S-1；不加开关（除既有的 `phaseEnabled` / `packMerge` 外）。

---

## 13. 取舍裁定（口径：与"同一元件单独放置在那一格"的行为一致）

> 裁定依据统一为：**把元件当成原版独立方块时，原版在同样的输入/邻居/玩家操作下会做什么**。
> 凡涉及"宿主"的位置，映射为"元件占用的那一格"（元件贴宿主表面、I/O 面沿用宿主的面）。
> 逐项结论见 §13.1 的汇总表；**口径选不出唯一答案、或口径答案与表示法冲突的**集中在 §13.15。

**十句结论**（细节与出处见各小节）：

1. **A → A-1**：三条通道都补，含红石相；比较器只有 `POWERED` 翻转才算语义变化。
2. **B → B-1**：元件必须有自己的 tick 槽位（B-2 同样可以，这里按工程量取 B-1）。
3. **C → C-2**：吸收时写"新建"初始态并排 1 tick，**不要**立即现算（原版放置本身晚 1 拍）。
4. **D → D-1**：补 `shouldPrioritize` 与三档优先级。
5. **E → E-3 + F 的 E-2**：只修比较器任意面连接（"向上爬"对元件不是原版行为）；
   刷怪/铁轨按"该格有元件"补，第三方 `isSignalSource()` 无坐标做不到。
6. **G → 掉落 (a) + 硬度 (b) + 瞄准 (c)**：创造不掉、元件按自身硬度、命中盒按 16×2 轮廓。
7. **H → R-3（阈值用原版 `SupportType.RIGID`）+ 侦测器另立规则 + L-3**。
8. **I → 碰撞 I-2 + 粒子 PT-2**；侦测器整格碰撞与原版一致但**不可采纳**，保持无碰撞。
9. **J → J-1**：所有破坏来源都掉（原版支撑消失时也掉）。
10. **K → K-2、L → CL-1**：模拟量不受露出门控（原版没有这条门控）；客户端不写 `locked`。

其中 **8 项与 §12 的"倾向"不同**（§12.1 A-1、§12.3 C-2、§12.5 E-3+E-2、§12.6 瞄准 (c)、
§12.7 R-3+L-3、§12.8 H2-2、§12.9 K-2、§12.11 碰撞 I-2），原因都是 §12 的倾向偏向了
"手感/内部自洽"，而本口径要求对齐原版；每处都写了差在哪里。
（与倾向一致的是 §12.2 B-1、§12.4 D-1、§12.10 CL-1、§12.11 的粒子 PT-2；
§12.12 倾向给的是"J-2 或 J-1"，这里取 J-1；§12.13 口径不决定。）

### 13.0 裁定前的两处事实更正（影响 §12.5 / §12.1 的结论）

1. **"线向上爬到元件宿主"不是原版行为（§5 的 E2 不成立）。**
   1.21.11 `RedStoneWireBlock.getConnectingSide(..., boolean bl)` 的 `canConnectUp` 分支用的是
   **无方向重载** `shouldConnectTo(blockState)`（`direction == null`）：

   ```java
   if (bl2 && shouldConnectTo(blockGetter.getBlockState(blockPos2.above()))) { ... }
   protected static boolean shouldConnectTo(BlockState state) { return shouldConnectTo(state, null); }
   // 中继器：facing == direction || facing.getOpposite() == direction  → null 时恒 false
   // 侦测器：direction == FACING                                        → null 时恒 false
   // 其它：  isSignalSource() && direction != null                      → null 时恒 false
   // 红石线：true（唯一命中）
   ```

   26.3 结构相同（`shouldRedstoneWireConnectTo` 的默认实现同样是 `isSignalSource() && direction != null`）。
   **所以这条分支实际上只对"上方的原版红石线"生效**，三种元件在原版都不会被线从斜上方连上。
   我们的元件宿主不爬，与原版一致，**不需要修**。

   顺带一个**真正的**红石相对等缺口（不属本节元件范围，记录备查）：红石相宿主等价于红石线，
   但原版 `shouldConnectTo(state above)` 只认 `Blocks.REDSTONE_WIRE`，看不到"宿主 + 条目"，
   所以线仍然爬不到斜上方的**红石相**格。这是红石相的一致性缺口，应另行处理（改
   `RedStoneWireBlockMixin` 的 `canConnectUp` 判定，或在 26.3 的 `shouldRedstoneWireConnectTo`
   走无方向查询），**不要**和元件连接面的修改混在一起。

2. **比较器的"输出数值变化"在原版不触发形状更新，只有 `POWERED` 翻转才触发。**
   `ComparatorBlock.refreshOutputState` 只在 `POWERED` 变化时 `setBlock(..., 2)`；方块实体里的
   output signal 变化本身不经过 `Level.setBlock`，只走 `updateNeighborsInFront`（邻居变化）。
   中继器相反：`POWERED` 翻转（`setBlock(...,2)`）、`DELAY`/`LOCKED` 变化（`setBlock(...,3)` /
   `updateShape → updateOrDestroy`）都会走形状更新。
   这决定 §12.1 的"按种类区分语义变化"不是可选项，而是必要条件（否则观察者会对比较器的
   数值变化多发一次脉冲，反而比原版多）。

### 13.1 汇总

| 小节 | 选定 | 一句话理由 |
| --- | --- | --- |
| 12.1 | **A-1**（同步、`locked` 算、统一入口、按种类过滤、必带反向桥接） | 原版观察者能观测红石线的功率变化，能观测 `LOCKED`/`DELAY`；比较器数值变化不算 |
| 12.2 | **B-1** | 独立元件占自己的 tick 槽位；B-2 同样满足，故用工程口径定 |
| 12.3 | **C-2**（排刻条件必须复刻 `setPlacedBy`） | 原版"放置"本身就有 1 tick 延迟 |
| 12.4 | **D-1** | 原版有 `shouldPrioritize` 与三档优先级 |
| 12.5 | **E-3**（连接面）+ **E-2**（F 的两个原版调用点） | 比较器任意面连接是原版；`canConnectUp` 与元件无关；刷怪/铁轨按 `isSignalSource` |
| 12.6 | 掉落 **(a)** + 硬度 **(b)** + 瞄准 **(c)** | 创造不掉、中继器/比较器瞬挖、侦测器 3.0+正确工具、轮廓 16×2 |
| 12.7 | **R-3**（阈值 = 原版 `SupportType.RIGID`）+ 侦测器另立规则 + **L-3** | 原版支撑判据就是 `RIGID`（边缘 2px 环），不能改成"中心足迹" |
| 12.8 | **H2-2 + 修 `columnTop` 哨兵**（R-3 大幅收窄但不保证消失） | `RIGID` 只保证最顶层外环，环形宿主仍可能留下无表面列 |
| 12.9 | **K-2** | 原版比较器没有"被盖住就不读模拟量"的概念 |
| 12.10 | **CL-1** | 原版 `RepeaterBlock.updateShape` 有 `!isClientSide` 守卫 |
| 12.11 | 碰撞 **I-2** + 粒子 **PT-2** | 原版碰撞 16×2、只有点亮的中继器发粒子；侦测器整格碰撞不可采纳 |
| 12.12 | **J-1** | 原版爆炸会掉落；且原版支撑被破坏时元件也掉落 |
| 12.13 | 口径不决定（发布工程） | 见 §13.15 |

### 13.2 §12.1 根因 A：选 A-1

**为什么不是 A-2**：A-2 保留"观察者看不到红石相功率变化"。原版里红线功率变化走
`setBlock(..., 2)`，`Level.setBlock` 的形状更新只由 `j > 0` 控制（`(i & 16) == 0 && j > 0`），
与更新 flag 无关，所以相邻观察者**一定**会收到 `updateShape` 并起脉冲。红石相是我们给
"红石线"的等价物，观察者相看不到它就等于"观察者观测不到红石线"，与原版不一致。
A2 因此不是"少修一条"，而是保留了 A2 自己列出的那条差异。

**子项的裁定**：

- **时机：同 tick 同步。** 原版形状更新发生在 `setBlock` 调用内，同一 tick 完成；
  每 tick 末合并会让观察者的脉冲整体晚 1 tick（`ObserverBlock.startSignal` 排的是 2 tick，
  但"何时开始排"差 1 tick 就是相位差）。
- **`locked` 算语义变化。** 原版 `LOCKED` 是状态位，`RepeaterBlock.updateShape` 改它以后经
  `Block.updateOrDestroy → setBlock`，照样发形状更新。不算就等于"锁存翻转观察者看不到"。
- **按种类过滤语义变化（必需，见 §13.0 第 2 条）**：
  - 中继器：`powered` / `delay` / `locked` 变化 → 三条通道全发；
  - 比较器：只有 `powered` 翻转才发形状更新；`power`（模拟量）单独变化只发邻居变化
    （对齐 `refreshOutputState`）；
  - 侦测器：`powered` 翻转发形状更新（`ObserverBlock.tick` 里是 `setBlock(...,2)`）；
  - 红石相：功率变化发形状更新（对齐 `DefaultRedstoneWireEvaluator` 的 `setBlock(...,2)`）。
- **入口：`PhaseExtras.fireChanged` 统一（仅服务端）。** 原版只有 `Level.setBlock` 一个出口，
  对应到本 mod 就是"写表"这一个出口；在各调用点逐处补是 §1.2 那张表列出的漏点形态本身。
- **反向桥接必带。** `Level.updateNeighbourForOutputSignal` 只找 `Blocks.COMPARATOR`，
  不加桥接则 A1（容器变化）仍然坏——A1 是玩家最常误判成"元件坏了"的一条。

**残余注意**：`clearAt` 发生在 `LevelChunk.setBlockState` 之前，原版随后那次 `setBlock`
自己会发形状更新，要在同一次方块替换里合并，避免发两遍。

> 附：这一项同时把 §9 里"调档本身不通知邻居"变成已修——原版 `RepeaterBlock.useWithoutItem`
> 用 `setBlock(..., 3)`（含 `UPDATE_NEIGHBORS`），调档本来就该通知。

### 13.3 §12.2 根因 B：选 B-1

判据是"元件的计划刻不得与任何其它逻辑共用槽位"。B-3 直接违反；B-1 与 B-2 都满足，
**这条口径区分不了二者**，因此按工程口径取 B-1（复用原版容器，持久化/卸载/`hasScheduledTick`
免费）。同时必须照选项里写的拆掉 `BlockStateBaseMixin.tick` 的宿主型钩子，否则宿主自己的 tick
仍会推进元件（那还是共用槽位，等于没改）。

**这条例口径管不到的部分**：旧存档里已排的宿主型 tick 会失效，正在脉冲的侦测器可能停在点亮态。
要么做一次迁移扫描，要么接受"重载/拆一下修复"——这是取舍，不是判据能推出的结论。

### 13.4 §12.3 根因 C：选 C-2（与 §12 的倾向 C-1 相反）

原版元件的"出现"只有一条路径：`setPlacedBy` →

```java
public void setPlacedBy(...) { if (this.shouldTurnOn(level, pos, state)) level.scheduleTick(pos, this, 1); }
```

也就是**放置后的第一 tick 输出仍然是 0 / 未点亮**，下一 tick 才由 `tick` 结算。
C-1（立即现算）比原版快一个红石刻；C-2 与原版的放置时序逐 tick 对齐，所以按本口径取 C-2。

这与 §3 的建议代码一致（那里就是"排 1 tick + 立即 `refreshLock` + 立即复位侦测器"），
也与 §12.3 自己的"最贴近原版'方块实体先为空、下一 tick 刷新'"一致；只是 §12.3 的 **倾向** 写成了 C-1。
若更看重"吸收瞬间输出就正确"这一体验，选 C-1 也可以，但那是**故意**偏离原版一个红石刻，要写进文档。

**C-2 的两个实现约束（写错会比现状更糟）**：

1. **排刻必须复刻 `setPlacedBy` 的条件**（`shouldTurnOn`），不能无条件排 1 tick。
   条件：中继器 = 输入 > 0；比较器 = `shouldTurnOn`。比较器不必额外判 `comparatorOutput != 0`
   —— `calculateOutputSignal` 非 0 必然蕴含 `shouldTurnOn`（减法是 `i > j`，比较是 `i >= j`），
   §3 代码里的那一项是冗余的。
2. **无条件排刻会给中继器注入假脉冲**：我们的 `tick` 镜像 `DiodeBlock#tick`，在
   `!powered` 时会点亮并（输入已消失时）再排一次；对"本来就没输入"的中继器无脑排 1 tick，
   就会凭空产生一个完整延迟的脉冲。原版不会，因为 `setPlacedBy` 只在 `shouldTurnOn` 时排。
3. 侦测器复位是**立即**（`ObserverBlock.onPlace` 是同步的），并且 B-1 之后要改成查哨兵类型
   的 `hasScheduledTick`。
4. **吸收时不要把 `powered` 抄进来**（`extraOfBlock` 现在三种元件都抄 `POWERED`）。原版"新建
   一个元件"的初始态是 `POWERED=false` + 方块实体 output 0（`ComparatorBlockEntity` 的
   `DEFAULT_OUTPUT = 0`），再按 `setPlacedBy` 排 1 tick；本 mod 没有方块实体，条目就是那个方格，
   所以 C-2 的正确形态是**吸收时写 `power=0, powered=false`**（中继器 `locked` 现算），之后由
   1 tick 结算。现在抄 `powered` 会让"吸收一个正在输出的比较器"落进 `power=0, powered=true`：
   减法模式下 `tick` 的早退（对齐原版 `refreshOutputState` 的 `j == i && SUBTRACT`）不纠正它，
   `getSignal` 又要求 `powered` 才读 `power`——两头都不出错，于是这条不一致会静默留着，
   并让每次邻居变化都多排一次空刻。改成 `powered=false` 后它就是原版那个"新建"状态。

### 13.5 §12.4 根因 D：选 D-1

原版 `DiodeBlock.checkTickOnNeighbor` 的 `EXTREMELY_HIGH / VERY_HIGH / HIGH` 与
`ComparatorBlock.checkTickOnNeighbor` 的 `HIGH / NORMAL`、以及 `shouldPrioritize`
（"正前方是朝向不同的二极管"）都是可观察的顺序语义：同 tick 内多个二极管按优先级出队。
D-2 缺 `shouldPrioritize`，相邻二极管互指时档位就是错的；D-3 保留差异。只有 D-1 与原版一致。

`shouldPrioritize` 的判定要同时认真实二极管与旁路条目（`facing.getOpposite()` 方向的邻居），
原版只认方块，我们多认一条表查询。

### 13.6 §12.5 根因 E+F：连接面选 E-3，F 选 E-2

**E 部分：E-3，不是 E-1。** 原版 `shouldConnectTo` 里比较器没有专门分支，落到
`isSignalSource() && direction != null` → **任意非 null 面都连**；中继器是轴向两面、侦测器只有
`facing` 面。所以"比较器任意面"必须修（E1 是真差异），而"向上爬"对元件不是差异（§13.0 第 1 条），
E-1 的后半段会**主动造出一条原版没有的行为**。E-3 正好等于"只修比较器任意面"。
两侧都仍保留 `exposed`（宿主覆盖面不连）。

**F 部分：E-2。** 原版元件 `isSignalSource() == true`，影响的两个原版调用点都能带坐标判定：

| 调用点 | 原版行为 | 元件宿主（加旁路判定后） |
| --- | --- | --- |
| `NaturalSpawner.isValidEmptySpawnBlock` | 先看 `isCollisionShapeFullBlock`，再看 `isSignalSource` → 元件格**禁止刷怪** | 同（元件格禁止刷怪） |
| `RailBlock.updateState` | 条件里 `block.defaultBlockState().isSignalSource()`，且**要求相邻铁轨恰好 3 个潜在连接**才改朝向 | 有元件条目时视为信号源（仍受"3 个连接"约束） |

铁轨这一条要注意**它不常触发**：条件是"被破坏/变化的那个方块是信号源"**且**当前铁轨有
恰好 3 个潜在连接（`countPotentialConnections() == 3`，看四向 `hasRail`）。所以元件宿主对铁轨
的影响面比 §6 表格暗示的窄；加旁路判定要对齐这个完整条件，不要只把 `isSignalSource()` 换成
"该格有元件条目"就完事。

两者都有 `pos`，可以查表。**代价要讲清**：半砖上的元件会让**整个宿主格**禁止刷怪——这不是
"像原版"的副作用，而就是原版的结论（原版中继器所在格也禁止刷怪），只是位置从"元件上方那格"
映射成了"元件所在那格"（本 mod 没有独立格子）。这一条会改变生存玩法，属于要在发布说明里点名的变化。

**第三条（第三方 mod 的 `isSignalSource()`）做不到**：状态级判定没有坐标，除非引入
"按坐标的信号源查询"接口并让所有调用方改走它，代价远大于收益。这一条只能写进 README。

### 13.7 §12.6 根因 G：三项分别选 (a) / (b) / (c)

| 子项 | 选定 | 依据 |
| --- | --- | --- |
| 掉落 | **(a) 补 `!player.isCreative()`** | 原版创造模式走 `Player.preventsBlockDrops()`（= `abilities.instabuild`），`destroyBlock` 直接返回、不掉落；`ElementBlocks.mine` 现在无条件 `Block.popResource` |
| 硬度 | **(b) 按种类** | `Blocks.java`：中继器/比较器 `instabreak`；侦测器 `strength(3.0F).requiresCorrectToolForDrops()`。随宿主硬度（现状）在三种元件里全错；三者都瞬挖对侦测器错。实现上 `PhaseTargeting.pseudoState` 已有"条目 → 伪状态"的先例（红石相 → 红石线），元件扩展同一个 switch 即可 |
| 瞄准 | **(c) 折中，且 2px 按宿主逐列顶面算** | 原版轮廓就是 16×2（`DiodeBlock.SHAPE = Block.column(16, 0, 2)`），不是杆/红条的细几何 |

**瞄准为什么是 (c) 而不是 (a)/(b)**：

- 原版**选中**用的是 `getShape`（轮廓）：中继器/比较器覆写为 16×16 覆盖面、高 2px
  （`DiodeBlock.SHAPE = Block.column(16, 0, 2)`）；侦测器**根本没有覆写 `getShape`**
  → `BlockBehaviour` 默认 = 整格。
- 所以"点哪都算元件"对**侦测器**成立，对中继器/比较器只成立在顶部 2px 那一层。
  现状（(a)）是"点宿主任何位置都先拆元件"，**比原版更宽**：原版在 y=1 那一层点到的是
  上方空气，根本不会选中中继器。
- (b) 若按渲染几何（杆/红条）做命中盒就是细条，比原版窄；(c) 把 2px 落到**宿主逐列顶面**上，
  既等于原版 16×2 的口径，也与 0.2.4 起的逐列抬升渲染自然对齐（下半楼梯那侧抬 2px）。
- 侦测器按 (c) 的等价物 = **整格**命中（原版整格轮廓）；这一格里的宿主因此点不到，
  但原版里那一格本来就只有侦测器，属于表示法的必然结果，写进 README。

**附加**：硬度 (b) 里侦测器的 `requiresCorrectToolForDrops` 必须同步到 `mine` 的掉落条件
（`player.hasCorrectToolForDrops(伪状态)`），这与掉落 (a) 是两件事，别只做一件。

### 13.8 §12.7 根因 H1/H2：选 R-3，阈值用原版 `SupportType.RIGID` 定义（不是"再加一个面积阈值"）

**原版判据是什么**（`DiodeBlock.canSurviveOn`，六版本同结构）：

```java
return blockState.isFaceSturdy(levelReader, blockPos, Direction.UP, SupportType.RIGID);
```

`SupportType.RIGID` = "上表面的形状**包含**整块减掉中心 12×12 内圈后的 2px 外环"
（`Shapes.joinIsNotEmpty(faceShape, RIGID_SUPPORT_SHAPE, BooleanOp.ONLY_SECOND)` 为假）。
两个要点，都是从源码读出来的：

1. 它看的是**边缘 2px 环**，不是中心区域；
2. 它看的是**上表面**（`getFaceShape(UP)`），而 `SliceShape` 只保留形状最顶层那一层格子、
   并把该轴坐标拉成 `[0,1]`——所以"支撑"与**顶面高度无关**，只与最顶层的 x/z 足迹有关。

**R-2 为什么不对**：R-2 把足迹定义成**渲染几何**（杆+红条，x 7..9、z 2..14）的"每一列都有表面"。
这与 `RIGID` 判的**不是同一个区域**（中心条带 vs 边缘环）、也不是同一个高度口径
（"每列都要有" vs "最顶层有环"）。所以 R-2 既会放行原版放不上的窄宿主，也会拒绝原版能放的形状。
增加面积阈值（R-3 的字面做法）也修不了区域错位。

**裁定：选 R-3，但"阈值"直接用原版判据本身**，不引入新数字：

> `hasCavity`（中继器/比较器）改为两条同时成立：
> 1. `host.isFaceSturdy(level, pos, Direction.UP, SupportType.RIGID)`；
> 2. 保留"足迹/整格内存在 `top < 1` 的列"（这是本 mod 特有的"元件要在格内"约束，
>    原版靠"放到上一格"绕开，我们没有上一格）。

第 1 条是状态级、带 `pos` 的原版方法（`BlockBehaviour.BlockStateBase` →
`Cache.faceSturdy[UP][RIGID]`，六版本都有四参重载），直接调用即可：**不需要重写形状采样，
也不需要新阈值**，而且自动覆盖"新增宿主"。

⚠️ **不要把第 1 条换成"退回 `getShape` 再自己采样"**（即 §8.3 建议 1 的方向）：空碰撞/窄形状的
视觉形状不是支撑面，原版对它们就是 `false`。`RedstoneBlocks.hostOutline` 里那次
"碰撞为空 → 退回视觉形状"是给 **I/O 露出**用的（需求是"宿主挡住了哪一面"），不能复用为支撑判定。

**这条判据的后果（按形状推导）**：

- **仍可装**：下半砖、地毯、耕地/土径（15/16）、营火、附魔台、灵魂沙（`getBlockSupportShape`
  覆写为整块）、以及任何"上表面足迹铺满外沿"的宿主。
- **退出**：§8.1 那 80 个空碰撞宿主（告示牌/珊瑚/铁轨/传送门/多面体/垂滴叶茎）、
  §8.2 的细柱（铁链、朝上避雷针、蜡烛、单颗海泡菜、钟乳石尖）、12×12 的小垂滴叶与海泡菜簇。
- **楼梯**：下半楼梯的上表面只有 16×8 / 8×8，按 `RIGID` 的边缘环推导会被拒。
  这与本 mod 0.2.4 起的"逐列抬升 + 楼梯上两根杆有落差"既有特性**直接冲突**
  （README 已知限制 10、`FenceRedstoneRenderTest` 的 `element_stair` 场景、自检里的
  "下半楼梯南/北脸"断言都依赖能把元件装到楼梯上）。

> ⚠️ **执行前必须先做一次实测核对**：上表是从 `SupportType.RIGID` 的源码语义推出的，没有在
> 六版本上逐类跑过。请先写一条自检/临时 gametest，对
> `{下半砖, 下半楼梯, 地毯, 耕地, 营火, 附魔台, 灵魂沙, 下半垂滴叶, 铁链, 蜡烛, 海泡菜, 告示牌}`
> 各放一个真实中继器，记录 `canSurvive` 结果，再据此确认"楼梯确实被拒"以及"下半砖仍然通过"。
> 这一步能一次排除"我对 `SliceShape`/`RIGID` 的理解有偏差"的风险——若实测与推导不符，
> 以实测算据为准，R-3 的字面形式（`isFaceSturdy(UP, RIGID)`）本身不变，只是取消清单要改。

**楼梯这一项的处置**（本条裁定唯一的实质代价，需要单独确认）：

- **接受"楼梯不可装"**：放置门控拒绝，渲染那份逐列抬升逻辑即成死代码（可留可删），
  三项测试同步改成"楼梯被拒"的反向断言。
- **不接受**：唯一自洽的替代是 R-4 按家族白名单（楼梯放行），并在 README 明确写
  "楼梯上的元件在原版不存在"——不能声称楼梯 parity。

**侦测器必须另立规则**（§12.7 自己提出的子问题，答案是"要"）：原版 `ObserverBlock`
**没有覆写 `canSurvive`，也没有覆写 `getShape`/`getCollisionShape`**，它是整格方块，
六面都能贴、不需要下方支撑。若套用 `RIGID`，会得出"侦测器只能装在下半砖上"这种与它几何
完全无关的结论。判据应为**所选面露出即可**（`RedstoneBlocks.exposed(host, 面)`），
与"贴在宿主某一面、只渲染那一面"的表示法一致。

> 这会改写 README 已知限制 2：从"元件只能放在有顶面空腔的宿主"
> 改为"中继器/比较器需要宿主上表面可支撑（`RIGID`）且有格内空间，侦测器只需要目标面露出"。
> README、AGENTS §3.3.3 的 H 行与自检 `checkElementPlacement` 要同步改。

**已存在条目：L-3**。按本口径，历史条目（`PhasePersistence.load` 用 `putRaw` 直接灌入）
停留在原版不可能出现的状态（元件贴底、嵌在告示牌里），只有 L-3 让它回到原版状态。
L-3 是"清除**并掉落**"，不吞玩家资产，所以"一致"与"不损失玩家物品"不冲突——
这就是它比 L-2 更优的原因（L-2 会保留错位状态）。
**残余风险**：掉落发生在区块加载时（可能没有玩家在场、只加载了部分区块），
如果实测发现物品在加载时散落得不可接受，退 L-2 并把差异写进文档。

### 13.9 §12.8 根因 H2：R-3 大幅收窄，但没有完全消失

R-3 用 `RIGID` 判支撑，绝大多数能装元件的宿主（下半砖、地毯、耕地、营火、附魔台、
大垂滴叶）在足迹内都不缺表面，`columnTop` 不会遇到 `-∞`，H2 在这些宿主上消失。

**残留面**：`RIGID` 只保证最顶层一圈外环，所以"顶面到 1.0 且中间空心"的环形宿主仍可通过
`RIGID` 而在足迹内留下无表面的列（这类宿主在 413 个相容宿主里是否真实存在需要跑一遍核对，
本审计没有穷举）。若存在，答案是 **H2-2**：原版元件是刚性的一块，
H2-1（拆成两截）和 H2-3（只剩中间一小段）都不像元件，H2-2 的悬空是最接近的连续形态。

若最终核对确认没有这类宿主，则 §12.8 在本 mod 的宿主集合上确实不需要，
仅作为 R-4 保留细柱宿主时的规则保留。

**独立于放置判定**：`columnTop` 把 `-∞` 当 0 这个**函数级**问题仍应修（返回哨兵、
无表面列不参与抬升），因为它是对"没有表面"的错误编码，与哪条放置规则无关；
只是 R-3 之后它会落在"不该发生"的分支上。

### 13.10 §12.9 根因 K：选 K-2（与 §12 的倾向 K-1 相反）

原版比较器**没有**"输入面被盖住就不读"的概念：`ComparatorBlock.getInputSignal` 先
`super.getInputSignal`（读 `facing` 邻格），再在 `state.hasAnalogOutputSignal()` 时无条件
`getAnalogOutputSignal`。所以"输入面被完整盖住 → 模拟量也不读"是**比原版更严**，
不是"更一致"。K-1 修的是**内部**一致性（README 声明的门控被贯彻），代价是离原版更远。

所以：**按"与单独放置一致"取 K-2**，并把"模拟量不受露出门控"写进 README；
若项目更看重"门控必须一处生效"的内部自洽，取 K-1——但要知道那是主动偏离原版。

**实际影响很小**：可放元件的宿主（按 §13.8 的 `RIGID` 判据）必须有格内空间，
其侧/顶面基本不会 `isFaceFull`，`exposed == false` 几乎只出现在底面；
这也是这条容易被忽略的原因。

### 13.11 §12.10 根因 L：选 CL-1

原版 `RepeaterBlock.updateShape` 的写回被 `!levelReader.isClientSide()` 挡住，客户端
`LOCKED` 只来自服务器下发的方块更新。CL-2 会让客户端算出服务器没有的值并滞留，
是**新增**一种原版不存在的错误来源。锁存没有视觉，本地预测没有收益。取 CL-1。

### 13.12 §12.11 根因 I：碰撞 I-2、粒子 PT-2

**碰撞选 I-2**，理由分两种元件：

- 中继器/比较器：原版 `getCollisionShape` 未覆写 → 落到 `getShape` → **16×2 板**。
  I-2 在宿主逐列顶面上加同样 2px，与原版一致（也顺带让"元件不是空气"这件事成立）。
- 侦测器：原版是**整格**碰撞（未覆写 `getShape`/`getCollisionShape`）。I-3 才是严格 parity，
  但它会把半砖上的侦测器变成整格障碍，与"元件是贴在宿主表面的装饰"这一表示法前提直接冲突
  （AGENTS §3.3 的原话）。所以侦测器保持 I-1，并把"侦测器不提供碰撞"写进 README——
  **这是本裁定里唯一"明知原版如此却不照做"的一处**（其余偏离都记在 §13.15 里，性质不同）。

  ⚠️ I-2 与 §13.6 的 E-2 有**耦合**：原版刷怪判定是"碰撞满方块 → 否；`isSignalSource()` → 否"，
  中继器命中第二条，与它的 16×2 碰撞无关。所以这两项要么一起做（碰撞 + 元件格禁刷怪），
  要么都不做；只加 I-2 会让同一格的刷怪结论变成"既不是原版碰撞、也不是原版信号源"。

**粒子选 PT-2**：`RepeaterBlock.animateTick` 是三种元件里唯一的粒子（点亮时发
`DustParticleOptions.REDSTONE`）；比较器/侦测器都没有。补它才是 parity，不补则少一条。

### 13.13 §12.12 根因 J：选 J-1

三条独立的原版依据都指向"要掉"：

1. 掉落表 `data/minecraft/loot_table/blocks/{repeater,comparator,observer}.json` 都带
   `minecraft:survives_explosion`，爆炸破坏**会**掉落；
2. 原版 `DiodeBlock.neighborChanged` 在 `!canSurvive` 时
   `dropResources(...) + removeBlock(..., false)` ——**支撑被破坏时元件本身也掉落**；
3. 活塞路径我们已经在掉（`PistonPhaseOps`），J-2 的"活塞掉、爆炸不掉"本身不自洽。

另外，原版**元件本身**被破坏的掉落在 `destroyBlock` 里由
`player.hasCorrectToolForDrops(blockState)` 把关（中继器/比较器 `instabreak` 恒真，
侦测器需要镐）——这与 §13.7 的硬度 (b) 是同一份伪状态判定，实现时共用。

所以 J-1 不只是"更像原版"，它还修掉一条现在被 README 已知限制 9 记录下来的差异
（"破坏宿主时元件随宿主一起消失、不额外掉落"）。**该条 README/AGENTS 记录要随之改写**。

J-3（区分来源）被否：原版不区分来源，且"坏方块被爆炸/支撑移除/玩家挖"三种都掉，
加来源判定只会再漏一种。掉落仍需服从 G-掉落 (a) 的创造模式守卫与侦测器的正确工具条件。

⚠️ **与 G 的边界**：J-1 补的是"非玩家破坏"这一侧（爆炸、支撑消失、活塞之外的一切移除路径），
G-掉落 (a) 补的是"玩家破坏时创造不掉"这一侧。两条都是"掉落条件"，但走的是不同入口
（`mine` vs. 移除路径），要分别实现、分别断言，不能靠同一条守卫覆盖。

### 13.14 §12.13 修复批次与开关：口径不决定

分批是发布工程，与"元件行为是否一致"无关；同一条差异分几版发，行为结论不变。
**口径唯一能约束的是默认值**：任何开关的默认值都必须是"与单独放置一致"那一档，
否则默认发行版仍然是本审计列出的差异。

若只是要一个不与口径冲突的排期，按"是否算元件坏了"排序：
A（A1 最容易被当成 bug）、B（脉冲被吞）、C（吸收后输出错）、D（同 tick 顺序）属于**功能不正确**；
E/G/H/I/J/K 属于**表现、手感与可放边界**。D 与 A 同属信号时序，放同一批比 §12.13 的 S-1 更省事。

### 13.15 这条口径选不出来的项

以下并非"两种做法都自洽"，而是三种不同情形：口径**给不出唯一答案**、口径给出的答案与本项目的
表示法前提**冲突**、或该事项**根本不属于行为一致性**。

| 项 | 情况 |
| --- | --- |
| §12.2 B-1 / B-2 | 两者都满足"元件不共用 tick 槽位"，行为等价，口径区分不了；按工程口径取 B-1 |
| §12.11 侦测器碰撞 | 口径给 I-3（整格），但与"元件是宿主表面装饰"的表示法前提冲突（半砖上的侦测器会变成整格障碍）。取 I-1 并记录，**这是有意不按口径执行**，README 必须写明 |
| §12.5 F 的第三方 `isSignalSource()` | 状态级判定无坐标，不引入按坐标的信号源查询接口就不可能一致；属结构性不可达 |
| §13.8 `RIGID` 判据会拒掉楼梯 | 口径**能**给出答案（拒绝），但代价是删掉一项既有已验证特性（README 已知限制 10 / `element_stair` gametest / 自检三处依赖）。因此采用 RIGID 判据、同时把楼梯这一项单列出来待确认：接受删特性，或退 R-4 白名单并在 README 标为差异 |
| §12.13 批次与开关 | 发布工程，与行为一致性无关；口径只能约束"开关默认值必须是 parity 那一档" |
| §13.3 旧存档迁移 | B-1 引入的"旧宿主型 tick 失效"没有唯一正确答案（迁移扫描 vs 接受一次修复） |
| §13.8 L-3 的掉落时机 | "回到原版状态"要求清除；但"何时掉、掉给谁"在区块加载时没有原版参照 |

---

## 14. 0.2.7 落地记录（用户裁定后的实际实现）

> 本节记录"§13 的裁定"与"最终实现"的对应关系，以及**用户逐项确认后与原审计不同的地方**。
> 未在此列出的项一律按 §13 裁定实现。

### 14.1 用户确认的五处口径调整

| 项 | §13 裁定 | 0.2.7 实际做法与理由 |
| --- | --- | --- |
| 根因 B 的 tick key | B-1：新增**专用哨兵方块** | 用户要求"**不新增新型计划刻**"，改为用**原版元件方块**（REPEATER/COMPARATOR/OBSERVER）当 tick key，`ServerLevel.tickBlock` HEAD 识别条目后执行元件 tick。同样与宿主分槽、不新增注册类型，且旧存档里真实元件方块排下的刻能继续执行（吸收时"填充形态直接照常继承"靠这一点） |
| 根因 C 的初始态 | C-2：写 `power=0/powered=false` 再按 `shouldTurnOn` 排 1 tick | 用户口径是"**填充形态直接照常继承**"：原样继承全部状态，比较器额外把方块实体的 `OutputSignal` 读进 `power`；不补初始结算、不额外排刻 |
| 根因 E 的范围 | E-3（只放宽比较器任意面） | 用户要求**连红石相的"向上爬"一起修**（§13.0 记录的真实缺口：原版 `shouldConnectTo(state above)` 只认红石线）。元件仍**不**爬（原版对元件恒 false） |
| 根因 G 的瞄准几何 | 16×2 薄壳（逐列顶面 2px） | 用户口径"**瞄准宿主任意朝上的面**"：命中宿主形状且 `hit.getDirection() == UP` 即算元件；侦测器整格且始终优先。不设 2px 深度限制 |
| 根因 H | R-3（`RIGID`）+ 侦测器另立规则 + L-3 | 用户口径"**H 不做拒绝和阈值**，允许同一部件错位，宿主相应位置轮廓为空处照常落在 0"：`hasCavity` 与 `columnTop` **未改**；只把"允许错位"写进文档 |
| 根因 J | J-1 | 按用户口径"**都掉**"，包括"同格被另一个方块直接替换"这条原审计认为原版不掉的路径 |
| 根因 I | I-2 碰撞 + PT-2 粒子 | 用户口径"**不加碰撞**（碰撞不算元件作为元件的行为）"：只补点亮的中继器粒子 |
| 根因 K | K-2（模拟量不受门控） | 用户口径"**门控**（宿主面控制输入输出不在与独立行为同步的范围内）"：`exposed` 提到 `comparatorInput` 最外层 |
| 根因 L | CL-1 | 与用户口径一致（客户端不写元件状态） |

### 14.2 通知通道的最终形态（根因 A）

- **统一出口**：`PhaseExtras.set(level, pos, extra, Notify)` → `PhaseNotifications.entryChanged`。
  `Notify` 三个取值：`WORLD`（正常写表＝原版一次 `setBlock`）、`INSIDE_BLOCK_WRITE`
  （那次 `setBlock` 自己会发基础通道，只补元件前方通知 / 红石相扇出）、`NONE`（静默）。
- **逐事件对齐**（次数与顺序）：元件装配（`onPlace` 前方 → `updateNeighborsAt(pos, 旧方块)` → 形状）、
  元件拆除（前方 → `updateNeighborsAt(pos, 元件方块)` → 形状）、中继器 POWERED/LOCKED
  （前方 → 形状）、中继器 DELAY（前方 → `updateNeighborsAt` → 形状）、比较器 POWERED/MODE
  （前方 → 形状 → 前方）、比较器 power（前方）、侦测器 POWERED（形状 → 前方）、
  红石相放置（邻居 → 形状 → 上下邻居 → 邻线拐角 → `updatePowerStrength`）、
  红石相功率（形状 → 本格与 6 邻居）、红石相移除（`updateNeighborsAt` → 形状 → 6 邻居 → 邻线拐角）。
- **不做任何去重**：同值写入在 `PhaseExtras.set` 就被挡掉（与原版 `setBlockState` 对同一状态返回 null 同口径）。
- **反向桥接**：`Level.updateNeighbourForOutputSignal` 的 TAIL（`LevelUpdateNeighbourForOutputSignalMixin`）
  按原版同一套邻居扫描（四水平 + 隔一格导体再往里一格）找**有比较器条目**的格子，执行与
  `ComparatorBlock.neighborChanged` 相同的 2 tick 重排（含 `willTickThisTick` 守卫与优先级）。
- **模拟量通知（第③条）不需要补**：`Level.setBlock` 只在 `blockState.hasAnalogOutputSignal()` 时发；
  元件/红石相都不报告模拟量输出。反方向由上面的桥接负责。

### 14.3 仍然保留的差异（与 README/AGENTS 一致）

1. **元件宿主的 `isSignalSource()` 仍为 false**：两个原版调用点按坐标补齐
   （刷怪、铁轨），第三方 mod 直接调仍不可达。
2. **元件不提供碰撞**（中继器/比较器/侦测器都不加）——表示法取舍。
3. **放置边界未改**：80 种"碰撞为空"宿主仍可装配；细柱宿主上杆/红条仍可能被拆成两截。
4. **元件仍只能放在有顶面空腔的宿主上**；中继器 `locked` 仍只做逻辑锁定（无锁形）。
