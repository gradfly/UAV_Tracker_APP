package com.example.dronetracker;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";

    // Target UUID specified by user
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB");
    private static final UUID TARGET_CHARACTERISTIC_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB");

    // Common BLE Serial UUIDs (HM-10, JDY-08 etc.)
    private static final UUID SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");

    // Alternative: Nordic UART Service UUIDs
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private final Handler handler;
    private OnConnectionChangeListener listener;
    private OnDeviceFoundListener scanListener;
    private OnDataReceivedListener dataListener;
    private boolean isConnected = false;
    private boolean isScanning = false;

    public interface OnConnectionChangeListener {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed();
    }

    public interface OnDataReceivedListener {
        void onDataSent(byte[] data);
        void onDataReceived(byte[] data);
    }

    public interface OnDeviceFoundListener {
        void onDeviceFound(BluetoothDevice device);
    }

    public BluetoothManager(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            this.bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setOnConnectionChangeListener(OnConnectionChangeListener listener) {
        this.listener = listener;
    }

    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataListener = listener;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return isConnected && writeCharacteristic != null;
    }

    @SuppressLint("MissingPermission")
    public void startScan(OnDeviceFoundListener scanListener) {
        if (bluetoothLeScanner == null) {
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available");
            return;
        }

        this.scanListener = scanListener;
        if (!isScanning) {
            isScanning = true;
            bluetoothLeScanner.startScan(scanCallback);
            // Stop scanning after a pre-defined scan period.
            handler.postDelayed(this::stopScan, 10000);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (scanListener != null) {
                scanListener.onDeviceFound(result.getDevice());
            }
        }
    };

    @SuppressLint("MissingPermission")
    public void connectToDevice(BluetoothDevice device) {
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        writeCharacteristic = null;
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    @SuppressLint("MissingPermission")
    public void close() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
        writeCharacteristic = null;
    }

    @SuppressLint("MissingPermission")
    public void sendCommand(DroneCommand command) {
        if (!isConnected() || writeCharacteristic == null) {
            return;
        }

        byte[] data = command.toBytes();
        sendRawData(data);
    }

    @SuppressLint("MissingPermission")
    public void sendRawData(byte[] data) {
        if (!isConnected() || writeCharacteristic == null) {
            return;
        }
        writeCharacteristic.setValue(data);
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        bluetoothGatt.writeCharacteristic(writeCharacteristic);
        
        if (dataListener != null) {
            dataListener.onDataSent(data);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                isConnected = false;
                writeCharacteristic = null;
                notifyDisconnected();
                close();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                findWriteCharacteristic(gatt);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
                notifyConnectionFailed();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (dataListener != null) {
                dataListener.onDataReceived(data);
            }
        }
    };

    private void findWriteCharacteristic(BluetoothGatt gatt) {
        // 1. Try target UUID specified by user (usually inside FFF0 service)
        BluetoothGattService service = gatt.getService(TARGET_SERVICE_UUID);
        if (service != null) {
            writeCharacteristic = service.getCharacteristic(TARGET_CHARACTERISTIC_UUID);
        }

        // 2. If not found in FFF0, search all services for characteristic FFF2
        if (writeCharacteristic == null) {
            for (BluetoothGattService s : gatt.getServices()) {
                BluetoothGattCharacteristic c = s.getCharacteristic(TARGET_CHARACTERISTIC_UUID);
                if (c != null) {
                    writeCharacteristic = c;
                    break;
                }
            }
        }

        // 3. Try common BLE serial services (FFE0/FFE1)
        if (writeCharacteristic == null) {
            service = gatt.getService(SERVICE_UUID);
            if (service != null) {
                writeCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
            }
        }

        // Try Nordic UART if not found
        if (writeCharacteristic == null) {
            service = gatt.getService(UART_SERVICE_UUID);
            if (service != null) {
                writeCharacteristic = service.getCharacteristic(UART_TX_CHARACTERISTIC_UUID);
            }
        }

        // Generic fallback: find any characteristic that supports WRITE or WRITE_NO_RESPONSE
        if (writeCharacteristic == null) {
            for (BluetoothGattService s : gatt.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int props = c.getProperties();
                    if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        writeCharacteristic = c;
                        break;
                    }
                }
                if (writeCharacteristic != null) break;
            }
        }

        if (writeCharacteristic != null) {
            isConnected = true;
            
            // Enable notifications for the characteristic if supported
            int props = writeCharacteristic.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                gatt.setCharacteristicNotification(writeCharacteristic, true);
                BluetoothGattDescriptor descriptor = writeCharacteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            }

            notifyConnected();
        } else {
            Log.e(TAG, "No writable characteristic found");
            notifyConnectionFailed();
            disconnect();
        }
    }

    private void notifyConnected() {
        handler.post(() -> {
            if (listener != null) {
                listener.onConnected();
            }
        });
    }

    private void notifyDisconnected() {
        handler.post(() -> {
            if (listener != null) {
                listener.onDisconnected();
            }
        });
    }

    private void notifyConnectionFailed() {
        handler.post(() -> {
            if (listener != null) {
                listener.onConnectionFailed();
            }
        });
    }
}