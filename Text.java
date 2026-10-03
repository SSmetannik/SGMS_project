package com.steelrework.playerprogress.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** Перевод строк с цветовыми кодами (&a, &#rrggbb) в компоненты. */
public final class Text {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    /** Подставляет плейсхолдеры: pairs = ключ, значение, ключ, значение... (ключи без %). */
    public static String replace(String text, String... pairs) {
        if (text == null) return "";
        String result = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace("%" + pairs[i] + "%", pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return result;
    }

    public static Component parse(String text) {
        return SERIALIZER.deserialize(text == null ? "" : text);
    }

    /** Для названий и описаний предметов: без курсива по умолчанию. */
    public static Component item(String text) {
        return parse(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(item(line));
        return out;
    }

    /** Убирает цветовые коды. */
    public static String plain(String text) {
        return PlainTextComponentSerializer.plainText().serialize(parse(text));
    }
}
