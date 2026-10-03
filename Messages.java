package com.steelrework.playerprogress;

import com.steelrework.playerprogress.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Сообщения из конфига с префиксом и защитой от спама. */
public final class Messages {

    private static final long SPAM_COOLDOWN_MS = 1500;

    private FileConfiguration config;
    private final Map<UUID, Map<String, Long>> lastSent = new HashMap<>();

    public void load(FileConfiguration config) {
        this.config = config;
    }

    public String raw(String key) {
        return config.getString("messages." + key, key);
    }

    public String format(String key, String... pairs) {
        return Text.replace(raw(key), pairs);
    }

    public void send(CommandSender sender, String key, String... pairs) {
        String text = format(key, pairs);
        if (text.isEmpty()) return;
        sender.sendMessage(Text.parse(config.getString("messages.prefix", "") + text));
    }

    /** Без префикса (для многострочных подсказок). */
    public void sendPlain(CommandSender sender, String key, String... pairs) {
        String text = format(key, pairs);
        if (text.isEmpty()) return;
        for (String line : text.split("\n")) sender.sendMessage(Text.parse(line));
    }

    /** То же, что send, но одно и то же сообщение не чаще раза в 1.5 сек. */
    public void sendLimited(Player player, String key, String... pairs) {
        String text = format(key, pairs);
        long now = System.currentTimeMillis();
        Map<String, Long> map = lastSent.computeIfAbsent(player.getUniqueId(), u -> new HashMap<>());
        Long last = map.get(text);
        if (last != null && now - last < SPAM_COOLDOWN_MS) return;
        map.put(text, now);
        if (!text.isEmpty()) player.sendMessage(Text.parse(config.getString("messages.prefix", "") + text));
    }

    public void actionBar(Player player, String key, String... pairs) {
        String text = format(key, pairs);
        if (!text.isEmpty()) player.sendActionBar(Text.parse(text));
    }

    public void forget(UUID uuid) {
        lastSent.remove(uuid);
    }
}
