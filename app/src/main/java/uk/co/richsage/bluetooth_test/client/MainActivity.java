package uk.co.richsage.bluetooth_test.client;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;


public class MainActivity extends Activity {

    private final int REQUEST_ENABLE_BT = 1;

    private final int MESSAGE_INCOMING = 1;

    private BluetoothAdapter bluetoothAdapter;
    private AcceptThread acceptThread;
    private ConnectedThread connectedThread;

    private void log(String text) {
        TextView textView = (TextView) findViewById(R.id.textView2);
        textView.append(text + "\n");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
    }

    public void onStart() {
        super.onStart();

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            log("Turn BT on");
        } else {
            log("BT is on already");
        }

        // Make this device always discoverable via Bluetooth
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0); // 0 is indefinite
        startActivity(discoverableIntent);
        log("Requested indefinite discovery");

        // Start threads
        log("Starting server thread...");
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode != RESULT_OK) {
                android.util.Log.d("Client", "EEK! Bluetooth not enabled");
            }
        }
    }

    /**
     * Bluetooth server thread
     */
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            // Use a temporary object that is later assigned to mmServerSocket,
            // because mmServerSocket is final
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the client code
                UUID ourUUID = UUID.fromString(getString(R.string.uuid));
                tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("BT_TEST_CLIENT", ourUUID); // UUID identifies this service
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmServerSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    log("In accept thread run() method");
                }
            });

            // Keep listening until exception occurs or a socket is returned
            while(true) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }
                // If a connection was accepted
                if (socket != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            log("Connection accepted");
                        }
                    });

                    // Do work to manage the connection (in a separate thread)
                    manageConnectedSocket(socket);

                    // Close when we're finished
                    try {
                        mmServerSocket.close();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                log("BT connection closed; waiting for new connection...");
                            }
                        });
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }

        /** Will cancel the listening socket, and cause the thread to finish */
        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) { }
        }

        private void manageConnectedSocket(BluetoothSocket socket) {
            connectedThread = null;
            connectedThread = new ConnectedThread(socket);
            connectedThread.start();
        }
    }

    /**
     * Thread to handle read/write to BT socket
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];  // buffer store for the stream
            int bytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    // Send the obtained bytes to the UI activity
                    mHandler.obtainMessage(MESSAGE_INCOMING, bytes, -1, buffer).sendToTarget();
                } catch (IOException e) {
                    android.util.Log.d("Client", "exception when reading from input stream");
                    e.printStackTrace();
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) { }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    /**
     * Handler for incoming data
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_INCOMING:
                log("--- incoming message ---");
                byte[] readBuf = (byte []) msg.obj;
                String message = new String(readBuf, 0, msg.arg1);
                log(message);
                log("--- end of message ---");
                break;
            default:
                log("Another message type: " + msg.what);
        }
        }
    };
}
