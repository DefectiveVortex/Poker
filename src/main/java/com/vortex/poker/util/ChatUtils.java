package com.vortex.poker.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Clickable chat buttons and action-bar text.
 */
public final class ChatUtils {
    private ChatUtils() {
    }

    /** A button that runs a command when clicked. */
    public static TextComponent runButton(String text, String command, String hover) {
        return button(text, new ClickEvent(ClickEvent.Action.RUN_COMMAND, command), hover);
    }

    /** A button that puts a command in the player's chat box to finish typing. */
    public static TextComponent suggestButton(String text, String command, String hover) {
        return button(text, new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command), hover);
    }

    private static TextComponent button(String text, ClickEvent click, String hover) {
        TextComponent component = new TextComponent(TextComponent.fromLegacyText(text));
        component.setClickEvent(click);
        if (hover != null && !hover.isEmpty()) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover))));
        }
        return component;
    }

    /** One chat line: a prompt followed by buttons with a separator between them. */
    public static void sendRow(Player player, String prompt, String separator, List<? extends BaseComponent> buttons) {
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(prompt));
        for (int i = 0; i < buttons.size(); i++) {
            if (i > 0) {
                line.addExtra(new TextComponent(TextComponent.fromLegacyText(separator)));
            }
            line.addExtra(buttons.get(i));
        }
        player.spigot().sendMessage(line);
    }

    /** A short line above the hotbar (e.g. the turn countdown). */
    public static void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }
}
