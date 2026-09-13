package com.apple9317.accduel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本工具：统一 MiniMessage 解析，兼容 & 颜色代码。
 */
public final class Txt {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Txt() {
    }

    /** 解析字符串：优先 MiniMessage，失败回退 & 颜色代码。 */
    public static Component parse(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        try {
            return MM.deserialize(s);
        } catch (Throwable t) {
            return LEGACY.deserialize(s);
        }
    }

    /** 严格 MiniMessage 解析（配置消息用）。 */
    public static Component mm(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        try {
            return MM.deserialize(s);
        } catch (Throwable t) {
            return Component.text(s);
        }
    }

    /** & 颜色代码解析。 */
    public static Component legacy(String s) {
        return s == null ? Component.empty() : LEGACY.deserialize(s);
    }

    /** 转义用户输入中的 MiniMessage 标签，防止注入。 */
    public static String escape(String s) {
        return s == null ? "" : MM.escapeTags(s);
    }

    /** 将组件转回带 & 的旧格式字符串（用于自定义装备方案存取）。 */
    public static String toLegacy(Component c) {
        return c == null ? "" : LEGACY.serialize(c);
    }

    public static String plain(Component c) {
        return c == null ? "" : PLAIN.serialize(c);
    }

    /** 批量解析。 */
    public static List<Component> parseAll(List<String> lines) {
        List<Component> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) out.add(parse(line));
        }
        return out;
    }
}
