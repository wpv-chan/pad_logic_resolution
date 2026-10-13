package com.padtools.display;

import android.app.Application;

public final class PadApplication extends Application {
    DisplayController controller;
    @Override public void onCreate() { super.onCreate(); controller = new DisplayController(this); }
}
