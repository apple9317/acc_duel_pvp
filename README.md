# ACCDuel 竞技决斗场

一个面向 **Paper 1.21.x - 26.2** 的竞技决斗场插件（玩法参考布吉岛决斗场），具备：

- **类型化装备配置**：每个竞技类型对应 `plugins/ACCDuel/kits/<类型>.yml` 一个配置文件，高度自定义（附魔、属性修饰符、药水效果、皮革染色、自定义模型数据、无限耐久等全支持）；`/duel arena create <名称> <类型>` 创建竞技场时自动生成默认类型配置（保护 III 钻套 + 锋利 I 钻剑 + 满背包治疗药水）；
- **GUI 操作**：`/duel` 打开类型选择界面（Java 版箱子 / 基岩版原生表单）选择类型加入匹配；`/duelplayer <玩家>` 打开界面选类型后邀请对方；
- **Gson 数据存储**：玩家统计数据存为 JSON（`data/stats.json`），原子写入、自动保存；
- **Geyser 自动检测**：检测到 Geyser-Spigot / Floodgate 后自动识别基岩版玩家，支持 [BE] 前缀、比赛夜视补偿、欢迎提示、基岩版原生表单（反射调用新 Geyser API，失败自动回退箱子界面）；
- **完整决斗玩法**：请求决斗（带 GUI）、随机匹配队列、多回合制（默认 3 局 2 胜）、观战、段位积分（ELO）、排行榜、竞技场保护、防逃跑判负等。

---

## 一、兼容性

| 项目 | 说明 |
|---|---|
| 服务端 | Paper 1.21.x ～ 26.2（同一 jar 通吃） |
| Java | 21 及以上（26.2 服务端需 Java 25，同样能运行本插件） |
| 依赖 | 无硬依赖；可选软依赖 `Geyser-Spigot` / `floodgate-bukkit`（基岩版识别与原生表单） |

兼容性已通过**双端编译验证**：同一份源码分别对 `paper-api 1.21.1-R0.1-SNAPSHOT` 与 `paper-api 26.2.build.121-stable` 编译均通过；版本差异点（`Attribute.GENERIC_*` 重命名、`AttributeModifier` 构造器、附魔/药水注册表等）在兼容层做了反射兜底。

## 二、安装

1. 将 `ACCDuel-1.1.0.jar` 放入服务端 `plugins/` 目录；
2. 启动服务端，首次运行自动生成：
   ```
   plugins/ACCDuel/
   ├── config.yml      # 主配置（规则、消息、Geyser 等）
   ├── arenas.yml      # 竞技场配置
   ├── kits/           # 竞技类型装备配置（/duel arena create 自动生成）
   │   └── no_debuff.yml
   └── data/
       └── stats.json  # 玩家统计（Gson）
   ```
3. 管理员按下方流程配置竞技场，即可使用。

## 三、快速开始（管理员）

```
/duel arena help                       查看创建流程
/duel arena create arena1 no_debuff    创建竞技场（自动生成 kits/no_debuff.yml）
/duel arena set arena1 pos1            站在 1 号战斗出生点执行
/duel arena set arena1 pos2            站在 2 号战斗出生点执行
/duel arena set arena1 spawn red       站在红方进入地图出生点执行
/duel arena set arena1 spawn blue      站在蓝方进入地图出生点执行
/duel arena set arena1 miny 0          低于 Y=0 判负（可选，默认世界最低高度）
/duel arena list                       确认状态（绿色 = 可用）
```

只有一个竞技场时，`/duel arena set pos1` 可省略竞技场名称直接设置。
竞技场要求 `pos1`、`pos2`、`spawn red`、`spawn blue` 齐全且 `enabled: true` 才能开赛；也可以直接编辑 `arenas.yml` 后 `/duel reload`。
流程说明：匹配成功后两名玩家先被传送到地图的红/蓝出生点（进入地图），约 2 秒后移动到 pos1/pos2 战斗点开始倒计时开打；比赛结束恢复回赛前位置（不需要 lobby）。观战者默认传送到红方出生点。

## 四、命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/duel` | 打开类型选择界面（Java 箱子 / 基岩表单），选择类型加入匹配 | accduel.use |
| `/duelplayer <玩家>` | 打开类型选择界面，选类型后邀请对方决斗 | accduel.use |
| `/duel <玩家> [类型]` | 直接发起决斗（不指定类型则打开选择界面） | accduel.use |
| `/duel accept <玩家>` | 接受请求（也可点请求界面绿按钮） | accduel.use |
| `/duel deny <玩家>` | 拒绝请求 | accduel.use |
| `/duel queue [类型]` | 进入指定类型匹配（无类型打开主界面） | accduel.use |
| `/duel leave` | 退出匹配；比赛中则弃权判负 | accduel.use |
| `/duel spectate <玩家>` | 观战进行中的决斗 | accduel.spectate |
| `/duel stats [玩家]` | 查看决斗数据 | accduel.use |
| `/duel top` | 排行榜（按积分） | accduel.use |
| `/duel list` | 当前进行中的决斗 | accduel.use |
| `/duel arena help` | 竞技场创建流程说明 | accduel.admin |
| `/duel arena create <名称> <类型>` | 创建竞技场并自动生成类型配置 | accduel.admin |
| `/duel arena set <名称> <pos1\|pos2\|miny\|spawn red\|spawn blue> [y]` | 设置点位 | accduel.admin |
| `/duel arena remove <名称>` | 删除竞技场 | accduel.admin |
| `/duel arena list` | 竞技场列表 | accduel.admin |
| `/duel reload` | 重载配置（config/arenas/kits） | accduel.admin |

别名：`/jj`、`/pvp`、`/决斗`、`/duelplayer`。

## 五、权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `accduel.use` | true | 使用竞技决斗场（界面/请求/匹配/观战等） |
| `accduel.admin` | op | 竞技场管理、重载 |
| `accduel.spectate` | true | 观战 |
| `accduel.kit.*` | true | 使用全部竞技类型装备配置 |

类型可单独设置权限：某类型 yml 中 `permission: accduel.kit.no_debuff` 后，只有拥有该权限的玩家可使用该类型。

## 六、配置要点（config.yml）

- `match.rounds`：总回合数（奇数最佳，先赢一半以上者胜；`1` = 一局定胜负）
- `match.countdown` / `round-restart-delay`：开局/回合间倒计时
- `match.cancel-outside-damage`：只保留对手造成的伤害（保证公平 1v1）
- `match.fall-damage` / `natural-regen` / `hunger`：关闭摔落伤害 / 自然回血 / 饥饿
- `match.forfeit-on-quit`：比赛中退出服务器判负
- `match.block-commands` + `allowed-commands`：比赛中命令白名单
- `queue`：随机匹配开关与检查间隔
- `rating`：ELO 积分开关、初始值、K 系数
- `geyser.*`：基岩版玩家 [BE] 前缀、夜视补偿、欢迎提示
- `messages.*`：全部提示文案（MiniMessage 格式，支持 `<red>` `<gradient:#55d6ff:#6a5cff>` 等，占位符 `{player}` `{type}` `{score}` 等；`arena-help` 是多行列表）

## 七、竞技类型装备配置（kits/<类型>.yml）

每个文件 = 一个竞技类型，结构如下（`/duel arena create` 自动生成的 no_debuff 模板：保护 III 钻套 + 锋利 I 钻剑 + 满背包治疗药水）：

```yaml
display-name: "<aqua>no_debuff</aqua>"   # 界面显示名
icon: DIAMOND_SWORD                      # 界面图标
description:                             # 界面描述（多行）
  - "<gray>保护 III 钻石套装</gray>"
permission: ""                           # 留空 = 所有人可用
enabled: true
rules:                                   # 对战规则（留空项继承全局）
  health: 20.0
  food-level: 20
attributes:                              # 覆盖玩家属性基础值（可选）
  max-health: 20.0
  movement-speed: 0.12
effects:                                 # 进场附加药水（可选）
  - type: speed
    duration: 999999
    amplifier: 0
items:
  - slot: 0                              # 0-8 快捷栏 / 9-35 背包 / 36-39 盔甲 / 40 副手
    material: DIAMOND_SWORD
    amount: 1
    name: "<aqua>决斗之剑</aqua>"
    lore: ["<gray>竞技场专用</gray>"]
    enchants: { sharpness: 1, unbreaking: 3 }
    unbreakable: true
    custom-model-data: 0
    flags: [HIDE_ENCHANTS, HIDE_UNBREAKABLE]
    attributes:                          # 物品自带属性修饰符（可选）
      - attribute: attack_damage
        amount: 3.0
        operation: add_number            # add_number 加 / add_scalar_multiplier 百分比 / multiply_scalar_1 乘
        slot: HAND                       # 作用部位，可留空
    leather-color: "22AA55"              # 皮革染色（仅皮革装备）
    potion-effects:                      # 药水效果（仅药水物品）
      - type: instant_health
        duration: 1
        amplifier: 0
```

物品名/lore 支持 MiniMessage 或 `&` 颜色代码。属性/附魔/药水 ID 使用 1.13+ 的标准 ID（`sharpness`、`protection`、`instant_health`、`attack_damage`、`movement_speed` 等）。改完 `/duel reload` 生效。

## 八、Geyser（基岩版互通）自动检测

插件启动时自动检测 `Geyser-Spigot` / `Geyser` 与 `floodgate-bukkit`：

- 通过反射调用 GeyserApi / FloodgateApi 识别基岩版玩家（无硬依赖，没装也不影响）；
- 基岩版玩家聊天/排行榜显示 `[BE]` 前缀（`geyser.bedrock-tag` 可改）；
- `/duel` 与 `/duelplayer` 类型选择界面为基岩版玩家发送**原生表单**（下拉选择类型；新 Geyser API `connectionByUuid(...).sendForm(cumulus CustomForm)`，兼容新旧 cumulus 方法名）；任何环节失败自动回退到箱子界面（Geyser 会把箱子界面翻译成基岩 UI，功能不受影响）；
- 比赛中自动附加夜视（`geyser.night-vision-during-match`）补偿基岩版渲染差异；
- 进入服务器时发送欢迎提示（`geyser.welcome-message`）。

## 九、数据存储（Gson）

| 文件 | 内容 |
|---|---|
| `data/stats.json` | 每个玩家：胜/负/平、击杀/死亡、积分（ELO）、连胜、回合数、首末次上线时间 |

写入方式：先写临时文件再原子替换，避免写一半损坏；每场比赛结束立即保存，另有定时自动保存（`stats.autosave-minutes`）。

## 十、构建

```bash
# 默认：paper-api 1.21.1 基线（兼容 1.21.x 全系列）
mvn -o clean package

# 交叉验证：paper-api 26.2 基线（建议 JDK 25）
JAVA_HOME=<jdk25> mvn -o -Ppaper26 clean package
```

产物：`target/ACCDuel-1.1.0.jar`。

## 十一、注意事项

- 竞技场请建在封闭空间（防摔/虚空由 `miny` 与摔落规则兜底）；
- 比赛中玩家会被禁止破坏/放置方块、丢拾物品、使用大部分命令、传送出场地；
- 退出服务器默认判负（可改 `forfeit-on-quit`）；
- 一个竞技场绑定一个类型；想开多种类型就创建多个竞技场。
