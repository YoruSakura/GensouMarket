# ✨ GensouMarket

`GensouMarket` 是一个为 `Paper 1.21.x` 服务器设计的综合经济交易插件。  
它把全球市场、拍卖行、服务器商店、动态回收、邮箱补发和玩家面对面交易整合到一个系统里；如果你的服务器是多子服架构，还可以配合附带的 Velocity 组件实现跨服同步。

> 一个把市场、拍卖、商店、回收、邮箱和交易整合在一起的服务器经济插件。

## 🌟 插件能做什么

- 🛒 全球市场
  - 玩家可直接上架手持物品
  - 支持浏览、搜索、购买、主动下架、自动过期
  - 支持上架税和交易税
- 🔨 拍卖行
  - 支持起拍价、拍卖时长、手续费
  - 支持竞拍、取消拍卖、自动结算
  - 退款、成交物品、离线补发均可走邮箱
- 🏪 服务器商店
  - 服务器固定价格出售物品
  - 支持 GUI 购买和命令购买
  - 支持管理员在线增删改商品
- ♻️ 回收站
  - 服务器按配置回收玩家物品
  - 支持动态价格、供需变化、市场波动
  - GUI 内可实时查看涨跌趋势
- 📬 邮箱系统
  - 用于补发离线玩家的金币和物品
  - 玩家登录会收到提醒
  - 可通过命令一键领取
- 🤝 面对面交易
  - 蹲下右键玩家即可发起交易
  - 双方确认后完成交换
  - 超时、掉线、超距自动取消并返还物品
- 🌐 跨服联动
  - 多个子服共享市场、拍卖、邮箱事件
  - 支持通过 Velocity 进行跨服消息转发

## 🧩 适用环境

### 📦 Paper 端

- Java `21`
- Paper `1.21.x`
- 需要 `Vault`
- 需要一个兼容 Vault 的经济插件

### 🚉 Velocity 端

- Java `21`
- Velocity `3.4.x`

## 🚀 快速安装

### 🖥️ 单服安装

1. 安装 `Vault` 和你的经济插件。
2. 将 `GensouMarket-plugin-1.0.0.jar` 放入服务器 `plugins/` 目录。
3. 启动服务器一次，让插件生成默认配置。
4. 修改 `config.yml`、`shop.yml`、`recycle.yml`。
5. 重启服务器，或执行 `/gmarket reload`。

### 🌉 跨服安装

如果你使用 `Velocity + 多个 Paper 子服`：

1. 每个 Paper 子服都安装 `GensouMarket-plugin-1.0.0.jar`。
2. Velocity 安装 `GensouMarket-velocity-1.0.0.jar`。
3. 所有子服共用同一个 `MySQL` 数据库。
4. 在每个子服的 `config.yml` 中启用：
   - `storage.type: mysql`
   - `cluster.enabled: true`
5. 为每个子服设置不同的 `cluster.server-id`。
6. 确保所有子服和 Velocity 的 `channel` 完全一致。

## ⚙️ 主要配置

### `config.yml`

常用配置项：

- `storage.type`
  - `sqlite`：默认，适合单服
  - `mysql`：推荐，跨服必须使用
  - `yaml`：仅保底使用，不建议正式环境
- `market`
  - `listing-tax`：上架税
  - `transaction-tax`：交易税
  - `max-listings`：每名玩家最大上架数量
  - `expire-hours`：市场物品过期时间
- `auction`
  - `default-duration`：默认拍卖时长
  - `min-duration`：最短拍卖时长
  - `max-duration`：最长拍卖时长
  - `listing-fee-rate`：拍卖手续费比例
- `shop.enabled`：是否启用服务器商店
- `recycle.enabled`：是否启用回收站
- `dynamic-pricing`：动态回收价格相关设置
- `trade`：面对面交易超时、距离限制、检测频率
- `cluster`：跨服模式开关与子服标识
- `prefix`：聊天消息前缀

### 🏪 `shop.yml`

用于配置服务器商店出售的物品和固定售价。

示例：

```yml
items:
  diamond:
    material: DIAMOND
    buy-price: 1000.0
```

### ♻️ `recycle.yml`

用于配置回收物品的基础价格。  
注意：实际成交价格可能会受到动态价格系统影响。

示例：

```yml
items:
  diamond:
    material: DIAMOND
    recycle-price: 500.0
```

### 🌐 Velocity `config.properties`

跨服时会在 Velocity 端生成：

```properties
channel=gensoumarket:main
request-timeout-seconds=10
dedup-ttl-seconds=30
```

其中 `channel` 必须与 Paper 端 `cluster.channel` 一致。

## 🎮 玩家常用命令

| 命令 | 说明 |
| --- | --- |
| `/gmarket` | 打开主菜单 |
| `/gmarket help` | 查看帮助 |
| `/gmarket sell <价格>` | 上架手持物品 |
| `/gmarket cancel <ID>` | 下架自己的市场物品 |
| `/gmarket buy <ID>` | 购买市场物品 |
| `/gmarket search [关键词]` | 搜索市场 |
| `/gmarket my` | 查看自己的上架 |
| `/gmarket auction <起拍价> [时长分钟]` | 发起拍卖 |
| `/gmarket auction list` | 查看拍卖列表 |
| `/gmarket auction cancel <ID>` | 取消拍卖 |
| `/gmarket bid <ID> <价格>` | 对拍卖出价 |
| `/gmarket shop` | 打开服务器商店 |
| `/gmarket shop buy <物品ID> [数量]` | 从商店购买物品 |
| `/gmarket recycle` | 打开回收站 |
| `/gmarket recycle sell [数量]` | 回收手持物品 |
| `/gmarket recycle prices` | 查看回收价格 |
| `/gmarket collect` | 领取邮箱中的金币和物品 |
| `/gmarket trade accept` | 接受交易请求 |
| `/gmarket trade deny` | 拒绝交易请求 |
| `/gmarket trade cancel` | 取消已发送的交易请求 |

命令别名：

- `/gensoumarket`
- `/gmarket`

## 🛠️ 管理员命令

| 命令 | 说明 |
| --- | --- |
| `/gmarket reload` | 重载配置 |
| `/gmarket shop add <价格>` | 将手持物品加入商店 |
| `/gmarket shop add <物品> <价格>` | 指定物品加入商店 |
| `/gmarket shop remove <物品ID>` | 移除商店商品 |
| `/gmarket shop setprice <物品ID> <价格>` | 修改商店价格 |
| `/gmarket recycle add <回收价>` | 将手持物品加入回收 |
| `/gmarket recycle add <物品> <回收价>` | 指定物品加入回收 |
| `/gmarket recycle remove <物品ID>` | 移除回收项 |
| `/gmarket recycle setprice <物品ID> <价格>` | 修改回收基础价 |

## 🔐 权限节点

| 权限 | 说明 | 默认 |
| --- | --- | --- |
| `gensoumarket.use` | 基本使用权限 | `true` |
| `gensoumarket.sell` | 上架市场物品 | `true` |
| `gensoumarket.buy` | 购买市场物品 | `true` |
| `gensoumarket.auction` | 使用拍卖功能 | `true` |
| `gensoumarket.shop` | 使用服务器商店 | `true` |
| `gensoumarket.recycle` | 使用回收站 | `true` |
| `gensoumarket.trade` | 使用面对面交易 | `true` |
| `gensoumarket.admin` | 管理员总权限 | `op` |
| `gensoumarket.admin.reload` | 重载配置 | `op` |
| `gensoumarket.admin.shop.edit` | 编辑商店 | `op` |
| `gensoumarket.admin.recycle.edit` | 编辑回收项 | `op` |

## 📖 玩法说明

### 🛒 全球市场

玩家手持物品执行 `/gmarket sell <价格>` 即可上架。  
其他玩家可以通过主菜单或 `/gmarket search` 查找商品，并直接购买。  
如果卖家或买家当时不在线，插件会自动通过邮箱补发金币或物品。

### 🔨 拍卖行

玩家手持物品执行 `/gmarket auction <起拍价> [时长]` 可发起拍卖。  
其他玩家可在拍卖列表中查看并出价。拍卖结束后会自动结算；无人竞拍时物品会返还。

### 🏪 商店与回收

商店适合固定售卖服务器物资。  
回收站适合做资源回收与经济调控，特别适用于长期生存服或箱庭经济体系。

GUI 交互：

- 商店中左键购买 `1` 个
- 商店中 `Shift + 左键` 购买 `64` 个
- 回收站中左键回收 `1` 个
- 回收站中右键回收 `1` 组

### 🤝 面对面交易

玩家 `蹲下 + 右键` 另一名玩家即可发起交易。  
对方也可以再次蹲下右键，或使用 `/gmarket trade accept` 接受。  
若交易过程中有人离线、距离过远或关闭界面，交易会自动取消并返还物品。

## 🌐 跨服注意事项

- 跨服模式必须使用 `MySQL`
- 每个子服的 `server-id` 必须唯一
- 所有 Paper 子服与 Velocity 的 `channel` 必须一致
- 如果你只做单服使用，不需要安装 Velocity 组件

## 💡 常见建议

- 单服小型服务器可直接使用 `sqlite`
- 中大型服务器或跨服环境建议直接使用 `mysql`
- 正式服建议先配置好商店、回收、税率和拍卖时长，再开放给玩家使用
- 如果你服务器内经济插件异常，市场、拍卖、商店、回收都会受影响

## 🧪 自行构建

如果你需要自己编译：

```bash
./gradlew build
```

构建输出：

- `GensouMarket-plugin/build/libs/GensouMarket-plugin-1.0.0.jar`
- `GensouMarket-velocity/build/libs/GensouMarket-velocity-1.0.0.jar`
