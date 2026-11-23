package vn.luxguard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CalibrationCurveTest {
    private List<CalibrationCurve.Point> examples() {
        return Arrays.asList(
            new CalibrationCurve.Point(0,12),new CalibrationCurve.Point(3,16),
            new CalibrationCurve.Point(9,22),new CalibrationCurve.Point(27,31),
            new CalibrationCurve.Point(70,47),new CalibrationCurve.Point(160,65),
            new CalibrationCurve.Point(420,87),new CalibrationCurve.Point(1600,135));
    }
    @Test public void rejectsFlatSettingsEvenAcrossManyLux() {
        List<CalibrationCurve.Point> flat=new ArrayList<>();
        float[] lux={0,3,9,27,70,160,420,1600};
        for(float l:lux)flat.add(new CalibrationCurve.Point(l,128));
        assertNotNull(CalibrationCurve.validate(flat));
    }
    @Test public void rejectsNonMonotonicSamples() {
        List<CalibrationCurve.Point> points=new ArrayList<>(examples());
        points.set(4,new CalibrationCurve.Point(70,15));
        assertNotNull(CalibrationCurve.validate(points));
    }
    @Test public void acceptsDiverseMeasurements() {
        assertNull(CalibrationCurve.validate(examples()));
    }
    @Test public void lowerEnvelopeIsNeverBrighterThanMeasuredEndpoints() {
        CalibrationCurve curve=new CalibrationCurve(examples());
        for(int i=0;i<curve.points().size()-1;i++) {
            CalibrationCurve.Point a=curve.points().get(i),b=curve.points().get(i+1);
            for(int j=1;j<20;j++) {
                float lux=a.lux+(b.lux-a.lux)*j/20f;
                int t=curve.target(lux);
                assertTrue(t>=1);
                assertTrue(t<=Math.floor(0.85*Math.min(a.brightness,b.brightness)));
            }
        }
    }
    @Test public void noExtrapolation() {
        CalibrationCurve curve=new CalibrationCurve(examples());
        assertEquals(-1,curve.target(-1));
        assertEquals(-1,curve.target(30000));
    }
}
