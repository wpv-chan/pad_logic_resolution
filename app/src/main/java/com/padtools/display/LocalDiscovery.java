package com.padtools.display;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only services hosted by a local network interface may reach the connection controller. */
final class LocalDiscovery {
    interface Listener { void onEndpoint(String host, int port, boolean pairing); }
    private final Context context;
    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> discoveries = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private WifiManager.MulticastLock multicastLock;
    private boolean active, resolving;

    LocalDiscovery(Context context, Listener listener) {
        this.context = context;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    static boolean isLocal(String host) {
        try {
            // No DNS names and no remote devices: this app changes the current tablet only.
            if (!(host.matches("[0-9.]+") || host.contains(":"))) return false;
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
            if (address.isLoopbackAddress()) return true;
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (Exception e) { return false; }
    }

    static String defaultHost() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (address instanceof java.net.Inet4Address && host != null) return host;
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    void start() {
        if (active || nsd == null) return;
        active = true;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("PadDisplay.discovery");
            multicastLock.setReferenceCounted(false);
            try { multicastLock.acquire(); } catch (RuntimeException ignored) { }
        }
        discover("_adb-tls-pairing._tcp.");
        discover("_adb-tls-connect._tcp.");
    }

    private void discover(String type) {
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> { if (active) { queue.offer(info); resolveNext(); } });
            }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int code) {
                try { nsd.stopServiceDiscovery(this); } catch (RuntimeException ignored) { }
            }
            @Override public void onStopDiscoveryFailed(String t, int code) { }
        };
        discoveries.add(discovery);
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery); } catch (RuntimeException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (!active || resolving || queue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo info = queue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo service, int code) {
                    main.post(() -> { resolving = false; resolveNext(); });
                }
                @Override public void onServiceResolved(NsdServiceInfo service) {
                    main.post(() -> {
                        if (active && service.getHost() != null && isLocal(service.getHost().getHostAddress()))
                            listener.onEndpoint(service.getHost().getHostAddress(), service.getPort(),
                                    service.getServiceType().contains("pairing"));
                        resolving = false;
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) { resolving = false; resolveNext(); }
    }

    void stop() {
        active = false;
        queue.clear();
        for (NsdManager.DiscoveryListener discovery : discoveries)
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
        discoveries.clear();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }
}
