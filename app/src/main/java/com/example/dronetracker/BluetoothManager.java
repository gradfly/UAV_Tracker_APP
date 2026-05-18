package com.example.dronetracker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private static final UUID UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private ConnectThread connectThread;
    private Handler handler;
    private OnConnectionChangeListener listener;

    public interface OnConnectionChangeListener {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed();
    }

    public BluetoothManager(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setOnConnectionChangeListener(OnConnectionChangeListener listener) {
        this.listener = listener;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public void connectToDevice(BluetoothDevice device) {
        if (connectThread != null && connectThread.isAlive()) {
            connectThread.cancel();
        }
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    public void disconnect() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        closeSocket();
        notifyDisconnected();
    }

    private void closeSocket() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
    }

    public void sendCommand(DroneCommand command) {
        if (!isConnected() || outputStream == null) {
            return;
        }
        try {
            outputStream.write(command.toBytes());
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error sending command", e);
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

    private class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private volatile boolean running;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            this.running = true;
        }

        @Override
        public void run() {
            try {
                socket = device.createRfcommSocketToServiceRecord(UUID_SPP);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                outputStream = socket.getOutputStream();
                notifyConnected();
            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                closeSocket();
                notifyConnectionFailed();
            }
        }

        public void cancel() {
            running = false;
            closeSocket();
        }
    }
}