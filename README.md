# 幻想集市 GensouMarket 🛒

幻想集市是一个 Minecraft 服务器经济交易插件，让玩家可以摆摊、买卖、拍卖、回收物品，也可以和其他玩家面对面安全交易。

主命令：`/gmarket`

完整命令：`/gensoumarket`

游戏内帮助：`/gmarket help`

## 玩家怎么用 🎮

输入 `/gmarket` 可以打开主菜单，大部分操作都能直接点 GUI 完成。

### 全球市场

全球市场是玩家之间买卖物品的地方。

| 你想做什么 | 命令 |
| --- | --- |
| 打开市场菜单 | `/gmarket` |
| 上架手中物品 | `/gmarket sell <价格>` |
| 搜索商品 | `/gmarket search [关键词]` |
| 查看自己的上架 | `/gmarket my` |
| 购买指定商品 | `/gmarket buy <ID>` |
| 下架自己的商品 | `/gmarket cancel <ID>` |

也可以在市场 GUI 中左键购买，右键下架自己的商品。

### 个人商店

个人商店会只显示某个玩家正在出售的商品。

| 你想做什么 | 命令或操作 |
| --- | --- |
| 打开自己的个人商店 | `/gmarket personal` |
| 查看其他玩家的个人商店 | `/gmarket personal <玩家名>` |
| 创建个人商店牌子 | 牌子第一行写 `[个人商店]` 或 `[gmarketshop]` |
| 打开牌子商店 | 右键个人商店牌子 |

### 拍卖行

拍卖适合出售稀有物品或让玩家竞价。

| 你想做什么 | 命令 |
| --- | --- |
| 查看拍卖列表 | `/gmarket auction list` |
| 拍卖手中物品 | `/gmarket auction <起拍价> [时长分钟]` |
| 出价竞拍 | `/gmarket bid <拍卖ID> <出价>` |
| 取消自己的拍卖 | `/gmarket auction cancel <拍卖ID>` |

### 服务器商店

服务器商店由服主设置，玩家可以直接向服务器购买物品。

| 你想做什么 | 命令 |
| --- | --- |
| 打开服务器商店 | `/gmarket shop` |
| 查看当前价格 | `/gmarket shop prices` |
| 购买指定商品 | `/gmarket shop buy <物品ID> [数量]` |

在商店 GUI 中左键购买 1 个，Shift+左键购买 64 个。

### 回收站

回收站可以把指定物品卖给服务器。回收价格可能会随供需、回收量和全服经济情况变化。

| 你想做什么 | 命令 |
| --- | --- |
| 打开回收站 | `/gmarket recycle` |
| 查看回收价格 | `/gmarket recycle prices` |
| 回收手中物品 | `/gmarket recycle sell [数量]` |

在回收 GUI 中左键回收 1 个，右键回收 1 组。价格变化较大时，插件可能会要求再次点击确认。

### 面对面交易

面对面交易适合两个玩家直接交换物品。

| 你想做什么 | 命令或操作 |
| --- | --- |
| 发起交易 | 蹲下右键点击玩家 |
| 接受交易 | `/gmarket trade accept` |
| 拒绝交易 | `/gmarket trade deny` |
| 取消请求 | `/gmarket trade cancel` |

交易双方距离太远时，交易会自动取消。

### 领取物品和金币

如果商品售出、拍卖结束、物品退回，或者跨服交易结算后没有直接发到背包里，可以使用：

`/gmarket collect`

## 服主怎么安装 ⚙️

### 基础要求

- Java 21
- Paper 1.21 系列服务器
- Vault
- 一个支持 Vault 的经济插件

### 单服安装

1. 把幻想集市插件放进服务器的 `plugins` 文件夹。
2. 确保已经安装 Vault 和经济插件。
3. 重启服务器。
4. 首次启动后会生成配置文件。
5. 修改配置后，在游戏内或控制台执行 `/gmarket reload`。

默认使用 SQLite，适合单服直接使用。需要数据库时，可以在 `config.yml` 中改为 MySQL。

### 跨服使用

跨服模式适合 Velocity 群组服。

1. 每个子服都安装幻想集市 Bukkit/Paper 插件。
2. Velocity 代理端安装幻想集市 Velocity 插件。
3. 每个子服的 `config.yml` 中开启 `cluster.enabled`。
4. 每个子服设置不同的 `cluster.server-id`。
5. 所有子服使用同一个 MySQL 数据库。
6. 重启 Velocity 和所有子服。

跨服模式必须使用 MySQL。

## 服主管理命令 🛠️

| 功能 | 命令 |
| --- | --- |
| 重载配置 | `/gmarket reload` |
| 手持物品添加到服务器商店 | `/gmarket shop add <价格>` |
| 指定物品添加到服务器商店 | `/gmarket shop add <物品> <价格>` |
| 移除服务器商店商品 | `/gmarket shop remove <物品ID>` |
| 修改服务器商店价格 | `/gmarket shop setprice <物品ID> <买入价>` |
| 设置有限库存商品库存 | `/gmarket shop setstock <物品ID> <数量>` |
| 手持物品添加到回收站 | `/gmarket recycle add <回收价>` |
| 指定物品添加到回收站 | `/gmarket recycle add <物品> <回收价>` |
| 移除回收物品 | `/gmarket recycle remove <物品ID>` |
| 修改回收价格 | `/gmarket recycle setprice <物品ID> <回收价>` |

## 常用配置 📄

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 开关各功能、设置税率、拍卖时间、存储方式、跨服模式、动态价格 |
| `shop.yml` | 配置服务器商店卖什么、卖多少钱、库存模式 |
| `recycle.yml` | 配置哪些物品可以回收、基础回收价是多少 |

### 可开关的功能

- 全球市场
- 拍卖行
- 邮箱领取入口
- 个人商店
- 服务器商店
- 回收站
- 面对面交易
- 动态价格
- 跨服模式

## 权限节点 🔐

大部分玩家权限默认开启，管理员权限默认 OP 拥有。

| 权限 | 说明 |
| --- | --- |
| `gensoumarket.use` | 使用基础命令 |
| `gensoumarket.sell` | 在全球市场上架物品 |
| `gensoumarket.buy` | 在全球市场购买物品 |
| `gensoumarket.auction` | 使用拍卖行 |
| `gensoumarket.shop` | 使用服务器商店 |
| `gensoumarket.recycle` | 使用回收站 |
| `gensoumarket.trade` | 使用面对面交易 |
| `gensoumarket.personal` | 使用个人商店 |
| `gensoumarket.personal.sign.create` | 创建个人商店牌子 |
| `gensoumarket.admin` | 管理员权限 |
| `gensoumarket.admin.reload` | 重载配置 |
| `gensoumarket.admin.shop.edit` | 管理服务器商店 |
| `gensoumarket.admin.recycle.edit` | 管理回收站 |

## 小提示 💡

- `/gmarket` 是最常用入口，不记得命令时先打开菜单。
- 商品 ID 会显示在市场、拍卖或价格列表里。
- 回收站和服务器商店的价格可能会变化，下单前看清当前价格。
- 如果背包满了，领取到的物品可能会掉在脚下。
- 拍卖取消后，手续费不会退还。
