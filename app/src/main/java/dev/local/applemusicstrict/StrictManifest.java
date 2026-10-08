package dev.local.applemusicstrict;

import java.lang.reflect.Constructor;
import java.util.*;

/** Rebuilds a master playlist before the tracker can request its first variant. */
final class StrictManifest {
    private final Class<?> master;
    private final Constructor<?> constructor;

    StrictManifest(ClassLoader loader) throws ReflectiveOperationException {
        master = Host.type(loader, "com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist");
        Class<?> format = Host.type(loader, "com.google.android.exoplayer2.Format");
        constructor = master.getDeclaredConstructor(String.class, List.class, List.class,
                List.class, List.class, List.class, List.class, format, List.class,
                boolean.class, Map.class, List.class);
        constructor.setAccessible(true);
        for (String f : new String[]{"variants", "videos", "audios", "subtitles", "closedCaptions",
                "muxedAudioFormat", "muxedCaptionFormats", "variableDefinitions", "sessionKeyDrmInitData",
                "baseUri", "tags", "hasIndependentSegments"}) Host.field(master, f);
    }

    Object filter(Object playlist, String quality) throws ReflectiveOperationException {
        if (!master.isInstance(playlist)) return playlist;
        List<?> variants = (List<?>) Host.get(playlist, "variants");
        Object best = null;
        QualityPolicy.Track bestTrack = null;
        for (Object variant : variants) {
            Object format = Host.get(variant, "format");
            if (Host.integer(format, "height") > 0 || Host.integer(format, "width") > 0)
                throw new IllegalStateException("Video variant in song playlist; strict playback stopped");
            String group = (String) Host.get(variant, "audioGroupId");
            QualityPolicy.Track t = Host.track(format, group);
            if (QualityPolicy.allows(quality, t)
                    && (best == null || QualityPolicy.compare(quality, t, bestTrack) > 0)) {
                best = variant; bestTrack = t;
            }
        }
        if (best == null) throw new IllegalStateException("No song variant satisfies " + quality);
        String audioGroup = (String) Host.get(best, "audioGroupId");
        List<Object> audios = new ArrayList<>();
        for (Object rendition : (List<?>) Host.get(playlist, "audios")) {
            if (audioGroup != null && audioGroup.equals(Host.get(rendition, "groupId"))) {
                QualityPolicy.Track t = Host.track(Host.get(rendition, "format"), audioGroup);
                if (!QualityPolicy.allows(quality, t))
                    throw new IllegalStateException("Audio rendition metadata conflicts with " + quality);
                audios.add(rendition);
            }
        }
        // Preserve session key data, variables and original selected variant objects.
        return constructor.newInstance(Host.get(playlist, "baseUri"), Host.get(playlist, "tags"),
                Collections.singletonList(best), Host.get(playlist, "videos"), audios,
                Host.get(playlist, "subtitles"), Host.get(playlist, "closedCaptions"),
                Host.get(playlist, "muxedAudioFormat"), Host.get(playlist, "muxedCaptionFormats"),
                Host.get(playlist, "hasIndependentSegments"), Host.get(playlist, "variableDefinitions"),
                Host.get(playlist, "sessionKeyDrmInitData"));
    }
}
