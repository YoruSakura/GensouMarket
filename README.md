# 🏪 GensouMarket 幻想集市

GensouMarket 是一个面向 Minecraft 服务器的经济交易插件，提供玩家市场、拍卖、服务器商店、物品回收、个人商店、邮件领取和面对面交易等功能。项目包含 Paper 子服插件和 Velocity 代理插件，可用于单服，也可用于群组服。

| 项目 | 信息 |
| --- | --- |
| 🧭 主命令 | `/gmarket` |
| 🧩 完整命令 | `/gensoumarket` |
| 📦 下载 | <https://github.com/YoruSakura/GensouMarket/releases> |

## ✨ 功能一览

| 模块 | 说明 |
| --- | --- |
| 🛒 全球市场 | 玩家上架、搜索、购买和下架商品 |
| 🪧 个人商店 | 查看指定玩家的在售商品，支持牌子入口 |
| 🔨 拍卖行 | 玩家发起拍卖并参与竞价 |
| 🏬 服务器商店 | 服主配置固定库存、限量库存或回流库存商品 |
| ♻️ 回收站 | 玩家向服务器出售指定物品 |
| 🤝 面对面交易 | 两名玩家直接交换物品 |
| 📬 邮件领取 | 处理售出、拍卖结算、跨服结算和背包满等领取场景 |
| 🌐 Velocity 跨服 | 支持跨服消息、邮件提示、回流库存和动态价格数据同步 |
| 📈 动态价格 | 价格可按库存、回收量、周期波动和经济阶段进行调整 |

## 🎯 适用场景

- 需要玩家之间自由交易的生存、城镇、RPG 或群组服。
- 需要通过服务器商店和回收站调节经济流通的服务器。
- 需要在 Velocity 群组服中同步交易相关状态的服务器。
- 需要让部分商品价格跟随库存、回收量或经济阶段变化的服务器。

## 🧑‍💻 玩家命令

### 🛒 全球市场

玩家可以把手中的物品上架到全服市场，其他玩家可以搜索和购买。

| 操作 | 命令 |
| --- | --- |
| 打开主菜单 | `/gmarket` |
| 上架手中物品 | `/gmarket sell <价格>` |
| 搜索商品 | `/gmarket search [关键词]` |
| 查看自己的上架 | `/gmarket my` |
| 购买商品 | `/gmarket buy <ID>` |
| 下架自己的商品 | `/gmarket cancel <ID>` |

### 🪧 个人商店

个人商店只显示某个玩家正在出售的商品，适合玩家摊位、城镇商铺或牌子入口。

| 操作 | 命令或方式 |
| --- | --- |
| 打开自己的个人商店 | `/gmarket personal` |
| 查看其他玩家的个人商店 | `/gmarket personal <玩家名>` |
| 创建个人商店牌子 | 牌子第一行写 `[个人商店]` 或 `[gmarketshop]` |
| 打开牌子商店 | 右键个人商店牌子 |

### 🔨 拍卖行

拍卖适合稀有物品、装备、材料包等需要玩家竞价的交易。

| 操作 | 命令 |
| --- | --- |
| 查看拍卖列表 | `/gmarket auction list` |
| 发起拍卖 | `/gmarket auction <起拍价> [时长分钟]` |
| 出价竞拍 | `/gmarket bid <拍卖ID> <出价>` |
| 取消自己的拍卖 | `/gmarket auction cancel <拍卖ID>` |

### 🏬 服务器商店

服务器商店由服主配置，玩家可以直接向服务器购买物品。

| 商品类型 | 说明 |
| --- | --- |
| ♾️ 固定无限库存 | 常驻基础商品 |
| 📦 固定有限库存 | 限量供应商品 |
| ♻️ 回流库存商品 | 库存来自玩家回收站卖出的物品 |

| 操作 | 命令 |
| --- | --- |
| 打开服务器商店 | `/gmarket shop` |
| 查看商店价格 | `/gmarket shop prices` |
| 购买商品 | `/gmarket shop buy <物品ID> [数量]` |

GUI 中左键购买 1 个，Shift+左键购买 64 个。价格变化较大时，插件会要求再次点击确认。

### ♻️ 回收站

玩家可以把指定物品卖给服务器。回收价格可受配置、短期回收量、周期波动和全服经济阶段影响。

| 操作 | 命令 |
| --- | --- |
| 打开回收站 | `/gmarket recycle` |
| 查看回收价格 | `/gmarket recycle prices` |
| 回收手中物品 | `/gmarket recycle sell [数量]` |

GUI 中左键回收 1 个，右键回收一组。价格变化较大时，插件会要求再次点击确认。

### 🤝 面对面交易

两个玩家可以直接打开交易界面交换物品。

| 操作 | 命令或方式 |
| --- | --- |
| 发起交易 | 蹲下右键点击玩家 |
| 接受交易 | `/gmarket trade accept` |
| 拒绝交易 | `/gmarket trade deny` |
| 取消请求 | `/gmarket trade cancel` |

双方都确认后交易完成。距离过远时交易会自动取消。

### 📬 邮件领取

商品售出、拍卖结算、跨服结算或背包无法直接接收物品时，可以使用：

```text
/gmarket collect
```

## 📈 动态价格

服主可以使用固定价格，也可以让部分价格按服务器经济状态变化。

### ♻️ 回收价格

回收站价格可以受到这些因素影响：

- 基础回收价。
- 每个物品独立的周期波动。
- 短时间内回收数量增加时，回收价可按配置调整。
- 全服经济总量处于不同阶段时，可使用不同倍率。
- 每个物品可以单独设置价格上下限。

### 🏬 商店售价

服务器商店支持：

- 固定无限库存商品保持基础售价，除非服主开启 fixed 动态售价。
- 固定有限库存商品可以根据剩余库存调整售价。
- 回流库存商品可以根据回流库存数量调整售价。
- 回流商品带有防套利保护，减少低买高卖刷钱风险。

### 🌐 跨服动态价格数据

Velocity 群组服可以在子服之间同步动态价格所需的数据，让不同子服在同一套配置和数据基础上计算价格。

跨服模式需要 Paper 主插件和 Velocity 插件来自同一次发布。

## 🧰 服主安装

### ✅ 基础要求

- Paper 服务端
- Java 运行环境
- Vault
- 一个支持 Vault 的经济插件

具体支持的服务端和运行环境以对应 Release 说明为准。

### 📥 下载文件

从 GitHub Releases 页面下载所需文件：<https://github.com/YoruSakura/GensouMarket/releases>

| 文件 | 放在哪里 |
| --- | --- |
| Paper 插件 jar | Paper 子服的 `plugins` 文件夹 |
| Velocity 插件 jar | Velocity 的 `plugins` 文件夹，跨服模式才需要 |
| SHA256 校验文件 | 用于校验下载文件 |

### 🖥️ 单服安装

1. 把 Paper 插件 jar 放进 Paper 服务器的 `plugins` 文件夹。
2. 确保已经安装 Vault 和经济插件。
3. 重启服务器。
4. 首次启动后会生成 `config.yml`、`shop.yml`、`recycle.yml` 等配置。
5. 修改配置后执行 `/gmarket reload`。

单服默认使用 SQLite。需要数据库时，可以在 `config.yml` 中改为 MySQL。

### 🌐 Velocity 跨服安装

1. 每个 Paper 子服都安装 Paper 插件 jar。
2. Velocity 代理端安装 Velocity 插件 jar。
3. 所有子服使用同一个 MySQL 数据库。
4. 每个子服在 `config.yml` 中设置：
   - `storage.type: mysql`
   - `cluster.enabled: true`
   - 唯一的 `cluster.server-id`
   - 与 Velocity 端一致的 `cluster.channel`
5. 重启 Velocity 和所有 Paper 子服。

跨服模式必须使用 MySQL。升级或替换插件时，Paper 主插件和 Velocity 插件请来自同一次发布。

## ⚙️ 配置文件

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 功能开关、税率、拍卖时间、存储方式、跨服模式、动态价格和经济阶段 |
| `shop.yml` | 服务器商店商品、售价、库存模式、回流来源 |
| `recycle.yml` | 可回收物品和基础回收价 |
| `personal-shop-signs.yml` | 个人商店牌子数据，自动维护 |

### 🧩 可开关模块

- 全球市场
- 拍卖行
- 邮件入口
- 个人商店
- 服务器商店
- 回收站
- 面对面交易
- 动态价格
- 跨服模式

## 🛠️ 服主管理命令

| 功能 | 命令 |
| --- | --- |
| 重载配置 | `/gmarket reload` |
| 手持物品添加到服务器商店 | `/gmarket shop add <价格>` |
| 指定物品添加到服务器商店 | `/gmarket shop add <物品> <价格>` |
| 移除服务器商店商品 | `/gmarket shop remove <物品ID>` |
| 修改 fixed 商品价格 | `/gmarket shop setprice <物品ID> <买入价>` |
| 设置 fixed limited 商品库存 | `/gmarket shop setstock <物品ID> <数量>` |
| 手持物品添加到回收站 | `/gmarket recycle add <回收价>` |
| 指定物品添加到回收站 | `/gmarket recycle add <物品> <回收价>` |
| 移除回收物品 | `/gmarket recycle remove <物品ID>` |
| 修改回收价格 | `/gmarket recycle setprice <物品ID> <回收价>` |

注意：

- `shop setprice` 不能直接修改回流商品价格；回流商品价格由回收价和倍率决定。
- `shop setstock` 只适用于 fixed limited 商品。
- recycled 商品库存来自回收池，不能直接 setstock。

## 🔐 权限节点

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

## 💡 使用提示

- 玩家不记得命令时，先输入 `/gmarket` 打开主菜单。
- 商品 ID 会显示在市场、拍卖、商店或价格列表中。
- 商店和回收站价格可能变化，成交前请看清确认提示。
- 背包满时，部分物品可能会掉落在脚下或进入待领取邮件。
- 拍卖取消后，手续费不会退还。
- 跨服环境中遇到同步问题时，先确认 Paper 主插件和 Velocity 插件来自同一次发布。
