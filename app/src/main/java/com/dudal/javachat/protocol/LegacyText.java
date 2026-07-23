package com.dudal.javachat.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses formatting embedded in legacy Minecraft text using section-sign codes. */
public final class LegacyText {
    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private LegacyText() {}

    public static Parsed parse(String value) {
        String text = value == null ? "" : value;
        List<Run> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        State state = new State();
        boolean hadFormatting = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\u00a7' || index + 1 >= text.length()) {
                current.append(character);
                continue;
            }

            char code = Character.toLowerCase(text.charAt(index + 1));
            Integer hexColor = code == 'x' ? parseHexColor(text, index) : null;
            if (hexColor != null) {
                flush(runs, current, state);
                state.setColor(hexColor);
                hadFormatting = true;
                index += 13;
                continue;
            }

            int colorIndex = colorIndex(code);
            if (colorIndex >= 0) {
                flush(runs, current, state);
                state.setColor(COLORS[colorIndex]);
                hadFormatting = true;
                index++;
                continue;
            }

            if (isStyleCode(code)) {
                flush(runs, current, state);
                applyStyleCode(state, code);
                hadFormatting = true;
                index++;
                continue;
            }

            current.append(character);
        }

        flush(runs, current, state);
        return new Parsed(runs, hadFormatting);
    }

    public static String strip(String text) {
        return parse(text).getPlainText();
    }

    private static void flush(List<Run> runs, StringBuilder text, State state) {
        if (text.length() == 0) {
            return;
        }
        runs.add(new Run(text.toString(), state.color, state.bold, state.italic,
                state.underlined, state.strikethrough));
        text.setLength(0);
    }

    private static Integer parseHexColor(String text, int start) {
        if (start + 13 >= text.length()) {
            return null;
        }
        int color = 0;
        for (int digit = 0; digit < 6; digit++) {
            int marker = start + 2 + digit * 2;
            if (text.charAt(marker) != '\u00a7') {
                return null;
            }
            int value = Character.digit(text.charAt(marker + 1), 16);
            if (value < 0) {
                return null;
            }
            color = (color << 4) | value;
        }
        return color;
    }

    private static int colorIndex(char code) {
        if (code >= '0' && code <= '9') {
            return code - '0';
        }
        if (code >= 'a' && code <= 'f') {
            return 10 + code - 'a';
        }
        return -1;
    }

    private static boolean isStyleCode(char code) {
        return (code >= 'k' && code <= 'o') || code == 'r' || code == 'x';
    }

    private static void applyStyleCode(State state, char code) {
        switch (code) {
            case 'l' -> state.bold = true;
            case 'm' -> state.strikethrough = true;
            case 'n' -> state.underlined = true;
            case 'o' -> state.italic = true;
            case 'r' -> state.reset();
            // Obfuscated text (§k) is kept readable; §x is consumed even if malformed.
            default -> { }
        }
    }

    private static final class State {
        private Integer color;
        private boolean bold;
        private boolean italic;
        private boolean underlined;
        private boolean strikethrough;

        private void setColor(int value) {
            color = value;
            bold = false;
            italic = false;
            underlined = false;
            strikethrough = false;
        }

        private void reset() {
            color = null;
            bold = false;
            italic = false;
            underlined = false;
            strikethrough = false;
        }
    }

    public static final class Parsed {
        private final List<Run> runs;
        private final boolean hadFormatting;
        private final String plainText;

        private Parsed(List<Run> runs, boolean hadFormatting) {
            this.runs = Collections.unmodifiableList(new ArrayList<>(runs));
            this.hadFormatting = hadFormatting;
            StringBuilder plain = new StringBuilder();
            for (Run run : runs) {
                plain.append(run.text);
            }
            plainText = plain.toString();
        }

        public List<Run> getRuns() { return runs; }
        public boolean hadFormatting() { return hadFormatting; }
        public String getPlainText() { return plainText; }
    }

    public static final class Run {
        private final String text;
        private final Integer color;
        private final boolean bold;
        private final boolean italic;
        private final boolean underlined;
        private final boolean strikethrough;

        private Run(String text, Integer color, boolean bold, boolean italic,
                    boolean underlined, boolean strikethrough) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
        }

        public String getText() { return text; }
        public Integer getColor() { return color; }
        public boolean isBold() { return bold; }
        public boolean isItalic() { return italic; }
        public boolean isUnderlined() { return underlined; }
        public boolean isStrikethrough() { return strikethrough; }
    }
}
