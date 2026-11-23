package vn.luxguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
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

public class BrightnessService extends Service implements SensorEventListener {
    public static final String PREFS="luxguard_state";
    private static final String CHANNEL="luxguard_active";
    private static final int NOTIFICATION_ID=8501;
    private static final float FACTOR=0.85f;
    private static final float[] LUX={0f,1f,5f,20f,50f,100f,300f,1000f,5000f,10000f,30000f};
    private static final float[] REF={.055f,.065f,.095f,.145f,.205f,.275f,.395f,.575f,.775f,.90f,1f};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<Float> samples=new ArrayDeque<>();
    private SensorManager sensors;
    private Sensor light;
    private BroadcastReceiver screenReceiver;
    private ContentObserver brightnessObserver;
    private boolean registered=false,screenOn=true;
    private float filteredLux=Float.NaN,userScale=1f;
    private int lastWrite=-1,lastApplied=-1;
    private long lastWriteAt=0,lastBrighten=0,lastDarken=0;

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS,MODE_PRIVATE);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID,notification());
        if (!Settings.System.canWrite(this)) {stopSelf();return;}
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        light=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light==null) {stopSelf();return;}

        // Snapshot original settings, even across an interrupted/restarted service.
        if (!saveOriginalStateOnce()) {stopSelf();return;}
        boolean wrote=Settings.System.putInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int observed=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
        if (!wrote || observed!=Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            restoreOriginalState(this);
            stopSelf();
            return;
        }
        if (!prefs().edit().putBoolean("enabled",true).commit()) {
            restoreOriginalState(this);
            stopSelf();
            return;
        }
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        screenOn=pm==null || pm.isInteractive();
        userScale=prefs().getFloat("user_scale",1f);
        registerScreenReceiver();
        registerBrightnessObserver();
        registerSensor();
    }

    private boolean saveOriginalStateOnce() {
        if (prefs().getBoolean("original_saved",false)) return true;
        int mode=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int brightness=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS,128);
        return prefs().edit().putInt("original_mode",mode)
            .putInt("original_brightness",brightness)
            .putBoolean("original_saved",true).commit();
    }

    private void registerSensor() {
        if (registered || !screenOn || sensors==null || light==null) return;
        registered=sensors.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL,handler);
    }
    private void unregisterSensor() {
        if (registered && sensors!=null) sensors.unregisterListener(this);
        registered=false;
    }
    private void registerScreenReceiver() {
        screenReceiver=new BroadcastReceiver() {
            @Override public void onReceive(Context context,Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn=false;unregisterSensor();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn=true;filteredLux=Float.NaN;samples.clear();registerSensor();
                }
            }
        };
        IntentFilter f=new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver,f);
    }
    private void registerBrightnessObserver() {
        brightnessObserver=new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                int current=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
                if (current<0) return;
                if (current==lastWrite && System.currentTimeMillis()-lastWriteAt<2500) return;
                if (lastApplied>0 && Math.abs(current-lastApplied)>=3 && !Float.isNaN(filteredLux)) {
                    float baseline=curve(filteredLux)*FACTOR;
                    if (baseline>.02f) {
                        float ratio=(current/255f)/baseline;
                        ratio=clamp(ratio,.7f,1f);
                        userScale=clamp(userScale*.75f+ratio*.25f,.7f,1f);
                        prefs().edit().putFloat("user_scale",userScale).apply();
                    }
                }
            }
        };
        getContentResolver().registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,brightnessObserver);
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (!screenOn || event.values==null || event.values.length==0) return;
        float lux=event.values[0];
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux<0f) return;
        samples.addLast(lux);
        while(samples.size()>5)samples.removeFirst();
        float[] values=new float[samples.size()];
        int i=0;for(Float x:samples)values[i++]=x;
        Arrays.sort(values);
        float median=values[values.length/2];
        filteredLux=Float.isNaN(filteredLux)?median:filteredLux*.72f+median*.28f;
        applyTarget();
    }
    private void applyTarget() {
        if (Float.isNaN(filteredLux))return;
        // Output is NEVER applied recursively; it is always computed from fresh lux.
        float normalized=clamp(curve(filteredLux)*FACTOR*userScale,.025f,.85f);
        int target=clamp(Math.round(normalized*255f),1,255);
        int current=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128);
        long now=System.currentTimeMillis();
        int delta=target-current;
        if (Math.abs(delta)<2)return;
        if (delta>0) {
            if (Math.abs(delta)<5 || now-lastBrighten<700)return;
            lastBrighten=now;
        } else {
            if (Math.abs(delta)<4 || now-lastDarken<1100)return;
            lastDarken=now;
        }
        int next=current+clamp(delta,-12,12);
        lastWrite=next;
        lastWriteAt=now;
        try {
            if (Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,next))
                lastApplied=next;
        } catch(SecurityException ex) {stopSelf();}
    }
    private static float curve(float lux) {
        if(lux<=LUX[0])return REF[0];
        for(int i=1;i<LUX.length;i++) {
            if(lux<=LUX[i]) {
                double lo=Math.log10(LUX[i-1]+1),hi=Math.log10(LUX[i]+1);
                float weight=(float)((Math.log10(lux+1)-lo)/(hi-lo));
                return REF[i-1]+(REF[i]-REF[i-1])*weight;
            }
        }
        return REF[REF.length-1];
    }
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private void createChannel() {
        NotificationChannel channel=new NotificationChannel(CHANNEL,"LuxGuard đang hoạt động",
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }
    private Notification notification() {
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_day)
            .setContentTitle("LuxGuard đang bật")
            .setContentText("Điều khiển độ sáng theo cảm biến, hệ số 85%")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        return prefs().getBoolean("enabled",false)?START_STICKY:START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        unregisterSensor();
        if(brightnessObserver!=null)getContentResolver().unregisterContentObserver(brightnessObserver);
        if(screenReceiver!=null)try {unregisterReceiver(screenReceiver);}catch(Exception ignored){}
        // Unplanned system stop must preserve saved state for recovery.
        super.onDestroy();
    }
    public static boolean restoreOriginalState(Context context) {
        if(!Settings.System.canWrite(context))return false;
        android.content.SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(!p.getBoolean("original_saved",false))
            return p.edit().putBoolean("enabled",false).commit();
        int brightness=p.getInt("original_brightness",128);
        int mode=p.getInt("original_mode",Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        try {
            boolean brightnessOk=Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,brightness);
            boolean modeOk=Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,mode);
            boolean verified=modeOk && Settings.System.getInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,-1)==mode;
            if(brightnessOk && verified)
                return p.edit().putBoolean("enabled",false)
                    .putBoolean("original_saved",false).commit();
        } catch(SecurityException ignored){}
        return false;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
}
