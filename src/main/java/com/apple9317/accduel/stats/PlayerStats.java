package com.apple9317.accduel.stats;

/**
 * 单个玩家的决斗统计数据（Gson 持久化）。
 */
public class PlayerStats {

    public int wins;
    public int losses;
    public int draws;
    public int kills;
    public int deaths;
    public int rating = 1000;
    public int matches;
    public int roundsWon;
    public int roundsLost;
    public int streak;
    public int bestStreak;
    public long firstSeen;
    public long lastSeen;

    public int played() {
        return wins + losses + draws;
    }

    public double winRate() {
        int played = played();
        return played == 0 ? 0 : Math.round(wins * 1000.0 / played) / 10.0;
    }
}
