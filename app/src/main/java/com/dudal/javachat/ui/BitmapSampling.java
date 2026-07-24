package com.dudal.javachat.ui;

/** Sampling calculations shared by server icons and pixel-art player skins. */
public final class BitmapSampling {
    private BitmapSampling() {}

    public static int fit(int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1;
        }
        int sample = 1;
        while (width / (sample * 2) >= targetWidth
                && height / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    public static int preserveGrid(int width, int height,
                                   int gridWidth, int gridHeight) {
        if (width <= 0 || height <= 0 || gridWidth <= 0 || gridHeight <= 0) {
            return 1;
        }
        int sample = 1;
        while (width % (gridWidth * sample * 2) == 0
                && width / (sample * 2) >= gridWidth
                && height / (sample * 2) >= gridHeight) {
            sample *= 2;
        }
        return sample;
    }
}
