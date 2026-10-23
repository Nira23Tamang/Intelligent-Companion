package com.assistant;

public class AudioFilter {

    // Bandpass filter cutoff frequencies for human speech (80 Hz to 3400 Hz at 16kHz sample rate)
    private static final double LOW_CUTOFF = 80.0;
    private static final double HIGH_CUTOFF = 3400.0;
    private static final double SAMPLE_RATE = 16000.0;

    // Filter state memory
    private double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

    // Adaptive noise floor parameters
    private double ambientNoiseRms = 150.0;
    private static final double NOISE_SMOOTHING = 0.05;
    private static final double SPEECH_THRESHOLD_MULTIPLIER = 1.8;
    private static final double MIN_SPEECH_RMS = 250.0;

    public AudioFilter() {}

    /**
     * Applies a 2nd-order IIR Bandpass filter (80Hz - 3400Hz) to raw 16-bit PCM audio.
     * Strips low frequency rumbles (table bumps, HVAC) and high-frequency hiss/clicks.
     */
    public byte[] applyBandpassFilter(byte[] rawPcm) {
        if (rawPcm == null || rawPcm.length < 2) return rawPcm;

        byte[] filtered = new byte[rawPcm.length];
        double omegaLow = 2.0 * Math.PI * LOW_CUTOFF / SAMPLE_RATE;
        double omegaHigh = 2.0 * Math.PI * HIGH_CUTOFF / SAMPLE_RATE;
        double bandwidth = omegaHigh - omegaLow;
        double centerFreq = Math.sqrt(omegaLow * omegaHigh);

        double q = centerFreq / bandwidth;
        double alpha = Math.sin(centerFreq) / (2.0 * q);
        double cosOmega = Math.cos(centerFreq);

        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosOmega;
        double a2 = 1.0 - alpha;

        for (int i = 0; i < rawPcm.length - 1; i += 2) {
            short sample = (short) ((rawPcm[i + 1] << 8) | (rawPcm[i] & 0xFF));
            double x0 = (double) sample;
            double y0 = (b0 / a0) * x0 + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2;

            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;

            short outSample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (long) y0));
            filtered[i] = (byte) (outSample & 0xFF);
            filtered[i + 1] = (byte) ((outSample >> 8) & 0xFF);
        }
        return filtered;
    }

    /**
     * Calculates the Root-Mean-Square (RMS) audio energy of a PCM buffer.
     */
    public static double calculateRMS(byte[] audioData, int length) {
        if (audioData == null || length < 2) return 0.0;
        long sum = 0;
        int count = 0;
        for (int i = 0; i < length - 1; i += 2) {
            int sample = (audioData[i + 1] << 8) | (audioData[i] & 0xFF);
            sum += (long) sample * sample;
            count++;
        }
        if (count == 0) return 0.0;
        return Math.sqrt(sum / (double) count);
    }

    /**
     * Updates the running ambient room noise floor during quiet periods.
     */
    public void updateAmbientNoiseFloor(double currentRms) {
        if (currentRms > 0 && currentRms < ambientNoiseRms * 1.5) {
            ambientNoiseRms = (1.0 - NOISE_SMOOTHING) * ambientNoiseRms + NOISE_SMOOTHING * currentRms;
        }
    }

    /**
     * Determines whether the current audio frame contains active human speech.
     */
    public boolean isSpeechActive(double currentRms) {
        double threshold = Math.max(MIN_SPEECH_RMS, ambientNoiseRms * SPEECH_THRESHOLD_MULTIPLIER);
        return currentRms > threshold;
    }

    public double getAmbientNoiseRms() {
        return ambientNoiseRms;
    }
}
