package com.mihan.englishaitutor.v2;

import java.util.Arrays;

/**
 * Exact audio clock preservation and residual conservation.
 * Exercises the SAME 512-point sqrt-Hann STFT and overlap-add used by GTCRN.
 * Unlike a model-specific smoke test, identity processing has a known exact
 * output and catches half-window slip and sample-rate drift.
 */
public final class GtcrnStftTest {
    private static int checks;
    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void test(int total) {
        GtcrnStft processor = new GtcrnStft();
        float[] signal = new float[total], restored = new float[total];
        for (int i = 0; i < total; i++)
            signal[i] = (float) (0.24 * Math.sin(i * .17)
                    + .2 * Math.sin(i * .051)
                    + (i % 701 < 20 ? .22 : 0));
        float[] frame = new float[GtcrnStft.FFT_SIZE];
        float[] fft = new float[GtcrnStft.COMPLEX_LENGTH];
        float[] reconstructed = new float[GtcrnStft.FFT_SIZE];
        float[] pending = new float[GtcrnStft.FFT_SIZE];
        float[] power = new float[GtcrnStft.FFT_SIZE];
        int count = 0;
        for (int start = -GtcrnStft.HOP; start <= total; start += GtcrnStft.HOP) {
            Arrays.fill(frame, 0f);
            for (int j = 0; j < frame.length; j++) {
                int source = start + j;
                if (source >= 0 && source < total) frame[j] = signal[source];
            }
            processor.forward(frame, fft);
            processor.inverse(fft, reconstructed);
            for (int j = 0; j < frame.length; j++) {
                pending[j] += reconstructed[j];
                power[j] += processor.windowPower(j);
            }
            for (int j = 0; j < GtcrnStft.HOP; j++) {
                int at = start + j;
                if (at >= 0 && at < total) {
                    restored[at] = pending[j] / power[j];
                    count++;
                }
            }
            System.arraycopy(pending, GtcrnStft.HOP, pending, 0, GtcrnStft.HOP);
            System.arraycopy(power, GtcrnStft.HOP, power, 0, GtcrnStft.HOP);
            Arrays.fill(pending, GtcrnStft.HOP, pending.length, 0f);
            Arrays.fill(power, GtcrnStft.HOP, power.length, 0f);
        }
        ok(count == total, "sample count exact at " + total);
        double maxDiff = 0;
        for (int i = 0; i < total; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(signal[i] - restored[i]));
            float residual = signal[i] - restored[i];
            ok(Float.isFinite(residual), "finite background residual");
        }
        ok(maxDiff < 0.00005, "no clock drift or FFT window gain at " + total
                + " maxDiff=" + maxDiff);
    }

    public static void main(String[] args) {
        test(1024);
        test(16037);
        test(16000);
        test(353);
        System.out.println("PASS " + checks + " time-aligned GTCRN frame and residual tests");
    }
}
