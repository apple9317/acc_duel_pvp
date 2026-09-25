package com.apple9317.accduel.arena;

import com.apple9317.accduel.ACCDuelPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 竞技场方块模板（Gson，data/templates/&lt;id&gt;.json）。
 *
 * <p>管理员执行 {@code /duel arena set <id> save} 时，把 pos1-pos2 区域内
 * 所有非空气方块（含 BlockData 状态）抓取保存；每场比赛结束 cleanup 时
 * 用模板复原区域，玩家放置/破坏的方块全部还原。</p>
 */
public class ArenaTemplate {

    /** 模板所在世界名。 */
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    /** 非空气方块：坐标 key（x,y,z） -> BlockData 字符串。 */
    public Map<String, String> blocks = new LinkedHashMap<>();

    public static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** 从竞技场当前区域抓取模板；点位不足 / 世界未加载返回 null。 */
    public static ArenaTemplate capture(Arena arena) {
        int[] b = arena.blockBounds();
        if (b == null) return null;
        World w = arena.primaryWorld();
        if (w == null) return null;

        ArenaTemplate t = new ArenaTemplate();
        t.world = w.getName();
        t.minX = b[0];
        t.minY = b[1];
        t.minZ = b[2];
        t.maxX = b[3];
        t.maxY = b[4];
        t.maxZ = b[5];
        for (int x = t.minX; x <= t.maxX; x++) {
            for (int y = t.minY; y <= t.maxY; y++) {
                for (int z = t.minZ; z <= t.maxZ; z++) {
                    Block block = w.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    t.blocks.put(key(x, y, z), block.getBlockData().getAsString());
                }
            }
        }
        return t;
    }

    /**
     * 复原区域：模板中有的位置设为对应方块，其余设为空气。
     *
     * @return 发生变化的方块数量；世界未加载返回 -1。
     */
    public int restore(ACCDuelPlugin plugin) {
        World w = Bukkit.getWorld(world);
        if (w == null) return -1;
        BlockData air = Bukkit.createBlockData("minecraft:air");
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = w.getBlockAt(x, y, z);
                    String data = blocks.get(key(x, y, z));
                    if (data != null) {
                        BlockData bd = Bukkit.createBlockData(data);
                        if (!block.getBlockData().matches(bd)) {
                            block.setBlockData(bd, false);
                            changed++;
                        }
                    } else if (!block.getType().isAir()) {
                        block.setBlockData(air, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    public int blockCount() {
        return blocks.size();
    }

    /** Gson 原子写入。 */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            gson.toJson(this, writer);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 读取模板；文件不存在返回 null。 */
    public static ArenaTemplate load(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, ArenaTemplate.class);
        }
    }
}
