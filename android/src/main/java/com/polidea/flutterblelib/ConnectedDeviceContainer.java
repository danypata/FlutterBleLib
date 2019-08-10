package com.polidea.flutterblelib;


import androidx.annotation.Nullable;

import com.polidea.flutterblelib.wrapper.Device;

import java.util.HashMap;

public class ConnectedDeviceContainer extends HashMap<String, Device> {

    public void register(Device connectedDeviceData) {
        this.put(connectedDeviceData.getRxBleDevice().getMacAddress(), connectedDeviceData);
    }

    @Nullable
    public Device getConnectedDeviceMessage(String macAddress) {
        return get(macAddress);
    }
}
