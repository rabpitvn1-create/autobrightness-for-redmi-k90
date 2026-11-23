package vn.luxguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One visible control. First activation walks through calibration with OEM auto enabled.
 * Subsequent taps toggle LuxGuard on/off. Long press resets calibration.
 */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int NOTIFICATION_REQUEST = 41;
    private static final int WINDOW_MS = 5000;
    private static final int SETTLE_MS = 8500;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<Measurement> readings=new ArrayDeque<>();
    private Button button;
    private TextView status,detail;
    private SensorManager manager;
    private Sensor light;
    private boolean sensorRegistered=false,awaitingWriteSettings=false,calibrating=false;
    private long startMs;
    private String message="";
    private final Runnable refreshTask=new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this,1000);
        }
    };

    private static final class Measurement {
        final long time;
        final float lux;
        final int brightness;
        Measurement(long t,float l,int b){time=t;lux=l;brightness=b;}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(26),dp(26),dp(26),dp(26));
        root.setBackgroundColor(0xFFF7F8FA);
        TextView title=new TextView(this);
        title.setText("LuxGuard");
        title.setTextColor(0xFF18241C);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        detail=new TextView(this);
        detail.setTextSize(15);
        detail.setTextColor(0xFF536358);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);
        dp.topMargin=dp(12);dp.bottomMargin=dp(26);
        root.addView(detail,dp);
        button=new Button(this);
        button.setTextSize(17);button.setAllCaps(false);
        root.addView(button,new LinearLayout.LayoutParams(-1,dp(64)));
        status=new TextView(this);
        status.setTextColor(0xFF566359);status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);
        sp.topMargin=dp(22);
        root.addView(status,sp);
        setContentView(root);
        button.setOnClickListener(v->handleClick());
        button.setOnLongClickListener(v->{
            if (!enabled()) {
                stopCalibration();
                CalibrationStore.clearProfile(this);
                CalibrationStore.prefs(this).edit().putBoolean("calibrating",false).apply();
                message="Đã xóa hồ sơ. Nhấn nút để hiệu chuẩn lại.";
                refresh();
            } else message="Tắt LuxGuard trước khi hiệu chuẩn lại.";
            return true;
        });
        manager=(SensorManager)getSystemService(SENSOR_SERVICE);
        light=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_LIGHT);
        refresh();
    }

    private boolean enabled() {
        return CalibrationStore.prefs(this).getBoolean("enabled",false);
    }
    private boolean canWrite() {return Settings.System.canWrite(this);}
    private void handleClick() {
        if (enabled()) {
            stopService(new Intent(this,BrightnessService.class));
            boolean ok=BrightnessService.restoreOriginalState(this);
            message=ok?"Đã tắt và khôi phục chế độ sáng gốc.":
                "Không khôi phục được chế độ sáng. Kiểm tra quyền Modify system settings.";
            refresh();return;
        }
        if (!canWrite()) {
            awaitingWriteSettings=true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:"+getPackageName())));
            } catch(Exception ex) {message="Không mở được trang cấp quyền: "+ex.getMessage();}
            refresh();return;
        }
        if (light==null) {
            message="Không tìm thấy cảm biến ánh sáng TYPE_LIGHT.";refresh();return;
        }
        if (calibrating) {capture();return;}
        CalibrationCurve curve=CalibrationStore.loadValid(this);
        if (curve==null) {beginCalibration();return;}
        startController();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingWriteSettings) {
            awaitingWriteSettings=false;
            if (canWrite()) {
                if(CalibrationStore.loadValid(this)==null) beginCalibration();
                else startController();
            }
        }
        if (calibrating) registerSensor();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        unregisterSensor();
        super.onPause();
    }
    private void beginCalibration() {
        // Learn only from OEM auto, never from the app's manual output.
        if (!canWrite()) return;
        stopService(new Intent(this,BrightnessService.class));
        if (CalibrationStore.prefs(this).getBoolean("original_saved",false)) {
            if(!BrightnessService.restoreOriginalState(this)) {
                message="Không thể khôi phục chế độ gốc. Chưa bắt đầu hiệu chuẩn.";
                refresh();return;
            }
        }
        boolean changed=Settings.System.putInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        int mode=Settings.System.getInt(getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
        if (!changed || mode!=Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            message="Không thể bật Auto Brightness Xiaomi để hiệu chuẩn.";refresh();return;
        }
        CalibrationStore.prefs(this).edit().putBoolean("calibrating",true).apply();
        calibrating=true;
        readings.clear();
        startMs=android.os.SystemClock.elapsedRealtime();
        message="Đang chạy Auto Brightness Xiaomi. Di chuyển đến từng môi trường sáng khác nhau.";
        registerSensor();refresh();
    }
    private void registerSensor() {
        if (sensorRegistered||!calibrating||manager==null||light==null)return;
        sensorRegistered=manager.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL);
    }
    private void unregisterSensor() {
        if(sensorRegistered && manager!=null)manager.unregisterListener(this);
        sensorRegistered=false;
    }
    private void stopCalibration() {
        calibrating=false;unregisterSensor();readings.clear();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (!calibrating||event.values==null||event.values.length==0) return;
        float lux=event.values[0];
        if (!Float.isFinite(lux)||lux<0||lux>500000) return;
        long now=android.os.SystemClock.elapsedRealtime();
        int mode=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
        if(mode!=Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            stopCalibration();message="Auto Brightness gốc bị tắt, dừng hiệu chuẩn.";refresh();return;
        }
        int b=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
        if(b<1||b>255)return;
        readings.addLast(new Measurement(now,lux,b));
        while(!readings.isEmpty() && now-readings.peekFirst().time>WINDOW_MS)
            readings.removeFirst();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    private void capture() {
        if (android.os.SystemClock.elapsedRealtime()-startMs<SETTLE_MS) {
            message="Chờ ít nhất 9 giây để Xiaomi ổn định ở mức sáng này.";refresh();return;
        }
        List<Measurement> window=new ArrayList<>(readings);
        if(window.size()<8||window.get(window.size()-1).time-window.get(0).time<3500) {
            message="Chưa đủ dữ liệu ổn định trong 4 giây gần nhất.";refresh();return;
        }
        List<Float> luxes=new ArrayList<>();
        List<Integer> brightnesses=new ArrayList<>();
        for(Measurement x:window){luxes.add(x.lux);brightnesses.add(x.brightness);}
        Collections.sort(luxes);Collections.sort(brightnesses);
        float medianLux=luxes.get(luxes.size()/2);
        int medianBrightness=brightnesses.get(brightnesses.size()/2);
        float minLux=luxes.get(0), maxLux=luxes.get(luxes.size()-1);
        int minB=brightnesses.get(0),maxB=brightnesses.get(brightnesses.size()-1);
        if(maxB-minB>2 || Math.log1p(maxLux)-Math.log1p(minLux)>0.20) {
            message="Cảm biến hoặc độ sáng còn dao động. Giữ máy cố định rồi thử lại.";
            refresh();return;
        }
        List<CalibrationCurve.Point> points=CalibrationStore.loadDraft(this);
        for(CalibrationCurve.Point prev:points) {
            if(Math.abs(Math.log1p(prev.lux)-Math.log1p(medianLux))<0.13) {
                message="Mức lux này đã được đo. Hãy chuyển sang nơi sáng/tối khác.";
                refresh();return;
            }
        }
        points.add(new CalibrationCurve.Point(medianLux,medianBrightness));
        if(!CalibrationStore.saveDraft(this,points)) {
            message="Không lưu được dữ liệu hiệu chuẩn.";refresh();return;
        }
        startMs=android.os.SystemClock.elapsedRealtime();
        readings.clear();
        if(points.size()>=CalibrationCurve.REQUIRED_POINTS) {
            String problem=CalibrationCurve.validate(points);
            if(problem==null) {
                boolean saved=CalibrationStore.saveProfile(this,points);
                stopCalibration();
                if(saved) {
                    message="Đã thu thập và kiểm tra 8 điểm. Đang bật chế độ giảm 15%.";
                    startController();
                } else message="Không lưu được hồ sơ hiệu chuẩn.";
            } else {
                // Invalid readings must never activate an unverified curve.
                message=problem+" Đo thêm ở mức sáng khác hoặc nhấn giữ nút để làm lại.";
            }
        } else message="Đã lưu "+points.size()+"/8 điểm. Đổi nơi sáng, chờ 9 giây rồi nhấn lại.";
        refresh();
    }

    private void startController() {
        if(CalibrationStore.loadValid(this)==null) {
            message="Hồ sơ đo không hợp lệ. Không bật LuxGuard.";refresh();return;
        }
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
        try {
            Intent service=new Intent(this,BrightnessService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(service);
            else startService(service);
            message="Đang kiểm tra và chuyển sang MANUAL...";
        }catch(RuntimeException ex) {
            message="Không thể bật dịch vụ: "+ex.getClass().getSimpleName();
        }
        refresh();
    }
    private void refresh() {
        if(button==null)return;
        int n=CalibrationStore.loadDraft(this).size();
        if(enabled()) {
            button.setText("Tắt giảm 15%");
            detail.setText("Đang dùng hồ sơ đo từ máy thật");
        } else if(calibrating) {
            button.setText("Lưu điểm đo ("+n+"/8)");
            detail.setText("Giữ Auto Brightness Xiaomi bật. Đưa máy đến 8 vùng sáng khác nhau, ổn định 9 giây mỗi vùng.");
        } else if(CalibrationStore.loadValid(this)!=null) {
            button.setText("Bật giảm 15%");
            detail.setText("Hồ sơ đã hiệu chuẩn trên firmware hiện tại");
        } else {
            button.setText("Bắt đầu hiệu chuẩn");
            detail.setText("Bước bắt buộc: học độ sáng gốc từ cảm biến và hệ thống. Nhấn giữ nút để xóa dữ liệu hiệu chuẩn.");
        }
        status.setText(message.isEmpty()? (calibrating?
            "Đã thu "+n+" điểm; giữ máy cố định trước mỗi lần bấm." :
            "Chỉ điều khiển trong phạm vi lux đã đo. Ngoài phạm vi: trả về Xiaomi Auto.")
            :message);
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
