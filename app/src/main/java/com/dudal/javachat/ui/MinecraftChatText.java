package com.dudal.javachat.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import com.dudal.javachat.protocol.LegacyText;

public final class MinecraftChatText {
    private MinecraftChatText() {}

    public static CharSequence format(String rawText, int fallbackColor) {
        LegacyText.Parsed parsed = LegacyText.parse(rawText);
        if (!parsed.hadFormatting()) {
            return parsed.getPlainText();
        }

        SpannableStringBuilder result = new SpannableStringBuilder();
        for (LegacyText.Run run : parsed.getRuns()) {
            int start = result.length();
            result.append(run.getText());
            int end = result.length();
            int color = run.getColor() == null
                    ? fallbackColor : 0xFF000000 | run.getColor();
            result.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (run.isBold() || run.isItalic()) {
                int style = run.isBold() && run.isItalic()
                        ? Typeface.BOLD_ITALIC
                        : run.isBold() ? Typeface.BOLD : Typeface.ITALIC;
                result.setSpan(new StyleSpan(style), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.isUnderlined()) {
                result.setSpan(new UnderlineSpan(), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.isStrikethrough()) {
                result.setSpan(new StrikethroughSpan(), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return result;
    }
}
