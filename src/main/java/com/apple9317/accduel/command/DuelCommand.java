package com.apple9317.accduel.command;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.arena.Arena;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.duel.DuelManager;
import com.apple9317.accduel.duel.Match;
import com.apple9317.accduel.kit.Kit;
import com.apple9317.accduel.kit.KitManager;
import com.apple9317.accduel.stats.PlayerStats;
import com.apple9317.accduel.stats.StatsManager;
import com.apple9317.accduel.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /duel 命令：主界面（选择类型加入匹配）、请求、匹配、观战、竞技场管理、统计。
 * /duelplayer <玩家> 别名指向 /duel <玩家>，打开类型选择界面邀请对方。
 */
public class DuelCommand implements CommandExecutor, TabCompleter {

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final DuelManager manager;
    private final KitManager kits;
    private final StatsManager stats;

    private static final List<String> POS_KEYS = List.of("pos1", "pos2", "miny", "spawn");
    private static final List<String> SPAWN_COLORS = List.of("red", "blue");

    public DuelCommand(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.manager = plugin.getDuelManager();
        this.kits = plugin.getKitManager();
        this.stats = plugin.getStatsManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                manager.openMainMenu(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "accept" -> accept(sender, args);
            case "deny" -> deny(sender, args);
            case "queue" -> queue(sender, args);
            case "leave" -> leave(sender);
            case "spectate", "spec", "watch" -> spectate(sender, args);
            case "stats" -> stats(sender, args);
            case "top", "leaderboard" -> top(sender, args);
            case "arena" -> arena(sender, args);
            case "arenas" -> arenas(sender);
            case "list" -> listMatches(sender);
            case "reload" -> reload(sender);
            default -> {
                if (sender instanceof Player player) {
                    // /duel <玩家> [类型] 或 /duelplayer <玩家>：发起决斗（未指定类型则打开选择界面）
                    request(player, args[0], args.length > 1 ? args[1] : null);
                } else {
                    sendHelp(sender);
                }
            }
        }
        return true;
    }

    // ---------------- 子命令 ----------------

    private void sendHelp(CommandSender sender) {
        config.sendRaw(sender, "help-header");
        help(sender, "help", "查看帮助");
        help(sender, "<玩家> [类型]", "向玩家发起决斗（无类型打开选择界面）");
        help(sender, "accept <玩家>", "接受决斗请求");
        help(sender, "deny <玩家>", "拒绝决斗请求");
        help(sender, "queue [类型]", "进入随机匹配（无类型打开主界面）");
        help(sender, "leave", "退出匹配 / 放弃比赛");
        help(sender, "spectate <玩家>", "观战");
        help(sender, "stats [玩家]", "查看决斗数据");
        help(sender, "top", "查看排行榜");
        help(sender, "arena", "竞技场管理（管理员）");
        help(sender, "reload", "重载配置（管理员）");
        if (sender instanceof Player player) {
            config.sendRaw(sender, "menu-hint");
        }
    }

    private void help(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Txt.mm(config.raw("help-line", Map.of("cmd", cmd, "desc", desc))));
    }

    private void request(Player player, String targetName, String typeArg) {
        Player target = findPlayer(targetName);
        if (target == null) {
            config.send(player, "player-not-found", Map.of("player", targetName));
            return;
        }
        if (typeArg != null && !typeArg.isEmpty()) {
            Kit kit = kits.getType(typeArg);
            if (kit == null) {
                config.send(player, "type-not-found", Map.of("type", typeArg));
                return;
            }
            manager.requestDuel(player, target, kit.id);
            return;
        }
        // 未指定类型：打开类型选择界面，选中后立即发起
        manager.openInviteMenu(player, target);
    }

    /** 玩家名大小写不敏感查找。 */
    private static Player findPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private void accept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        if (args.length < 2) {
            config.send(player, "no-request", Map.of("player", "?"));
            return;
        }
        Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) {
            config.send(player, "player-not-found", Map.of("player", args[1]));
            return;
        }
        manager.acceptRequest(player, requester);
    }

    private void deny(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        if (args.length < 2) return;
        Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) return;
        manager.denyRequest(player, requester);
    }

    private void queue(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        if (args.length >= 2 && !args[1].isEmpty()) {
            Kit kit = kits.getType(args[1]);
            if (kit == null) {
                config.send(player, "type-not-found", Map.of("type", args[1]));
                return;
            }
            manager.joinQueue(player, kit.id);
            return;
        }
        manager.openMainMenu(player);
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        Match match = manager.matchOf(player);
        if (match != null && !match.ended()) {
            manager.handleForfeit(player);
            return;
        }
        manager.leaveQueue(player);
    }

    private void spectate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            config.send(player, "player-not-found", Map.of("player", args[1]));
            return;
        }
        manager.spectate(player, target);
    }

    private void stats(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                config.sendRaw(sender, "player-not-found", Map.of("player", args[1]));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            config.sendRaw(sender, "only-player");
            return;
        }
        PlayerStats ps = stats.get(target.getUniqueId());
        config.sendRaw(sender, "stats-header", Map.of("player", target.getName()));
        stat(sender, "场次", String.valueOf(ps.played()));
        stat(sender, "胜/负/平", ps.wins + " / " + ps.losses + " / " + ps.draws);
        stat(sender, "胜率", ps.winRate() + "%");
        stat(sender, "击杀 / 死亡", ps.kills + " / " + ps.deaths);
        stat(sender, "积分", String.valueOf(ps.rating));
        stat(sender, "回合胜 / 负", ps.roundsWon + " / " + ps.roundsLost);
        stat(sender, "连胜（最佳）", ps.streak + "（" + ps.bestStreak + "）");
    }

    private void stat(CommandSender sender, String key, String value) {
        sender.sendMessage(Txt.mm(config.raw("stats-line", Map.of("key", key, "value", value))));
    }

    private void top(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        List<Map.Entry<UUID, PlayerStats>> list = stats.top(10);
        config.sendRaw(sender, "top-header");
        int rank = 1;
        for (Map.Entry<UUID, PlayerStats> e : list) {
            config.sendRaw(sender, "top-line", Map.of(
                    "rank", String.valueOf(rank),
                    "player", stats.nameOf(e.getKey()),
                    "rating", String.valueOf(e.getValue().rating),
                    "wins", String.valueOf(e.getValue().wins),
                    "losses", String.valueOf(e.getValue().losses)));
            rank++;
        }
    }

    private void listMatches(CommandSender sender) {
        List<Match> matches = manager.activeMatches();
        if (matches.isEmpty()) {
            sender.sendMessage(Txt.mm("<gray>当前没有进行中的决斗。</gray>"));
            return;
        }
        for (Match match : matches) {
            String text = match.player(match.fighter1()).getName() + " vs " + match.player(match.fighter2()).getName()
                    + " <dark_gray>(" + match.kitName() + ")</dark_gray>";
            sender.sendMessage(Txt.mm(text));
        }
    }

    // ---------------- 竞技场管理 ----------------

    private void arena(CommandSender sender, String[] args) {
        if (!sender.hasPermission("accduel.admin")) {
            config.sendRaw(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            arenaHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "help" -> arenaHelp(sender);
            case "create" -> arenaCreate(sender, args);
            case "set" -> arenaSet(sender, args);
            case "remove" -> arenaRemove(sender, args);
            case "list" -> arenas(sender);
            default -> arenaHelp(sender);
        }
    }

    /** /duel arena help：列出竞技场创建流程。 */
    private void arenaHelp(CommandSender sender) {
        config.sendLines(sender, "arena-help");
    }

    /** /duel arena create <名称> [类型]：创建竞技场并在 kits/ 自动生成类型配置。 */
    private void arenaCreate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            config.sendRaw(sender, "arena-create-usage");
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        String type = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "no_debuff";
        if (!id.matches("[a-z0-9_\\-]+") || !type.matches("[a-z0-9_\\-]+")) {
            config.sendRaw(sender, "arena-invalid-name");
            return;
        }
        if (plugin.getArenaManager().contains(id)) {
            config.sendRaw(sender, "arena-exists", Map.of("id", id));
            return;
        }
        boolean typeNew = !kits.hasTypeFile(type);
        kits.ensureType(type);
        plugin.getArenaManager().create(id, type);
        config.sendRaw(sender, "arena-created", Map.of("id", id, "type", type));
        if (typeNew) {
            config.sendRaw(sender, "arena-type-created", Map.of("type", type, "file", "kits/" + type + ".yml"));
        } else {
            config.sendRaw(sender, "arena-type-exists", Map.of("type", type));
        }
    }

    /** /duel arena set <id> <pos1|pos2|miny|spawn red|spawn blue> [y]；只有一个竞技场时可省略 id。 */
    private void arenaSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return;
        }
        var arenas = plugin.getArenaManager();
        String id;
        String pos;
        String[] extra;
        if (args.length >= 3 && POS_KEYS.contains(args[2].toLowerCase(Locale.ROOT))) {
            // /duel arena set pos1 —— 只有一个竞技场时直接使用
            List<Arena> all = arenas.all();
            if (all.size() != 1) {
                config.sendRaw(sender, "arena-set-which", Map.of("pos", args[2].toLowerCase(Locale.ROOT)));
                arenas(sender);
                return;
            }
            id = all.get(0).id;
            pos = args[2].toLowerCase(Locale.ROOT);
            extra = java.util.Arrays.copyOfRange(args, 3, args.length);
        } else if (args.length >= 4 && POS_KEYS.contains(args[3].toLowerCase(Locale.ROOT))) {
            id = args[2].toLowerCase(Locale.ROOT);
            pos = args[3].toLowerCase(Locale.ROOT);
            extra = java.util.Arrays.copyOfRange(args, 4, args.length);
        } else {
            config.sendRaw(sender, "arena-set-usage");
            return;
        }
        switch (pos) {
            case "miny" -> {
                double y;
                if (extra.length > 0) {
                    try {
                        y = Double.parseDouble(extra[0]);
                    } catch (NumberFormatException e) {
                        config.sendRaw(sender, "arena-invalid-y", Map.of("y", extra[0]));
                        return;
                    }
                } else {
                    y = player.getLocation().getY();
                }
                if (arenas.setMinY(id, y)) {
                    config.sendRaw(sender, "arena-set", Map.of("id", id, "pos", "miny=" + y));
                } else {
                    config.sendRaw(sender, "arena-not-found", Map.of("id", id));
                }
            }
            case "spawn" -> {
                if (extra.length < 1 || !SPAWN_COLORS.contains(extra[0].toLowerCase(Locale.ROOT))) {
                    config.sendRaw(sender, "arena-set-usage");
                    return;
                }
                String color = extra[0].toLowerCase(Locale.ROOT);
                if (arenas.setSpawn(id, color, player.getLocation())) {
                    config.sendRaw(sender, "arena-set", Map.of("id", id, "pos", "spawn-" + color));
                } else {
                    config.sendRaw(sender, "arena-not-found", Map.of("id", id));
                }
            }
            default -> {
                if (arenas.setPos(id, pos, player.getLocation())) {
                    config.sendRaw(sender, "arena-set", Map.of("id", id, "pos", pos));
                } else {
                    config.sendRaw(sender, "arena-not-found", Map.of("id", id));
                }
            }
        }
    }

    private void arenaRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            config.sendRaw(sender, "arena-remove-usage");
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (plugin.getArenaManager().remove(id)) {
            config.sendRaw(sender, "arena-removed", Map.of("id", id));
        } else {
            config.sendRaw(sender, "arena-not-found", Map.of("id", id));
        }
    }

    private void arenas(CommandSender sender) {
        List<Arena> list = plugin.getArenaManager().all();
        List<String> parts = new ArrayList<>();
        for (Arena arena : list) {
            String typeTag = arena.type == null ? "" : " <dark_gray>(" + arena.type + ")</dark_gray>";
            if (arena.isComplete()) {
                parts.add("<green>" + arena.id + typeTag + "</green>");
            } else {
                parts.add("<red>" + arena.id + typeTag + "（未完整）</red>");
            }
        }
        config.sendRaw(sender, "arena-list", Map.of("list", parts.isEmpty() ? "<gray>（无）</gray>" : String.join(" ", parts)));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("accduel.admin")) {
            config.sendRaw(sender, "no-permission");
            return;
        }
        plugin.reloadAll();
        config.sendRaw(sender, "reloaded");
    }

    // ---------------- Tab 补全 ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"help", "accept", "deny", "queue", "leave", "spectate", "stats", "top", "arena", "list", "reload"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept", "deny", "spectate" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
                }
            }
            case "queue" -> {
                for (Kit kit : kits.getTypes()) {
                    if (kit.id.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(kit.id);
                }
            }
            case "arena" -> {
                if (args.length == 2) {
                    for (String s : new String[]{"help", "create", "set", "remove", "list"}) {
                        if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
                    }
                } else if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("set")) {
                        for (String s : POS_KEYS) {
                            if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
                        }
                    }
                    if (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove")) {
                        for (Arena arena : plugin.getArenaManager().all()) {
                            if (arena.id.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(arena.id);
                        }
                    } else if (args[1].equalsIgnoreCase("create")) {
                        for (Kit kit : kits.getTypes()) {
                            if (kit.id.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(kit.id);
                        }
                        out.add("no_debuff");
                    }
                } else if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                    for (String s : POS_KEYS) {
                        if (s.startsWith(args[3].toLowerCase(Locale.ROOT))) out.add(s);
                    }
                } else if (args.length == 5 && args[1].equalsIgnoreCase("set") && args[3].equalsIgnoreCase("spawn")) {
                    for (String s : SPAWN_COLORS) {
                        if (s.startsWith(args[4].toLowerCase(Locale.ROOT))) out.add(s);
                    }
                }
            }
            default -> {
                if (sender instanceof Player player) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (!p.equals(player) && p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                            out.add(p.getName());
                        }
                    }
                }
            }
        }
        return out;
    }
}
