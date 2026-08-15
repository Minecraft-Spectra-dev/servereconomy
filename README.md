# ServerEconomy（服务器经济系统）

一个 **Fabric 服务端**经济模组，面向 **Minecraft 26.2**（要求 **Java 25** 与 **Fabric API**）。它提供完整的货币经济体系：**收入**（每日任务、工程验收、物品回收、交易）、**支出**（假人计费、飞行计费、地标、额外账号槽位、交易手续费），以及玩家间的**转账、红包与交易市场**。

此外还提供 Tab 列表、称号、TPA、家、返回和戴帽子等服务器常用功能。

> 本模组仅需安装在**服务端**，客户端无需安装。
> 货币默认名称为 **dollar（$）**，精度默认 **2 位小数**，均可配置（见 [配置](#config)）。

---

## 特性一览

- **每日任务**：每人每天从任务池独立抽取任务，独立进度、实时计分板，完成自动发奖。
- **工程验收**：玩家提交坐标申请，管理员验收后发放奖励。
- **物品回收**：`/sell` 出售，全局限量、价格随供应量动态下调。
- **交易市场**：原版箱子界面，出售 / 求购两种订单，2% 手续费。
- **红包**：拼手气 / 普通均分，聊天栏一键抢。
- **消耗项**：假人计费、飞行按秒计费、公共地标、额外账号槽位、扩充个人地标。
- **点对点转账**：`/pay` 无手续费。
- **服务器常用功能**：Tab 列表、称号、TPA、家、返回、戴帽子。

---

## 安装与快速开始

1. 准备 Minecraft 26.2、Fabric Loader 0.19.3 或更高版本、Fabric API，以及 Java 25 或更高版本。
2. 将 `servereconomy-<version>.jar` 放入服务端 `mods/` 目录。
3. 启动服务器，模组会自动生成 `config/servereconomy.json` 和默认 SQLite 数据库。
4. 按需调整货币、费率、任务、回收和数据库配置。
5. 重启服务器，或由 OP/控制台执行 `/eco reload` 应用支持热加载的配置。
6. 管理员可使用 `/eco recycle add ...` 配置可回收物品，并使用 `/warp add ...` 配置公共地标。

> 本模组为服务端模组，客户端无需安装。

# 🎮 玩家指南

## 一、基础命令（余额 / 转账）

| 命令 | 说明 |
| --- | --- |
| `/balance` 或 `/bal` 或 `/money` | 查看自己的余额 |
| `/balance <玩家>` | 管理员查看他人余额 |
| `/pay <玩家> <金额>` | 点对点转账（**无手续费**） |
| `/redpacket <lucky\|normal> <总额> <份数>` | 发红包（`lucky`=拼手气随机、`normal`=普通均分，无手续费） |
| `/redpacket grab <id>` | 抢红包（发布者也可抢） |

## 二、如何赚钱（收入）

### 1. 每日任务
- 每个现实日，系统会从**任务池**随机为你抽取 `tasks.dailyCount`（默认 **5**）个任务，进度与奖励**每人独立**。
- `/task` 查看今日任务；**计分板实时刷新**（只显示你自己的任务）。
- 任务类型包括：`kill`（击杀）、`mine`（挖掘方块）、`use`（使用物品，如拉弓、吃金苹果、扔末影珍珠等）、`consume`（消耗）、`reach`（到达某坐标）。
- 完成一项即自动把奖励发到你的余额。

### 2. 工程验收奖励（贡献申请）
- `/build submit <x> <y> <z> [说明]`：为自动机器、公共设施或大型建筑提交验收申请（坐标支持 `~` 相对坐标，可附带说明）。
- 管理员验收通过后会发放奖励给你。

### 3. 物品回收
- 手持可回收物品，输入 `/sell [数量]` 出售给服务器。
- 回收**数量有限**（全局额度），单价随全服累计供应量**动态下调**，且不低于地板价；回收价通常低于玩家市场价。
- 可回收物品默认**为空**，由管理员用 `/eco recycle add` 添加后才可出售。

### 4. 交易市场出售
- 在 [交易市场](#market) 上架物品，成交后即获得货款（扣除 2% 手续费）。

## 三、怎么花钱（支出）

| 项目 | 说明 |
| --- | --- |
| **假人计费** | 每名玩家免费 **2** 个假人，超出部分按 **/小时/个** 计费（默认 5$/时/个）。安装 **Carpet** 时，模组会尝试识别在线假人并按持有者计费，无需手动命令；召唤超出免费额度的假人时会提示。不同 Carpet 版本若无法解析持有者，会回退到假人自身 UUID。 |
| **额外账号槽位** | 免费 **1** 个，超出部分可用 `/accountslot buy` 一次性购买（默认 2000$/个）。 |
| **飞行权限** | `/fly` 开关；开启后按**秒**实时扣费（默认 0.01$/秒，高精度扣除）。余额不足会自动禁用飞行。 |
| **公共地标** | 传送到服务器设置的公共地标需消耗货币，见 `/warp list`。 |
| **个人地标** | 默认 **5** 个，可用 `/buyhome [数量]` 以货币扩充（默认 200$/个）；传送个人地标免费。 |
| **称号** | 用 `/buytitle <称号>` 自助购买/修改称号：首次 **500$**，之后每次修改 **200$**（可在配置调整）；`/buytitle clear` 免费清除。称号支持 `&` 颜色码与 `<gradient:#A:#B:...#C>` 多色渐变（三色及以上）。 |
| **交易手续费** | 玩家市场成交金额 **2%** 作为手续费归服务器（卖家实收 98%）。 |

## 四、家 / 个人地标

- `/home [<名称>]`：传送到默认家或指定家（免费）。
- `/sethome [<名称>]`：在当前位置保存家（不填名称时保存为 `home`）。
- `/delhome [<名称>]`、`/homes`、`/renamehome <旧> <新>`：删除 / 列出 / 重命名家。
- 家名称同样支持中文；位于命令**末尾**的名称可直接输入（如 `/home 主城`、`/sethome 主城`），位于命令**中间**（`/renamehome <旧> <新>`）且含中文或空格时请用引号（如 `/renamehome "主城" "新家"`）。
- `/buyhome [数量]`：用货币扩充个人地标上限（不填数量默认 1 个，200$/个）。

<a id="market"></a>

## 五、交易市场

原版箱子界面，`/market` 打开。格子显示**原始物品（NBT 完整保留）**，tooltip 追加价格 / 数量 / 商家。

- **出售**：手持物品 `/market sell <单价> [数量]`（数量可超过一组，手持不足时自动从背包取走完全相同的物品），买家点击商品即可选中。
- **求购**：`/market buylist <单价> [数量] [物品id]`（**物品可留空**，留空时按手持物品收购），发布时**预支货款托管**；`/market fulfill <id>` 供货收款；`/market cancel <id>` 取消并**全额退回**。
- `/market list [页码]`：分页查看在售/求购订单，**每页固定 10 条**，聊天栏中的物品以**可悬停查看的物品**形式展示（不再只是物品名或 id）；`/market buy <id>` 购买；`/market fulfill <id>` 供货。
- **购买/供货数量**：在 `/market` 界面点击任意商品即**选中**该商品，随后关闭界面并在**聊天栏输入数量**（购买出售单 / 向求购单供货）；一次只允许选中一个商品，避免误操作。聊天提示会展示该商品详细信息，其中物品同样以**可悬停查看的物品**形式展示（单价、数量、商家照常显示）。
- 界面底部按钮：**筛选**（全部 → 出售 → 求购）与**排序**（最新 → 价格从低到高 → 价格从高到低），点击即可切换；默认不筛选、按最新排序。
- `/mymarket`：打开「我的商品」界面，可对自己的订单进行 **下架 / 改价 / 补货**（改价与补货会在聊天栏输入数值），也可用 `/market restock <id> [数量]` 直接补货；补货与上架一样会从背包取走完全相同的物品。
- 求购到货等物品会进入**邮箱**：`/mails` 打开邮箱领取（离线或背包满时也会暂存在邮箱）。
- 成交金额 **2%** 作为手续费归服务器。

## 六、服务器常用功能

- **Tab 列表**：管理员使用 `/eco tab header <文本>` 和 `/eco tab footer <文本>` 设置头部/尾部文本；使用 `/eco tab clear <header|footer|all>` 清除。
- **称号**：管理员使用 `/eco title <玩家> set <称号>` 设置、`/eco title <玩家> clear` 清除；玩家可用 `/buytitle <称号>` 自助购买/修改（首次 **500**，之后每次 **200**，可配置），`/buytitle clear` 免费清除；聊天、名字、Tab 均显示称号前缀，玩家参数支持选择器。称号文本支持 `&` 颜色码与 `<gradient:#A:#B:...#C>` 多色渐变（**三色及以上**）。
- **TPA**：`/tpa <玩家>`、`/tpahere <玩家>`、`/tpaccept`、`/tpdeny`、`/tpacancel`（2 分钟过期，带同意/拒绝按钮）。
- **返回**：`/back`（死亡或传送后记录）。
- **戴帽子**：`/hat`。

## 七、玩家命令速查

```
/balance /bal /money          查看余额
/baltop                       查看余额排行榜
/pay <玩家> <金额>            转账（无手续费）
/redpacket lucky|normal <总额> <份数>   发红包
/redpacket grab <id>          抢红包
/task                         查看今日任务
/sell [数量]                  出售手持物品给服务器
/market                       打开交易市场
/market sell <单价> [数量]    上架出售
/market buylist <单价> [数量] [物品id]  发布求购（物品可留空，留空按手持物品）
/market fulfill <id>          供货收款
/market cancel <id>           取消求购（全额退回）
/market list [页码]           分页查看订单（每页10条）
/market buy <id>              购买
/market restock <id> [数量]    给自己的订单补货
/mymarket                     管理我的商品（下架/改价/补货）
/mails                        打开邮箱
/fly                          飞行开关（按秒扣费）
/buyhome [数量]              扩充个人地标槽位（默认 1 个）
/buytitle <称号>              购买/修改称号（首次 500，之后每次 200）
/buytitle clear               清除称号（免费）
/accountslot buy              购买额外账号槽位
/build submit <x> <y> <z> [说明]   提交工程验收申请
/warp list                    查看公共地标
/warp tp <名>                 传送到公共地标（消耗货币）
/tpa /tpahere /tpaccept /tpdeny /tpacancel   TPA 传送
/home [<名称>]                 传送到家
/sethome /delhome /homes /renamehome        家（别名）
/back                         返回死亡/传送前位置
/hat                          戴帽子
```

---

# 🛠 管理员指南

## 一、经济管理命令

```
/eco balance <玩家>            查看余额
/eco give  <玩家> <金额>       发钱
/eco take  <玩家> <金额>       扣钱
/eco set   <玩家> <金额>       设置余额
/eco reload                   重新加载配置（热重载）
/eco stats                    经济概况（账户数、总发行量、回收、在售、启用任务）
/eco tab header <文本>         设置 Tab 头部文本
/eco tab footer <文本>         设置 Tab 尾部文本
/eco tab clear <header|footer|all>  清除 Tab 文本
/eco title <玩家> set <称号>    设置玩家称号（支持选择器）
/eco title <玩家> clear         清除玩家称号（支持选择器）
/eco log <玩家>                查看玩家流水（最近 20 条）
/eco homelimit <玩家> <数量>    设置个人地标上限
/eco buildlist                查看待验收的工程申请
/eco buildapprove <id> <金额>  验收并发放奖励给提交者
/eco buildreject <id>          驳回验收申请
/eco buildreward <玩家> <金额>  工程验收奖励（管理员直发）
```

## 二、任务管理

### 任务池（全体玩家共享的任务模板）
```
/eco task add <kill|mine|use|consume> <目标id> <次数> <奖励>   添加击杀/挖掘/使用/消耗任务
/eco task add reach <x y z> <维度> <次数> <奖励>               添加到达坐标任务（支持 ~ 相对坐标）
/eco task list                列出任务池
/eco task del <id>            删除任务
```
- 目标 id 示例：击杀 `minecraft:zombie`、挖掘 `minecraft:diamond_ore`、使用 `minecraft:ender_pearl`。
- 任务池中的任务会按 `tasks.dailyCount`（默认 5）随机分配给每名玩家作为**每日任务**。

### 每日任务（按玩家独立管理）
```
/eco dailytask refresh <玩家> [数量]        重新为玩家抽取每日任务
/eco dailytask add <玩家> <任务id>          为玩家手动添加某任务
/eco dailytask remove <玩家> <任务id>       移除玩家某任务
/eco dailytask progress <玩家> <任务id> <增量>   调整进度（可正可负，完成后自动发奖）
/eco dailytask view <玩家>                  查看玩家今日任务与进度
```
- `<玩家>` 支持选择器（如 `@a`），可批量操作。
- 计分板语言由 `scoreboardLanguage` 控制（默认 `zh_cn`，可选 `en_us`）。

## 三、物品回收管理

```
/eco recycle list                查看全部可回收物品及其基价/地板/降幅/现价
/eco recycle add <物品> <基价> <地板> <降幅%>   添加可回收物品（候选为整个物品集）
/eco recycle remove <物品>       移除可回收物品
/eco recycle set <物品> <base|floor|decay> <值>   修改某项参数
/eco price <物品> <基价>         快捷调整某物品的回收基价
```
- **回收默认关闭**（`sellableItems` 为空），需管理员手动添加后才可 `/sell`。
- 参数说明：
  - `基价 base`：供应量为 0 时的单价。
  - `地板 floor`：价格无论如何不跌破的下限。
  - `降幅 decay`：每累计卖出 1 个单位，价格下降的百分比（如 `0.05` 表示每单位降 0.05%）。
- 回收有**全局额度上限** `sell.globalMaxSupply`（默认 10000），并支持 `dynamicPricing` 动态调价。

## 四、地标管理

- **公共地标**（玩家传送需消耗货币）：
  ```
  /warp add <名> [费用]         添加公共地标（未填费用时用 rates.publicLandmarkCost）
  /warp rename <旧名> <新名>     重命名公共地标
  /warp del <名>                 删除公共地标
  ```
- 地标名称支持中文；位于命令**末尾**的名称可直接输入（如 `/warp tp 主城`、`/warp del 主城`），位于命令**中间**（`/warp add`、`/warp rename`）且含中文或空格时请用引号（如 `/warp rename "主城" "新主城"`）。
- **个人地标**：默认上限 `landmarks.defaultPersonalLimit`（5），玩家可用 `/buyhome` 扩充；管理员可用 `/eco homelimit <玩家> <数量>` 直接设置（设为 5 即恢复默认）。

<a id="config"></a>

## 五、配置

配置文件为服务端 `config/servereconomy.json`，首次启动自动生成。修改后由 OP 或控制台执行 `/eco reload` 重新加载配置。

> 注意：`currencyDecimals` 只在服务器启动时初始化，修改该字段需要重启服务器；其它费率、任务和回收配置可通过 `/eco reload` 重新读取。金额精度目前限制为 `0`～`2` 位小数。管理员命令仅 OP 或控制台可执行。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `currencyName` | `dollar` | 货币名称 |
| `currencyAbbreviation` | `$` | 货币缩写 |
| `currencyDecimals` | `2` | 金额小数精度（支持 `0`～`2`，修改后需重启） |
| `scoreboardLanguage` | `zh_cn` | 每日任务计分板语言（`zh_cn` / `en_us`） |
| `tabHeader` / `tabFooter` | `""` | Tab 列表头部/尾部文本，也可用 `/eco tab` 管理 |
| `rates.fakePlayerHourly` | `5.00` | 超额假人每小时费用（每个） |
| `rates.accountSlotPrice` | `2000.00` | 每个额外账号槽位价格 |
| `rates.landmarkSlotPrice` | `200.00` | 每个扩充个人地标槽位价格 |
| `rates.flightPerSecond` | `0.01` | 飞行每秒费用 |
| `rates.titleFirstPurchase` | `500.00` | 玩家首次购买称号费用（/buytitle） |
| `rates.titleChange` | `200.00` | 玩家之后每次修改称号费用（/buytitle） |
| `rates.tradeFeePercent` | `2.00` | 市场成交手续费百分比（卖家实收 98%） |
| `rates.publicLandmarkCost` | `1.00` | 公共地标默认传送费用（新建地标未指定时的默认值） |
| `rates.taskReachRadius` | `8` | `reach` 任务判定半径（格） |
| `fakePlayers.freePerPlayer` | `2` | 每人免费假人数量 |
| `accountSlots.freeSlots` | `1` | 每人免费额外账号槽位 |
| `landmarks.defaultPersonalLimit` | `5` | 默认个人地标数量上限 |
| `trade.maxListingsPerPlayer` | `20` | 每名玩家最多同时在架的订单数 |
| `tasks.dailyCount` | `5` | 每人每天抽取的每日任务数 |
| `sell.globalMaxSupply` | `10000` | 每种物品回收的全局额度上限 |
| `sell.dynamicPricing` | `true` | 是否按供应量动态调价 |
| `sellableItems` | `[]` | 可回收物品列表（默认空，需管理员添加） |
| `database` | 见下 | 数据库后端配置 |
| `database.transactionRetentionDays` | `90` | 流水保留天数；启动时清理更早记录，设为 `0` 表示永久保留 |

## 六、数据库后端

- **SQLite（默认）**：本地文件 `config/servereconomy/economy.db`，无需额外配置。
- **MySQL（可选）**：在 `servereconomy.json` 的 `database` 段配置。使用 MySQL 前请先创建数据库（schema），模组启动时会自动建表：

```json
"database": {
  "type": "mysql",
  "host": "127.0.0.1",
  "port": 3306,
  "database": "servereconomy",
  "username": "root",
  "password": "",
  "extraParams": "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
}
```
- `type`：`"sqlite"`（默认，本地文件，其它配置忽略）或 `"mysql"`。
- 使用 MySQL 时，数据库（schema）需**预先创建**；`extraParams` 为追加到 JDBC URL 的额外连接参数（`&` 分隔）。
- 所有 SQL 都在专用后台线程执行，服务端主线程只会做有上限的等待；数据库卡顿时会触发熔断快速失败，避免服务器 Watchdog 崩溃。飞行扣费、每日任务进度等高频路径已改为异步/批量落库。
- 未在 `extraParams` 中显式配置时，MySQL 连接会自动追加 `connectTimeout=5000&socketTimeout=30000`，防止数据库 Worker 被卡死。
- 请使用专用数据库账号，并不要把生产密码提交到 Git 仓库；切换 SQLite / MySQL 前请先备份数据。
- SQLite 与 MySQL 的表结构/行为一致，切换后端无需改动命令或玩法；但数据库中的已有数据不会自动迁移。

### 数据表
余额 `balances`、流水 `transactions`、回收供应 `supply`、地标 `landmarks`、玩家元数据 `player_meta`、称号 `titles`、任务池 `tasks`、每日任务 `daily_tasks`、市场 `market_listings`、红包 `redpackets` / `redpacket_taken`、邮箱 `mailbox`、工程验收 `build_requests`。

## 七、构建与部署

```
./gradlew build
```
产物：`build/libs/servereconomy-<version>.jar`，放入服务端 `mods/` 目录（需 **Fabric API**，Java 25 / Minecraft 26.2）。

### 运行测试

- `./gradlew test`：运行单元测试和 SQLite 集成测试；MySQL 集成测试在数据库不可用时会自动跳过。
- MySQL 集成测试默认连接 `127.0.0.1:3306`（root / 空密码），连接失败时**自动跳过**而不使构建失败；连接参数可用 `-P` 覆盖，例如：
  ```
  ./gradlew test -Pservereconomy.test.mysql.host=127.0.0.1 -Pservereconomy.test.mysql.port=3307 \
      -Pservereconomy.test.mysql.user=root -Pservereconomy.test.mysql.password=secret
  ```
  测试会重建专用的 `servereconomy_test` 数据库，请勿指向生产库。

CI 使用 Ubuntu 和 Java 25 执行 `./gradlew build`，构建产物位于 `build/libs/`，文件名格式为 `servereconomy-<version>.jar`。

## 八、目录结构

```
src/main/java/cn/choosec/economy/
├── ServerEconomy.java           模组入口（初始化、事件、计分板、市场输入）
├── command/                     命令注册：EconomyCommands / LegacyCommands / EcoSuggestions
├── config/                      配置加载与数据模型（ConfigManager / EconomyConfig）
├── database/                    SQLite / MySQL 数据库管理
├── economy/                     账本式经济 API（EconomyService / MoneyUtil）
├── mixin/                       Mixin（显示名、物品消耗等）
├── model/                       数据模型
├── service/                     各功能服务（交易、任务、红包、回收、邮箱、地标、假人、玩家等）
├── ticker/                      定时计费与任务刷新
├── ui/                          原版箱子菜单（市场 / 邮箱）
└── util/                        消息与任务名称工具
```

---

> 货币采用**账本式 API 设计**（`has/get/set/add/remove/transfer`，类似 Vault Economy 接口），金额一律使用 `BigDecimal` 避免浮点误差，便于与其它经济插件对接。
