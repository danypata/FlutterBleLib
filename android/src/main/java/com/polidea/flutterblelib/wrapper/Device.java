package com.polidea.flutterblelib.wrapper;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;

import java.util.List;
import java.util.UUID;

public class Device {


    private RxBleDevice device;
    @Nullable
    private RxBleConnection connection;
    @Nullable
    private List<Service> services;

    public Device(@NonNull RxBleDevice device, @Nullable RxBleConnection connection) {
        this.device = device;
        this.connection = connection;
    }

    @Nullable
    public List<Service> getServices() {
        return services;
    }

    public void setServices(@NonNull List<Service> services) {
        this.services = services;
    }

    public RxBleDevice getRxBleDevice() {
        return device;
    }

    @Nullable
    public RxBleConnection getConnection() {
        return connection;
    }

    @Nullable
    public Service getServiceByUUID(@NonNull UUID uuid) {
        if (services == null) {
            return null;
        }

        for (Service service : services) {
            if (uuid.equals(service.getNativeService().getUuid()))
                return service;
        }
        return null;
    }
}