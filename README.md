# GensouMarket 幻想集市

GensouMarket 是 Minecraft 的交易插件，支持全球市场、个人商店、拍卖、服务器商店、回收、面对面交易和邮件领取。插件分为 Paper 子服端和 Velocity 代理端，可单服使用，也可用于群组服。

| 项目 | 信息 |
| --- | --- |
| 当前版本 | [1.2.4](https://github.com/YoruSakura/GensouMarket/releases/tag/v1.2.4) |
| 主命令 | `/gmarket` |
| 完整命令 | `/gensoumarket` |
| 下载 | <https://github.com/YoruSakura/GensouMarket/releases> |

## 1.2.4 更新

- 限量和回流商品批量购买时逐件计价，合计与相同条件下分次购买一致。
- 全服经济变化对价格的影响更平滑，跨过设置的金额门槛时不会突然跳价。
- 新增定价分组，每组一个配置文件，也能在游戏内创建分组、改参数和管理物品。
- 手持添加商品可保存名称、附魔、容器内容、自定义组件和标签；同款物品会拒绝重复登记，不同数据的物品可分别定价。
- 修复跨服拍卖的大物品无法发出时未转入邮箱的问题，玩家可用 `/gmarket collect` 完整领取。
- 拍卖创建时显示上架总费用，并列出上架税和拍卖手续费。

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
| 动态价格 | 价格可随库存、回收量和全服经济变化，不同物品可按组设置波动幅度 |
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
- 限量和回流商品一次购买多件时，按每件购买前的剩余库存依次计价。
- 可以给农作物、矿物、稀有物品设置不同分组，分别调整价格波动；各物品的库存和回收量仍独立统计。

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
| [GensouMarket-plugin-1.2.4.jar](https://github.com/YoruSakura/GensouMarket/releases/download/v1.2.4/GensouMarket-plugin-1.2.4.jar) | Paper 子服的 `plugins` 文件夹 |
| [GensouMarket-velocity-1.2.4.jar](https://github.com/YoruSakura/GensouMarket/releases/download/v1.2.4/GensouMarket-velocity-1.2.4.jar) | Velocity 代理的 `plugins` 文件夹 |

单服只需要 Paper 插件。跨服时，Paper 和 Velocity 两端都要使用同一版本。

### 单服

1. 安装 Paper、Vault 和经济插件。
2. 把 `GensouMarket-plugin-1.2.4.jar` 放进 `plugins` 文件夹。
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
| `pricing-groups/` | 每个定价分组一个 `.yml` 文件 |
| `personal-shop-signs.yml` | 个人商店牌子数据 |

### 按组设置动态价格

在 `config.yml` 中开关分组功能，修改后执行 `/gmarket reload`：

```yaml
dynamic-pricing:
  groups-enabled: true
```

关闭后保留分组文件和物品的归组记录，价格沿用单件配置和全局默认。重新开启后恢复使用分组参数。

每组一个文件，放在插件数据目录的 `pricing-groups/` 中。例如 `pricing-groups/farm.yml`：

```yaml
recycle:
  min-price-volume: 16384
  cycle-amplitude: 0.10
shop:
  target-recycled-stock: 16384
  fixed-dynamic-enabled: true
```

这是示例数值：这一组要积累更多回收量才明显降价，时间波动为 ±10%。组内物品使用相同计算方式和参数，各自统计回收量和库存。

游戏内可管理分组，所有修改保存后立即生效：

| 操作 | 命令 |
| --- | --- |
| 列出分组 | `/gmarket group list` |
| 创建分组 | `/gmarket group create <组名>` |
| 查看参数和物品 | `/gmarket group info <组名>` |
| 修改参数 | `/gmarket group set <组名> <shop或recycle> <参数> <值>` |
| 取消参数覆盖 | `/gmarket group set <组名> <shop或recycle> <参数> default` |
| 将现有物品归组或移组 | `/gmarket group add <组名> <shop或recycle> <物品ID>` |
| 手持注册新物品并归组 | `/gmarket group add <组名> <shop或recycle> <新物品ID> <基础价格>` |
| 将物品移出组 | `/gmarket group remove <组名> <shop或recycle> <物品ID>` |
| 删除空组 | `/gmarket group delete <组名>` |

`shop` 表示服务器出售，`recycle` 表示回收。移出组会保留商品、基础价格和库存；彻底移除商品使用原来的 `shop remove` 或 `recycle remove`。组名和新物品 ID 使用小写字母、数字、下划线或短横线，最多 64 个字符。

例如手持一件自定义物品，将它分别加入回收和商店：

```text
/gmarket group create rare
/gmarket group set rare recycle cycle-amplitude 0.05
/gmarket group set rare shop fixed-dynamic-enabled true
/gmarket group add rare recycle magic_gem 500
/gmarket group add rare shop magic_gem 1000
```

注册不会消耗手持物品。基础价格由服主填写，再按所选分组调价；插件不会根据 NBT 自动猜测价值。商店注册后默认无限库存，回收和商店分别归组、分别定价。

名称、附魔、耐久、容器内容、自定义组件和标签会一起保存，匹配时只忽略数量。手持添加时，同款物品在商店或回收站内不能重复登记；商店和回收站可以各登记一次。数据不同的同材质物品可用不同 ID 分别定价。普通材质配置只回收普通物品，避免误收自定义物品。回流商品购买时发放回收来源的物品，固定商品发放注册时保存的物品。

不使用分组时，也能执行 `/gmarket shop addhand <物品ID> <价格>` 或 `/gmarket recycle addhand <物品ID> <价格>`。需要同时归组时，在末尾加组名。原来的手持 `add <价格>` 也会保存完整数据，ID 默认使用材质名；同材质有多个版本时使用 `addhand` 自定 ID。

商品、基础价格和手持保存的数据仍在 `shop.yml`、`recycle.yml` 中，物品下的 `pricing-group` 指向分组文件。参数按“单件 `pricing` → 分组 → 全局默认”逐项取值。分组 `recycle`、`shop` 支持各自全局配置中的价格参数，也支持 `dynamic-enabled`；单件 `pricing` 使用相同字段。固定商品需开启 `fixed-dynamic-enabled` 才会动态调价。

商店的周期和振幅可在组内 `shop` 中设置 `cycle-period-hours`、`cycle-amplitude`；未写时沿用组内 `recycle` 的值，再沿用回收来源（没有来源则按同 ID 回收项或全局默认）。手改文件后执行 `/gmarket reload`。群组服的各子服使用相同的商品和分组文件，游戏内修改只保存到当前子服。

## 服主管理命令

| 功能 | 命令 |
| --- | --- |
| 重载配置 | `/gmarket reload` |
| 手持物品加入商店 | `/gmarket shop add <价格>` |
| 手持完整物品指定 ID 加入商店 | `/gmarket shop addhand <物品ID> <价格> [组]` |
| 指定物品加入商店 | `/gmarket shop add <物品> <价格>` |
| 移除商店商品 | `/gmarket shop remove <物品ID>` |
| 修改商店价格 | `/gmarket shop setprice <物品ID> <买入价>` |
| 设置限量库存 | `/gmarket shop setstock <物品ID> <数量>` |
| 手持物品加入回收站 | `/gmarket recycle add <回收价>` |
| 手持完整物品指定 ID 加入回收站 | `/gmarket recycle addhand <物品ID> <价格> [组]` |
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
| `gensoumarket.admin.group` | 管理定价分组及参数；添加/移出物品还需对应商店或回收编辑权限 |
