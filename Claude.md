# Claude Code 协作规范

## 强制规则（所有实例必须遵守）

1. **禁止擅自使用 Git 功能**：未经用户明确许可，不得执行任何 git 命令（commit、push、pull、checkout、reset 等），这可能导致项目损坏。
2. **禁止未经允许进行危险操作**：包括但不限于删除文件、修改构建配置、变更项目结构等具有破坏性的操作，必须先征得用户同意。
3. **禁止频繁写入 Claude.md**：仅在项目发生重大结构性变更时才更新。
4. **允许有条件的自动 Gradle 构建验证**：完成代码编写后可自动运行 `./gradlew build`，无需每次询问。

---

## 项目概述

**GensouMarket**（幻想集市）是一个 Minecraft 群组服插件系统，由两个独立子项目组成：

| 子项目 | 说明 | 版本 |
|--------|------|------|
| **GensouMarket-plugin** | Paper 子服端插件（市场/拍卖/商店/回收/交易） | 1.0.0 |
| **GensouMarket-velocity** | Velocity 代理端插件（跨服消息路由） | 1.0.0 |

- **包名**: `net.scarletphantasy.gensouMarket`（plugin）/ `net.scarletphantasy.gensouMarket.velocity`（velocity）
- **Java 版本**: 21
- **构建工具**: Gradle（两个子项目各自独立，不共用）

## 仓库结构

```
GensouMarket/
├── Claude.md                      # 项目文档
├── MC插件开发踩坑总结.md           # 通用踩坑经验（供其他项目参考）
├── Velocity插件架构规划.md         # Phase 0-5 跨服实施计划
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
- 正式版 `1.0.0`
- 人工终验已完成
- 项目已进入正式版结档状态

**输出位置**:
- Plugin: `GensouMarket-plugin/build/libs/GensouMarket-plugin-1.0.0.jar`
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

- **全球市场**: 上架/购买/搜索，GUI 二次确认，上架税+交易税
- **拍卖行**: 创建/竞拍/取消，独立手续费（`auction.listing-fee-rate`），启动时取消遗留拍卖
- **服务器商店**: 固定价格，GUI 二次确认，管理员命令管理
- **回收站**: 动态定价（供需+市场波动），GUI 每秒刷新 lore，二次确认
- **面对面交易**: 蹲下右键发起，54格左右分栏，纯内存态
- **邮箱系统**: 离线物品/金币暂存，异步领取
- **add 命令简化**: `shop/recycle add <价格>`（手持）或 `add <物品> <价格>`，ID 自动生成

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

## 测试与修复进度（更新于 2026-04-07）

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
- 当前进度以“Bug 专项复测全通过”为准。
- `测试结果-2026-04-04-风险修复回归.md` 记录的是首轮风险回归结果（含历史失败现象），可作为回归对照。
- 后续若再改动经济判断、跨服退款/发放或邮箱领取逻辑，必须优先重跑 `B1 ~ B7`。

## 版本历史

**dev-2.2 / dev-1.1** (2026-04-04) - 跨服问题修复与收尾
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
