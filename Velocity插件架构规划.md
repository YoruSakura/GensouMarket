# GensouMarket Velocity 插件架构规划

本文档最初是 `GensouMarket-velocity` 与 `GensouMarket-plugin` 跨服协作能力的架构输入。

截至 2026-04-08：

- Phase 0-5 已全部落地
- 跨服 GUI 注册一致性问题已修复并通过专项验证
- 阻塞级并发/状态悬空问题已修复并通过人工验证
- 当前版本状态为 `1.0.0`
- 项目已完成正式版终验并结档

以下内容保留为历史规划与设计归档，便于回看实施思路与取舍。

最初目标不是直接开写 Velocity 空壳，而是先明确：

1. 当前代码实际处于什么状态
2. 跨服同步真正缺的是什么
3. 哪些问题必须先在 Paper 子服端修正
4. Velocity 端应该承担什么职责，不应该承担什么职责
5. 实施顺序应该如何安排，避免返工

---

## 1. 当前项目状态

### 1.1 子项目状态

- `GensouMarket-plugin` 已实现市场、拍卖、商店、回收、邮箱、面对面交易，以及跨服桥接接入。
- `GensouMarket-velocity` 已实现代理侧注册、广播、定向路由、应答回传与后端服管理。
- 当前两个模块均可独立构建，产物版本为 `1.0.0-rc-2`。

关键文件：

- `GensouMarket-velocity/build.gradle`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/GensouMarket.java`

### 1.2 Plugin 当前生命周期

`plugin` 端在启动时完成以下动作：

- 加载配置
- 初始化 Vault
- 初始化存储
- 初始化 `MarketManager` / `AuctionManager` / `ShopManager` / `RecycleManager` / `TradeManager`
- 恢复活跃拍卖定时任务
- 注册命令与事件
- 每分钟异步检查：
  - 过期市场上架
  - 已结束拍卖

关键入口：

- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/GensouMarket.java`

### 1.3 Claude.md 已明确的目标

Claude.md 中已明确跨服同步的背景问题：

- 跨服购买成功但卖家金币需手动 `collect`
- 子服关停后拍卖状态不同步
- 并发竞拍时需要点击两次才刷新到最新价格

并且已经选定方向：

- 使用 `Velocity Plugin Messaging Channel`

---

## 2. 代码现状分析结论

### 2.1 跨服桥接实现状态

当前已落地以下跨服能力：

- Bukkit Plugin Messaging 注册
- 与 Velocity 的自定义频道通信
- `ClusterEventPublisher` 语义化事件发布
- `RemoteActionHandler` 远程动作处理
- `ViewSessionRegistry` GUI 视图跟踪
- Velocity 侧广播、定向路由与应答回传

当前结论：

- 跨服架构已从设计阶段进入可运行阶段
- 现阶段关注点已从“是否有桥接”转为“是否通过正式版终验与发布收口”

### 2.2 当前实现默认仍偏单服思维

市场和拍卖目前都采用：

1. 异步读取数据库状态
2. 回主线程进行 Bukkit / Vault / 背包操作
3. 再异步写回数据库

这在单服下可以工作，但在多服共享 MySQL 时有两个核心问题：

- 业务状态变更不是原子的
- 玩家可能在线，但不在当前执行逻辑的那台服上

### 2.3 当前最危险的问题不是“没广播”，而是“并发语义不安全”

现有存储层中：

- `getListing(id)` + `updateListing(listing)` 是分离的
- `getAuction(id)` + `updateAuction(auction)` 是分离的
- `update` 没有带状态条件
- 没有事务式 compare-and-set
- 没有版本号
- 没有行级锁方案

这意味着在共享 MySQL 的多服环境下：

- 同一件市场物品可能被两台服同时判定为可购买
- 同一拍卖可能被两台服同时判定为可出价/可结算
- 同一拍卖结束逻辑可能被多个服重复执行

结论：

- **必须先补齐 plugin + storage 的并发安全语义**
- 否则即使做了 Velocity 广播，广播出去的也可能是已经竞争失效或重复结算的状态

---

## 3. 设计原则

### 3.1 权威状态不放在 Velocity

权威业务状态应继续放在共享 MySQL，而不是迁到 Velocity。

原因：

- 市场 / 拍卖 / 邮箱本来就依赖持久化
- Velocity 不能直接使用 Bukkit API / 背包 API / Vault
- 真正的经济扣款、发物品、打开 GUI、发聊天提示，都必须由 Paper 端执行

因此职责划分应为：

### 3.2 推荐职责划分

#### Paper 插件负责

- 所有业务规则校验
- 所有 MySQL 持久化
- 所有 Bukkit / Vault / 背包操作
- 所有 GUI 打开与刷新
- 所有玩家本服可见提示

#### Velocity 插件负责

- 代理层消息总线
- 后端服注册与存活感知
- 玩家当前所在服路由
- 指令式消息转发
- 跨服广播
- 请求/响应超时与简单幂等保护

简化理解：

- **MySQL 是权威状态源**
- **Paper 是业务执行端**
- **Velocity 是路由与广播层**

---

## 4. 先做什么，不要做什么

## 4.1 第一优先级：先修 plugin 侧并发语义

在开始 Velocity 实现前，先完成以下基础改造：

### 市场购买

要把当前的：

- `getListing(id)`
- 校验状态
- `updateListing(listing)`

改成可判定成功/失败的原子更新流程，例如：

- `markListingSoldIfActive(listingId, buyerUuid, buyerName)`

建议 SQL 语义：

```sql
UPDATE market_listings
SET status='SOLD', buyer_uuid=?, buyer_name=?
WHERE id=? AND status='ACTIVE'
```

并检查 `affectedRows == 1`。

只有抢占成功后，才执行：

- 扣买家钱
- 发物品
- 给卖家打钱或写邮箱
- 广播跨服事件

### 拍卖出价

当前竞拍流程也必须改为带条件更新。

至少要避免：

- 基于旧价格重复出价覆盖
- 两台服同时给同一拍卖设置不同最高价

建议方向：

- 基于 `id + status + current_price + highest_bidder_uuid` 做条件更新
- 或增加 `version` 字段做乐观锁

### 拍卖结束

拍卖结束不能再由每台子服都各自恢复一套内存定时器并结算。

当前代码会在每台服启动时：

- 读取所有活跃拍卖
- 为每个拍卖注册本地延迟任务

这在群组服下会导致重复结束。

必须改成以下二选一：

#### 方案 A：数据库 lease 主节点

- 某一台 Paper 服成为拍卖结算 leader
- 只有 leader 恢复和驱动拍卖结束任务
- leader 失效后由其他服接管

#### 方案 B：无 leader，但结算时用原子状态抢占

- 每台服都可以检测到拍卖到期
- 但只有第一个成功把 `ACTIVE -> ENDED` 的服才能继续执行结算副作用

建议：

- 第一版优先做 **方案 B**
- 因为它改动小，且能和 Velocity 架构独立推进

### 跨服模式必须强制 MySQL

当前配置默认仍是：

- `storage.type: sqlite`

如果启用跨服模式，应在启动时强制要求：

- `storage.type == mysql`

否则直接拒绝启用跨服能力。

---

## 5. 目标架构

## 5.1 总体结构

```text
Paper Server A (plugin)  ----\
Paper Server B (plugin)  ----- > Velocity (message router) ----> 目标 Paper Server
Paper Server C (plugin)  ----/
            \________________________ shared MySQL ________________________/
```

核心关系：

- 数据统一进 MySQL
- 事件经 Velocity 广播/定向路由
- 执行动作始终落在玩家当前所在的 Paper 服

---

## 6. Velocity 插件建议模块

建议在 `GensouMarket-velocity` 中建立如下模块。

## 6.1 主类

### `net.scarletphantasy.gensouMarket.velocity.GensouMarketVelocity`

职责：

- 加载配置
- 注册 plugin messaging channel
- 注册监听器
- 初始化各服务
- 管理后端服上下线

## 6.2 协议层

### `protocol/PacketType`

定义消息类型枚举，例如：

- `SERVER_HELLO`
- `SERVER_GOODBYE`
- `MARKET_LISTING_CHANGED`
- `MARKET_LISTING_SOLD`
- `AUCTION_CREATED`
- `AUCTION_BID_UPDATED`
- `AUCTION_ENDED`
- `AUCTION_CANCELLED`
- `MAIL_CREATED`
- `PLAYER_NOTIFY`
- `REMOTE_DEPOSIT`
- `REMOTE_GIVE_ITEM`
- `REFRESH_MARKET_VIEW`
- `REFRESH_AUCTION_VIEW`
- `REQUEST_ACK`
- `REQUEST_FAIL`

### `protocol/PacketCodec`

职责：

- 编码/解码消息
- 写入 `protocolVersion`
- 写入 `messageType`
- 写入 `requestId`
- 写入 `sourceServerId`
- 处理字节长度与异常保护

第一版不要引入复杂序列化框架，直接：

- `DataInputStream`
- `DataOutputStream`

即可。

## 6.3 服务注册与路由

### `service/BackendRegistry`

维护：

- 已注册的后端服
- 后端服 `serverId -> RegisteredServer`
- 后端服是否启用了 GensouMarket bridge

### `service/PlayerRouteService`

职责：

- 根据玩家 UUID 查找当前所处后端服
- 判断目标玩家是否在线
- 获取最适合投递的目标服

### `service/BroadcastService`

职责：

- 广播消息给所有已注册 GensouMarket 后端
- 广播时排除源服
- 只向可用目标服发送

## 6.4 请求跟踪

### `service/PendingRequestStore`

职责：

- 管理 `requestId -> pending context`
- 超时清理
- 防止同一个请求无限等待

### `service/DedupService`

职责：

- 对短时间内重复的 `requestId` 做幂等去重
- 避免因网络抖动或重发导致重复执行

第一版可以用内存 + TTL 即可。

---

## 7. Paper 端必须新增的桥接层

Velocity 端不是孤立实现，`plugin` 端必须同时加 bridge。

## 7.1 `bridge/ProxyBridge`

职责：

- 注册 Bukkit plugin messaging channel
- 向 Velocity 发送消息
- 接收来自 Velocity 的消息
- 将消息路由到本服 handler

## 7.2 `bridge/ClusterEventPublisher`

职责：

- 对业务层暴露语义化方法，而不是让业务层自己拼字节包

例如：

- `publishListingSold(...)`
- `publishAuctionBidUpdated(...)`
- `publishAuctionEnded(...)`
- `publishMailCreated(...)`
- `broadcastRefreshAuction(...)`

## 7.3 `bridge/RemoteActionHandler`

职责：

- 处理来自 Velocity 的远程动作请求

例如：

- 给在线玩家发金币
- 给在线玩家发物品
- 给在线玩家发提示
- 刷新某玩家正在打开的市场/拍卖 GUI

## 7.4 `bridge/ViewSessionRegistry`

职责：

- 记录本服当前哪些玩家正在查看市场 GUI / 拍卖列表 / 拍卖详情
- 记录详情页对应的 `auctionId`
- 在收到跨服刷新事件时，找到应该刷新的玩家并刷新

这一步很重要，因为当前 GUI 刷新逻辑只会刷新本服当前操作玩家自己的界面，不会自动跨服通知。

---

## 8. 第一版消息模型建议

## 8.1 消息头

每条消息都建议包含：

- `protocolVersion`
- `messageType`
- `requestId`
- `timestamp`
- `sourceServerId`

## 8.2 事件载荷

### 市场相关

#### `MARKET_LISTING_SOLD`

字段建议：

- `listingId`
- `sellerUuid`
- `sellerName`
- `buyerUuid`
- `buyerName`
- `price`
- `sellerReceive`
- `tax`

用途：

- 提醒其他服关闭/刷新该商品
- 尝试给在线卖家即时到账
- 提醒买家/卖家

#### `MARKET_LISTING_CHANGED`

用于：

- 上架
- 下架
- 过期

字段：

- `listingId`
- `newStatus`
- `sellerUuid`

### 拍卖相关

#### `AUCTION_CREATED`

字段：

- `auctionId`
- `sellerUuid`
- `sellerName`
- `startingPrice`
- `currentPrice`
- `endTime`

#### `AUCTION_BID_UPDATED`

字段：

- `auctionId`
- `sellerUuid`
- `bidderUuid`
- `bidderName`
- `previousBidderUuid`
- `newPrice`
- `endTime`

用途：

- 刷新打开中的拍卖列表/详情
- 给卖家发提示
- 给被超价玩家退款/提示

#### `AUCTION_ENDED`

字段：

- `auctionId`
- `sellerUuid`
- `winnerUuid`
- `winnerName`
- `finalPrice`
- `sellerReceive`
- `hasWinner`

用途：

- 跨服发钱
- 跨服发物品
- 跨服提示
- 刷新 GUI

#### `AUCTION_CANCELLED`

字段：

- `auctionId`
- `sellerUuid`
- `highestBidderUuid`
- `refundAmount`

### 邮箱相关

#### `MAIL_CREATED`

字段：

- `playerUuid`
- `hasMoney`
- `hasItem`
- `message`

用途：

- 如果玩家在线于任意服，立即弹提示

### 远程动作

#### `REMOTE_DEPOSIT`

字段：

- `targetPlayerUuid`
- `amount`
- `reason`

#### `REMOTE_GIVE_ITEM`

字段：

- `targetPlayerUuid`
- `itemData`
- `reason`

#### `PLAYER_NOTIFY`

字段：

- `targetPlayerUuid`
- `message`

#### `REFRESH_AUCTION_VIEW`

字段：

- `auctionId`
- `mode`

模式可为：

- `DETAIL_ONLY`
- `LIST_AND_DETAIL`

---

## 9. 关键业务流设计

## 9.1 市场购买成功

建议流程：

1. Paper 服先通过原子数据库更新抢占 `ACTIVE -> SOLD`
2. 抢占失败则提示“已被其他玩家购买”
3. 抢占成功后，处理本服买家扣款与拿物品
4. 检查卖家是否在线于当前服
5. 若不在当前服，则通过 Velocity 定向投递 `REMOTE_DEPOSIT`
6. 若远程动作成功，则卖家即时到账
7. 若远程动作失败或目标玩家不在线，则写邮箱
8. 广播 `MARKET_LISTING_SOLD` 给所有后端，用于刷新 GUI / 提示

注意：

- 不要先写邮箱再尝试跨服到账，否则会双发
- 需要 request/response 机制确认远程存款是否执行成功

## 9.2 新出价

建议流程：

1. 读取当前拍卖
2. 通过原子更新抢占新的最高价
3. 抢占失败则重新读取最新拍卖状态并反馈
4. 抢占成功后：
   - 本地扣新竞拍者的钱
   - 退还旧最高价玩家
   - 若旧玩家在别服在线，则远程投递退款
   - 若退款失败则写邮箱
5. 广播 `AUCTION_BID_UPDATED`
6. 所有服收到后：
   - 刷新拍卖列表
   - 刷新该拍卖详情页
   - 给卖家发提示
   - 给旧竞拍者发“被超价”提示

## 9.3 拍卖结束

建议流程：

1. 到期检测触发
2. 通过原子状态更新抢占 `ACTIVE -> ENDED`
3. 只有抢占成功的服继续执行结算
4. 若有中标者：
   - 尝试给卖家打钱
   - 尝试给赢家发物品
   - 远程成功则即时发放
   - 远程失败或不在线则写邮箱
5. 若无人竞拍：
   - 尝试把物品返还卖家
   - 在线但在别服时走远程发物品
   - 失败则写邮箱
6. 广播 `AUCTION_ENDED`
7. 所有服刷新相关 GUI

## 9.4 新邮箱产生

无论由于：

- 市场售出
- 市场过期
- 拍卖结束
- 拍卖取消
- 竞拍退款

只要新建 `MailEntry`，都应该额外触发：

- `MAIL_CREATED(playerUuid, ...)`

若玩家在线于某服，则即时提示：

- 你有新的待领取物品/金币

这样可以显著改善“必须手动 collect 才意识到到账”的体验。

---

## 10. 推荐实施顺序

## 实施结果归档

### Phase 0: 并发安全基础

- 已完成跨服配置段接入
- 已完成跨服模式强制 MySQL
- 已完成市场/拍卖原子状态更新
- 已完成拍卖结束抢占与定时恢复

### Phase 1: Velocity 最小骨架

- 已完成主类、配置、channel 注册
- 已完成 `PacketType` / `PacketCodec`
- 已完成 `BackendRegistry` / `PlayerRouteService` / `BroadcastService`

### Phase 2: Plugin 端 Bridge

- 已完成 `ProxyBridge`
- 已完成 `ClusterEventPublisher`
- 已完成 `RemoteActionHandler`
- 已完成 `ViewSessionRegistry`

### Phase 3: 拍卖链路

- 已完成 `AUCTION_CREATED`
- 已完成 `AUCTION_BID_UPDATED`
- 已完成 `AUCTION_ENDED`
- 已完成 `AUCTION_CANCELLED`
- 已完成拍卖 GUI 跨服刷新

### Phase 4: 市场链路

- 已完成 `MARKET_LISTING_CHANGED`
- 已完成 `MARKET_LISTING_SOLD`
- 已完成跨服卖家即时到账
- 已完成市场列表跨服刷新

### Phase 5: 邮箱即时通知

- 已完成 `MAIL_CREATED`
- 已完成跨服在线即时提醒
- 当前已进入正式版预发布与收口阶段

---

## 11. 目录建议

## 11.1 Velocity 端

建议目录：

```text
GensouMarket-velocity/src/main/java/net/scarletphantasy/gensouMarket/velocity/
├── GensouMarketVelocity.java
├── config/
│   └── VelocityConfig.java
├── protocol/
│   ├── PacketType.java
│   ├── PacketCodec.java
│   ├── IncomingPacket.java
│   └── OutgoingPacket.java
├── service/
│   ├── BackendRegistry.java
│   ├── PlayerRouteService.java
│   ├── BroadcastService.java
│   ├── PendingRequestStore.java
│   └── DedupService.java
└── listener/
    ├── PluginMessageListener.java
    ├── BackendConnectionListener.java
    └── PlayerConnectionListener.java
```

## 11.2 Plugin 端

建议新增目录：

```text
GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/
├── bridge/
│   ├── ProxyBridge.java
│   ├── ClusterEventPublisher.java
│   ├── RemoteActionHandler.java
│   └── ViewSessionRegistry.java
```

如果后续协议对象较多，也可在 plugin 侧增加：

```text
protocol/
```

保持与 velocity 侧协议定义同步。

---

## 12. 风险与注意事项

## 12.1 不要把 Velocity 当业务主控

Velocity 不是 Bukkit。

它不应负责：

- 经济扣款
- 背包发物品
- GUI 打开
- 直接读取/操作 Bukkit 业务对象

它只做路由与消息转发。

## 12.2 幂等必须考虑

跨服请求可能因为以下原因重发：

- 网络抖动
- 后端重连
- 超时后补发

因此：

- 所有远程动作请求都要有 `requestId`
- 接收端要做短时间去重

## 12.3 失败回退必须保守

优先级建议：

1. 在线当前服直接发
2. 在线别服远程发
3. 远程失败则写邮箱

这样最安全，也最贴合现有架构。

## 12.4 GUI 刷新不应耦合业务对象

广播给其他服时，不要传整份 Bukkit `ItemStack` 对象图用于刷新界面。

更合适的做法是：

- 广播 `auctionId` / `listingId`
- 目标服自己重新从数据库读取最新状态后刷新 GUI

这和现有 GUI 逻辑更一致，也能避免消息体过大。

## 12.5 兼容单服模式

必须保留：

- `cluster.enabled = false`

时的单服运行路径。

也就是说：

- bridge 可以不启用
- velocity 不存在也能正常运行

---

## 13. 对 Claude Opus 的明确执行建议

如果要真正开始落地，不建议直接从 `GensouMarket-velocity` 开始写主类。

建议按以下顺序推进：

1. 先改 `plugin` 存储与管理器接口，补原子更新语义
2. 再改拍卖结束机制，避免多服重复结算
3. 然后实现 plugin 侧 `bridge` 基础设施
4. 再实现 velocity 侧最小消息路由骨架
5. 先打通 `AUCTION_BID_UPDATED` 与 `AUCTION_ENDED`
6. 再接市场售出与邮箱即时通知

一句话总结：

- **先修业务一致性，再做代理广播**

否则后面一定返工。

---

## 14. 已识别的关键代码入口

以下文件是后续实现时最关键的切入点：

- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/GensouMarket.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/market/MarketManager.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/market/AuctionManager.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/command/CommandManager.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/listener/PlayerListener.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/storage/MySQLStorage.java`
- `GensouMarket-plugin/src/main/java/net/scarletphantasy/gensouMarket/storage/StorageFactory.java`
- `GensouMarket-plugin/src/main/resources/config.yml`
- `GensouMarket-velocity/build.gradle`

---

## 15. 最终判断

这项工作本质上不是“补一个 Velocity 子项目”，而是“把现有单服业务模型升级成群组服可用模型”。

真正的正确顺序是：

- 先修数据一致性与执行归属
- 再加代理层路由
- 最后补用户体验层的实时通知与 GUI 刷新

如果跳过前两步直接写 Velocity，会得到一个能发消息但不能保证正确性的系统。
