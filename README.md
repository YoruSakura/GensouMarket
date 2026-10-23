# GensouMarket 幻想集市

GensouMarket 是 Minecraft 的交易插件，支持全球市场、个人商店、拍卖、服务器商店、回收、面对面交易和邮件领取。插件分为 Paper 子服端和 Velocity 代理端，可单服使用，也可用于群组服。

| 项目 | 信息 |
| --- | --- |
| 当前版本 | [1.2.3](https://github.com/YoruSakura/GensouMarket/releases/tag/v1.2.3) |
| 主命令 | `/gmarket` |
| 完整命令 | `/gensoumarket` |
| 下载 | <https://github.com/YoruSakura/GensouMarket/releases> |

## 1.2.3 更新

- 修复面对面交易中的物品显示、确认和取消返还。
- 补齐菜单、商店、拍卖和个人商店的权限检查。
- 管理员下架商品或取消拍卖时，物品退还原卖家。
- 回收失败退回物品时，保留原有名称、附魔等信息。
- 多个子服购买限量商品时共用库存，重启后保留剩余数量。
- 加强跨服消息和后端服注册检查。
- 支持同一台机器上使用共享 SQLite；跨服生产环境仍推荐 MySQL。

## 功能

| 功能 | 说明 |
| --- | --- |
| 全球市场 | 玩家上架、搜索、购买和下架物品 |
| 个人商店 | 查看指定玩家的商品，也可以用牌子打开 |
| 拍卖行 | 发起拍卖和竞价 |
| 服务器商店 | 购买服主配置的商品 |
| 回收站 | 把指定物品卖给服务器 |
| 面对面交易 | 两名玩家直接交换物品 |
| 邮件 | 领取暂时无法直接交付的物品和钱 |
| 动态价格 | 价格可随库存、回收量和经济阶段变化 |
| Velocity 跨服 | 同步市场消息、邮件提醒和动态价格数据 |

## 玩家命令

### 全球市场

| 操作 | 命令 |
| --- | --- |
| 打开主菜单 | `/gmarket` |
| 上架手中物品 | `/gmarket sell <价格>` |
| 搜索商品 | `/gmarket search [关键词]` |
| 查看自己的商品 | `/gmarket my` |
| 购买商品 | `/gmarket buy <ID>` |
| 下架商品 | `/gmarket cancel <ID>` |

### 个人商店

| 操作 | 命令或方式 |
| --- | --- |
| 打开自己的商店 | `/gmarket personal` |
| 查看其他玩家的商店 | `/gmarket personal <玩家名>` |
| 创建商店牌子 | 牌子第一行写 `[个人商店]` 或 `[gmarketshop]` |
| 打开牌子商店 | 右键牌子 |

### 拍卖行

| 操作 | 命令 |
| --- | --- |
| 查看拍卖 | `/gmarket auction list` |
| 发起拍卖 | `/gmarket auction <起拍价> [时长分钟]` |
| 出价 | `/gmarket bid <拍卖ID> <出价>` |
| 取消自己的拍卖 | `/gmarket auction cancel <拍卖ID>` |

取消拍卖不退手续费。

### 服务器商店

| 商品类型 | 说明 |
| --- | --- |
| 无限库存 | 一直可以买到的商品 |
| 限量库存 | 库存卖完后需要服主补充 |
| 回流库存 | 库存来自玩家卖到回收站的物品 |

| 操作 | 命令 |
| --- | --- |
| 打开商店 | `/gmarket shop` |
| 查看价格 | `/gmarket shop prices` |
| 购买商品 | `/gmarket shop buy <物品ID> [数量]` |

在商店界面左键购买 1 个，Shift+左键购买 64 个。价格变化较大时，需要再次点击确认。

### 回收站

| 操作 | 命令 |
| --- | --- |
| 打开回收站 | `/gmarket recycle` |
| 查看价格 | `/gmarket recycle prices` |
| 回收手中物品 | `/gmarket recycle sell [数量]` |

在回收界面左键回收 1 个，右键回收一组。价格变化较大时，需要再次点击确认。

### 面对面交易

| 操作 | 命令或方式 |
| --- | --- |
| 发起交易 | 蹲下右键点击玩家 |
| 接受交易 | `/gmarket trade accept` |
| 拒绝交易 | `/gmarket trade deny` |
| 取消请求 | `/gmarket trade cancel` |

双方确认后交易完成，距离过远会自动取消。

### 邮件

有待领取的物品或钱时，使用：

```text
/gmarket collect
```

背包装满时，物品可能掉在脚下，请及时拾取。

## 价格

服主可以给商品设置固定价格，也可以让价格跟着库存、回收量、周期或全服经济变化。

- 无限库存商品通常使用固定价格。
- 限量库存商品可以随剩余库存改变价格。
- 回流库存商品的库存来自回收站。
- 每个商品都可以设置价格上下限。

## 安装

### 运行环境

- 支持 **Paper 1.21–26.3**。
- Paper 1.21 系列使用 Java 21；Paper 26.x 使用 Java 25。
- 不支持 Spigot。
- Paper 子服必须安装 Vault 和一个支持 Vault 的经济插件。
- CMI 可以作为经济插件使用，但不是必须的。

已在 Paper 26.1.2 上测试，范围内的其他版本做过兼容检查。

### 下载

| 文件 | 放置位置 |
| --- | --- |
| [GensouMarket-plugin-1.2.3.jar](https://github.com/YoruSakura/GensouMarket/releases/download/v1.2.3/GensouMarket-plugin-1.2.3.jar) | Paper 子服的 `plugins` 文件夹 |
| [GensouMarket-velocity-1.2.3.jar](https://github.com/YoruSakura/GensouMarket/releases/download/v1.2.3/GensouMarket-velocity-1.2.3.jar) | Velocity 代理的 `plugins` 文件夹 |

单服只需要 Paper 插件。跨服时，Paper 和 Velocity 两端都要使用同一版本。

### 单服

1. 安装 Paper、Vault 和经济插件。
2. 把 `GensouMarket-plugin-1.2.3.jar` 放进 `plugins` 文件夹。
3. 启动服务器，确认市场、Vault 和经济插件正常加载。
4. 修改配置后执行 `/gmarket reload`。

单服默认使用 SQLite。需要 MySQL 时，在 `config.yml` 中修改存储设置。

### Velocity 跨服

跨服需要另装一个能同步玩家**背包和金钱**的插件。

1. 每个 Paper 子服安装 Paper 插件。
2. Velocity 代理安装 Velocity 插件。
3. 每个子服安装 Vault、经济插件和背包/金钱同步插件。
4. 所有子服的市场使用同一个 MySQL 数据库，也可以共享同一个本地 SQLite 文件。
5. 在每个子服的 `config.yml` 中设置：
   - `storage.type: mysql`；使用 SQLite 时，设为 `sqlite`，并将 `storage.sqlite.path` 填为同一个文件的绝对路径。
   - `cluster.enabled: true`。
   - 每个子服使用不同的 `cluster.server-id`。
   - 所有子服的 `cluster.channel` 与 Velocity 配置一致。
6. 启动后确认所有子服都已注册，玩家切服后钱和物品能正常保存。

跨服的各个子服应使用相同的 Minecraft 版本。生产环境推荐 MySQL；共享 SQLite 只适合同机、同系统用户的情况。

### 升级

1. 停止服务器和 Velocity。
2. 备份配置和数据库。
3. 替换旧 jar，每个端只保留一份 GensouMarket。
4. 启动后查看日志，确认存储、经济插件和跨服连接正常。

## 配置文件

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 功能开关、价格、存储和跨服设置 |
| `shop.yml` | 服务器商店商品和库存 |
| `recycle.yml` | 回收物品和价格 |
| `personal-shop-signs.yml` | 个人商店牌子数据 |

## 服主管理命令

| 功能 | 命令 |
| --- | --- |
| 重载配置 | `/gmarket reload` |
| 手持物品加入商店 | `/gmarket shop add <价格>` |
| 指定物品加入商店 | `/gmarket shop add <物品> <价格>` |
| 移除商店商品 | `/gmarket shop remove <物品ID>` |
| 修改商店价格 | `/gmarket shop setprice <物品ID> <买入价>` |
| 设置限量库存 | `/gmarket shop setstock <物品ID> <数量>` |
| 手持物品加入回收站 | `/gmarket recycle add <回收价>` |
| 指定物品加入回收站 | `/gmarket recycle add <物品> <回收价>` |
| 移除回收物品 | `/gmarket recycle remove <物品ID>` |
| 修改回收价格 | `/gmarket recycle setprice <物品ID> <回收价>` |

`shop setstock` 只用于限量商品。回流商品的库存来自回收站，价格由回收价和倍率决定，不能用 `setstock` 或 `setprice` 直接修改。

## 权限

大部分玩家权限默认开启，管理员权限默认只有 OP 拥有。

| 权限 | 用途 |
| --- | --- |
| `gensoumarket.use` | 使用基础功能 |
| `gensoumarket.sell` | 上架市场商品 |
| `gensoumarket.buy` | 购买市场商品 |
| `gensoumarket.auction` | 使用拍卖行 |
| `gensoumarket.shop` | 使用服务器商店 |
| `gensoumarket.recycle` | 使用回收站 |
| `gensoumarket.trade` | 使用面对面交易 |
| `gensoumarket.personal` | 使用个人商店 |
| `gensoumarket.personal.sign.create` | 创建个人商店牌子 |
| `gensoumarket.admin` | 管理员功能 |
| `gensoumarket.admin.reload` | 重载配置 |
| `gensoumarket.admin.shop.edit` | 修改服务器商店 |
| `gensoumarket.admin.recycle.edit` | 修改回收站 |
