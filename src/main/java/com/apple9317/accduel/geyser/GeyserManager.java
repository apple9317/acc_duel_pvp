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
            java.util.List<String> found = new java.util.ArrayList<>();
            if (geyserPresent) found.add("Geyser");
            if (floodgatePresent) found.add("Floodgate");
            plugin.getLogger().info("检测到基岩版互通插件: " + String.join(" + ", found)
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
        // Floodgate 插件实际注册名为 floodgate（floodgate-bukkit 是模块名）
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null) floodgate = Bukkit.getPluginManager().getPlugin("floodgate-bukkit");
        floodgatePresent = floodgate != null;
        // 插件管理器找不到时，用 API 类是否存在兜底
        if (!floodgatePresent) {
            try {
                Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgatePresent = true;
            } catch (Throwable ignored) {
            }
        }
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

    /** 获取可发送表单的目标（Geyser Connection 或 FloodgatePlayer），找不到返回 null。 */
    private Object formTarget(Player player) {
        UUID uuid = player.getUniqueId();
        if (geyserPresent) {
            try {
                Object api = Class.forName("org.geysermc.geyser.api.GeyserApi")
                        .getMethod("api").invoke(null);
                Object conn = api.getClass().getMethod("connectionByUuid", UUID.class)
                        .invoke(api, uuid);
                if (conn != null) return conn;
            } catch (Throwable ignored) {
            }
        }
        if (floodgatePresent && floodgateApiMethod != null) {
            try {
                Object api = floodgateApiMethod.invoke(null);
                Object fp = api.getClass().getMethod("getFloodgatePlayer", UUID.class)
                        .invoke(api, uuid);
                if (fp != null) return fp;
            } catch (Throwable ignored) {
            }
        }
        return null;
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
    public boolean sendTypeForm(Player player, String title, List<String> options,
                                List<String> values, Consumer<String> onPick) {
        if ((!geyserPresent && !floodgatePresent) || options == null || options.isEmpty()) return false;
        try {
            Object conn = formTarget(player);
            if (conn == null) return false;

            // cumulus CustomForm.builder()（运行时由 Geyser 提供实现）
            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = customFormClass.getMethod("builder").invoke(null);

            // title(String)
            builder = invoke1(builder, "title", title);
            if (builder == null) return false;

            // 下拉用显示标签 options
            Object dropdownBuilder = invokeVarargs(builder, new String[]{"addDropdown", "dropdown"}, options);
            if (dropdownBuilder == null) return false;
            builder = dropdownBuilder;

            // 选中显示标签后映射回实际 id（values）
            final List<String> opts = options;
            final List<String> vals = values == null ? options : values;
            Consumer<String> mapped = label -> {
                int idx = opts.indexOf(label);
                onPick.accept(idx >= 0 ? vals.get(idx) : label);
            };
            Object form = buildForm(builder, new FormHandler(mapped));
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
    private static Object buildForm(Object builder, Consumer<Object> rawHandler) {
        try {
            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("build") && m.getParameterCount() == 1
                        && Consumer.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    return m.invoke(builder, rawHandler);
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
            return setHandler.invoke(form, rawHandler);
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

    // ---------------- 决斗请求表单（SimpleForm 两按钮） ----------------

    /**
     * 向基岩版玩家发送决斗请求表单（接受/拒绝两个按钮）。
     *
     * @param onChoice true = 接受，false = 拒绝/关闭
     */
    public boolean sendRequestForm(Player target, String title, String content,
                                   String acceptLabel, String denyLabel,
                                   Consumer<Boolean> onChoice) {
        if (!geyserPresent && !floodgatePresent) return false;
        try {
            Object conn = formTarget(target);
            if (conn == null) return false;

            Class<?> simpleFormClass = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Object builder = simpleFormClass.getMethod("builder").invoke(null);
            builder = invoke1(builder, "title", title);
            builder = invoke1(builder, "content", content);
            if (builder == null) return false;
            Method button = findMethod(builder.getClass(), "button", 1);
            if (button == null) return false;
            button.invoke(builder, acceptLabel);
            button.invoke(builder, denyLabel);

            Consumer<Object> rawHandler = resp -> {
                try {
                    Method closed = findMethod(resp.getClass(), "isClosed", 0);
                    if (closed != null && Boolean.TRUE.equals(closed.invoke(resp))) {
                        onChoice.accept(false);
                        return;
                    }
                    Method getId = findMethod(resp.getClass(), "getClickedButtonId", 0);
                    if (getId != null) {
                        int bid = (Integer) getId.invoke(resp);
                        onChoice.accept(bid == 0);
                    } else {
                        onChoice.accept(false);
                    }
                } catch (Throwable ignored) {
                }
            };
            Object form = buildForm(builder, rawHandler);
            if (form == null) return false;
            Method send = findMethod(conn.getClass(), "sendForm", 1);
            if (send == null) return false;
            send.invoke(conn, form);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("基岩决斗请求表单发送失败: " + t);
            return false;
        }
    }

    // ---------------- 个人设置表单（CustomForm：开关 + 下拉） ----------------

    /**
     * 向基岩版玩家发送个人设置表单。
     *
     * @param effectOptions 击杀特效选项列表
     * @param selectedIndex 当前选中的特效下标
     * @param onResult      (接受申请开关, 选中的特效字符串)
     */
    /** 个人设置表单结果：接受申请、新版本UI（null 表示未改动）、击杀特效标签。 */
    public interface SettingsCallback {
        void accept(Boolean acceptRequests, Boolean modernUi, String effect);
    }

    public boolean sendSettingsForm(Player player, String title, boolean acceptRequests,
                                    boolean modernUi,
                                    List<String> effectOptions, int selectedIndex,
                                    SettingsCallback onResult) {
        if (!geyserPresent && !floodgatePresent) return false;
        try {
            Object conn = formTarget(player);
            if (conn == null) return false;

            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = customFormClass.getMethod("builder").invoke(null);
            builder = invoke1(builder, "title", title);
            if (builder == null) return false;

            // toggle 0：接受决斗申请
            Object toggled = null;
            for (String mn : new String[]{"addToggle", "toggle"}) {
                toggled = tryInvoke(builder, mn, String.class, boolean.class, "接受决斗申请", acceptRequests);
                if (toggled != null) break;
            }
            if (toggled == null) return false;
            builder = toggled;

            // toggle 1：新版本屏幕 UI
            Object toggled2 = null;
            for (String mn : new String[]{"addToggle", "toggle"}) {
                toggled2 = tryInvoke(builder, mn, String.class, boolean.class, "新版本UI（1.21.6+）", modernUi);
                if (toggled2 != null) break;
            }
            if (toggled2 == null) return false;
            builder = toggled2;

            // dropdown 2：击杀特效
            Object dropped = null;
            for (String mn : new String[]{"addDropdown", "dropdown"}) {
                dropped = tryInvoke(builder, mn, String.class, List.class, int.class,
                        "击杀特效", effectOptions, selectedIndex);
                if (dropped == null) {
                    dropped = tryInvoke(builder, mn, String.class, String[].class, int.class,
                            "击杀特效", effectOptions.toArray(new String[0]), selectedIndex);
                }
                if (dropped != null) break;
            }
            if (dropped == null) return false;
            builder = dropped;

            Consumer<Object> rawHandler = resp -> {
                try {
                    Method closed = findMethod(resp.getClass(), "isClosed", 0);
                    if (closed != null && Boolean.TRUE.equals(closed.invoke(resp))) return;
                    Method getToggle = findMethod(resp.getClass(), "getToggle", 1);
                    Boolean accept = null;
                    Boolean modern = null;
                    if (getToggle != null) {
                        accept = (Boolean) getToggle.invoke(resp, 0);
                        modern = (Boolean) getToggle.invoke(resp, 1);
                    }
                    String effect = null;
                    Method getDropdown = findMethod(resp.getClass(), "getDropdownOption", 1);
                    if (getDropdown != null) {
                        Object v = getDropdown.invoke(resp, 2);
                        if (v != null) effect = String.valueOf(v);
                    }
                    onResult.accept(accept, modern, effect);
                } catch (Throwable ignored) {
                }
            };
            Object form = buildForm(builder, rawHandler);
            if (form == null) return false;
            Method send = findMethod(conn.getClass(), "sendForm", 1);
            if (send == null) return false;
            send.invoke(conn, form);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("基岩个人设置表单发送失败: " + t);
            return false;
        }
    }
    /** 反射按指定参数类型调用（自动装箱基本类型）。 */
    private static Object tryInvoke(Object target, String name, Class<?> p1, Class<?> p2,
                                    Object a1, Object a2) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 2) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0].isAssignableFrom(p1) && boxed(params[1]).isAssignableFrom(boxed(p2))) {
                    return m.invoke(target, a1, a2);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object tryInvoke(Object target, String name, Class<?> p1, Class<?> p2, Class<?> p3,
                                    Object a1, Object a2, Object a3) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 3) continue;
                Class<?> params[] = m.getParameterTypes();
                if (params[0].isAssignableFrom(p1) && boxed(params[1]).isAssignableFrom(boxed(p2))
                        && boxed(params[2]).isAssignableFrom(boxed(p3))) {
                    return m.invoke(target, a1, a2, a3);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == long.class) return Long.class;
        return c;
    }
}
