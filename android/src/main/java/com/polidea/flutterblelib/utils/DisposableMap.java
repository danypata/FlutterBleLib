package com.polidea.flutterblelib.utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.reactivex.disposables.Disposable;


public class DisposableMap {

    final private Map<String, Disposable> subscriptions = new HashMap<>();

    public void replaceSubscription(String key, Disposable subscription) {
        Disposable oldSubscription = subscriptions.put(key, subscription);
        if (oldSubscription != null && !oldSubscription.isDisposed()) {
            oldSubscription.dispose();
        }
    }

    public boolean removeSubscription(String key) {
        Disposable subscription = subscriptions.remove(key);
        if (subscription == null) return false;
        if (!subscription.isDisposed()) {
            subscription.dispose();
        }
        return true;
    }

    /**
     * Removes all subscriptions from map and unsubscribes them if they were subscribed.
     */
    public void removeAllSubscriptions() {
        Iterator<Map.Entry<String, Disposable>> it = subscriptions.entrySet().iterator();
        while (it.hasNext()) {
            Disposable subscription = it.next().getValue();
            it.remove();
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        }
    }
}
