package com.apple9317.accduel;

import com.apple9317.accduel.arena.ArenaManager;
import com.apple9317.accduel.command.DuelCommand;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.duel.DuelManager;
import com.apple9317.accduel.geyser.GeyserManager;
import com.apple9317.accduel.gui.GuiManager;
import com.apple9317.accduel.kit.KitManager;
import com.apple9317.accduel.listener.Listeners;
import com.apple9317.accduel.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ACCDuel 竞技决斗场
 * 支持 Paper 1.21.x - 26.2；装备高度自定义（含默认配置）；数据 Gson 存储；Geyser 自动检测。
 */
public final class ACCDuelPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private ArenaManager arenaManager;
    private KitManager kitManager;
    private StatsManager statsManager;
    private GeyserManager geyserManager;
    private GuiManager guiManager;
    private DuelManager duelManager;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();

        configManager = new ConfigManager(this);
        configManager.load();

        arenaManager = new ArenaManager(this);
        arenaManager.load();

        kitManager = new KitManager(this, configManager);
        try {
            kitManager.load();
        } catch (Exception e) {
            getLogger().severe("加载装备方案数据失败: " + e.getMessage());
        }

        statsManager = new StatsManager(this, configManager);
        try {
            statsManager.load();
        } catch (Exception e) {
            getLogger().severe("加载统计数据失败: " + e.getMessage());
        }

        geyserManager = new GeyserManager(this);
        geyserManager.detect();

        guiManager = new GuiManager();

        duelManager = new DuelManager(this);
        duelManager.start();

        DuelCommand command = new DuelCommand(this);
        org.bukkit.command.PluginCommand cmd = getCommand("duel");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        getServer().getPluginManager().registerEvents(new Listeners(this), this);

        // 自动保存
        int minutes = Math.max(1, configManager.getInt("stats.autosave-minutes", 10));
        Bukkit.getScheduler().runTaskTimer(this, statsManager::save, minutes * 60 * 20L, minutes * 60 * 20L);

        getLogger().info("ACCDuel 竞技决斗场 已启用（耗时 " + (System.currentTimeMillis() - start) + "ms）");
    }

    @Override
    public void onDisable() {
        if (duelManager != null) {
            duelManager.shutdown();
        }
        if (statsManager != null) {
            statsManager.save();
        }
        getLogger().info("ACCDuel 竞技决斗场 已禁用，数据已保存");
    }

    /** 重载所有配置。 */
    public void reloadAll() {
        configManager.reload();
        arenaManager.reload();
        kitManager.reload();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public GeyserManager getGeyserManager() {
        return geyserManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public DuelManager getDuelManager() {
        return duelManager;
    }
}
