package vn.luxguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences(BrightnessService.PREFS,Context.MODE_PRIVATE)
                .getBoolean("enabled",false)) return;
        try {
            Intent service = new Intent(context,BrightnessService.class);
            if (Build.VERSION.SDK_INT>=26) context.startForegroundService(service);
            else context.startService(service);
        } catch (RuntimeException ignored) {
            // HyperOS may reject background starts; user can open LuxGuard again.
        }
    }
}
