package com.polidea.flutterblelib;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.polidea.flutterblelib.exception.CannotMonitorCharacteristicException;
import com.polidea.flutterblelib.exception.CharacteristicNotFoundException;
import com.polidea.flutterblelib.exception.ConnectionNotFoundException;
import com.polidea.flutterblelib.exception.RxBleDeviceNotFoundException;
import com.polidea.flutterblelib.exception.ServiceNotFoundException;
import com.polidea.flutterblelib.listener.EventDelegate;
import com.polidea.flutterblelib.listener.OnErrorAction;
import com.polidea.flutterblelib.listener.OnSuccessAction;
import com.polidea.flutterblelib.utils.SafeAction;
import com.polidea.flutterblelib.utils.StringUtils;
import com.polidea.flutterblelib.utils.UUIDConverter;
import com.polidea.flutterblelib.wrapper.Characteristic;
import com.polidea.flutterblelib.wrapper.Device;
import com.polidea.flutterblelib.wrapper.ScanSettingsWrapper;
import com.polidea.flutterblelib.wrapper.Service;
import com.polidea.rxandroidble2.NotificationSetupMode;
import com.polidea.rxandroidble2.RxBleAdapterStateObservable;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.exceptions.BleCharacteristicNotFoundException;
import com.polidea.rxandroidble2.internal.RxBleLog;
import com.polidea.rxandroidble2.scan.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;

public class BleHelper {
    private static final int NO_VALUE = -1;

    private final Converter converter;

    private final ConnectedDeviceContainer connectedDevices;

    private final ConnectingDevicesContainer connectingDevices;
    private final TransactionsContainer transactions = new TransactionsContainer();
    private final Context context;
    private final StringUtils stringUtils;
    private SparseArray<Service> discoveredServices = new SparseArray<>();
    private SparseArray<Characteristic> discoveredCharacteristics = new SparseArray<>();
    private RxBleClient rxBleClient;

    private Disposable scanDevicesSubscription;

    @Nullable
    private Disposable adapterStateChangesSubscription;

    private int currentLogLevel = RxBleLog.NONE;

    private EventDelegate eventDelegate;

    BleHelper(Context context) {
        this.context = context;
        stringUtils = new StringUtils();
        converter = new Converter(stringUtils);
        connectedDevices = new ConnectedDeviceContainer();
        connectingDevices = new ConnectingDevicesContainer();
    }

    private boolean isRxBleDeviceReady(final OnErrorAction error) {
        if (rxBleClient == null) {
            error.onError(new IllegalStateException("BleManager not created when tried to start device scan"));
            return false;
        }
        return true;
    }

    void createClient(String restoreStateIdentifier, OnSuccessAction<Void> successAction) {
        rxBleClient = RxBleClient.create(context);
        adapterStateChangesSubscription = monitorAdapterStateChanges(context);
        if (restoreStateIdentifier != null) {
            sendEvent(Event.RestoreStateEvent, null);
        }
        successAction.onSuccess(null);
    }

    void registerEventDelegate(EventDelegate eventDelegate) {
        this.eventDelegate = eventDelegate;
    }

    void destroyClient() {
        // Subscriptions
        if (adapterStateChangesSubscription != null) {
            adapterStateChangesSubscription.dispose();
            adapterStateChangesSubscription = null;
        }
        if (scanDevicesSubscription != null && !scanDevicesSubscription.isDisposed()) {
            scanDevicesSubscription.dispose();
        }
        scanDevicesSubscription = null;

        connectedDevices.clear();
        eventDelegate = null;
        rxBleClient = null;
    }

    void startDeviceScan(byte[] scanSettingsWrapperBytes,
                         final OnSuccessAction<Void> successAction,
                         final OnErrorAction errorAction) {
        if (!isRxBleDeviceReady(errorAction)) {
            return;
        }
        safeStartDeviceScan(converter.convertToScanSettings(scanSettingsWrapperBytes), errorAction);
        successAction.onSuccess(null);
    }

    private void safeStartDeviceScan(ScanSettingsWrapper scanSettingsWrapper, final OnErrorAction errorAction) {
        if (rxBleClient == null) {
            throw new IllegalStateException("BleManager not created when tried to start device scan");
        }
        scanDevicesSubscription = rxBleClient
                .scanBleDevices(scanSettingsWrapper.getScanSetting(), scanSettingsWrapper.getScanFilters())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<ScanResult>() {

                    @Override
                    public void accept(ScanResult rxBleScanResult) throws Exception {
                        sendEvent(Event.ScanEvent, converter.convertToScanResultMessage(rxBleScanResult));
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        errorAction.onError(throwable);
                    }
                });

    }

    void cancelTransaction(String transactionId) {
        transactions.removeTransactionSubscription(transactionId);
    }

    void setLogLevel(BleData.LogLevelMessage logLevel) {
        currentLogLevel = converter.convertLogLevelMessageToInt(logLevel);
        RxBleClient.setLogLevel(currentLogLevel);
    }

    void logLevel(OnSuccessAction<BleData.LogLevelMessage> successAction) {
        successAction.onSuccess(converter.convertIntToLogLevel(currentLogLevel));
    }

    void state(OnSuccessAction<BleData.BluetoothStateMessage> successAction) {
        successAction.onSuccess(getCurrentState());
    }

    private Disposable monitorAdapterStateChanges(Context context) {
        return new RxBleAdapterStateObservable(context)
                .map(new Function<RxBleAdapterStateObservable.BleAdapterState, BleData.BluetoothStateMessage>() {
                    @Override
                    public BleData.BluetoothStateMessage apply(RxBleAdapterStateObservable.BleAdapterState bleAdapterState) throws Exception {
                        return rxAndroidBleAdapterStateToReactNativeBluetoothState(bleAdapterState);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<BleData.BluetoothStateMessage>() {
                    @Override
                    public void accept(BleData.BluetoothStateMessage bluetoothStateMessage) throws Exception {
                        sendEvent(Event.StateChangeEvent, bluetoothStateMessage);
                    }

                });
    }

    private BleData.BluetoothStateMessage rxAndroidBleAdapterStateToReactNativeBluetoothState(RxBleAdapterStateObservable.BleAdapterState rxBleAdapterState) {
        if (rxBleAdapterState == RxBleAdapterStateObservable.BleAdapterState.STATE_ON) {
            return BleData.BluetoothStateMessage.POWERED_ON;
        }
        if (rxBleAdapterState == RxBleAdapterStateObservable.BleAdapterState.STATE_OFF) {
            return BleData.BluetoothStateMessage.POWERED_OFF;
        }
        return BleData.BluetoothStateMessage.RESETTING;
    }

    private BleData.BluetoothStateMessage getCurrentState() {
        if (!supportsBluetoothLowEnergy()) {
            return BleData.BluetoothStateMessage.UNSUPPORTED;
        }

        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return BleData.BluetoothStateMessage.POWERED_OFF;
        }
        final BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        return nativeAdapterStateToReactNativeBluetoothState(bluetoothAdapter.getState());
    }

    private BleData.BluetoothStateMessage nativeAdapterStateToReactNativeBluetoothState(int adapterState) {
        switch (adapterState) {

            case BluetoothAdapter.STATE_OFF:
                return BleData.BluetoothStateMessage.POWERED_OFF;
            case BluetoothAdapter.STATE_ON:
                return BleData.BluetoothStateMessage.POWERED_ON;
            case BluetoothAdapter.STATE_TURNING_OFF:
                // fallthrough
            case BluetoothAdapter.STATE_TURNING_ON:
                return BleData.BluetoothStateMessage.RESETTING;
            default:
                return BleData.BluetoothStateMessage.UNKNOWN;
        }
    }


    private boolean supportsBluetoothLowEnergy() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    void stopDeviceScan() {
        if (scanDevicesSubscription != null && !scanDevicesSubscription.isDisposed()) {
            scanDevicesSubscription.dispose();
        }
        scanDevicesSubscription = null;
    }

    void requestMTUForDevice(
            final String deviceId,
            final int mtu,
            final String transactionId,
            final OnSuccessAction<BleData.BleDeviceMessage> successAction,
            final OnErrorAction errorAction) {
        final Device device = getDeviceOrReject(deviceId, errorAction);
        if (device == null) {
            return;
        }

        final RxBleConnection connection = getConnectionOrReject(device, errorAction);
        if (connection == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            final SafeAction<BleData.BleDeviceMessage> safeAction = new SafeAction<>(successAction, errorAction);
            connection
                    .requestMtu(mtu)

                    .doOnDispose(new Action() {
                        @Override
                        public void run() throws Exception {
                            safeAction.onError(new Throwable("Reject"));
                            transactions.removeTransactionSubscription(transactionId);
                        }
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SingleObserver<Integer>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            transactions.replaceTransactionSubscription(transactionId, d);
                        }

                        @Override
                        public void onSuccess(Integer integer) {
                            safeAction.onSuccess(converter.convertToBleDeviceMessage(device.getRxBleDevice(), integer, NO_VALUE));
                            transactions.removeTransactionSubscription(transactionId);
                        }

                        @Override
                        public void onError(Throwable e) {
                            safeAction.onError(e);
                            transactions.removeTransactionSubscription(transactionId);
                        }
                    });
        } else {
            successAction.onSuccess(converter.convertToBleDeviceMessage(device.getRxBleDevice(), connection.getMtu(), NO_VALUE));
        }
    }

    void readRSSIForDevice(
            final String deviceId,
            final String transactionId,
            final OnSuccessAction<BleData.BleDeviceMessage> successAction,
            final OnErrorAction errorAction) {

        final Device device = getDeviceOrReject(deviceId, errorAction);
        if (device == null) {
            return;
        }
        final RxBleConnection connection = getConnectionOrReject(device, errorAction);
        if (connection == null) {
            return;
        }

        final SafeAction<BleData.BleDeviceMessage> safeAction = new SafeAction<>(successAction, errorAction);
        connection
                .readRssi()
                .doOnDispose(new Action() {
                    @Override
                    public void run() throws Exception {
                        safeAction.onError(new Throwable("Reject"));
                        transactions.removeTransactionSubscription(transactionId);
                    }

                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<Integer>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        transactions.replaceTransactionSubscription(transactionId, d);
                    }

                    @Override
                    public void onSuccess(Integer rssi) {
                        safeAction.onSuccess(converter.convertToBleDeviceMessage(device.getRxBleDevice(), NO_VALUE, rssi));
                        transactions.removeTransactionSubscription(transactionId);
                    }

                    @Override
                    public void onError(Throwable e) {
                        safeAction.onError(e);
                        transactions.removeTransactionSubscription(transactionId);
                    }
                });
    }


    void connectToDevice(byte[] connectToDeviceDataMessageByte,
                         final OnSuccessAction<BleData.BleDeviceMessage> successAction,
                         final OnErrorAction errorAction) {

        if (!isRxBleDeviceReady(errorAction)) {
            return;
        }
        final BleData.ConnectToDeviceDataMessage connectToDeviceDataMessage
                = converter.convertToConnectToDeviceDataMessage(connectToDeviceDataMessageByte);
        if (connectToDeviceDataMessage == null) {
            errorAction.onError(new IllegalArgumentException("scanResultByte argument contains wrong data"));
            return;
        }
        final String macAddress = connectToDeviceDataMessage.getMacAddress();
        final RxBleDevice rxBleDevice = rxBleClient.getBleDevice(macAddress);
        if (rxBleDevice == null) {
            errorAction.onError(new RxBleDeviceNotFoundException("Not found device for mac address : " + macAddress));
            return;
        }
        final boolean isAutoConnect = connectToDeviceDataMessage.getIsAutoConnect();
        final int requestMtu = connectToDeviceDataMessage.getRequestMtu();
        saveConnectToDevice(rxBleDevice, isAutoConnect, requestMtu, new SafeAction<>(successAction, errorAction));
    }

    private void saveConnectToDevice(final RxBleDevice device, boolean autoConnect, final int requestMtu,
                                     final SafeAction<BleData.BleDeviceMessage> safeAction) {
        Observable<RxBleConnection> connect = device
                .establishConnection(autoConnect)
                .doOnDispose(new Action() {
                    @Override
                    public void run() {
                        safeAction.onError(new Throwable("Reject"));
                        onDeviceDisconnected(device);
                    }
                });


        final int[] mtuRequested = {23};
        if (requestMtu > 0) {
            connect = connect.flatMap(new Function<RxBleConnection, Observable<RxBleConnection>>() {
                @Override
                public Observable<RxBleConnection> apply(final RxBleConnection rxBleConnection) throws Exception {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        return rxBleConnection
                                .requestMtu(requestMtu)
                                .map(new Function<Integer, RxBleConnection>() {
                                    @Override
                                    public RxBleConnection apply(Integer integer) {
                                        mtuRequested[0] = integer;
                                        return rxBleConnection;
                                    }
                                }).toObservable();
                    } else {
                        return Observable.just(rxBleConnection);
                    }
                }
            });
        }

        connect
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<RxBleConnection>() {

                    @Override
                    public void onError(Throwable e) {
//                        safeAction.onError(e);
                        onDeviceDisconnected(device);
                    }

                    @Override
                    public void onComplete() {

                    }

                    @Override
                    public void onSubscribe(Disposable d) {
                        connectingDevices.replaceConnectingSubscription(device.getMacAddress(), d);
                    }

                    @Override
                    public void onNext(RxBleConnection connection) {
                        Device deviceWrapper = new Device(device, connection);
                        cleanServicesAndCharacteristicsForDevice(deviceWrapper);
                        connectedDevices.put(device.getMacAddress(), deviceWrapper);
                        safeAction.onSuccess(converter.convertToBleDeviceMessage(device, mtuRequested[0], NO_VALUE));
                    }
                });


    }


    private void onDeviceDisconnected(RxBleDevice device) {
        Log.e("BLE", "DISCONNECTED");
        final Device connectedDevice = connectedDevices.remove(device.getMacAddress());
        if (connectedDevice == null) {
            Log.e("BLE", "DISCONNECTED == null");
            return;
        }

        cleanServicesAndCharacteristicsForDevice(connectedDevice);
        final RxBleConnection connection = connectedDevice.getConnection();
        int mtu = connection != null ? connection.getMtu() : NO_VALUE;
        BleData.BleDeviceMessage message = converter.convertToBleDeviceMessage(device, mtu, NO_VALUE);
        message = BleData.BleDeviceMessage.newBuilder()
                .setId(message.getId())
                .setName(message.getName())
                .setMtu(message.getMtu())
                .setRssi(message.getRssi())
                .setIsConnected(false)
                .build();
        sendEvent(Event.DisconnectionEvent, message);
        connectingDevices.removeConnectingDeviceSubscription(device.getMacAddress());
    }

    void isDeviceConnected(String macAddress, OnSuccessAction<Boolean> successAction, OnErrorAction errorAction) {
        if (rxBleClient == null) {
            throw new IllegalStateException("BleManager not created when tried to check device connection");
        }

        final RxBleDevice device = rxBleClient.getBleDevice(macAddress);
        if (device == null) {
            errorAction.onError(new RxBleDeviceNotFoundException("Can't find device with id : " + macAddress));
            return;
        }

        boolean connected = device.getConnectionState()
                .equals(RxBleConnection.RxBleConnectionState.CONNECTED);
        successAction.onSuccess(connected);
    }

    void discoverAllServicesAndCharacteristicsForDevice(String deviceId, OnSuccessAction<BleData.BleDeviceMessage> successAction, OnErrorAction errorAction) {
        final Device device = getDeviceOrReject(deviceId, errorAction);
        if (device == null) {
            return;
        }

        safeDiscoverAllServicesAndCharacteristicsForDevice(device, new SafeAction<>(successAction, errorAction));
    }

    private void safeDiscoverAllServicesAndCharacteristicsForDevice(final Device device,
                                                                    final SafeAction<BleData.BleDeviceMessage> safeAction) {
        final RxBleConnection connection = getConnectionOrReject(device, safeAction);
        if (connection == null) {
            safeAction.onError(new ConnectionNotFoundException("Cannot find connection for : " + device.getRxBleDevice().getMacAddress()));
            return;
        }

        connection
                .discoverServices()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<RxBleDeviceServices>() {


                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(RxBleDeviceServices rxBleDeviceServices) {
                        ArrayList<Service> services = new ArrayList<>();
                        for (BluetoothGattService gattService : rxBleDeviceServices.getBluetoothGattServices()) {
                            Service service = new Service(device, gattService);
                            discoveredServices.put(service.getId(), service);
                            services.add(service);

                            for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
                                Characteristic characteristic = new Characteristic(service, gattCharacteristic);
                                discoveredCharacteristics.put(characteristic.getId(), characteristic);
                            }
                        }
                        device.setServices(services);
                        safeAction.onSuccess(converter.convertToBleDeviceMessage(device));
                    }

                    @Override
                    public void onError(Throwable e) {
                        safeAction.onError(e);
                    }
                });
    }


    void cancelDeviceConnection(String deviceId, OnSuccessAction<BleData.BleDeviceMessage> successAction, OnErrorAction errorAction) {
        if (rxBleClient == null) {
            throw new IllegalStateException("BleManager not created when tried cancel device connection");
        }
        final RxBleDevice device = rxBleClient.getBleDevice(deviceId);
        if (connectingDevices.removeConnectingDeviceSubscription(deviceId) && device != null) {
            successAction.onSuccess(converter.convertToBleDeviceMessage(device));
        } else {
            if (device == null) {
                errorAction.onError(new RxBleDeviceNotFoundException("Not found device with id = " + deviceId));
            } else {
                errorAction.onError(new ConnectionNotFoundException("Device with id = " + deviceId + " is not connected."));
            }
        }
    }

    void servicesForDevice(final String deviceId, final OnSuccessAction<BleData.ServiceMessages> onSuccessAction, OnErrorAction errorAction) {
        final Device device = getDeviceOrReject(deviceId, errorAction);
        if (device == null) {
            return;
        }
        final List<Service> services = getServicesOrReject(device, errorAction);
        if (services == null) {
            return;
        }

        final BleData.ServiceMessages.Builder builder = BleData.ServiceMessages.newBuilder();
        for (Service service : services) {
            builder.addServiceMessages(converter.convertToBleServiceMessage(service));
        }

        onSuccessAction.onSuccess(builder.build());
    }

    void characteristicsForDevice(final String deviceId,
                                  final String serviceUUID,
                                  final OnSuccessAction<BleData.CharacteristicMessages> successAction,
                                  final OnErrorAction errorAction) {

        final UUID convertedServiceUUID = UUIDConverter.convert(serviceUUID);
        if (convertedServiceUUID == null) {
            errorAction.onError(new Throwable("UUID conversion error"));
            return;
        }

        final Device device = getDeviceOrReject(deviceId, errorAction);
        if (device == null) {
            return;
        }

        final Service service = device.getServiceByUUID(convertedServiceUUID);
        if (service == null) {
            errorAction.onError(new ServiceNotFoundException("Cannot find service for  UUID : " + serviceUUID));
            return;
        }

        characteristicsForService(service, successAction);
    }

    void characteristicsForService(final int serviceIdentifier,
                                   final OnSuccessAction<BleData.CharacteristicMessages> successAction,
                                   final OnErrorAction errorAction) {
        Service service = discoveredServices.get(serviceIdentifier);
        if (service == null) {
            errorAction.onError(new ServiceNotFoundException("Cannot find service for  service identifier : " + serviceIdentifier));
            return;
        }

        characteristicsForService(service, successAction);
    }

    private void characteristicsForService(final Service service, final OnSuccessAction<BleData.CharacteristicMessages> successAction) {

        final BleData.CharacteristicMessages.Builder builder = BleData.CharacteristicMessages.newBuilder();
        for (Characteristic characteristic : service.getCharacteristics()) {
            builder.addCharacteristicMessage(converter.convertToBleCharacteristicMessage(characteristic, null));
        }

        successAction.onSuccess(builder.build());
    }

    void writeCharacteristicForDevice(final String deviceId,
                                      final String serviceUUID,
                                      final String characteristicUUID,
                                      final String valueBase64,
                                      final Boolean response,
                                      final String transactionId,
                                      final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                                      final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(
                deviceId, serviceUUID, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        writeCharacteristicWithValue(
                characteristic,
                valueBase64,
                response,
                transactionId,
                successAction,
                errorAction);
    }

    void writeCharacteristicForService(final int serviceIdentifier,
                                       final String characteristicUUID,
                                       final String valueBase64,
                                       final Boolean response,
                                       final String transactionId,
                                       final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                                       final OnErrorAction errorAction) {
        final Characteristic characteristic = getCharacteristicOrReject(
                serviceIdentifier, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        writeCharacteristicWithValue(
                characteristic,
                valueBase64,
                response,
                transactionId,
                successAction,
                errorAction);
    }

    void writeCharacteristic(
            final int characteristicIdentifier,
            final String valueBase64,
            final Boolean response,
            final String transactionId,
            final OnSuccessAction<BleData.CharacteristicMessage> successAction,
            final OnErrorAction errorAction) {
        final Characteristic characteristic = getCharacteristicOrReject(characteristicIdentifier, errorAction);
        if (characteristic == null) {
            return;
        }

        writeCharacteristicWithValue(
                characteristic,
                valueBase64,
                response,
                transactionId,
                successAction,
                errorAction);
    }

    private void writeCharacteristicWithValue(final Characteristic characteristic,
                                              final String valueBase64,
                                              final Boolean response,
                                              final String transactionId,
                                              final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                                              final OnErrorAction errorAction) {
        final byte[] value;
        try {
            value = Base64.decode(valueBase64, Base64.NO_WRAP);
        } catch (Throwable e) {
            errorAction.onError(new Throwable("Invalid write data for characteristic"
                    + UUIDConverter.fromUUID(characteristic.getNativeCharacteristic().getUuid()),
                    e));
            return;
        }
        Log.e("BLE", "WRITE WITH RESPONSE: " + response);
        characteristic.getNativeCharacteristic()
                .setWriteType(response ?
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        safeWriteCharacteristicForDevice(
                characteristic,
                value,
                transactionId,
                new SafeAction<>(successAction, errorAction));
    }

    private void safeWriteCharacteristicForDevice(final Characteristic characteristic,
                                                  final byte[] value,
                                                  final String transactionId,
                                                  final SafeAction<BleData.CharacteristicMessage> safeAction) {
        final RxBleConnection connection = getConnectionOrReject(characteristic.getService().getDevice(), safeAction);
        if (connection == null) {
            return;
        }
        Log.e("WRITE:", "" + value.length);
        connection
                .writeCharacteristic(characteristic.getNativeCharacteristic(), value)
                .doOnDispose(new Action() {
                    @Override
                    public void run() {
                        safeAction.onError(new Throwable("Canceled"));
                        transactions.removeTransactionSubscription(transactionId);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<byte[]>() {


                    @Override
                    public void onSubscribe(Disposable d) {
                        transactions.replaceTransactionSubscription(transactionId, d);
                    }

                    @Override
                    public void onSuccess(byte[] bytes) {
                        characteristic.logValue("Write to", bytes);
                        safeAction.onSuccess(converter.convertToBleCharacteristicMessage(characteristic, bytes));
                        transactions.removeTransactionSubscription(transactionId);
                    }

                    @Override
                    public void onError(Throwable e) {
                        if (e instanceof BleCharacteristicNotFoundException) {
                            safeAction.onError(new CharacteristicNotFoundException(
                                    "Characteristic not found for :"
                                            + UUIDConverter.fromUUID(
                                            characteristic.getNativeCharacteristic().getUuid())
                            ));
                            return;
                        }
                        safeAction.onError(e);
                        transactions.removeTransactionSubscription(transactionId);
                    }


                });


    }

    void readCharacteristicForDevice(final String deviceId,
                                     final String serviceUUID,
                                     final String characteristicUUID,
                                     final String transactionId,
                                     final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                                     final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(
                deviceId, serviceUUID, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        safeReadCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }

    void readCharacteristicForService(final int serviceIdentifier,
                                      final String characteristicUUID,
                                      final String transactionId,
                                      final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                                      final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(
                serviceIdentifier, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        safeReadCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }

    void readCharacteristic(final int characteristicIdentifier,
                            final String transactionId,
                            final OnSuccessAction<BleData.CharacteristicMessage> successAction,
                            final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(characteristicIdentifier, errorAction);
        if (characteristic == null) {
            return;
        }

        safeReadCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }


    private void safeReadCharacteristicForDevice(final Characteristic characteristic,
                                                 final String transactionId,
                                                 final SafeAction<BleData.CharacteristicMessage> safeAction) {
        final RxBleConnection connection = getConnectionOrReject(characteristic.getService().getDevice(), safeAction);
        if (connection == null) {
            return;
        }
        Log.e("BLE_LIB", "PREPARE TO READ");
        connection
                .readCharacteristic(characteristic.getNativeCharacteristic())
                .doOnDispose(new Action() {
                    @Override
                    public void run() {
                        safeAction.onError(new Throwable("Canceled"));
                        transactions.removeTransactionSubscription(transactionId);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<byte[]>() {

                    @Override
                    public void onSubscribe(Disposable d) {
                        transactions.replaceTransactionSubscription(transactionId, d);
                    }

                    @Override
                    public void onSuccess(byte[] bytes) {
                        characteristic.logValue("Read from", bytes);
                        safeAction.onSuccess(converter.convertToBleCharacteristicMessage(characteristic, bytes));
                        transactions.removeTransactionSubscription(transactionId);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e("BLE_LIB", "WELL FUCKING FUCK");
                        e.printStackTrace();
                        if (e instanceof BleCharacteristicNotFoundException) {
                            safeAction.onError(new CharacteristicNotFoundException(
                                    "Characteristic not found for :"
                                            + UUIDConverter.fromUUID(
                                            characteristic.getNativeCharacteristic().getUuid())));
                            return;
                        }
                        safeAction.onError(e);
                        transactions.removeTransactionSubscription(transactionId);
                    }

                });


    }

    void monitorCharacteristicForDevice(final String deviceId,
                                        final String serviceUUID,
                                        final String characteristicUUID,
                                        final String transactionId,
                                        final OnSuccessAction<Void> successAction,
                                        final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(
                deviceId, serviceUUID, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        safeMonitorCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }

    void monitorCharacteristicForService(final int serviceIdentifier,
                                         final String characteristicUUID,
                                         final String transactionId,
                                         final OnSuccessAction<Void> successAction,
                                         final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(
                serviceIdentifier, characteristicUUID, errorAction);
        if (characteristic == null) {
            return;
        }

        safeMonitorCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }

    void monitorCharacteristic(final int characteristicIdentifier,
                               final String transactionId,
                               final OnSuccessAction<Void> successAction,
                               final OnErrorAction errorAction) {

        final Characteristic characteristic = getCharacteristicOrReject(characteristicIdentifier, errorAction);
        if (characteristic == null) {
            return;
        }

        safeMonitorCharacteristicForDevice(characteristic, transactionId, new SafeAction<>(successAction, errorAction));
    }

    private void safeMonitorCharacteristicForDevice(final Characteristic characteristic,
                                                    final String transactionId,
                                                    final SafeAction<Void> safeAction) {
        final RxBleConnection connection = getConnectionOrReject(characteristic.getService().getDevice(), safeAction);
        if (connection == null) {
            return;
        }


        final BluetoothGattCharacteristic gattCharacteristic = characteristic.getNativeCharacteristic();
        final int properties = gattCharacteristic.getProperties();
        final boolean notifications = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        final boolean indications = (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        transactions.removeTransactionSubscription(transactionId);
        Observable.just(connection)
                .flatMap(new Function<RxBleConnection, Observable<Observable<byte[]>>>() {
                    @Override
                    public Observable<Observable<byte[]>> apply(RxBleConnection rxBleConnection) throws Exception {
                        if (notifications || indications) {
                            // NotificationSetupMode.COMPAT does not write CCC Descriptor on it's own
                            return connection.setupNotification(gattCharacteristic, NotificationSetupMode.COMPAT);
                        }

                        return Observable.error(new CannotMonitorCharacteristicException(gattCharacteristic));
                    }
                })
                .flatMap(new Function<Observable<byte[]>, Observable<byte[]>>() {
                    @Override
                    public Observable<byte[]> apply(Observable<byte[]> observable) throws Exception {
                        BluetoothGattDescriptor cccDescriptor =
                                gattCharacteristic.getDescriptor(Characteristic.CLIENT_CHARACTERISTIC_CONFIG_UUID);
                        if (cccDescriptor == null) {
                            Log.e("FUCK", "CC is null");
                            return observable;
                        } else {
                            Log.e("FUCK", "CC IS NOT NULL");
                            byte[] enableValue = notifications
                                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            // Keep in mind that every subscription to this observable will initiate another descriptor write
                            return observable.mergeWith(connection.writeDescriptor(cccDescriptor, enableValue).<byte[]>toObservable().ignoreElements());
                        }
                    }

                })
                .doOnDispose(new Action() {
                    @Override
                    public void run() {
                        safeAction.onSuccess(null);
                        transactions.removeTransactionSubscription(transactionId);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<byte[]>() {

                    @Override
                    public void onError(Throwable e) {
                        safeAction.onError(e);
                        transactions.removeTransactionSubscription(transactionId);
                    }

                    @Override
                    public void onComplete() {
                        safeAction.onSuccess(null);
                        transactions.removeTransactionSubscription(transactionId);
                    }

                    @Override
                    public void onSubscribe(Disposable d) {
                        transactions.replaceTransactionSubscription(transactionId, d);
                    }

                    @Override
                    public void onNext(byte[] bytes) {
                        characteristic.logValue("Notification from", bytes);
                        BleData.MonitorCharacteristicMessage monitorCharacteristicMessage = BleData.MonitorCharacteristicMessage.newBuilder()
                                .setTransactionId(transactionId)
                                .setCharacteristicMessage(converter.convertToBleCharacteristicMessage(characteristic, bytes))
                                .build();
                        sendEvent(Event.ReadEvent, monitorCharacteristicMessage);
                    }
                });

        safeAction.onSuccess(null);

    }

    @Nullable
    private Characteristic getCharacteristicOrReject(@NonNull final String deviceId,
                                                     @NonNull final String serviceUUID,
                                                     @NonNull final String characteristicUUID,
                                                     @NonNull OnErrorAction errorAction) {

        final UUID[] UUIDs = UUIDConverter.convert(serviceUUID, characteristicUUID);
        if (UUIDs == null) {
            errorAction.onError(new Throwable("Invalid UUIDs for service : " + serviceUUID + " and characteristic " + characteristicUUID));
            return null;
        }

        final Device device = connectedDevices.get(deviceId);
        if (device == null) {
            errorAction.onError(new RxBleDeviceNotFoundException("Device not found for id : " + deviceId));
            return null;
        }

        final Service service = device.getServiceByUUID(UUIDs[0]);
        if (service == null) {
            errorAction.onError(new ServiceNotFoundException("Service not found for uuid : " + serviceUUID));
            return null;
        }

        final Characteristic characteristic = service.getCharacteristicByUUID(UUIDs[1]);
        if (characteristic == null) {
            errorAction.onError(new ServiceNotFoundException("Characteristic not found for uuid : " + characteristicUUID));
            return null;
        }

        return characteristic;
    }

    @Nullable
    private Characteristic getCharacteristicOrReject(final int serviceIdentifier,
                                                     @NonNull final String characteristicUUID,
                                                     @NonNull OnErrorAction errorAction) {

        final UUID uuid = UUIDConverter.convert(characteristicUUID);
        if (uuid == null) {
            errorAction.onError(new Throwable("UUID parse exception for " + characteristicUUID));
            return null;
        }

        final Service service = discoveredServices.get(serviceIdentifier);
        if (service == null) {
            errorAction.onError(new ServiceNotFoundException("Service not found for identifier " + serviceIdentifier));
            return null;
        }

        final Characteristic characteristic = service.getCharacteristicByUUID(uuid);
        if (characteristic == null) {
            errorAction.onError(new CharacteristicNotFoundException("Characteristic not found for uuid " + uuid));
            return null;
        }
        return characteristic;
    }

    private Characteristic getCharacteristicOrReject(final int characteristicIdentifier,
                                                     OnErrorAction errorAction) {

        final Characteristic characteristic = discoveredCharacteristics.get(characteristicIdentifier);
        if (characteristic == null) {
            errorAction.onError(new CharacteristicNotFoundException("Characteristic not found for identifier " + characteristicIdentifier));
            return null;
        }

        return characteristic;
    }

    private RxBleConnection getConnectionOrReject(final Device device,
                                                  OnErrorAction errorAction) {
        final RxBleConnection connection = device.getConnection();
        if (connection == null) {
            errorAction.onError(new ConnectionNotFoundException("Could not find connection for : " + device.getRxBleDevice().getMacAddress()));
            return null;
        }
        return connection;
    }

    @Nullable
    private List<Service> getServicesOrReject(@NonNull final Device device,
                                              @NonNull OnErrorAction errorAction) {
        final List<Service> services = device.getServices();
        if (services == null) {
            errorAction.onError(new ServiceNotFoundException(device.getRxBleDevice().getMacAddress()));
            return null;
        }
        return services;
    }

    @Nullable
    private Device getDeviceOrReject(final String deviceId, OnErrorAction errorAction) {
        final Device device = connectedDevices.get(deviceId);
        if (device == null) {
            errorAction.onError(new RxBleDeviceNotFoundException(deviceId));
            return null;
        }
        return device;
    }

    private void cleanServicesAndCharacteristicsForDevice(Device device) {
        for (int i = discoveredServices.size() - 1; i >= 0; i--) {
            int key = discoveredServices.keyAt(i);
            Service service = discoveredServices.get(key);

            if (service.getDevice().getRxBleDevice().getMacAddress().equals(device.getRxBleDevice().getMacAddress())) {
                discoveredServices.remove(key);
            }
        }
        for (int i = discoveredCharacteristics.size() - 1; i >= 0; i--) {
            int key = discoveredCharacteristics.keyAt(i);
            Characteristic characteristic = discoveredCharacteristics.get(key);

            if (characteristic.getService().getDevice().getRxBleDevice().getMacAddress().equals(device.getRxBleDevice().getMacAddress())) {
                discoveredCharacteristics.remove(key);
            }
        }
    }

    private <T> void sendEvent(Event event, T value) {
        if (eventDelegate != null) {
            eventDelegate.dispatchEvent(event, value);
        }
    }
}