package com.dudal.javachat.protocol;

import net.kyori.adventure.text.Component;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ComponentTextTest {
    @Test
    public void translatesInvalidProfileKeyReason() {
        assertEquals(
                "프로필 공개키 서명이 올바르지 않습니다. Microsoft 로그인을 다시 진행해 주세요.",
                ComponentText.plain(Component.translatable(
                        "multiplayer.disconnect.invalid_public_key_signature")));
    }

    @Test
    public void stripsLegacyFormattingCodesFromPlainText() {
        assertEquals(
                "[MVP+] AnSoNcMs 님이 로비에 접속했습니다!",
                ComponentText.plain(Component.text(
                        "§b[MVP§3+§b] AnSoNcMs§f§6 님이 로비에 접속했습니다!")));
    }

    @Test
    public void stripsUppercaseAndHexLegacyFormattingCodes() {
        assertEquals(
                "색상 텍스트",
                ComponentText.plain(Component.text(
                        "§L색상 §x§F§F§A§A§0§0텍스트§R")));
    }

    @Test
    public void keepsInvalidOrIncompleteSectionSigns() {
        assertEquals(
                "가격 §z / 끝§",
                ComponentText.plain(Component.text("가격 §z / 끝§")));
    }
}
