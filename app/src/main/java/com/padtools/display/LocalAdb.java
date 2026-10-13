package com.padtools.display;

import android.content.Context;
import android.os.Build;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

final class LocalAdb extends AbsAdbConnectionManager {
    private final AdbIdentity identity;
    LocalAdb(Context context) throws Exception {
        identity = AdbIdentity.load(context);
        setApi(Build.VERSION.SDK_INT);
        setTimeout(10, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }
    @Override protected PrivateKey getPrivateKey() { return identity.privateKey; }
    @Override protected Certificate getCertificate() { return identity.certificate; }
    @Override protected String getDeviceName() { return "PadDisplay"; }
}
