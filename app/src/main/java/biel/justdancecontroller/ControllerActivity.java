package biel.justdancecontroller;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;

import android.support.annotation.NonNull;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.gesture.GestureOverlayView;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ControllerActivity extends Activity implements SensorEventListener {

    private long lastBackPress = 0;
    private Toast backToast;
    private static final int totalBackPresses = 3;
    private static final long maxDelayBetweenBackPresses = 500;
    private int backPresses = 0;
    private PowerManager.WakeLock wl;
    private DatagramSocket udpSocket;
    private final byte[] sendBuffer = new byte[27];
    private final ScheduledExecutorService sendExecutor = Executors.newSingleThreadScheduledExecutor();
    private DatagramPacket sendPacket;
    private final AtomicButtonMask buttonMask = new AtomicButtonMask();
    private final Map<Integer, Integer> maskMap = new HashMap<>();
    private AtomicAccelerometerData accelerometer;
    private final float[] localAccelerometer = new float[3];
    private float lastX = 0.f;
    private float lastY = 0.f;
    private final AtomicIRData ir = new AtomicIRData();
    private final float[] localIR = new float[2];
    private SharedPreferences settings;
    final int accSensorType = Sensor.TYPE_ACCELEROMETER;
    private float scalingFactor;
    private float gravityScalingFactor;
    private boolean freeFallFrame;
    int period;
    private float GAMMA;
    private float CENTER;

    @SuppressLint("ShowToast")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);
        backToast = Toast.makeText(getApplicationContext(), null, Toast.LENGTH_SHORT);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //noinspection deprecation
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "jdctrl");

        settings = PreferenceManager.getDefaultSharedPreferences(this);
        scalingFactor = settings.getInt("scaling-factor", 100) / 100F;
        freeFallFrame = settings.getBoolean("free-fall-frame", false);
        gravityScalingFactor = settings.getInt("gravity-scaling-factor", 100) / 100F;
        period = settings.getInt("packet-delay", 33);
        ALPHA = settings.getInt("low-pass-alpha", 15) / 100F;
        GAMMA = settings.getInt("acceleration-gamma", 1) / 100F;
        CENTER = settings.getInt("acceleration-center", 450) / 100F;
        accelerometer = new AtomicAccelerometerData(ALPHA, GAMMA, CENTER);

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if(freeFallFrame){
            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                    SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
                    SensorManager.SENSOR_DELAY_GAME);
        }else{
            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_GAME);
        }


        final boolean absolute = settings.getBoolean("abs-ir-pointer", false);
        Toast.makeText(this, Boolean.toString(absolute), Toast.LENGTH_LONG).show();
        GestureOverlayView gestureIR = (GestureOverlayView) findViewById(R.id.gesture_ir);
        gestureIR.setUncertainGestureColor(absolute ? Color.RED : Color.GREEN);
        System.out.println(gestureIR.getMeasuredWidth() + "x" + gestureIR.getHeight());
        gestureIR.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                    float x, y;
                    if(absolute){
                        x = (event.getX() / v.getWidth()) * 2 - 1;
                        y = ((v.getHeight() - event.getY()) / v.getHeight()) * 2 - 1;
                        ir.set(x, y);
                    }else{
                       // If action == down, click A
                        float ratio = 2.f/Math.max(v.getWidth(), v.getHeight());
                        x = event.getX() * ratio;
                        y = event.getY() * ratio;
                        if (action == MotionEvent.ACTION_MOVE) {
                            ir.add(x - lastX, -(y - lastY));
                        }else {
                            //pressButton(R.id.button_a);
                        }
                        lastX = x;
                        lastY = y;
                    }

                }
                return true;
            }
        });

        maskMap.put(R.id.button_1, 1);
        maskMap.put(R.id.button_2, 1 << 1);
        maskMap.put(R.id.button_a, 1 << 2);
        maskMap.put(R.id.button_b, 1 << 3);
        maskMap.put(R.id.button_plus, 1 << 4);
        maskMap.put(R.id.button_minus, 1 << 5);
        maskMap.put(R.id.button_home, 1 << 6);
        maskMap.put(R.id.button_up, 1 << 7);
        maskMap.put(R.id.button_down, 1 << 8);
        maskMap.put(R.id.button_left, 1 << 9);
        maskMap.put(R.id.button_right, 1 << 10);
        maskMap.put(R.id.button_ul, 1 << 7 | 1 << 9);
        maskMap.put(R.id.button_ur, 1 << 7 | 1 << 10);
        maskMap.put(R.id.button_dl, 1 << 8 | 1 << 9);
        maskMap.put(R.id.button_dr, 1 << 8 | 1 << 10);

        for (Map.Entry<Integer, Integer> entry : maskMap.entrySet()) {
            Button b = (Button) findViewById(entry.getKey());
            b.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                        pressButton(v.getId());
                    }
                    return true;
                }
            });
        }

        Intent intent = getIntent();
        final String serverAddress = intent.getStringExtra("address");
        final int serverPort = intent.getIntExtra("port", 0);

        Runnable setupSendRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    udpSocket = new DatagramSocket();
                }
                catch (SocketException e) {
                    disconnect(getResources().getString(R.string.socket_failed));
                    return;
                }

                sendBuffer[0] = (byte) 0xde;
                /* Accelerometer,buttons and IR */
                sendBuffer[2] = (byte) 0x7;

                try {
                    sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length,
                            InetAddress.getByName(serverAddress), serverPort);
                }
                catch (UnknownHostException e) {
                    disconnect(getResources().getString(R.string.resolv_failed));
                    return;
                }

                scheduleSend();
            }
        };

        sendExecutor.schedule(setupSendRunnable, 0, TimeUnit.MILLISECONDS);
    }
    private void pressButton(int v){
        buttonMask.xor(maskMap.get(v));
    }
    private void scheduleSend() {
        Runnable sendRunnable = new Runnable() {
            @Override
            public void run() {
                int mask = buttonMask.get();
                accelerometer.get(localAccelerometer);
                ir.get(localIR);

                int offset = 3;
                for (int i = 0; i < 3; i++) {
                    /* Divide by Earth's gravity to get the acceleration in Gs */
                    //Change this to a high pass filter
                    int b = (int) ((localAccelerometer[i] / 9.80665f) * 1024.f * 1024.f);
                    sendBuffer[offset+3] = (byte) (b & 0xff);
                    sendBuffer[offset+2] = (byte) ((b >> 8) & 0xff);
                    sendBuffer[offset+1] = (byte) ((b >> 16) & 0xff);
                    sendBuffer[offset] = (byte) ((b >> 24) & 0xff);

                    offset += 4;
                }

                sendBuffer[offset+3] = (byte) (mask & 0xff);
                sendBuffer[offset+2] = (byte) ((mask >> 8) & 0xff);
                sendBuffer[offset+1] = (byte) ((mask >> 16) & 0xff);
                sendBuffer[offset] = (byte) ((mask >> 24) & 0xff);
                offset += 4;

                for (int i = 0; i < 2; i++) {
                    int b = (int) (localIR[i] * 1024.f * 1024.f);
                    sendBuffer[offset+3] = (byte) (b & 0xff);
                    sendBuffer[offset+2] = (byte) ((b >> 8) & 0xff);
                    sendBuffer[offset+1] = (byte) ((b >> 16) & 0xff);
                    sendBuffer[offset] = (byte) ((b >> 24) & 0xff);

                    offset += 4;
                }


                try {
                    udpSocket.send(sendPacket);
                }
                catch (IOException e) {
                }
            }
        };


        sendExecutor.scheduleAtFixedRate(sendRunnable, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if(sensor.getType() == accSensorType){ //Swap with accelerometer
            //Toast.makeText(this,"Accelerometer accuracy: " + accuracy, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
       // final float maximumRange = event.sensor.getMaximumRange();
        //Toast.makeText(this,"maximumRange: " + maximumRange, Toast.LENGTH_SHORT).show();
        if(freeFallFrame)
        if(event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION)
        accelerometer.setV(event.values, scalingFactor);

        if(event.sensor.getType() == Sensor.TYPE_GRAVITY)
            accelerometer.setG(event.values, gravityScalingFactor);
    }

    /*
     * time smoothing constant for low-pass filter
     * 0 ≤ alpha ≤ 1 ; a smaller value basically means more smoothing
     * See: http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization
     */
    static float ALPHA;

    /**
     * http://en.wikipedia.org/wiki/Low-pass_filter#Algorithmic_implementation
     * http://developer.android.com/reference/android/hardware/SensorEvent.html#values
     */

    @Override
    protected void onResume() {
        wl.acquire();
        super.onResume();
    }

    @Override
    protected void onPause() {
        wl.release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sendExecutor.shutdown();
        try {
            sendExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
        udpSocket.close();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastBackPress > maxDelayBetweenBackPresses) {
                backPresses = 1;
            }
            else {
                backPresses++;
            }
            lastBackPress = currentTime;

            if (backPresses == totalBackPresses) {
                backToast.cancel();
                disconnect(null);
            }
            else {

                backToast.setText((totalBackPresses - backPresses)
                        + getResources().getString(R.string.presses_to_go));
                backToast.show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void disconnect(String errorMessage) {
        Intent intent = new Intent(this, MainActivity.class);
        if (errorMessage != null) {
            intent.putExtra("error", errorMessage);
        }
        startActivity(intent);
        finish();
    }
}

class AtomicButtonMask {
    private int mask = 0;

    public synchronized int get() {
        return mask;
    }

    public synchronized void xor(int v) {
        mask ^= v;
    }
}

class AtomicAccelerometerData {
    float ALPHA;
    float GAMMA;
    float CENTER;
    private final float[] v = new float[3];
    private final float[] g = new float[3];

    public AtomicAccelerometerData(float ALPHA, float GAMMA, float CENTER) {
        this.ALPHA = ALPHA;
        this.GAMMA = GAMMA;
        this.CENTER = CENTER;
    }

    public synchronized void get(float r[]) {
        for(int i=0; i < 3; i++){
            r[i] = v[i] - g[i];
        }

    }
    protected float[] lowPass( float[] input, float[] output) {
        if ( output == null ) return input;

        for ( int i=0; i<input.length; i++ ) {
            output[i] = output[i] + ALPHA * (input[i] - output[i]);
        }
        return output;
    }
    protected float[] power( float[] input, float[] output) {
        if ( output == null ) return input;
        float length = 0;
        for ( int i=0; i<input.length; i++ ) length += Math.pow(input[i],2);
        length = (float) Math.sqrt(length);
        float factor = (float) ((Math.pow(length / CENTER, GAMMA) * CENTER) / length);
        for ( int i=0; i<input.length; i++ ) {
            output[i] = input[i] * factor;
        }
        return output;
    }
    public synchronized void setV(float n[], float scaling) {
        power(n, n);
        n[0] = -n[0] * scaling;
        n[1] = -n[1] * scaling;
        n[2] = n[2] * scaling;
        lowPass(n, v);
    }
    public synchronized void setG(float n[], float scaling) {
        n[0] = -n[0] * scaling;
        n[1] = -n[1] * scaling;
        n[2] = n[2] * scaling;
        lowPass(n, g);
    }
}

class AtomicIRData {
    private final float[] v = new float[2];

    public AtomicIRData() {
        v[0] = 0.5f;
        v[1] = 0.5f;
    }

    public synchronized void get(float r[]) {
        r[0] = v[0];
        r[1] = v[1];
    }
    public synchronized void set(float x, float y) {
        v[0] = x;
        v[0] = Math.max(Math.min(v[0], 1.1f), -1.1f);
        v[1] = y;
        v[1] = Math.max(Math.min(v[1], 1.1f), -1.1f);
    }
    public synchronized void add(float x, float y) {
        v[0] += x;
        v[0] = Math.max(Math.min(v[0], 1.1f), -1.1f);
        v[1] += y;
        v[1] = Math.max(Math.min(v[1], 1.1f), -1.1f);
    }

}