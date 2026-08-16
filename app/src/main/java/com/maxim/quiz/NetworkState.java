package com.maxim.quiz;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.concurrent.CopyOnWriteArrayList;

/** Small process-wide network state used to start outbox sync promptly. */
public final class NetworkState {

    public interface Listener {
        void onNetworkStateChanged(boolean available);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean initialized;
    private static volatile boolean available;

    private NetworkState() {
    }

    public static void initialize(Context context) {
        if (initialized) {
            return;
        }
        synchronized (NetworkState.class) {
            if (initialized) {
                return;
            }
            ConnectivityManager manager = (ConnectivityManager)
                    context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            available = isAvailable(manager);
            if (manager != null) {
                manager.registerNetworkCallback(
                        new NetworkRequest.Builder()
                                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build(),
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(Network network) {
                                update(true);
                            }

                            @Override
                            public void onLost(Network network) {
                                update(isAvailable(manager));
                            }
                        });
            }
            initialized = true;
        }
    }

    public static boolean isAvailable(Context context) {
        ConnectivityManager manager = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        return isAvailable(manager);
    }

    public static boolean isAvailable() {
        return available;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    private static boolean isAvailable(ConnectivityManager manager) {
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void update(boolean next) {
        boolean changed = available != next;
        available = next;
        if (changed) {
            for (Listener listener : LISTENERS) {
                listener.onNetworkStateChanged(next);
            }
        }
    }
}
