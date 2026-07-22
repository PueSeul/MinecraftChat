package com.dudal.javachat.update;

public final class VersionOrder {
    private VersionOrder() {}

    public static int compare(String left, String right) {
        int[] leftParts = parse(left);
        int[] rightParts = parse(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? leftParts[i] : 0;
            int rightPart = i < rightParts.length ? rightParts[i] : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static int[] parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Version is missing");
        }
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        int suffix = normalized.indexOf('-');
        if (suffix >= 0) {
            normalized = normalized.substring(0, suffix);
        }
        suffix = normalized.indexOf('+');
        if (suffix >= 0) {
            normalized = normalized.substring(0, suffix);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Version is missing");
        }
        String[] values = normalized.split("\\.", -1);
        int[] result = new int[values.length];
        try {
            for (int i = 0; i < values.length; i++) {
                if (values[i].isEmpty()) {
                    throw new NumberFormatException("empty version component");
                }
                result[i] = Integer.parseInt(values[i]);
                if (result[i] < 0) {
                    throw new NumberFormatException("negative version component");
                }
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Unsupported version: " + value, error);
        }
        return result;
    }
}
