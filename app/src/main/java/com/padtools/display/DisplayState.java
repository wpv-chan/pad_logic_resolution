package com.padtools.display;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Values from wm use the display's natural orientation, independently of rotation. */
public final class DisplayState {
    public final int physicalWidth, physicalHeight, physicalDensity;
    public final Integer overrideWidth, overrideHeight, overrideDensity;

    public DisplayState(int width, int height, int density, Integer ow, Integer oh, Integer od) {
        if (width <= 0 || height <= 0 || density <= 0) throw new IllegalArgumentException("无法读取设备显示参数");
        physicalWidth = width;
        physicalHeight = height;
        physicalDensity = density;
        overrideWidth = ow;
        overrideHeight = oh;
        overrideDensity = od;
    }

    public int width() { return overrideWidth == null ? physicalWidth : overrideWidth; }
    public int height() { return overrideHeight == null ? physicalHeight : overrideHeight; }
    public int density() { return overrideDensity == null ? physicalDensity : overrideDensity; }
    public int smallestWidthDp() { return Math.min(width(), height()) * 160 / density(); }
    public String restoreSize() { return overrideWidth == null ? "reset" : overrideWidth + "x" + overrideHeight; }
    public String restoreDensity() { return overrideDensity == null ? "reset" : overrideDensity.toString(); }

    public static DisplayState parse(String output) {
        int[] physical = size(output, "Physical");
        int[] override = size(output, "Override");
        Integer density = density(output, "Physical");
        if (physical == null || density == null) throw new IllegalArgumentException("系统未返回完整显示参数：" + output);
        return new DisplayState(physical[0], physical[1], density,
                override == null ? null : override[0], override == null ? null : override[1], density(output, "Override"));
    }

    private static int[] size(String output, String prefix) {
        Matcher m = Pattern.compile(prefix + " size:\\s*(\\d+)x(\\d+)").matcher(output);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }
    private static Integer density(String output, String prefix) {
        Matcher m = Pattern.compile(prefix + " density:\\s*(\\d+)").matcher(output);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
}
