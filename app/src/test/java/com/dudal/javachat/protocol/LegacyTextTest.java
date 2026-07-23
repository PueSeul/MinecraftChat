package com.dudal.javachat.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class LegacyTextTest {
    @Test
    public void parsesLegacyColorsFromHypixelMessage() {
        LegacyText.Parsed parsed = LegacyText.parse(
                "§b[MVP§3+§b] AnSoNcMs§f§6 님이 로비에 접속했습니다!");

        assertTrue(parsed.hadFormatting());
        assertEquals("[MVP+] AnSoNcMs 님이 로비에 접속했습니다!", parsed.getPlainText());
        assertEquals(4, parsed.getRuns().size());
        assertEquals(Integer.valueOf(0x55FFFF), parsed.getRuns().get(0).getColor());
        assertEquals(Integer.valueOf(0x00AAAA), parsed.getRuns().get(1).getColor());
        assertEquals(Integer.valueOf(0x55FFFF), parsed.getRuns().get(2).getColor());
        assertEquals(Integer.valueOf(0xFFAA00), parsed.getRuns().get(3).getColor());
    }

    @Test
    public void leavesUnformattedTextOnItsFallbackStyle() {
        LegacyText.Parsed parsed = LegacyText.parse("기존 색상 메시지");

        assertFalse(parsed.hadFormatting());
        assertEquals("기존 색상 메시지", parsed.getPlainText());
        assertNull(parsed.getRuns().get(0).getColor());
    }

    @Test
    public void parsesRgbColorAndTextStyles() {
        LegacyText.Parsed parsed = LegacyText.parse(
                "§x§1§2§A§b§3§4§l§o§n§m서식§r 기본");

        LegacyText.Run styled = parsed.getRuns().get(0);
        assertEquals(Integer.valueOf(0x12AB34), styled.getColor());
        assertTrue(styled.isBold());
        assertTrue(styled.isItalic());
        assertTrue(styled.isUnderlined());
        assertTrue(styled.isStrikethrough());
        assertNull(parsed.getRuns().get(1).getColor());
        assertEquals("서식 기본", parsed.getPlainText());
    }

    @Test
    public void keepsInvalidAndIncompleteSectionSigns() {
        LegacyText.Parsed parsed = LegacyText.parse("가격 §z / 끝§");

        assertFalse(parsed.hadFormatting());
        assertEquals("가격 §z / 끝§", parsed.getPlainText());
    }

    @Test
    public void chatLineKeepsFormattingOnlyForRendering() {
        ChatLine line = new ChatLine(1L, ChatLine.Kind.SYSTEM,
                "§7서버", "§6안내 §r내용");

        assertEquals("서버", line.getSender());
        assertEquals("안내 내용", line.getMessage());
        assertEquals("§7서버", line.getFormattedSender());
        assertEquals("§6안내 §r내용", line.getFormattedMessage());
    }
}
