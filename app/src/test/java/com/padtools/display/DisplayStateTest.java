package com.padtools.display;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayStateTest {
    @Test public void readsPhysicalValuesWithoutInventingOverrides() {
        DisplayState state = DisplayState.parse("Physical size: 2560x1600\r\nPhysical density: 320\r\n");
        assertEquals(2560, state.width()); assertEquals(1600, state.height()); assertEquals(320, state.density());
        assertEquals(800, state.smallestWidthDp());
        assertEquals("reset", state.restoreSize()); assertEquals("reset", state.restoreDensity());
    }
    @Test public void retainsPreviousOverridesForRollback() {
        DisplayState state = DisplayState.parse("Physical size: 1600x2560\nOverride size: 1200x1920\nPhysical density: 320\nOverride density: 240\n");
        assertEquals(1200, state.width()); assertEquals(1920, state.height()); assertEquals(240, state.density());
        assertEquals("1200x1920", state.restoreSize()); assertEquals("240", state.restoreDensity());
    }
    @Test public void handlesIndependentDensityOverride() {
        DisplayState state = DisplayState.parse("Physical size: 2560x1600\nPhysical density: 320\nOverride density: 240");
        assertEquals("reset", state.restoreSize()); assertEquals("240", state.restoreDensity());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsIncompleteSystemOutput() {
        DisplayState.parse("Permission denial: requires WRITE_SECURE_SETTINGS\nPhysical size: 2560x1600");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroDensity() {
        DisplayState.parse("Physical size: 2560x1600\nPhysical density: 0");
    }
    @Test public void validatesBothRangeEdges() {
        new DisplaySpec(320, 320, 120); new DisplaySpec(7680, 7680, 640);
        assertEquals("reset", DisplaySpec.defaults().sizeArg());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnsafeWidth() { new DisplaySpec(319, 1200, 240); }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnsafeDensity() { new DisplaySpec(1920, 1200, 641); }
    @Test(expected = IllegalArgumentException.class) public void rejectsTokenInjection() { GuardScript.base("; rm -rf /"); }
}
