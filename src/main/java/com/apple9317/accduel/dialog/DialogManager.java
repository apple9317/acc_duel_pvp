package com.apple9317.accduel.dialog;

import com.apple9317.accduel.ACCDuelPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Paper 原生屏幕对话框（Dialog）支持，纯反射实现。
 *
 * <p>Dialog 自 Minecraft 1.21.6 客户端引入、Paper 1.21.7 提供 API。它是一种
 * 非箱子的屏幕中央界面。本类不直接 import Dialog API（默认按 paper-api 1.21.1 编译，
 * 该版本没有这些类），全部反射调用，因此同一个 jar 可在 1.21.1 与 26.2 运行：
 * 服务端有 API 则展示，没有则返回 false 由调用方回退箱子界面。</p>
 *
 * <p>已核对 paper-api 1.21.8 与 26.2 的类名、方法签名一致。</p>
 */
public final class DialogManager {

    private final ACCDuelPlugin plugin;
    private final boolean supported;

    private Class<?> dialogClass;
    private Class<?> dialogBaseClass;
    private Class<?> actionButtonClass;
    private Class<?> dialogActionClass;
    private Class<?> callbackClass;
    private Class<?> dialogTypeClass;
    private Class<?> dialogLikeClass;
    private Class<?> consumerClass;
    private Class<?> optionsClass;
    private Class<?> clickEventClass;
    /** customClick 的默认 ClickCallback.Options（不能为 null）。 */
    private Object defaultOptions;

    public DialogManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try {
            dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            dialogBaseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            dialogActionClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.action.DialogAction");
            callbackClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.action.DialogActionCallback");
            dialogTypeClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.type.DialogType");
            dialogLikeClass = Class.forName("net.kyori.adventure.dialog.DialogLike");
            consumerClass = Class.forName("java.util.function.Consumer");
            optionsClass = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
            clickEventClass = Class.forName("net.kyori.adventure.text.event.ClickEvent");
            defaultOptions = buildDefaultOptions();
            ok = true;
        } catch (Throwable ignored) {
            // 服务端 < 1.21.7，无 Dialog API
        }
        this.supported = ok;
    }

    /** 构造 ClickCallback.Options：uses=1、lifetime=DEFAULT_LIFETIME。 */
    private static Object buildDefaultOptions() throws Exception {
        Class<?> clickCallback = Class.forName("net.kyori.adventure.text.event.ClickCallback");
        Class<?> options = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
        Class<?> optionsBuilder = Class.forName(
                "net.kyori.adventure.text.event.ClickCallback$Options$Builder");
        TemporalAmount lifetime = (TemporalAmount) clickCallback
                .getField("DEFAULT_LIFETIME").get(null);
        Object builder = options.getMethod("builder").invoke(null);
        optionsBuilder.getMethod("uses", int.class).invoke(builder, 1);
        optionsBuilder.getMethod("lifetime", TemporalAmount.class).invoke(builder, lifetime);
        return optionsBuilder.getMethod("build").invoke(builder);
    }

    /** 服务端是否具备 Dialog API。 */
    public boolean isSupported() {
        return supported;
    }

    /**
     * 展示一个多按钮对话框（multiAction 类型）。
     *
     * @param viewer 目标玩家
     * @param title  标题
     * @param labels 按钮文本（与 ids 一一对应）
     * @param ids    每个按钮对应的类型 id，点击时回传给 onPick
     * @param onPick 点击回调（已切到主线程）
     * @return 是否成功展示；不支持 / 失败返回 false，由调用方回退箱子界面
     */
    public boolean showButtonMenu(Player viewer, Component title,
                                  List<Component> labels, List<String> ids,
                                  Consumer<String> onPick) {
        if (!supported || viewer == null || labels == null || labels.isEmpty()
                || labels.size() != ids.size()) {
            return false;
        }
        try {
            // 1) DialogBase
            Object baseBuilder = dialogBaseClass
                    .getMethod("builder", Component.class).invoke(null, title);
            Object dialogBase = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            // 2) 每个类型一个 ActionButton
            List<Object> buttons = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                Object btnBuilder = actionButtonClass
                        .getMethod("builder", Component.class).invoke(null, labels.get(i));
                final String pickedId = ids.get(i);
                Object callback = Proxy.newProxyInstance(
                        callbackClass.getClassLoader(),
                        new Class<?>[]{callbackClass},
                        (proxy, method, args) -> {
                            if (method.getName().equals("accept")) {
                                // 统一切主线程再操作 Bukkit API
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Player current = Bukkit.getPlayer(viewer.getUniqueId());
                                    if (current != null && current.isOnline()) onPick.accept(pickedId);
                                });
                            }
                            return null;
                        });
                Object action = dialogActionClass
                        .getMethod("customClick", callbackClass, optionsClass)
                        .invoke(null, callback, defaultOptions);
                btnBuilder.getClass()
                        .getMethod("action", dialogActionClass).invoke(btnBuilder, action);
                buttons.add(btnBuilder.getClass().getMethod("build").invoke(btnBuilder));
            }

            // 3) 取消匹配按钮：点击执行 /duel leave（离开匹配队列），显示在动作按钮之后（最后一行）
            Object cancelClick = clickEventClass
                    .getMethod("runCommand", String.class).invoke(null, "/duel leave");
            Object cancelAction = dialogActionClass
                    .getMethod("staticAction", clickEventClass).invoke(null, cancelClick);
            Object cancelBuilder = actionButtonClass
                    .getMethod("builder", Component.class).invoke(null,
                            Component.text("取消匹配").color(NamedTextColor.RED));
            cancelBuilder.getClass().getMethod("action", dialogActionClass)
                    .invoke(cancelBuilder, cancelAction);
            Object cancelButton = cancelBuilder.getClass().getMethod("build").invoke(cancelBuilder);

            // 4) DialogType：multiAction(buttons, exit=取消匹配, columns=1)
            Object dialogType = dialogTypeClass
                    .getMethod("multiAction", List.class, actionButtonClass, int.class)
                    .invoke(null, buttons, cancelButton, 1);

            // 5) Dialog.create(Consumer<RegistryBuilderFactory>)
            final Object fBase = dialogBase;
            final Object fType = dialogType;
            Object consumer = Proxy.newProxyInstance(
                    dialogClass.getClassLoader(),
                    new Class<?>[]{consumerClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept") && args != null && args.length == 1) {
                            Object factory = args[0];
                            Object entryBuilder = factory.getClass()
                                    .getMethod("empty").invoke(factory);
                            entryBuilder.getClass()
                                    .getMethod("base", dialogBaseClass)
                                    .invoke(entryBuilder, fBase);
                            entryBuilder.getClass()
                                    .getMethod("type", dialogTypeClass)
                                    .invoke(entryBuilder, fType);
                        }
                        return null;
                    });
            Object dialog = dialogClass
                    .getMethod("create", consumerClass).invoke(null, consumer);

            // 6) player.showDialog(dialog)：遍历 public 方法（含接口）定位
            Method show = null;
            for (Method m : viewer.getClass().getMethods()) {
                if (m.getName().equals("showDialog") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(dialog.getClass())) {
                    show = m;
                    break;
                }
            }
            if (show == null) return false;
            show.invoke(viewer, dialog);
            return true;
        } catch (Throwable t) {
            Throwable root = t;
            while (root instanceof java.lang.reflect.InvocationTargetException
                    && root.getCause() != null) {
                root = root.getCause();
            }
            plugin.getLogger().log(Level.WARNING, "展示屏幕对话框失败，回退箱子界面: "
                    + root.getClass().getSimpleName() + " " + root.getMessage(), root);
            return false;
        }
    }
}
