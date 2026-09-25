package com.apple9317.accduel.dialog;

import com.apple9317.accduel.ACCDuelPlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Paper 原生屏幕对话框（Dialog），纯反射实现（1.21.6+ 客户端 / Paper 1.21.7+）。
 * 服务端没有该 API 时返回 false，由调用方回退箱子界面。
 */
public final class DialogManager {

    /** 设置提交结果：save=true 表示点了保存，false 表示取消。 */
    public interface SettingsSubmitHandler {
        void handle(boolean save, Boolean accept, Boolean modern, String effectId, Audience audience);
    }

    /** 简单按钮：onClick 服务端回调，或 command 以玩家身份执行。 */
    /** 类型/主体按钮统一宽度（像素），让按钮左右拉长。 */
    private static final int BTN_WIDTH = 200;

    public static final class Button {
        final Component label;
        Runnable onClick;
        String command;
        Integer width;

        public Button(Component label) {
            this.label = label;
        }

        public Button onClick(Runnable r) {
            this.onClick = r;
            return this;
        }

        public Button command(String c) {
            this.command = c;
            return this;
        }

        public Button width(int w) {
            this.width = w;
            return this;
        }

        public static Button of(Component label, Runnable r) {
            return new Button(label).onClick(r);
        }
    }

    private final ACCDuelPlugin plugin;
    private final boolean supported;

    private Class<?> dialogClass;
    private Class<?> dialogBaseClass;
    private Class<?> actionButtonClass;
    private Class<?> dialogActionClass;
    private Class<?> callbackClass;
    private Class<?> dialogTypeClass;
    private Class<?> consumerClass;
    private Class<?> optionsClass;
    private Class<?> clickEventClass;
    private Class<?> dialogInputClass;
    private Class<?> optionEntryClass;
    private Object defaultOptions;

    public DialogManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try {
            dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            dialogBaseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            dialogActionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            callbackClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogActionCallback");
            dialogTypeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            consumerClass = Class.forName("java.util.function.Consumer");
            optionsClass = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
            clickEventClass = Class.forName("net.kyori.adventure.text.event.ClickEvent");
            dialogInputClass = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            optionEntryClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput$OptionEntry");
            defaultOptions = buildDefaultOptions();
            ok = true;
        } catch (Throwable ignored) {
            // 服务端 < 1.21.7，无 Dialog API
        }
        this.supported = ok;
    }

    private static Object buildDefaultOptions() throws Exception {
        Class<?> clickCallback = Class.forName("net.kyori.adventure.text.event.ClickCallback");
        Class<?> options = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
        Class<?> optionsBuilder = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options$Builder");
        TemporalAmount lifetime = (TemporalAmount) clickCallback.getField("DEFAULT_LIFETIME").get(null);
        Object builder = options.getMethod("builder").invoke(null);
        optionsBuilder.getMethod("uses", int.class).invoke(builder, 1);
        optionsBuilder.getMethod("lifetime", TemporalAmount.class).invoke(builder, lifetime);
        return optionsBuilder.getMethod("build").invoke(builder);
    }

    public boolean isSupported() {
        return supported;
    }

    /**
     * 展示对话框。
     *
     * @param body    主体按钮（至少一个）
     * @param exit    退出按钮（null 不添加）
     * @param inputs  输入控件（null 不添加）
     * @param columns 主体按钮每行数量
     */
    public boolean show(Player viewer, Component title, List<Button> body, Button exit,
                        List<Object> inputs, int columns) {
        if (!supported || viewer == null || body == null || body.isEmpty()) return false;
        try {
            Object baseBuilder = dialogBaseClass.getMethod("builder", Component.class).invoke(null, title);
            if (inputs != null && !inputs.isEmpty()) {
                baseBuilder = baseBuilder.getClass()
                        .getMethod("inputs", List.class).invoke(baseBuilder, inputs);
            }
            Object dialogBase = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            List<Object> buttons = new ArrayList<>();
            for (Button b : body) buttons.add(buildButton(viewer, b));

            Object dialogType;
            if (exit == null) {
                Object mb = dialogTypeClass.getMethod("multiAction", List.class).invoke(null, buttons);
                dialogType = mb.getClass().getMethod("build").invoke(mb);
            } else {
                Object exitButton = buildButton(viewer, exit);
                dialogType = dialogTypeClass
                        .getMethod("multiAction", List.class, actionButtonClass, int.class)
                        .invoke(null, buttons, exitButton, Math.max(1, columns));
            }
            return present(viewer, dialogBase, dialogType);
        } catch (Throwable t) {
            logFail(t);
            return false;
        }
    }

    /**
     * 设置对话框：两个勾选框（决斗申请 / 新版本UI）+ 一个循环选项（击杀特效），
     * 底部「保存 / 取消」。输入在客户端本地调整，点保存才提交，不闪屏。
     */
    public boolean showSettings(Player viewer, Component title,
                                boolean acceptInitial, boolean modernInitial,
                                List<Component> effectDisplays, List<String> effectIds,
                                int selectedIndex, SettingsSubmitHandler handler) {
        if (!supported || viewer == null) return false;
        try {
            List<Object> inputs = new ArrayList<>();

            // 勾选框：决斗申请
            inputs.add(buildBooleanInput("accept", Component.text("决斗申请"), acceptInitial));
            // 勾选框：新版本UI
            inputs.add(buildBooleanInput("modern", Component.text("新版本UI（1.21.6+）"), modernInitial));
            // 循环选项：击杀特效
            inputs.add(buildSingleOption("effect", Component.text("击杀特效"),
                    effectDisplays, effectIds, selectedIndex));

            Object baseBuilder = dialogBaseClass.getMethod("builder", Component.class).invoke(null, title);
            baseBuilder = baseBuilder.getClass().getMethod("inputs", List.class)
                    .invoke(baseBuilder, inputs);
            Object dialogBase = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            // 保存按钮：读取输入并回调
            Object saveCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept") && args != null && args.length == 2) {
                            Object view = args[0];
                            Object audience = args[1];
                            Runnable run = () -> handler.handle(true,
                                    readBool(view, "accept"),
                                    readBool(view, "modern"),
                                    readText(view, "effect"),
                                    (Audience) audience);
                            if (Bukkit.isPrimaryThread()) run.run();
                            else Bukkit.getScheduler().runTask(plugin, run);
                        }
                        return null;
                    });
            Object saveAction = dialogActionClass
                    .getMethod("customClick", callbackClass, optionsClass)
                    .invoke(null, saveCallback, defaultOptions);
            Object saveButton = actionButtonClass
                    .getMethod("builder", Component.class)
                    .invoke(null, Component.text("保存").color(NamedTextColor.GREEN));
            saveButton.getClass().getMethod("action", dialogActionClass)
                    .invoke(saveButton, saveAction);
            Object saveBtn = saveButton.getClass().getMethod("build").invoke(saveButton);

            // 取消按钮：无 action，点击仅关闭
            Object cancelBuilder = actionButtonClass
                    .getMethod("builder", Component.class)
                    .invoke(null, Component.text("取消").color(NamedTextColor.RED));
            Object cancelBtn = cancelBuilder.getClass().getMethod("build").invoke(cancelBuilder);

            Object dialogType = dialogTypeClass
                    .getMethod("multiAction", List.class, actionButtonClass, int.class)
                    .invoke(null, List.of(saveBtn), cancelBtn, 1);

            return present(viewer, dialogBase, dialogType);
        } catch (Throwable t) {
            logFail(t);
            return false;
        }
    }

    /** 构造勾选框输入。 */
    private Object buildBooleanInput(String key, Component label, boolean initial) throws Exception {
        Object builder = dialogInputClass
                .getMethod("bool", String.class, Component.class).invoke(null, key, label);
        builder.getClass().getMethod("initial", boolean.class).invoke(builder, initial);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /** 构造循环选项输入。 */
    private Object buildSingleOption(String key, Component label,
                                     List<Component> displays, List<String> ids,
                                     int selectedIndex) throws Exception {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            entries.add(optionEntryClass
                    .getMethod("create", String.class, Component.class, boolean.class)
                    .invoke(null, ids.get(i), displays.get(i), i == selectedIndex));
        }
        Object builder = dialogInputClass
                .getMethod("singleOption", String.class, Component.class, List.class)
                .invoke(null, key, label, entries);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /** 构造一个反射 ActionButton。 */
    private Object buildButton(Player viewer, Button b) throws Exception {
        Object bb = actionButtonClass.getMethod("builder", Component.class).invoke(null, b.label);
        Object action = null;
        if (b.onClick != null) {
            final Runnable r = b.onClick;
            Object cb = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            // 回调本就在主线程，直接执行；非主线程才切，避免延迟闪屏
                            if (Bukkit.isPrimaryThread()) r.run();
                            else Bukkit.getScheduler().runTask(plugin, r);
                        }
                        return null;
                    });
            action = dialogActionClass.getMethod("customClick", callbackClass, optionsClass)
                    .invoke(null, cb, defaultOptions);
        } else if (b.command != null) {
            Object ce = clickEventClass.getMethod("runCommand", String.class).invoke(null, b.command);
            action = dialogActionClass.getMethod("staticAction", clickEventClass).invoke(null, ce);
        }
        if (action != null) {
            bb.getClass().getMethod("action", dialogActionClass).invoke(bb, action);
        }
        if (b.width != null) {
            bb.getClass().getMethod("width", int.class).invoke(bb, b.width);
        }
        return bb.getClass().getMethod("build").invoke(bb);
    }

    /** 组装 Dialog 并展示给玩家。 */
    private boolean present(Player viewer, Object dialogBase, Object dialogType) throws Exception {
        final Object fBase = dialogBase;
        final Object fType = dialogType;
        Object consumer = Proxy.newProxyInstance(
                dialogClass.getClassLoader(),
                new Class<?>[]{consumerClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("accept") && args != null && args.length == 1) {
                        Object factory = args[0];
                        Object entry = factory.getClass().getMethod("empty").invoke(factory);
                        entry.getClass().getMethod("base", dialogBaseClass).invoke(entry, fBase);
                        entry.getClass().getMethod("type", dialogTypeClass).invoke(entry, fType);
                    }
                    return null;
                });
        Object dialog = dialogClass.getMethod("create", consumerClass).invoke(null, consumer);

        java.lang.reflect.Method showM = null;
        for (java.lang.reflect.Method m : viewer.getClass().getMethods()) {
            if (m.getName().equals("showDialog") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(dialog.getClass())) {
                showM = m;
                break;
            }
        }
        if (showM == null) return false;
        showM.invoke(viewer, dialog);
        return true;
    }

    private static Boolean readBool(Object view, String key) {
        try {
            return (Boolean) view.getClass().getMethod("getBoolean", String.class).invoke(view, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(Object view, String key) {
        try {
            return (String) view.getClass().getMethod("getText", String.class).invoke(view, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private void logFail(Throwable t) {
        Throwable root = t;
        while (root instanceof java.lang.reflect.InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        plugin.getLogger().log(Level.WARNING, "展示屏幕对话框失败，回退箱子界面: "
                + root.getClass().getSimpleName() + " " + root.getMessage(), root);
    }

    /**
     * 类型选择菜单（每个按钮回传 id）。
     * @param exitLabel   退出按钮文本
     * @param exitCommand 退出按钮执行的命令（null 表示仅关闭）
     */
    public boolean showButtonMenu(Player viewer, Component title,
                                  List<Component> labels, List<String> ids,
                                  Component exitLabel, String exitCommand,
                                  Consumer<String> onPick) {
        if (!supported || labels == null || labels.size() != ids.size()) return false;
        List<Button> body = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            final String id = ids.get(i);
            body.add(new Button(labels.get(i)).width(BTN_WIDTH).onClick(() -> onPick.accept(id)));
        }
        Button exit = new Button(exitLabel).width(BTN_WIDTH);
        if (exitCommand != null) exit.command(exitCommand);
        return show(viewer, title, body, exit, null, 1);
    }
}
