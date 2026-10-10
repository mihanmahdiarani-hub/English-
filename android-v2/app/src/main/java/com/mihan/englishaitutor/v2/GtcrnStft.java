package com.mihan.englishaitutor.v2;

/**
 * Deterministic 16-kHz, 512-point sqrt-Hann STFT used by the GTCRN ONNX
 * speech-enhancement model. NO time stretching, sample insertion, or shifts.
 *
 * The model's native input/output shape is [1, 257, 1, 2] for each 256-sample
 * hop. This class is pure Java and regression-tested independently of Android.
 */
final class GtcrnStft {
    static final int FFT_SIZE = 512;
    static final int HOP = 256;
    static final int BINS = FFT_SIZE / 2 + 1;
    static final int COMPLEX_LENGTH = BINS * 2;
    private final double[] window = new double[FFT_SIZE];
    private final int[] bitReverse = new int[FFT_SIZE];

    GtcrnStft() {
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FFT_SIZE));
            bitReverse[i] = Integer.reverse(i) >>> (32 - 9);
        }
    }

    void forward(float[] samples, float[] features) {
        if (samples.length != FFT_SIZE || features.length != COMPLEX_LENGTH)
            throw new IllegalArgumentException("GTCRN STFT shape mismatch");
        double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) re[i] = samples[i] * window[i];
        fft(re, im, false);
        for (int i = 0; i < BINS; i++) {
            features[2 * i] = (float) re[i];
            features[2 * i + 1] = (float) im[i];
        }
    }

    void inverse(float[] features, float[] output) {
        if (features.length != COMPLEX_LENGTH || output.length != FFT_SIZE)
            throw new IllegalArgumentException("GTCRN inverse shape mismatch");
        double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
        for (int i = 0; i < BINS; i++) {
            re[i] = features[2 * i];
            im[i] = features[2 * i + 1];
        }
        for (int i = 1; i < FFT_SIZE / 2; i++) {
            re[FFT_SIZE - i] = re[i];
            im[FFT_SIZE - i] = -im[i];
        }
        fft(re, im, true);
        for (int i = 0; i < FFT_SIZE; i++) {
            double v = re[i] * window[i];
            if (!Double.isFinite(v)) throw new IllegalStateException("Invalid enhanced sample");
            output[i] = (float) v;
        }
    }

    float windowPower(int index) {
        return (float) (window[index] * window[index]);
    }

    private void fft(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < FFT_SIZE; i++) {
            int j = bitReverse[i];
            if (j > i) {
                double temp = re[i]; re[i] = re[j]; re[j] = temp;
                temp = im[i]; im[i] = im[j]; im[j] = temp;
            }
        }
        for (int width = 2; width <= FFT_SIZE; width *= 2) {
            int half = width / 2;
            double direction = (inverse ? 2 : -2) * Math.PI / width;
            for (int start = 0; start < FFT_SIZE; start += width) {
                for (int j = 0; j < half; j++) {
                    double phase = direction * j;
                    double wr = Math.cos(phase), wi = Math.sin(phase);
                    int a = start + j, b = a + half;
                    double tr = wr * re[b] - wi * im[b];
                    double ti = wr * im[b] + wi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] /= FFT_SIZE;
                im[i] /= FFT_SIZE;
            }
        }
    }
}
