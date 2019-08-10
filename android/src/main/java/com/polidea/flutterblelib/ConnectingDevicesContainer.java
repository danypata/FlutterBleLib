package com.polidea.flutterblelib;


import com.polidea.flutterblelib.utils.DisposableMap;

import io.reactivex.disposables.Disposable;

public class ConnectingDevicesContainer {

    final private DisposableMap disposableMap = new DisposableMap();

    public void replaceConnectingSubscription(String key, Disposable subscription) {
        disposableMap.replaceSubscription(key, subscription);
    }

    public boolean removeConnectingDeviceSubscription(String key) {
        return disposableMap.removeSubscription(key);
    }

    public void clearConnectigDeviceSubscription() {
        disposableMap.removeAllSubscriptions();
    }
}
