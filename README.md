# ACCDuel 竞技决斗场

面向 **Paper 1.21.x - 26.2** 的竞技决斗场插件（玩法参考布吉岛决斗场）。

## 特性

- **类型化装备**：每个竞技类型对应 `kits/<类型>.yml`，支持附魔、属性修饰符、药水效果、皮革染色、自定义模型数据、无限耐久等，高度自定义。
- **多种界面**：`/duel` 打开类型选择——1.21.6+ 客户端默认用原生屏幕对话框，低版本/关闭后用箱子界面，基岩版用原生表单。
- **BedFight 起床单挑**：挖掉对方的床使其无法复活，再击败对方获胜；床在时死亡 3 秒后回到出生点并复原装备。
- **个人设置**：`/duelsetting` 开关决斗申请、切换新版本 UI、选择个人击杀特效。
- **击杀特效与嘲讽**：击杀特效纯个人设置；结算后随机播报一句温和有趣的嘲讽。
- **地形复原**：`save` 保存世界模板，比赛结束自动复原；比赛中仅可破坏玩家自己放置的方块，水桶/岩浆桶可自由使用。
- **Gson 存储**：统计与个人设置存为 JSON，原子写入、自动保存。
- **自动检测**：反射检测 Geyser/Floodgate（基岩版）与 ViaVersion（客户端版本），无硬依赖。
- **完整玩法**：请求决斗、随机匹配、多回合制（默认三局两胜）、观战、ELO 积分、排行榜、防逃跑判负。

---

## 一、兼容性

| 项目 | 说明 |
|---|---|
| 服务端 | Paper 1.21.x ～ 26.2（同一 jar 通吃） |
| Java | 21 及以上（26.2 服务端需 Java 25） |
| 软依赖 | `Geyser-Spigot` / `floodgate-bukkit`（基岩版）、`ViaVersion`（客户端版本） |

同一份源码分别对 paper-api 1.21.1 与 26.2 编译均通过；新版本才有的 API（屏幕对话框等）通过反射调用，在低版本上自动回退。

## 二、安装与目录

把 `ACCDuel-1.1.0.jar` 放入 `plugins/`，首次运行自动生成：

```
plugins/ACCDuel/
├── config.yml          # 主配置
├── arenas.yml          # 竞技场配置
├── kits/               # 竞技类型装备
│   ├── no_debuff.yml
│   └── bedfight.yml
├── Language/           # 语言文件
│   ├── zh_cn.yml
│   └── en.yml
└── data/
    ├── stats.json      # 玩家统计（Gson）
    ├── settings.json   # 个人设置（Gson）
    └── templates/      # 竞技场世界模板
```

## 三、创建竞技场（管理员）

输入 `/duel arena help` 可在游戏内查看流程。

**普通类型（以 no_debuff 为例）**

```
第一步  /duel arena create arena1 no_debuff   创建竞技场并生成装备配置
第二步  站到区域一角：/duel arena set arena1 pos1
第三步  站到区域另一对角：/duel arena set arena1 pos2
第四步  站到红方出生点：/duel arena set arena1 spawn red
第五步  站到蓝方出生点：/duel arena set arena1 spawn blue
第六步  /duel arena set arena1 save          保存世界模板（赛后复原）
最后    /duel arena list                      显示绿色即可用
```

**BedFight 类型额外一步**

```
/duel arena create bed1 bedfight
... 同样设置 pos1 / pos2 / spawn red / spawn blue
站到红方床旁：/duel arena set bed1 bed red    （自动寻找最近的床）
站到蓝方床旁：/duel arena set bed1 bed blue
/duel arena set bed1 save
```

可选：`/duel arena set <名称> miny [y]` 设置虚空判负线（默认世界最低高度）。
只有一个竞技场时可省略名称，例如 `/duel arena set pos1`。

匹配成功后，两名玩家被直接传送到红/蓝出生点，倒计时后开打（不需要大厅）。比赛结束恢复到赛前位置与状态。

## 四、命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/duel` | 打开类型选择并加入匹配 | accduel.use |
| `/duelplayer <玩家>` | 选类型后邀请对方决斗 | accduel.use |
| `/duel <玩家> [类型]` | 直接发起决斗 | accduel.use |
| `/duel accept <玩家>` | 接受请求（也可点聊天按钮） | accduel.use |
| `/duel deny <玩家>` | 拒绝请求 | accduel.use |
| `/duel leave` | 退出匹配；比赛中则弃权 | accduel.use |
| `/duel spectate <玩家>` | 观战 | accduel.spectate |
| `/duel stats [玩家]` | 查看决斗数据 | accduel.use |
| `/duel top` | 排行榜 | accduel.use |
| `/duelsetting` | 个人设置 | accduel.use |
| `/duel arena help` | 创建流程 | accduel.admin |
| `/duel arena create <名称> <类型>` | 创建竞技场 | accduel.admin |
| `/duel arena set <名称> <pos1\|pos2\|spawn red\|spawn blue\|bed red\|bed blue\|save\|miny>` | 设置点位 | accduel.admin |
| `/duel arena remove <名称>` | 删除竞技场 | accduel.admin |
| `/duel arena list` | 竞技场列表 | accduel.admin |
| `/duel reload` | 重载全部配置 | accduel.admin |

别名：`/jj`、`/pvp`、`/决斗`、`/duelplayer`。

## 五、权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `accduel.use` | true | 使用基础功能 |
| `accduel.spectate` | true | 观战 |
| `accduel.admin` | op | 竞技场管理、重载 |
| `accduel.kit.*` | true | 使用全部类型 |

类型可单独设置权限：在该类型 yml 中写 `permission: accduel.kit.xxx`。

## 六、配置要点（config.yml）

- `language`：语言，`zh_cn` 或 `en`（对应 Language 文件夹）。
- `match.rounds`：回合数（奇数最佳；`1` = 一局定胜负）。BedFight 不受此限制。
- `match.countdown` / `round-restart-delay`：开局倒计时 / 回合间隔。
- `match.cancel-outside-damage`：只保留对手造成的伤害。
- `match.fall-damage` / `natural-regen` / `hunger`：摔落伤害 / 自然回血 / 饥饿开关。
- `match.forfeit-on-quit`：比赛中退出是否判负。
- `match.block-commands` + `allowed-commands`：比赛中命令白名单。
- `queue` / `request-timeout`：匹配队列与请求有效期。
- `rating`：ELO 开关、初始分、K 系数。

所有提示文案在 `Language/` 中，可用 MiniMessage 格式（`<red>`、`<gradient:#55d6ff:#6a5cff>` 等），占位符用 `{player}` `{score}` 等。

## 七、竞技类型装备（kits/<类型>.yml）

```yaml
display-name: "<aqua>no_debuff</aqua>"   # 显示名
icon: DIAMOND_SWORD                      # 图标
description: ["<gray>描述</gray>"]       # 描述
permission: ""                           # 留空 = 所有人可用
enabled: true
rules:
  health: 20.0
  food-level: 20
items:
  - slot: 0
    # 槽位：0-8 快捷栏 / 9-35 背包 / 36 靴子 37 护腿 38 胸甲 39 头盔 / 40 副手
    material: DIAMOND_SWORD
    amount: 1
    name: "<aqua>决斗之剑</aqua>"
    enchants: { sharpness: 1 }
    unbreakable: true
    flags: [HIDE_ENCHANTS]
```

药水用 `base-potion: healing`（决定外观与效果），否则会显示为普通水瓶。改完 `/duel reload` 生效。

## 八、BedFight 规则

- 双方各有一张床，床在时死亡后 3 秒回到出生点、复原 `kits/bedfight.yml` 装备。
- 床被对方挖掉后，该玩家死亡即被淘汰。
- 一方床毁且本人被击败，另一方获胜。
- 床只能手动挖（爆炸受区域保护不破坏床）。

## 九、个人设置（/duelsetting）

- **决斗申请**：关闭后他人无法邀请你。
- **新版本 UI**：仅对 1.21.6+ 客户端有效，关闭后使用箱子界面。
- **击杀特效**：闪电/爆炸/爱心/灵魂/火焰/死亡烟雾/无，仅对你击杀他人生效。

## 十、构建

```bash
# 默认基线 paper-api 1.21.1（兼容 1.21.x 全系列），需 JDK 21
mvn -o clean package

# 交叉验证 paper-api 26.2，需 JDK 25
JAVA_HOME=<jdk25> mvn -o -Ppaper26 clean package
```

产物：`target/ACCDuel-1.1.0.jar`。

## 十一、注意事项

- 竞技场建议建在封闭空间，虚空由 `miny` 兜底。
- 比赛中仅可破坏玩家自己放置的方块；桶可自由倒、接。
- 旧的 config.yml / arenas.yml 不会自动追加新字段，必要时删除重新生成，或手动补 `language` 等选项。
- 一个竞技场绑定一个类型，多开类型请创建多个竞技场。
