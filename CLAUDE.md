# Claude Code 协作规范

## 强制规则（所有实例必须遵守）

1. **禁止擅自使用 Git 功能**：未经用户明确许可，不得执行任何 git 命令（commit、push、pull、checkout、reset 等），这可能导致项目损坏。
2. **禁止未经允许进行危险操作**：包括但不限于删除文件、修改构建配置、变更项目结构等具有破坏性的操作，必须先征得用户同意。
3. **禁止频繁写入 CLAUDE.md**：仅在项目发生重大结构性变更时才更新。
4. **允许有条件的自动 Gradle 构建验证**：完成代码编写后可自动运行 `./gradlew build`，无需每次询问。
5. **严禁推送任何看起来不适合公开的 Markdown 文档**：凡是带有阶段性规划、内部协作说明、私人备忘、测试草稿、临时记录、结档草案、版本想法等性质的 `*.md` 文件，一律默认视为**不可公开内容**，不得加入 Git、不得提交、不得推送；除非用户明确指定该文件可以公开入库。

---

## 项目概述

**GensouMarket**（幻想集市）是一个 Minecraft 群组服插件系统，由两个独立子项目组成：

| 子项目 | 说明 | 版本 |
|--------|------|------|
| **GensouMarket-plugin** | Paper 子服端插件（市场/拍卖/商店/回收/交易） | v1.1 |
| **GensouMarket-velocity** | Velocity 代理端插件（跨服消息路由） | 1.0.0 |

- **包名**: `net.scarletphantasy.gensouMarket`（plugin）/ `net.scarletphantasy.gensouMarket.velocity`（velocity）
- **Java 版本**: 21
- **构建工具**: Gradle（两个子项目各自独立，不共用）

## 仓库结构

```
GensouMarket/
├── CLAUDE.md                      # 协作规范（固定保留在根目录）
├── doc/                           # 文档总目录
│   ├── 文档索引.md                 # 文档索引
│   ├── project/
│   │   └── 项目说明.md             # 项目说明
│   ├── archive/
│   │   ├── 1.1版本开发想法.md      # 1.1 原始想法归档
│   │   └── 跨服代理插件架构规划.md  # 阶段 0-5 跨服实施计划
│   ├── guides/
│   │   └── 我的世界插件开发踩坑总结.md # 通用踩坑经验（供其他项目参考）
│   ├── tasks/
│   │   └── v1.1/                  # 1.1 任务书拆分目录
│   │       ├── 任务书目录.md
│   │       └── 修复任务-2026-05-22-跨服邮件提醒缺失.md
│   └── tests/
│       ├── MC插件通用测试模板.yml    # 通用人工测试模板
│       └── v1.1/                  # 1.1 人工测试目录
│           ├── v1.1-当前进度人工测试文档.md
│           ├── v1.1-测试结果-2026-05-21.md
│           ├── 复测说明-2026-05-22-跨服邮件提醒修复.md
│           └── v1.1-最终结档记录-2026-05-22.md
├── settings.gradle                # 顶层组合构建（include 注册子项目）
├── GensouMarket-plugin/           # Paper 子服端插件
│   ├── build.gradle               # Paper 1.21.4 + Vault + HikariCP (shadow)
│   ├── gradlew
│   └── src/main/java/net/scarletphantasy/gensouMarket/
│       ├── GensouMarket.java      # 主类（初始化、异步调度、Bridge集成、关闭）
│       ├── config/ConfigManager.java
│       ├── economy/VaultHook.java
│       ├── model/                 # MarketListing, Auction, ShopItem, RecycleItem, MailEntry
│       ├── storage/               # StorageProvider, MySQLStorage(HikariCP), SQLiteStorage, YamlStorage
│       ├── market/                # MarketManager, AuctionManager（全部异步DB + 跨服事件发布）
│       ├── shop/                  # ShopManager, RecycleManager, PriceEngine, MarketFluctuation
│       ├── trade/TradeManager.java
│       ├── command/CommandManager.java
│       ├── gui/                   # GuiHolder, GuiListener, MarketGui, AuctionGui, ShopGui, RecycleGui, TradeGui
│       ├── bridge/                # 跨服桥接层（cluster.enabled=true 时启用）
│       │   ├── ProxyBridge.java           # Bukkit Plugin Messaging Channel 收发
│       │   ├── ClusterEventPublisher.java # 语义化事件发布
│       │   ├── RemoteActionHandler.java   # 处理来自 Velocity 的远程动作
│       │   ├── ViewSessionRegistry.java   # 跟踪玩家正在查看的 GUI
│       │   └── protocol/                  # 协议层（与 Velocity 端镜像）
│       │       ├── PacketType.java        # 消息类型枚举 (byte ID)
│       │       ├── PacketCodec.java       # DataStream 编解码
│       │       ├── IncomingPacket.java     # 接收消息 record
│       │       └── OutgoingPacket.java    # 发送消息构建器
│       ├── listener/              # PlayerListener, TradeInteractListener
│       ├── mail/                  # MailNotificationService（跨服邮箱提醒兜底）
│       └── util/                  # MessageUtil, ItemSerializer, ConfigMigrator, PerlinNoise, ItemNameUtil
└── GensouMarket-velocity/         # Velocity 代理端插件
    ├── build.gradle               # Velocity API 3.4.0
    ├── gradlew
    └── src/main/java/net/scarletphantasy/gensouMarket/velocity/
        ├── GensouMarketVelocity.java      # 主类（配置/Channel/服务/监听器）
        ├── config/VelocityConfig.java     # properties 配置
        ├── protocol/                      # 协议层（与 Plugin 端镜像）
        │   ├── PacketType.java
        │   ├── PacketCodec.java
        │   ├── IncomingPacket.java
        │   └── OutgoingPacket.java
        ├── service/                       # 服务层
        │   ├── BackendRegistry.java       # 后端服注册/注销
        │   ├── PlayerRouteService.java    # 玩家路由（UUID→后端服）
        │   └── BroadcastService.java      # 广播/定向发送
        └── listener/                      # 监听器
            ├── PluginMessageListener.java # 核心消息分发
            └── PlayerConnectionListener.java
```

**文档约定**:
- 协作规范：根目录 `CLAUDE.md`
- 项目说明与外部介绍：`doc/project/`
- 历史规划与归档：`doc/archive/`
- 通用经验总结：`doc/guides/`
- 可分派任务书：`doc/tasks/`
- 人工测试与回归：`doc/tests/`

## 构建命令

```bash
# 根目录构建所有子项目
./gradlew build

# 单独构建 Plugin
./gradlew GensouMarket-plugin:build

# 单独构建 Velocity
./gradlew GensouMarket-velocity:build
```

**当前发布状态**:
- 正式版基线：`1.0.0`
- 当前插件端结档版本：`v1.1`（已于 2026-05-22 最终回归通过并正式结档）
- 当前 Velocity 端版本：`1.0.0`（本轮未改代码）
- 当前 v1.1 主线剩余任务：无；后续新功能应另起任务书，不继续追加到 v1.1 主线

**输出位置**:
- Plugin: `GensouMarket-plugin/build/libs/GensouMarket-plugin-v1.1.jar`
- Velocity: `GensouMarket-velocity/build/libs/GensouMarket-velocity-1.0.0.jar`

## 异步架构（Plugin 核心设计）

所有 DB 操作在异步线程执行，Bukkit API（经济、背包、消息）在主线程执行。

**三阶段模式**: 异步读DB → 主线程 Bukkit API → 异步写DB

| 模块 | 策略 |
|------|------|
| MarketManager/AuctionManager | 全部三阶段异步 |
| ShopManager/RecycleManager | 内存缓存，仅 DB 写入异步 |
| 定时任务 | `runTaskTimerAsynchronously` |
| GUI 加载 | 市场/拍卖列表异步加载 |
| 命令 | `search`/`my`/`auction list`/`collect` 异步化 |
| 面对面交易 | 纯内存，无需异步 |

**MySQL 连接池**: HikariCP（最大10连接，连接超时5s，keepalive 60s，限流日志30s去重）

## 跨服同步架构

**整体架构**:
```
Paper Server A (plugin)  ----\
Paper Server B (plugin)  ----- > Velocity (message router) ----> 目标 Paper Server
Paper Server C (plugin)  ----/
            \________________________ shared MySQL ________________________/
```

- **MySQL 是权威状态源**（所有业务数据持久化）
- **Paper 是业务执行端**（经济扣款、背包操作、GUI、Vault）
- **Velocity 是路由与广播层**（消息转发、玩家定位、不碰业务逻辑）
- **`cluster.enabled=false` 时 bridge 不初始化，单服模式零影响**

### Phase 0: 并发安全基础 ✅ (dev-2.0)
- 新增 `cluster.enabled`/`server-id`/`channel` 配置
- 启用跨服时强制 MySQL 存储
- StorageProvider 新增 5 个原子操作接口：
  - `markListingSoldIfActive()` - 原子购买抢占
  - `markListingExpiredIfActive()` - 原子过期
  - `updateAuctionBidIfMatch()` - 乐观锁出价
  - `markAuctionEndedIfActive()` - 原子结算抢占
  - `markAuctionCancelledIfActive()` - 原子取消
- MySQL/SQLite/YAML 三种存储全部实现原子操作
- MarketManager/AuctionManager 改用原子流程（先 DB 后经济操作）

### Phase 1: Velocity 最小骨架 ✅ (dev-1.0)
- 主类 `GensouMarketVelocity` — @Plugin 注册、配置加载、Channel 注册、服务组装
- 协议层 `protocol/` — PacketType(16种, byte ID)、PacketCodec(DataStream)、IncomingPacket/OutgoingPacket
- 服务层 `service/` — BackendRegistry(双向映射)、PlayerRouteService(UUID→后端服)、BroadcastService(广播/定向)
- 监听器 — PluginMessageListener(消息分发: HELLO/GOODBYE注册、广播转发、定向路由、应答回传)
- 配置 — properties 文件(channel/timeout/dedup TTL)

### Phase 2: Plugin 端 Bridge ✅ (dev-2.1)
- `ProxyBridge` — Bukkit Plugin Messaging Channel 注册/收发、SERVER_HELLO/GOODBYE
- `ClusterEventPublisher` — 语义化事件发布（市场/拍卖/邮箱/远程动作/GUI刷新共12个方法）
- `RemoteActionHandler` — 处理来自 Velocity 的远程动作（存款、通知、GUI刷新、事件响应）
- `ViewSessionRegistry` — 跟踪玩家正在查看的 GUI 类型和关联 ID
- `bridge/protocol/` — 镜像 Velocity 侧协议（PacketType/PacketCodec/IncomingPacket/OutgoingPacket）
- 主类集成 — `isClusterEnabled()` 守卫、首个玩家 HELLO、关闭时 GOODBYE
- GUI 打开方法统一注册 ViewSession，GuiListener 在关闭 GUI 时注销

### Phase 3: 拍卖链路接入 ✅ (dev-2.1)
- `createAuction` → 广播 `AUCTION_CREATED`（其他服刷新拍卖列表）
- `placeBid` → 广播 `AUCTION_BID_UPDATED`（其他服刷新GUI、退款、通知卖家/被超价者）
- `endAuctionAsync` → 广播 `AUCTION_ENDED`（跨服发钱/发物品，失败写邮箱）
- `cancelAuction` → 广播 `AUCTION_CANCELLED`（跨服退款）
- RemoteActionHandler 处理：刷新拍卖列表/详情、退还竞拍者、通知卖家

### Phase 4: 市场链路接入 ✅ (dev-2.1)
- `sellItem` → 广播 `MARKET_LISTING_CHANGED(ACTIVE)`
- `cancelListing` → 广播 `MARKET_LISTING_CHANGED(CANCELLED)`
- `buyListing` → 跨服模式尝试 `REMOTE_DEPOSIT` 即时到账 + 广播 `MARKET_LISTING_SOLD`
- `checkExpiredListingsAsync` → 广播 `MARKET_LISTING_CHANGED(EXPIRED)`
- RemoteActionHandler 处理：卖家即时到账、刷新市场 GUI

### Phase 5: 邮箱即时通知 ✅ (dev-2.1)
- 所有 `saveMail` 后统一发布 `MAIL_CREATED`
- RemoteActionHandler 收到后给在线玩家弹 "你有新的待领取物品/金币" 提示
- 覆盖场景：市场售出、过期、拍卖结束、拍卖取消、竞拍退款
- 2026-05-22 补充：Plugin 端新增 `MailNotificationService`，集群模式下由玩家所在服轮询共享邮箱，兜底源服无人在线导致 Paper Plugin Messaging 无法发出 `MAIL_CREATED` 的场景；收到 `MAIL_CREATED` 后会短时间抑制轮询重复提醒

### 消息协议格式
```
protocolVersion (short) | messageType (byte) | requestId (UTF)
timestamp (long) | sourceServerId (UTF)
payloadSize (short) | [key (UTF), value (UTF)] * N
```

### 消息流转规则
| 消息类型 | Velocity 行为 |
|---------|-------------|
| 广播类 (MARKET_*/AUCTION_*/MAIL_*/REFRESH_*) | 转发给所有其他已注册后端（排除源服）|
| 定向类 (PLAYER_NOTIFY/REMOTE_DEPOSIT/REMOTE_GIVE_ITEM) | 根据 targetPlayerUuid 路由到玩家所在后端 |
| 应答类 (REQUEST_ACK/REQUEST_FAIL) | 根据 targetServerId 回发给请求方 |
| SERVER_HELLO/GOODBYE | Velocity 本地处理（注册/注销后端）|

## Plugin 核心功能摘要

- **全球市场**: 上架/购买/搜索，界面二次确认，上架税+交易税
- **个人商店**（v1.1）: `/gmarket personal [玩家名]`，按卖家过滤市场视图的只读入口；配合牌子入口（写牌 `[个人商店]` 创建，右键打开）
- **拍卖行**: 创建/竞拍/取消，独立手续费（`auction.listing-fee-rate`），启动时取消遗留拍卖
- **服务器商店**: 双模式（v1.1）
  - 固定售价 + 无限库存（`fixed + unlimited`）：固定价格、无限库存
  - 固定售价 + 有限库存（`fixed + limited`）：固定价格、有限库存（持久化优先配置 `initial-stock`，支持 `/gmarket shop setstock`）
  - 回流出售模式（`recycled`）：动态售价 = 回收价 × `sell-multiplier`，库存来自 `RecycleItem.recycledStock`
- **回收站**: 动态定价（供需+市场波动），界面每秒刷新物品说明，二次确认；回收成功后同步注入 `recycledStock`（v1.1）
- **面对面交易**: 蹲下右键发起，54格左右分栏，纯内存态
- **邮箱系统**: 离线物品/金币暂存，异步领取；跨服模式下通过 `MAIL_CREATED` + `MailNotificationService` 轮询兜底保证异服在线提醒
- **统一模块开关**（v1.1）: `market` / `auction` / `mail` / `personal-shop` / `shop` / `recycle` / `trade` / `cluster` 八个独立开关；本地关闭入口不影响跨服被动同步
- **add 命令简化**: `shop/recycle add <价格>`（手持）或 `add <物品> <价格>`，ID 自动生成；`shop add` 创建 `fixed+unlimited`

## 代码质量状态

**IDEA 警告清理已完成**（除 storage 层重复代码段外）：
- 所有 GUI 类已迁移至 Adventure API（`Component`/`LegacyComponentSerializer`），不再使用已弃用的 `ChatColor`/`setDisplayName`/`setLore`
- `MessageUtil.color()` 已改用 `LegacyComponentSerializer` 替代已弃用的 `ChatColor.translateAlternateColorCodes`
- 所有 `printStackTrace` 已替换为 `Logger.log(Level.WARNING, ...)`
- 所有 `getString()` 可能为 null 的地方已加 null 检查
- model 类 `id`/`material` 字段已标记为 `final`
- 未使用的方法/导入/参数已清理
- `File.delete()`/`File.renameTo()` 返回值已检查
- **剩余**: storage 层（MySQL/SQLite/Yaml）各 CRUD 方法的 try-catch 结构性重复（跳过，强行消除反而降低可读性）

## 已知设计决策

- 拍卖启动时恢复定时任务（重启后继续计时）
- 拍卖到期由内存定时器驱动自动结算，兜底扫描每分钟补漏
- 物品匹配纯按 Material，同 Material 只能有一个价格条目
- shop/recycle add 内部 ID = `material.name().toLowerCase()`
- `auctions` 二级命令已并入 `auction list`，发起/取消拍卖仍统一走 `auction` 分支
- 面对面交易纯内存态，`ended` 标记防重复处理
- 涨跌百分比基于运行时 snapshot（不持久化）
- `isDynamicPricingEnabled`/`isMarketFluctuationEnabled` 返回 true 表示启用

## 测试与修复进度（更新于 2026-05-22）

### 2026-05-22 v1.1 最终回归与正式结档
- v1.1 主线任务 `task-01 ~ task-05`、2026-04-22 两轮代码审查修复、2026-05-22 跨服邮件提醒修复均已完成。
- 首轮人工测试（2026-05-21）结论：
  - 单服环境全部通过。
  - 跨服环境发现 `T-CLUSTER-02` / `T-CLUSTER-04` 邮箱数据落地正常但异服在线提醒缺失。
- 2026-05-22 修复：
  - 保留 `MAIL_CREATED` 作为有在线源玩家时的即时跨服提醒。
  - 新增 `MailNotificationService`：集群模式下由玩家所在服轮询共享邮箱，兜底源服无人在线导致 Plugin Messaging 无法发送的场景。
  - `/gmarket collect` 后刷新提醒状态，避免已领取邮件再次触发新邮件提醒。
- 最终复测结论：用户确认“回归测试完全正常”，`T-CLUSTER-02` / `T-CLUSTER-04` 及相关影响路径通过。
- 结档记录：
  - `doc/tests/v1.1/v1.1-最终结档记录-2026-05-22.md`
  - `doc/tests/v1.1/复测说明-2026-05-22-跨服邮件提醒修复.md`
  - `doc/tasks/v1.1/修复任务-2026-05-22-跨服邮件提醒缺失.md`

### 2026-04-04 风险扫描回归（首轮）
- 覆盖 `T-01 ~ T-13`。
- 首轮结论：`T-05` `T-06` `T-07` `T-09` `T-11` 失败，其余通过；`T-04` 因“金币强制直达”场景不可构造记为 `N/A`。
- 主要问题：
  - 市场跨服售出出现重复发放风险（直达+邮箱）
  - 卖家离线后商品可见性异常
  - 余额边界下购买失败触发误下架/过期返还
  - 拍卖被超价/取消时在线退款不即时且提醒缺失

### 2026-04-07 Bug 专项复测（按问题清单逐项）
- 专项清单：`B1 ~ B7`（仅覆盖上轮失败点与防重）。
- 复测结果：`B1` `B2` `B3` `B4` `B5` `B6` `B7` **全部通过**。
- 关键确认：
  - 跨服购买：扣款、通知、`collect` 均正常
  - 离线卖家：上架仍可见可买，上线后邮箱提示与领取正常
  - 余额边界：扣款失败不发物，商品不误下架
  - 拍卖退款：被超价与取消场景均为在线即时退款且有提示
  - 拍卖结束：赢家跨服即时发物，`collect` 不重复
  - 防重复：退款/收入/物品在 `collect` 路径均未复发

### 当前状态（供后续协作实例快速判断）
- v1.1 已于 2026-05-22 正式结档，当前主线剩余任务为无。
- `doc/tests/v1.1/v1.1-测试结果-2026-05-21.md` 记录首轮人工测试结果，其中跨服邮件提醒缺失为历史失败，已在 2026-05-22 修复并复测通过。
- `doc/tests/v1.1/v1.1-最终结档记录-2026-05-22.md` 是当前 v1.1 最终结论依据。
- 后续若再改动经济判断、跨服退款/发放、邮箱写入/提醒或 `/gmarket collect` 领取逻辑，必须优先重跑 `B1 ~ B7`，并补跑 `T-CLUSTER-02` / `T-CLUSTER-04` 与 2026-05-22 跨服邮件提醒复测清单。

## 版本历史

**dev-1.1-final** (2026-05-22) - v1.1 最终回归与结档
- 调整：插件端版本号从 `1.1-dev-1` 提升为正式结档版本 `v1.1`，打包产物为 `GensouMarket-plugin-v1.1.jar`。
- 修复：跨服邮件提醒缺失
  - 问题：`T-CLUSTER-02` / `T-CLUSTER-04` 中，邮件数据已写入共享 MySQL，`/gmarket collect` 可领取，但玩家在线于异服时缺少即时提醒。
  - 保留：`publishMailCreated()` / `MAIL_CREATED` 作为源服有在线玩家时的即时跨服提醒。
  - 新增：`mail/MailNotificationService`，集群模式下由玩家所在服每 5 秒轮询共享邮箱，发现新增未领取邮件 ID 时发送提醒；用于兜底源服无人在线时 Paper Plugin Messaging 无法发出的问题。
  - 防重：收到 `MAIL_CREATED` 后记录外部即时提醒，并刷新已知邮件 ID，抑制轮询重复刷屏。
  - `/gmarket collect` 成功/部分失败后刷新已知邮件状态，避免已领取邮件再次触发新邮件提醒。
- 文档：新增最终结档记录 `doc/tests/v1.1/v1.1-最终结档记录-2026-05-22.md`，跨服邮件修复任务归档至 `doc/tasks/v1.1/修复任务-2026-05-22-跨服邮件提醒缺失.md`，复测说明归档至 `doc/tests/v1.1/复测说明-2026-05-22-跨服邮件提醒修复.md`。
- 验证：`./gradlew GensouMarket-plugin:build` 通过；用户确认最终回归测试完全正常。
- 结论：v1.1 主线正式结档；`task-06` / `task-07` 仍为后备低优先级，不进入 v1.1 主线。

**dev-1.1-version** (2026-04-22) - 插件包版本标识调整
- 调整：`GensouMarket-plugin/build.gradle` 中插件端版本号从 `1.0.0` 改为 `1.1-dev-1`
- 结果：`plugin.yml` 继续通过 `${version}` 注入，插件打包产物变更为 `GensouMarket-plugin-1.1-dev-1.jar`
- 保持：`GensouMarket-velocity` 本轮无代码改动，版本仍为 `1.0.0`

**dev-1.1-fix-2** (2026-04-22) - task-02/05 补充修复
- 修复：`recycledStock` 在重载（`reload`）/重启后被重置
  - `RecycleManager.loadItems()` 合并 DB 数据时补 `configItem.setRecycledStock(dbItem.getRecycledStock())`，与 `totalRecycled` / `recycleMultiplier` / `lastUpdate` 并列恢复
- 修复：`/gmarket shop setprice` 把固定售价 + 有限库存（`fixed + limited`）商品误降级为固定售价 + 无限库存（`fixed + unlimited`）
  - 根因：原路径复用 `ConfigManager.saveShopItem()`，该方法会按 `/gmarket shop add` 模板整体重写 mode/stock-mode
  - 新增 `ConfigManager.updateShopBuyPrice(id, buyPrice)`：只改 `items.<id>.buy-price`，不触碰 mode/stock-mode/initial-stock/recycle-source/sell-multiplier
  - `ShopManager.setPrice()` 改走新方法；回流出售模式（`recycled`）商品仍拒绝
- 修复：被其他插件取消的 `SignChangeEvent` 仍落绑定数据
  - `PersonalShopSignListener.onSignChange` 在识别到创建请求后、调用 `register` 之前显式判断 `event.isCancelled()` 并直接返回，避免 personal-shop-signs.yml 出现脏数据
- 修复：挂式牌子支撑方块识别错误
  - `PersonalShopSignManager.computeSupportLocation()` 按四种 `BlockData` 类型分别处理：
    - `WallHangingSign`：`getFacing().getOppositeFace()`
    - `HangingSign`：`BlockFace.UP`
    - `WallSign`：`getFacing().getOppositeFace()`
    - `Sign`（立式）：`BlockFace.DOWN`
  - 其他 BlockData 返回 null，避免把非牌子块错误注册为支撑
  - 破坏/爆炸/活塞保护与解绑路径全部复用同一计算结果，原先挂式牌子真实支撑被漏保护的问题随之消失

**dev-1.1-features-2** (2026-04-22) - v1.1 task-02 + task-05 实装
- 新增：个人商店牌子（task-02）
  - 新服务 `PersonalShopSignManager`：独立 `personal-shop-signs.yml` 持久化；反向索引支撑方块用于保护
  - 新监听器 `PersonalShopSignListener`（EventPriority.HIGHEST）：
    - `SignChangeEvent`：第一行 `[个人商店]` / `[gmarketshop]` 触发创建；鉴权 `gensoumarket.personal.sign.create` 或 `gensoumarket.admin`；重写牌面为 `&6[个人商店]` / `<店主名>` / `&7右键打开` / (空)
    - `PlayerInteractEvent`：右键注册牌子抢先于普通牌子编辑，打开该店主的个人商店
    - `BlockBreakEvent`：仅店主或管理员可破坏牌子或支撑方块，破坏前先 `unregisterKey`
    - `BlockExplodeEvent` / `EntityExplodeEvent`：从 `blockList()` 中移除受保护方块
    - `BlockPistonExtendEvent` / `BlockPistonRetractEvent`：涉及受保护方块时取消事件
  - 支撑方块推断：墙上牌子取 `WallSign.getFacing().getOppositeFace()`，立式牌子取 `BlockFace.DOWN`
  - `personal-shop.enabled=false` 时：禁止新建，但旧牌子仍受保护，右键提示未启用
  - `plugin.yml` 新增 `gensoumarket.personal.sign.create` 权限
- 新增：服务器商店双模式（task-05）
  - `ShopItem` 扩展：`Mode{FIXED,RECYCLED}` / `StockMode{UNLIMITED,LIMITED}` / `availableStock` / `recycleSourceId` / `sellMultiplier`；`isFixedUnlimited/isFixedLimited/isRecycled` 便捷方法
  - `RecycleItem` 扩展：`recycledStock` 回流库存池字段
  - 配置层：`shop.yml` 支持 `mode/stock-mode/initial-stock/recycle-source/sell-multiplier`；旧条目兼容为固定售价 + 无限库存（`fixed + unlimited`）；`ConfigManager.getShopInitialStock(id,fallback)` 仅在 DB 无记录时用作初值
  - 存储层：`SQLiteStorage.migrateSchema()` 用 `PRAGMA table_info` 幂等补列；`MySQLStorage.migrateSchema()` 用 `information_schema` 幂等补列；YAML 扩字段随存随取
  - 持久化优先：`ShopManager.loadItems()` 合并 config+DB 时，固定售价 + 有限库存（`fixed + limited`）若 DB 有 `available_stock>=0` 则以 DB 为准，重载（`reload`）不覆盖
  - 业务流：`ShopManager.buyFromShop` 先判模式→判库存→判余额→扣款→发货→扣库存；固定售价 + 有限库存（`fixed + limited`）扣 `availableStock`，回流出售模式（`recycled`）扣关联 `RecycleItem.recycledStock`；回流出售模式（`recycled`）当前售价 = `RecycleItem.currentRecyclePrice(fluctuation) × sellMultiplier`，下限 0.01，保留两位小数
  - 回收链路：`RecycleManager.recycleItem` 成功后 `addRecycledStock(amount)`，与 `totalRecycled` 并列持久化
  - 界面：`ShopGui` 按模式渲染物品说明（无限/有限/回流库存 + 来源 + 倍率），缺货显示 `&c&l缺货中` 不渲染购买按钮
  - 命令：
    - `/gmarket shop add` 仅创建固定售价 + 无限库存（`fixed + unlimited`）（`config.saveShopItem` 同时写 `mode/stock-mode`）
    - `/gmarket shop setprice` 对回流出售模式（`recycled`）拒绝并提示"价格由回收价和倍率决定"
    - 新增 `/gmarket shop setstock <id> <amount>`：仅固定售价 + 有限库存（`fixed + limited`）可用；拒绝固定售价 + 无限库存（`fixed + unlimited`）/ 回流出售模式（`recycled`）；`amount<0` 视为非法
  - `showHelp` / Tab 补全同步新子命令

**dev-1.1-fix** (2026-04-22) - v1.1 代码审查修复
- 修复：`mail.enabled=false` 可能把玩家资产锁死
  - `CommandManager.requireMail()` 整体删除，`/gmarket collect` 不再受模块开关阻止；`showHelp` / Tab 补全仍按 `mail.enabled` 控制曝光
  - `PlayerListener.onPlayerJoin` 与 `RemoteActionHandler.handleMailCreated` 文案统一回 "使用 /gmarket collect 领取"，不再提示"请前往启用邮箱的子服领取"
  - `config.yml` 中 `mail.enabled` 注释改为 "控制帮助/入口曝光，不影响底层邮件写入与领取能力"
- 修复：`auction.enabled` 热重载不完整
  - `AuctionManager` 新增 `cancelAllScheduledTasks()` 与 `applyModuleState()`；`restoreActiveAuctions()` 加幂等保护（同一拍卖已存在定时器则跳过）
  - `GensouMarket.onEnable` 启动路径改走 `applyModuleState()`，与重载路径共用同一入口
  - `CommandManager.handleReload` 在 `configManager.reload()` 之后调用 `applyModuleState()`，实现 true↔false 热切换且不重复注册
- 修复：个人商店视图未接入跨服刷新
  - `ViewSessionRegistry.ViewSession` 扩展 `sellerUuid` 字段，新增 `registerPersonalShop()` 与 `getPersonalShopViewers(sellerUuid)`
  - `MarketGui.openPersonalShop` 注册时携带卖家 UUID
  - `RemoteActionHandler.handleMarketListingSold` / `handleMarketListingChanged` 在全局市场刷新之上，解析 payload 的 `sellerUuid` 并额外刷新对应卖家的 `PERSONAL_SHOP` 页；内部捕获异常，不影响其他刷新链路
  - 刷新粒度：统一刷到第一页；卖家已无活跃上架时关闭视图并提示

**dev-1.1** (2026-04-22) - v1.1 主线：统一模块开关 + 个人商店视图
- 新增：统一模块开关体系（task-03）
  - `config.yml` 补齐 `market.enabled` / `auction.enabled` / `mail.enabled` / `personal-shop.enabled`
  - `ConfigManager` 暴露对应 getter，`shop.enabled` / `recycle.enabled` / `trade.enabled` 保留
  - `CommandManager` 为每个子命令加装 `requireXxx(sender)` 守卫，被关闭的模块统一提示"未启用"（注：`mail` 守卫已在 dev-1.1-fix 中删除）
  - `MarketGui.openMainMenu` 按开关动态渲染入口，被关闭的模块不再出现主菜单格子
  - `GuiListener.handleMainMenu` 双重校验开关，防止客户端伪造点击
  - `GensouMarket` 主类定时任务按模块开关分路：`checkExpiredListingsAsync` / `checkEndedAuctionsAsync` / `saveAllData` 只在对应模块启用时执行
  - 拍卖定时器生命周期由 `AuctionManager.applyModuleState()` 统一管理（dev-1.1-fix 后）
  - `showHelp` / Tab 补全按开关动态过滤可用命令
- 新增：跨服模块开关兼容（task-04）
  - `RemoteActionHandler.handle` 分流：`PLAYER_NOTIFY` / `REMOTE_DEPOSIT` / `REMOTE_GIVE_ITEM` / `MAIL_CREATED` 无条件处理（保证跨服收入/补发/通知落地）；界面刷新类按对应模块开关门控
- 新增：个人商店一期视图（task-01）
  - `/gmarket personal [玩家名]` 命令，自动解析在线玩家优先、活跃上架卖家次之、最后 OfflinePlayer 兜底
  - `MarketGui.openPersonalShop` 复用市场浏览 lore 逻辑，按 `sellerUuid` 过滤 ACTIVE 上架
  - `GuiHolder.GuiType.PERSONAL_SHOP` 新类型，`GuiListener.handlePersonalShop` 处理购买/下架/翻页，购买走 `MarketManager.buyListing` 既有原子链路
  - `MarketGui.openMainMenu` 新增 slot 4 "个人商店"入口
  - `plugin.yml` 新增 `gensoumarket.personal` 权限
- 保持：`cluster.enabled=false` 时 bridge 不初始化，单服模式零影响
- 保持：`dev-2.2` 所有跨服修复、原子操作、邮箱路径与 `B1~B7` 验收行为不变

**dev-2.2 / dev-1.1-preview** (2026-04-04) - 跨服问题修复与收尾
- 修复：市场上架异步流程在玩家切服时可能中断，改为先锁定物品/税金再异步落库，失败时退回本服或邮箱
- 修复：市场过期除 `MAIL_CREATED` 外额外发送定向提醒，异服卖家可收到明确的 `collect` 提示
- 修复：拍卖异服卖家“新出价”提醒缺失，改为源服直接定向通知目标玩家
- 修复：拍卖结束时异服卖家收入提示缺失，改为优先 `REMOTE_DEPOSIT`，失败再回退邮箱
- 修复：`AUCTION_BID_UPDATED` 补充 `oldPrice` 载荷，恢复异服被超价竞拍者退款链路
- 修复：`MARKET_LISTING_SOLD` 远端不再重复给卖家入账，避免跨服售出时潜在双重到账
- 修复：关服阶段 `ProxyBridge.sendGoodbye()` 增加启用态保护，避免 `Plugin must be enabled to send messages`
- 改进：PlayerListener 任意玩家加入时补发 `SERVER_HELLO`，避免代理端重启后注册状态丢失

**dev-2.1** (2026-03-26) - Phase 1-5: 跨服同步完整实现
- 新增：Velocity 代理端插件 — 消息路由骨架（协议层/服务层/监听器）
- 新增：Plugin 端 Bridge 层 — ProxyBridge/ClusterEventPublisher/RemoteActionHandler/ViewSessionRegistry
- 新增：Plugin/Velocity 共用二进制协议（16种消息类型，DataStream 编解码）
- 新增：拍卖跨服链路 — 创建/出价/结束/取消全链路广播 + GUI 刷新
- 新增：市场跨服链路 — 上架/购买/下架/过期全链路广播 + 卖家即时到账
- 新增：邮箱即时通知 — 所有 saveMail 后跨服通知在线玩家
- 改进：GuiListener 集成 ViewSessionRegistry 跟踪打开的市场/拍卖 GUI
- 改进：PlayerListener 任意玩家加入时补发 SERVER_HELLO，避免代理端重启后注册状态丢失
- 修复（2026-04-04）：市场上架异步流程在玩家切服时可能中断，改为先锁定物品/税金再异步落库，失败时退回本服或邮箱
- 修复（2026-04-04）：市场过期除 MAIL_CREATED 外额外发送定向提醒，异服卖家可收到明确的 collect 提示
- 修复（2026-04-04）：拍卖异服卖家“新出价”提醒缺失，改为源服直接定向通知目标玩家
- 修复（2026-04-04）：拍卖结束时异服卖家收入提示缺失，改为优先 REMOTE_DEPOSIT，失败再回退邮箱
- 修复（2026-04-04）：`AUCTION_BID_UPDATED` 补充 `oldPrice` 载荷，恢复异服被超价竞拍者退款链路
- 修复（2026-04-04）：`MARKET_LISTING_SOLD` 远端不再重复给卖家入账，避免跨服售出时潜在双重到账
- 修复（2026-04-04）：关服阶段 `ProxyBridge.sendGoodbye()` 增加启用态保护，避免 `Plugin must be enabled to send messages`
- 保持：cluster.enabled=false 时 bridge 不初始化，单服模式零影响

**dev-2.0** (2026-03-26) - Phase 0: 跨服并发安全基础
- 新增：跨服集群配置（cluster.enabled/server-id/channel）
- 新增：启用跨服时强制要求 MySQL 存储校验
- 新增：StorageProvider 原子操作接口（4个方法）
- 改进：MySQL/SQLite/YAML 全部实现原子状态转换
- 改进：MarketManager.buyListing 改用原子抢占（避免双购）
- 改进：AuctionManager.placeBid 改用乐观锁（避免竞价覆盖）
- 改进：AuctionManager.endAuctionAsync 改用原子抢占（避免重复结算）
- 改进：AuctionManager.cancelAuction 改用原子取消
- 改进：MarketManager.checkExpiredListingsAsync 改用原子过期
- 修复：settings.gradle 从 includeBuild 改为 include（修复根目录构建）

**dev-1.4** (2026-03-26)
- 修复：动态定价和市场波动开关逻辑反转（移除错误的 `!` 取反）
- 修复：拍卖到期自动结算机制（改用内存定时器驱动，无需手动点击）
- 修复：`CommandManager` 命令提示文本编码异常，恢复中文提示
- 改进：拍卖结算优先直接发放（在线且背包有空位），否则发邮箱
- 改进：合并 `auctions` 二级命令为 `auction list`，精简拍卖命令结构
- 新增：`MessageUtil.hasInventorySpace()` 检查背包空位
- 新增：`AuctionManager.restoreActiveAuctions()` 启动时恢复定时任务
