package vn.luxguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Calibration belongs to exactly this device firmware. No network or third-party SDK. */
public final class CalibrationStore {
    public static final String PREFS = "luxguard_state";
    private static final String PROFILE = "measured_profile_v2";
    private static final String DRAFT = "draft_samples_v2";
    private static final int SCHEMA = 2;
    private static final long MAX_AGE = 30L * 24L * 60L * 60L * 1000L;

    private CalibrationStore() {}
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    private static JSONArray array(List<CalibrationCurve.Point> points) throws Exception {
        JSONArray a=new JSONArray();
        for (CalibrationCurve.Point p:points) {
            JSONObject entry=new JSONObject();
            entry.put("lux",p.lux);
            entry.put("brightness",p.brightness);
            a.put(entry);
        }
        return a;
    }
    private static List<CalibrationCurve.Point> read(JSONArray a) throws Exception {
        List<CalibrationCurve.Point> list=new ArrayList<>();
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            list.add(new CalibrationCurve.Point((float)o.getDouble("lux"),o.getInt("brightness")));
        }
        return list;
    }
    public static List<CalibrationCurve.Point> loadDraft(Context c) {
        try { return read(new JSONArray(prefs(c).getString(DRAFT,"[]"))); }
        catch(Exception bad) {return new ArrayList<>();}
    }
    public static boolean saveDraft(Context c,List<CalibrationCurve.Point> points) {
        try {return prefs(c).edit().putString(DRAFT,array(points).toString()).commit();}
        catch(Exception bad) {return false;}
    }
    public static boolean saveProfile(Context c,List<CalibrationCurve.Point> points) {
        try {
            CalibrationCurve curve=new CalibrationCurve(points);
            JSONObject o=new JSONObject();
            o.put("schema",SCHEMA);
            o.put("fingerprint",Build.FINGERPRINT);
            o.put("created",System.currentTimeMillis());
            o.put("points",array(curve.points()));
            return prefs(c).edit().putString(PROFILE,o.toString()).remove(DRAFT)
                    .putBoolean("calibrating",false).commit();
        } catch(Exception bad) {return false;}
    }
    public static CalibrationCurve loadValid(Context c) {
        try {
            JSONObject o=new JSONObject(prefs(c).getString(PROFILE,""));
            if(o.getInt("schema")!=SCHEMA || !Build.FINGERPRINT.equals(o.getString("fingerprint")))
                return null;
            long age=System.currentTimeMillis()-o.getLong("created");
            if(age<0 || age>MAX_AGE) return null;
            return new CalibrationCurve(read(o.getJSONArray("points")));
        } catch(Exception bad) {return null;}
    }
    public static void clearProfile(Context c) {
        prefs(c).edit().remove(PROFILE).remove(DRAFT).remove("user_scale").apply();
    }
}
