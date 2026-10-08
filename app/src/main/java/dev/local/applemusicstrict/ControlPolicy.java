package dev.local.applemusicstrict;

import java.util.*;

/** The format family is a preference, never a claim that Atmos is lossless. */
public final class ControlPolicy {
    private ControlPolicy() {}
    public static boolean atmos(QualityPolicy.Track t) {
        String g = Objects.toString(t.group(), "").toLowerCase(Locale.ROOT);
        return "ec+3".equals(t.codecs()) || "audio/eac3-joc".equals(t.mime())
                || (("ec-3".equals(t.codecs()) || "audio/eac3".equals(t.mime()))
                    && (g.contains("atmos") || g.contains("joc")));
    }
    public static String id(QualityPolicy.Track t) {
        if (t.group() != null && !t.group().isEmpty()) return Objects.toString(t.codecs(), "") + ":" + t.group();
        return Objects.toString(t.codecs(), "") + ":" + Objects.toString(t.group(), "") + ":"
                + t.sampleRate() + ":" + t.bitDepth() + ":" + t.channels() + ":" + t.bitrate();
    }
    public static boolean eligible(QualityPolicy.Track t, boolean allowAtmos) {
        return (t.alac() && t.sampleRate() > 0 && t.bitDepth() > 0)
                || t.aac() || (allowAtmos && atmos(t));
    }
    public static int compare(QualityPolicy.Track a, QualityPolicy.Track b, boolean preferAtmos) {
        int c = Integer.compare(rank(a, preferAtmos), rank(b, preferAtmos));
        return c != 0 ? c : QualityPolicy.compare("HIGH_RES_LOSSLESS", a, b);
    }
    private static int rank(QualityPolicy.Track t, boolean atmos) {
        return atmos && atmos(t) ? 3 : t.alac() ? 2 : t.aac() ? 1 : 0;
    }
    public static String label(QualityPolicy.Track t) {
        String family = atmos(t) ? "杜比全景声" : t.alac() ? "无损 ALAC" : t.aac() ? "AAC" : Objects.toString(t.codecs(), "未知编码");
        if (t.aac() && t.group() != null) {
            java.util.regex.Matcher nominal = java.util.regex.Pattern.compile("audio-(HE-)?stereo-([0-9]+)").matcher(t.group());
            if (nominal.matches()) family += " · " + nominal.group(2) + " kbps";
        }
        String detail = (t.bitDepth() > 0 ? t.bitDepth() + " bit · " : "")
                + (t.sampleRate() > 0 ? String.format(Locale.ROOT, "%s kHz", t.sampleRate() / 1000.0) : "采样率未知");
        if (!t.alac() && t.bitrate() > 0) detail += " · " + t.bitrate() / 1000 + " kbps";
        return family + "\n" + detail;
    }
    public static QualityPolicy.Track choose(List<QualityPolicy.Track> tracks, boolean atmos, String manual) {
        QualityPolicy.Track best = null;
        for (QualityPolicy.Track t : tracks) {
            if (!manual.isEmpty()) { if (manual.equals(id(t))) return t; continue; }
            if (eligible(t, atmos) && (best == null || compare(t, best, atmos) > 0)) best = t;
        }
        if (best == null) throw new IllegalStateException(manual.isEmpty() ? "没有可用的音轨" : "选中的音轨已不可用");
        return best;
    }
}
