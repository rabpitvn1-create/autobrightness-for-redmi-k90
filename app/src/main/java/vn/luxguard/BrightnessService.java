package vn.luxguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Uses only the measured profile. No fallback reference curve is permitted. */
public final class BrightnessService extends Service implements SensorEventListener {
    public static final String PREFS=CalibrationStore.PREFS;
    private static final String CHANNEL="luxguard_active";
    private static final int NOTIFICATION_ID=8501;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<Float> samples=new ArrayDeque<>();
    private SensorManager sensors;
    private Sensor light;
    private BroadcastReceiver screenReceiver;
    private CalibrationCurve curve;
    private boolean sensorRegistered=false,screenOn=true,initialized=false;
    private float filteredLux=Float.NaN;
    private int lastWrite=-1;
    private long lastWriteAt=0;

    private android.content.SharedPreferences prefs(){return CalibrationStore.prefs(this);}

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID,notification());
        if (!Settings.System.canWrite(this)) {stopSelf();return;}
        curve=CalibrationStore.loadValid(this);
        if(curve==null) {restoreOriginalState(this);stopSelf();return;}
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        light=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if(light==null) {restoreOriginalState(this);stopSelf();return;}

        if(!saveOriginalStateOnce()) {stopSelf();return;}
        // Work is gated on verified MANUAL mode.
        boolean ok=Settings.System.putInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int actual=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
        if(!ok || actual!=Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            restoreOriginalState(this);stopSelf();return;
        }
        // Quickly lower brightness while waiting for the first sensor event.
        Settings.System.putInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS,curve.minimumTarget());
        if(!prefs().edit().putBoolean("enabled",true).commit()) {
            restoreOriginalState(this);stopSelf();return;
        }
        initialized=true;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        screenOn=pm==null||pm.isInteractive();
        registerScreenReceiver();
        registerSensor();
    }
    private boolean saveOriginalStateOnce() {
        if(prefs().getBoolean("original_saved",false)) return true;
        int mode=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int brightness=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS,128);
        return prefs().edit().putInt("original_mode",mode)
            .putInt("original_brightness",brightness)
            .putBoolean("original_saved",true).commit();
    }
    private void registerSensor() {
        if(sensorRegistered||!screenOn||sensors==null||light==null)return;
        sensorRegistered=sensors.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL,handler);
        if(!sensorRegistered) {
            restoreOriginalState(this);stopSelf();
        }
    }
    private void unregisterSensor() {
        if(sensorRegistered&&sensors!=null)sensors.unregisterListener(this);
        sensorRegistered=false;
    }
    private void registerScreenReceiver() {
        screenReceiver=new BroadcastReceiver() {
            @Override public void onReceive(Context context,Intent intent) {
                if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn=false;unregisterSensor();
                } else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn=true;filteredLux=Float.NaN;samples.clear();registerSensor();
                }
            }
        };
        IntentFilter f=new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver,f);
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(!initialized||!screenOn||event.values==null||event.values.length==0)return;
        int mode=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
        if(mode!=Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            // User or OS changed mode. Do not fight it.
            prefs().edit().putBoolean("enabled",false).putBoolean("original_saved",false).commit();
            stopSelf();return;
        }
        float lux=event.values[0];
        if(!Float.isFinite(lux)||lux<0)return;
        samples.addLast(lux);
        while(samples.size()>5)samples.removeFirst();
        float[] sorted=new float[samples.size()];
        int n=0;for(Float l:samples)sorted[n++]=l;
        Arrays.sort(sorted);
        float median=sorted[sorted.length/2];
        filteredLux=Float.isNaN(filteredLux)?median:filteredLux*0.72f+median*0.28f;
        int target=curve.target(filteredLux);
        if(target<0) {
            // Fail closed: there is no OEM reference outside measured range.
            restoreOriginalState(this);stopSelf();return;
        }
        int current=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
        if(current<0) {restoreOriginalState(this);stopSelf();return;}
        long now=android.os.SystemClock.elapsedRealtime();
        if(Math.abs(target-current)<2 || now-lastWriteAt<350)return;
        // Never brighten above the measured conservative target while ramping.
        int next=target<current ? target : Math.min(target,current+8);
        if(next==lastWrite)return;
        lastWrite=next;lastWriteAt=now;
        try {
            if(!Settings.System.putInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,next)) {
                restoreOriginalState(this);stopSelf();
            }
        } catch(SecurityException ex) {restoreOriginalState(this);stopSelf();}
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    private void createChannel() {
        NotificationChannel channel=new NotificationChannel(CHANNEL,"LuxGuard đang hoạt động",
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    private Notification notification() {
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_day)
            .setContentTitle("LuxGuard đang bật")
            .setContentText("Đang áp dụng 85% đường cong hiệu chuẩn. Ngoài vùng đo: trả về Auto.")
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        return prefs().getBoolean("enabled",false)?START_STICKY:START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        unregisterSensor();
        if(screenReceiver!=null)try {unregisterReceiver(screenReceiver);}catch(Exception ignored){}
        super.onDestroy();
    }
    public static boolean restoreOriginalState(Context context) {
        if(!Settings.System.canWrite(context))return false;
        android.content.SharedPreferences p=CalibrationStore.prefs(context);
        if(!p.getBoolean("original_saved",false))
            return p.edit().putBoolean("enabled",false).commit();
        int brightness=p.getInt("original_brightness",128);
        int mode=p.getInt("original_mode",Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        try {
            boolean brightnessOk=Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,brightness);
            boolean modeOk=Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,mode);
            int verified=Settings.System.getInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
            if(brightnessOk && modeOk && verified==mode)
                return p.edit().putBoolean("enabled",false)
                    .putBoolean("original_saved",false).commit();
        }catch(SecurityException ignored){}
        return false;
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
