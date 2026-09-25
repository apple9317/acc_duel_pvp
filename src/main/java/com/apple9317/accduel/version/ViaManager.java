package com.apple9317.accduel.version;

import com.viaversion.viaversion.api.Via;
import org.bukkit.entity.Player;

/**
 * ViaVersion 客户端版本检测（软依赖）。
 *
 * <p>本类直接引用 Via API，仅在检测到服务端安装了 ViaVersion 时才会被加载调用，
 * 未安装时不会触发 NoClassDefFoundError。</p>
 */
public final class ViaManager {

    /** 1.21.6 客户端协议号（Dialog 屏幕界面的最低客户端版本）。 */
    public static final int PROTOCOL_1_21_6 = 771;

    private ViaManager() {
    }

    /** 玩家客户端协议版本号；ViaVersion 未就绪 / 查询失败返回 -1。 */
    public static int clientProtocol(Player player) {
        if (player == null) return -1;
        try {
            return Via.getAPI().getPlayerVersion(player.getUniqueId());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 玩家客户端是否为 1.21.6（协议 771）或更高。 */
    public static boolean clientAtLeast1216(Player player) {
        return clientProtocol(player) >= PROTOCOL_1_21_6;
    }
}
