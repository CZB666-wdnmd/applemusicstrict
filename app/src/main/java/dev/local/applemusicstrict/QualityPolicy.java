package dev.local.applemusicstrict;

/** Pure selection policy. Missing lossless metadata is rejected, never guessed. */
public final class QualityPolicy {
    private QualityPolicy() {}

    public record Track(String codecs, String mime, String group,
                        int bitrate, int sampleRate, int bitDepth, int channels) {
        public boolean alac() { return "alac".equals(codecs); }
        public boolean aac() {
            if (group != null && (group.contains("downmix") || group.contains("binaural"))) return false;
            return "mp4a.40.2".equals(codecs) || "mp4a.40.5".equals(codecs)
                    || "audio/mp4a-latm".equals(mime);
        }
    }

    public static boolean supportedQuality(String quality) {
        return switch (quality) {
            case "HIGH_EFFICIENCY", "HIGH_QUALITY", "LOSSLESS", "HIGH_RES_LOSSLESS" -> true;
            default -> false;
        };
    }

    public static boolean allows(String quality, Track t) {
        return switch (quality) {
            case "LOSSLESS" -> lossless(t, 48000);
            case "HIGH_RES_LOSSLESS" -> lossless(t, 192000);
            case "HIGH_QUALITY" -> t.aac() && t.bitrate >= 256000;
            case "HIGH_EFFICIENCY" -> t.aac() && t.bitrate > 0 && t.bitrate < 256000;
            default -> false;
        };
    }

    private static boolean lossless(Track t, int ceiling) {
        // The APK parses SAMPLE-RATE and BIT-DEPTH into these Format fields.
        return t.alac() && t.sampleRate > 0 && t.sampleRate <= ceiling
                && t.bitDepth > 0 && t.bitDepth <= 24;
    }

    public static int compare(String quality, Track a, Track b) {
        if ("HIGH_EFFICIENCY".equals(quality)) return Integer.compare(b.bitrate, a.bitrate);
        int c = Integer.compare(a.sampleRate, b.sampleRate);
        if (c == 0) c = Integer.compare(a.bitDepth, b.bitDepth);
        if (c == 0) c = Integer.compare(a.channels, b.channels);
        if (c == 0) c = Integer.compare(a.bitrate, b.bitrate);
        return c;
    }
}
