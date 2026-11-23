package vn.luxguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Calibrated brightness ceiling. No factory curve or made-up lux reference is used. */
public final class CalibrationCurve {
    public static final int REQUIRED_POINTS = 8;
    private static final double FACTOR = 0.85;
    private final List<Point> points;

    public static final class Point {
        public final float lux;
        public final int brightness;
        public Point(float lux, int brightness) {
            this.lux = lux;
            this.brightness = brightness;
        }
    }

    public CalibrationCurve(List<Point> readings) {
        String problem = validate(readings);
        if (problem != null) throw new IllegalArgumentException(problem);
        List<Point> ordered = new ArrayList<>(readings);
        Collections.sort(ordered, Comparator.comparingDouble(p -> p.lux));
        this.points = Collections.unmodifiableList(ordered);
    }

    public List<Point> points() { return points; }
    public float minimumLux() { return points.get(0).lux; }
    public float maximumLux() { return points.get(points.size() - 1).lux; }

    public static String validate(List<Point> input) {
        if (input == null || input.size() < REQUIRED_POINTS)
            return "Cần ít nhất 8 điểm đo độc lập.";
        if (input.size() > 24) return "Quá nhiều điểm đo; hãy hiệu chuẩn lại.";
        List<Point> p = new ArrayList<>(input);
        Collections.sort(p, Comparator.comparingDouble(x -> x.lux));
        int minB = 256, maxB = 0, distinct = 0, lastDistinct = -1000;
        for (int i = 0; i < p.size(); i++) {
            Point x = p.get(i);
            if (!Float.isFinite(x.lux) || x.lux < 0 || x.lux > 500000f
                    || x.brightness < 2 || x.brightness > 255)
                return "Dữ liệu độ sáng/lux không hợp lệ.";
            minB = Math.min(minB, x.brightness);
            maxB = Math.max(maxB, x.brightness);
            if (x.brightness - lastDistinct >= 4) {
                distinct++;
                lastDistinct = x.brightness;
            }
            if (i > 0) {
                Point prev = p.get(i - 1);
                if (Math.log1p(x.lux) - Math.log1p(prev.lux) < 0.13)
                    return "Các mức lux quá gần nhau, cần đổi môi trường sáng.";
                if (x.brightness + 2 < prev.brightness)
                    return "Độ sáng OEM giảm khi lux tăng. Mẫu chưa ổn định; hãy hiệu chuẩn lại.";
            }
        }
        if (Math.log1p(p.get(p.size()-1).lux)-Math.log1p(p.get(0).lux)<Math.log(20.0))
            return "Các điểm đo chưa trải rộng ít nhất 20 lần trên trục lux+1.";
        if (maxB - minB < 12 || distinct < 3)
            return "SCREEN_BRIGHTNESS chưa phản ánh thay đổi OEM rõ ràng. Không được sử dụng đường cong này.";
        return null;
    }

    /**
     * Conservative LOWER ENVELOPE: between two readings, use the lower endpoint,
     * not an unverified interpolated value. Minus 2 ticks is a small safety margin.
     * Return -1 outside measured lux range: NO EXTRAPOLATION.
     */
    public int target(float lux) {
        if (!Float.isFinite(lux) || lux < minimumLux() || lux > maximumLux())
            return -1;
        int n = points.size();
        int reference = points.get(n-1).brightness;
        for (int i = 1; i < n; i++) {
            if (lux < points.get(i).lux) {
                reference = Math.min(points.get(i-1).brightness,points.get(i).brightness);
                break;
            }
        }
        return Math.max(1, (int) Math.floor(FACTOR * Math.max(1,reference-2)));
    }

    public int minimumTarget() {
        return Math.max(1,(int)Math.floor(FACTOR*Math.max(1,points.get(0).brightness-2)));
    }
}
