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
| I | 元件**不提供碰撞**、没有光照/粒子 | 原版中继器/比较器是 16×2 碰撞；三者都有粒子 | P3（建议补进 README） | 固有 |
| J | 爆炸等"非玩家非活塞"破坏**不掉元件** | 原版中继器/比较器被爆炸破坏会掉落 | P3 | 既有取舍 |

已复核、**不算缺陷**的项见 §9。

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
| 铁链 / 铜锁链 | `6..10` × `6..10` 全高柱 | `z 2..6`、`z 10..14` |
| 朝上的避雷针（`RodBlock` 4×4 柱） | `6..10` × `6..10` | 同上 |
| 朝上的钟乳石尖 | `5..11` × `5..11`，顶 0.6875 | `z 2..5`、`z 11..14` |
| 蜡烛 / 海泡菜 | 中心 2×2 / 6×6，顶 0.375 / 0.25 | 绝大部分列 |
| 栅栏 / 墙 / 玻璃板 | 柱 `6..10`（顶 1.0）+ 横杆 `7..9`（顶约 0.56） | 恰好被覆盖（不触发） |

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
- 比较器相的 `power` 只通过 `getSignal` 暴露，**不会**经由 `hasAnalogOutputSignal`/
  `getAnalogOutputSignal` 被别人读到。原版比较器两者都有。所以用比较器相 → 比较器相的"模拟量直读"
  路径不存在（只能走 `getSignal` 15/功率），影响很小但属于差异。

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
