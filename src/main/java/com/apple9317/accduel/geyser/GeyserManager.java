package com.apple9317.accduel.geyser;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Geyser / Floodgate 自动检测与基岩版原生表单。
 *
 * 服务端运行 Geyser-Spigot（或 Geyser）+ floodgate-bukkit 时自动识别基岩版玩家：
 *  - 聊天/榜单加 [BE] 前缀；
 *  - 比赛期间为基岩版玩家附加夜视补偿；
 *  - 发送欢迎提示；
 *  - /duel 与 /duelplayer 的类型选择界面使用基岩版原生表单
 *    （新 Geyser API：GeyserApi.api().connectionByUuid(uuid).sendForm(cumulus CustomForm)，
 *    全部走反射以兼容各版本；任何一步失败都返回 false，由调用方回退到箱子界面）。
 */
public class GeyserManager {

    private final ACCDuelPlugin plugin;

    private boolean geyserPresent;
    private boolean floodgatePresent;
    private Method geyserApiMethod;
    private Method geyserConnectionByUuid;
    private Method geyserIsBedrock;
    private Method floodgateApiMethod;
    private Method floodgateIsPlayer;

    public GeyserManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动时检测。 */
    public void detect() {
        detectGeyser();
        detectFloodgate();
        if (geyserPresent || floodgatePresent) {
            plugin.getLogger().info("检测到基岩版互通插件: "
                    + (geyserPresent ? "Geyser" : "") + (floodgatePresent ? " + Floodgate" : "")
                    + "，已启用基岩版玩家识别与原生表单");
        } else {
            plugin.getLogger().info("未检测到 Geyser/Floodgate（不影响本插件运行，基岩版识别功能关闭）");
        }
    }

    private void detectGeyser() {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) geyser = Bukkit.getPluginManager().getPlugin("Geyser");
        geyserPresent = geyser != null;
        if (!geyserPresent) return;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserApiMethod = apiClass.getMethod("api");
            // 新 API（2.4+）：connectionByUuid(UUID) -> GeyserConnection（null = 非基岩版）
            geyserConnectionByUuid = apiClass.getMethod("connectionByUuid", UUID.class);
            // 旧 API（2.0-2.2）：isBedrockPlayer(UUID)
            try {
                geyserIsBedrock = apiClass.getMethod("isBedrockPlayer", UUID.class);
            } catch (NoSuchMethodException ignored) {
                geyserIsBedrock = null;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Geyser API 反射初始化失败（部分功能不可用）: " + t.getMessage());
            geyserApiMethod = null;
            geyserConnectionByUuid = null;
            geyserIsBedrock = null;
        }
    }

    private void detectFloodgate() {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate-bukkit");
        floodgatePresent = floodgate != null;
        if (!floodgatePresent) return;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApiMethod = apiClass.getMethod("getInstance");
            floodgateIsPlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable t) {
            plugin.getLogger().warning("Floodgate API 反射初始化失败（部分功能不可用）: " + t.getMessage());
            floodgateIsPlayer = null;
        }
    }

    /** 判断是否为基岩版玩家（Floodgate 优先，Geyser 新/旧 API 兜底）。 */
    public boolean isBedrock(UUID uuid) {
        if (uuid == null) return false;
        if (floodgatePresent && floodgateIsPlayer != null) {
            try {
                Object api = floodgateApiMethod.invoke(null);
                if (Boolean.TRUE.equals(floodgateIsPlayer.invoke(api, uuid))) return true;
            } catch (Throwable ignored) {
            }
        }
        if (geyserPresent && geyserConnectionByUuid != null) {
            try {
                Object api = geyserApiMethod.invoke(null);
                Object conn = geyserConnectionByUuid.invoke(api, uuid);
                if (conn != null) return true;
            } catch (Throwable ignored) {
            }
        }
        if (geyserPresent && geyserIsBedrock != null) {
            try {
                Object api = geyserApiMethod.invoke(null);
                return Boolean.TRUE.equals(geyserIsBedrock.invoke(api, uuid));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public boolean isBedrock(Player player) {
        return isBedrock(player.getUniqueId());
    }

    /** 聊天/榜单前缀，非基岩版返回空串。 */
    public String tag(Player player) {
        if (!isBedrock(player)) return "";
        return plugin.getConfigManager().getString("geyser.bedrock-tag", "<gray>[<aqua>BE</aqua>]</gray> ");
    }

    public void sendWelcome(Player player) {
        if (!plugin.getConfigManager().getBoolean("geyser.welcome-message", true)) return;
        if (isBedrock(player)) {
            player.sendMessage(Txt.mm(plugin.getConfigManager().raw("geyser-welcome")));
        }
    }

    /** 比赛期间是否需要给该玩家夜视补偿。 */
    public boolean wantsNightVision(Player player) {
        return plugin.getConfigManager().getBoolean("geyser.night-vision-during-match", true) && isBedrock(player);
    }

    // ---------------- 基岩版原生表单（反射） ----------------

    /**
     * 向基岩版玩家发送带下拉选择的 CustomForm。
     *
     * @return true = 表单已成功发送；false = 任何环节失败（调用方应回退到箱子界面）。
     */
    public boolean sendTypeForm(Player player, String title, List<String> options, Consumer<String> onPick) {
        if (!geyserPresent || options == null || options.isEmpty()) return false;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = apiClass.getMethod("api").invoke(null);
            Object conn = api.getClass().getMethod("connectionByUuid", UUID.class).invoke(api, player.getUniqueId());
            if (conn == null) return false;

            // cumulus CustomForm.builder()（运行时由 Geyser 提供实现）
            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = customFormClass.getMethod("builder").invoke(null);

            // title(String)
            builder = invoke1(builder, "title", title);
            if (builder == null) return false;

            // 下拉：现代 cumulus 用 addDropdown，旧版用 dropdown（均支持 varargs String...）
            Object dropdownBuilder = invokeVarargs(builder, new String[]{"addDropdown", "dropdown"}, options);
            if (dropdownBuilder == null) return false;
            builder = dropdownBuilder;

            // build：优先 build(Consumer)（现代 cumulus），否则 build() 后 setResponseHandler
            Object form = buildForm(builder, onPick);
            if (form == null) return false;

            // 发送：Connection#sendForm(Form)（新 API）
            Method send = findMethod(conn.getClass(), "sendForm", 1);
            if (send == null) return false;
            send.invoke(conn, form);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("基岩版表单发送失败，回退到箱子界面: " + t);
            return false;
        }
    }

    /** 调用 1 个 String 参数的方法，失败返回 null。 */
    private static Object invoke1(Object target, String name, String arg) {
        try {
            Method m = findMethod(target.getClass(), name, 1);
            if (m == null || !m.getParameterTypes()[0].isAssignableFrom(String.class)) return null;
            return m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 依次尝试方法名（varargs String...），把选项列表作为数组传入。 */
    private static Object invokeVarargs(Object target, String[] names, List<String> options) {
        String[] array = options.toArray(new String[0]);
        for (String name : names) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && params[0].equals(String.class) && params[1].isArray()
                            && params[1].getComponentType().equals(String.class)) {
                        return m.invoke(target, "选择竞技类型", (Object) array);
                    }
                    if (params.length == 2 && params[0].equals(String.class) && List.class.isAssignableFrom(params[1])) {
                        return m.invoke(target, "选择竞技类型", options);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 构建表单：优先 build(Consumer)，否则 build() 后 setResponseHandler(Consumer)。 */
    private static Object buildForm(Object builder, Consumer<String> onPick) {
        FormHandler handler = new FormHandler(onPick);
        try {
            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("build") && m.getParameterCount() == 1
                        && Consumer.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    return m.invoke(builder, handler);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Method build = findMethod(builder.getClass(), "build", 0);
            if (build == null) return null;
            Object form = build.invoke(builder);
            Method setHandler = findMethod(form.getClass(), "setResponseHandler", 1);
            if (setHandler == null) return null;
            return setHandler.invoke(form, handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 表单响应处理器：从响应中读取下拉选中的类型字符串。 */
    static final class FormHandler implements Consumer<Object> {
        private final Consumer<String> onPick;

        FormHandler(Consumer<String> onPick) {
            this.onPick = onPick;
        }

        @Override
        public void accept(Object resp) {
            try {
                Method closed = findMethod(resp.getClass(), "isClosed", 0);
                if (closed != null && Boolean.TRUE.equals(closed.invoke(resp))) return;
                String selected = null;
                // 现代 cumulus：getDropdownOption(int)
                Method get = findMethod(resp.getClass(), "getDropdownOption", 1);
                if (get != null) {
                    Object v = get.invoke(resp, 0);
                    if (v != null) selected = String.valueOf(v);
                }
                // 旧 cumulus：valueAt(int)
                if (selected == null) {
                    Method valueAt = findMethod(resp.getClass(), "valueAt", 1);
                    if (valueAt != null) {
                        Object v = valueAt.invoke(resp, 0);
                        if (v != null) selected = String.valueOf(v);
                    }
                }
                if (selected != null) onPick.accept(selected);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }
}
