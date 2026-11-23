package vn.luxguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 41;
    private Button button;
    private TextView status;
    private boolean awaitingWriteSettings;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(0xFFF7F8FA);
        TextView title = new TextView(this);
        title.setText("LuxGuard");
        title.setTextColor(0xFF17211C);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));
        TextView explanation = new TextView(this);
        explanation.setText("Auto brightness dịu hơn 15%\nĐiều chỉnh độ sáng màn hình thật");
        explanation.setTextColor(0xFF526057);
        explanation.setTextSize(15);
        explanation.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1,-2);
        ep.topMargin=dp(12); ep.bottomMargin=dp(34);
        root.addView(explanation, ep);
        button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(18);
        root.addView(button, new LinearLayout.LayoutParams(-1,dp(60)));
        button.setOnClickListener(v -> toggle());
        status = new TextView(this);
        status.setTextColor(0xFF526057);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,-2);
        sp.topMargin=dp(20);
        root.addView(status,sp);
        setContentView(root);
        refresh();
    }

    private boolean enabled() {
        return getSharedPreferences(BrightnessService.PREFS,MODE_PRIVATE)
            .getBoolean("enabled",false);
    }

    private void toggle() {
        if (enabled()) {
            // Stop sensor writes before restoring the saved system settings.
            stopService(new Intent(this,BrightnessService.class));
            if (BrightnessService.restoreOriginalState(this)) refresh();
            else status.setText("Không khôi phục được độ sáng gốc. Cấp lại quyền sửa cài đặt rồi thử lại.");
            return;
        }
        if (!Settings.System.canWrite(this)) {
            awaitingWriteSettings=true;
            Intent intent=new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:"+getPackageName()));
            startActivity(intent);
            return;
        }
        startController();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingWriteSettings) {
            awaitingWriteSettings=false;
            if (Settings.System.canWrite(this)) startController();
        }
        refresh();
    }

    private void startController() {
        if (Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
        try {
            Intent service = new Intent(this,BrightnessService.class);
            if (Build.VERSION.SDK_INT>=26) startForegroundService(service);
            else startService(service);
            status.setText("Đang kích hoạt...");
        } catch (RuntimeException ex) {
            status.setText("Không thể chạy dịch vụ: "+ex.getClass().getSimpleName());
        }
    }

    private void refresh() {
        if (button==null) return;
        button.setText(enabled() ? "Tắt giảm 15%" : "Bật giảm 15%");
        status.setText(enabled()
            ? "Đang bật. Auto Brightness gốc được chuyển sang Manual."
            : "Đang tắt. Trạng thái độ sáng gốc được khôi phục khi tắt bình thường.");
    }
    private int dp(int v) {return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
