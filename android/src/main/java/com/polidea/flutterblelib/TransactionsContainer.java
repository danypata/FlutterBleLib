package com.polidea.flutterblelib;


import com.polidea.flutterblelib.utils.DisposableMap;

import io.reactivex.disposables.Disposable;

public class TransactionsContainer {

    final private DisposableMap disposableMap = new DisposableMap();

    public void replaceTransactionSubscription(String key, Disposable subscription) {
        disposableMap.replaceSubscription(key, subscription);
    }

    public boolean removeTransactionSubscription(String key) {
        return disposableMap.removeSubscription(key);
    }

    public void clearTransactionsSubscription() {
        disposableMap.removeAllSubscriptions();
    }
}



