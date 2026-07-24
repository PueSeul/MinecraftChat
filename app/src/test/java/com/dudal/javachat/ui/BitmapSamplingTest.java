package com.dudal.javachat.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BitmapSamplingTest {
    @Test
    public void fitKeepsDecodedBitmapLargeEnoughForTarget() {
        assertEquals(1, BitmapSampling.fit(64, 64, 52, 52));
        assertEquals(8, BitmapSampling.fit(512, 512, 52, 52));
        assertEquals(4, BitmapSampling.fit(512, 256, 80, 52));
    }

    @Test
    public void preserveGridDownsamplesHdSkinWithoutFractionalPixels() {
        assertEquals(1, BitmapSampling.preserveGrid(64, 32, 64, 32));
        assertEquals(32, BitmapSampling.preserveGrid(2048, 1024, 64, 32));
        assertEquals(1, BitmapSampling.preserveGrid(192, 96, 64, 32));
    }
}
