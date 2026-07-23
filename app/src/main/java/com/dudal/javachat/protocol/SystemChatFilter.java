package com.dudal.javachat.protocol;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.TranslatableComponent;

import java.util.List;
import java.util.Set;

final class SystemChatFilter {
    private static final Set<String> HIDDEN_TRANSLATION_KEYS = Set.of(
            "multiplayer.player.joined",
            "multiplayer.player.joined.renamed",
            "multiplayer.player.left"
    );

    private SystemChatFilter() {}

    static boolean shouldDisplay(Component component) {
        return displayText(component) != null;
    }

    static boolean isPlayerPresence(Component component) {
        if (component instanceof TranslatableComponent translatable
                && HIDDEN_TRANSLATION_KEYS.contains(translatable.key())) {
            return true;
        }
        if (component != null) {
            for (Component child : component.children()) {
                if (isPlayerPresence(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String displayText(Component component) {
        if (component == null) {
            return null;
        }
        String presenceText = playerPresenceText(component);
        if (presenceText != null) {
            return presenceText;
        }
        String plain = ComponentText.plain(component).trim();
        return plain.isEmpty() || HIDDEN_TRANSLATION_KEYS.contains(plain) ? null : plain;
    }

    private static String playerPresenceText(Component component) {
        if (component instanceof TranslatableComponent translatable
                && HIDDEN_TRANSLATION_KEYS.contains(translatable.key())) {
            List<TranslationArgument> arguments = translatable.arguments();
            String playerName = argumentText(arguments, 0);
            if (playerName == null) {
                return null;
            }
            return switch (translatable.key()) {
                case "multiplayer.player.joined" -> playerName + "님이 접속했습니다.";
                case "multiplayer.player.left" -> playerName + "님이 나갔습니다.";
                case "multiplayer.player.joined.renamed" -> {
                    String oldName = argumentText(arguments, 1);
                    yield oldName == null
                            ? playerName + "님이 접속했습니다."
                            : playerName + "님이 접속했습니다. (이전 닉네임: "
                                    + oldName + ")";
                }
                default -> null;
            };
        }
        for (Component child : component.children()) {
            String childText = playerPresenceText(child);
            if (childText != null) {
                return childText;
            }
        }
        return null;
    }

    private static String argumentText(List<TranslationArgument> arguments, int index) {
        if (index >= arguments.size()) {
            return null;
        }
        Object value = arguments.get(index).value();
        String text = value instanceof Component component
                ? ComponentText.plain(component).trim()
                : String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
