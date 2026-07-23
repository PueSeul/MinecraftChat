package com.dudal.javachat.protocol;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class ComponentText {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ComponentText() {}

    static String plain(Component component) {
        String text = LegacyText.strip(legacy(component));
        return switch (text) {
            case "multiplayer.disconnect.invalid_public_key_signature" ->
                    "프로필 공개키 서명이 올바르지 않습니다. Microsoft 로그인을 다시 진행해 주세요.";
            case "multiplayer.disconnect.expired_public_key" ->
                    "프로필 공개키가 만료되었습니다. Microsoft 로그인을 다시 진행해 주세요.";
            default -> text;
        };
    }

    static String legacy(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }
}
