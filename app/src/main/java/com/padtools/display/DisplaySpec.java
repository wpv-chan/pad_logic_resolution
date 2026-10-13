package com.padtools.display;

public final class DisplaySpec {
    public final int width, height, density;
    public final boolean reset;
    public DisplaySpec(int width, int height, int density) { this(width, height, density, false); }
    private DisplaySpec(int width, int height, int density, boolean reset) {
        if (!reset && (width < 320 || height < 320 || width > 7680 || height > 7680))
            throw new IllegalArgumentException("宽高需要在 320–7680 px 之间");
        if (!reset && (density < 120 || density > 640))
            throw new IllegalArgumentException("DPI 需要在 120–640 之间");
        this.width = width; this.height = height; this.density = density; this.reset = reset;
    }
    public static DisplaySpec defaults() { return new DisplaySpec(0, 0, 0, true); }
    public String sizeArg() { return reset ? "reset" : width + "x" + height; }
    public String densityArg() { return reset ? "reset" : Integer.toString(density); }
    public int smallestWidthDp() { return reset ? 0 : Math.min(width, height) * 160 / density; }
}
