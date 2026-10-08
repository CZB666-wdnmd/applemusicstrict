package dev.local.applemusicstrict;

import android.app.Application;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** IPC and playback work are serialized on their respective owning threads. */
final class RuntimeControl {
    record Config(boolean enabled, boolean atmos, String song, String track, long revision) {
        String manual(String current) { return current.equals(song) ? track : ""; }
    }
    private final Application application;
    private final HandlerThread worker = new HandlerThread("MusicQualityControl");
    private Handler io;
    volatile Config config = new Config(false, false, "", "", 0);
    private volatile WeakReference<Object> controller = new WeakReference<>(null);
    private final Map<String, List<QualityPolicy.Track>> candidates = Collections.synchronizedMap(new LinkedHashMap<>());
    private record Selection(QualityPolicy.Track track, long revision) {}
    private final Map<String, Selection> targets = Collections.synchronizedMap(new HashMap<>());
    private volatile String error = "等待播放清单";
    private long processed = -1;
    private boolean started;
    private boolean loggedBridgeFailure;
    RuntimeControl(Application app) { application = app; }
    void start() {
        if (started) return; started = true;
        worker.start(); io = new Handler(worker.getLooper()); io.post(this::tick);
    }
    void stop() { worker.quitSafely(); }
    void capture(Object value) { if (controller.get() != value) Log.i("AppleMusicStrict", "Captured local playback controller"); controller = new WeakReference<>(value); }
    boolean enabled() { return config.enabled(); }
    void failure(String message) { error = message; }
    static String songId(Object item) throws ReflectiveOperationException {
        return Objects.toString(Host.call(item, "getSubscriptionStoreId"), "");
    }
    String manual(Object item) throws ReflectiveOperationException { return config.manual(songId(item)); }
    void discovered(Object item, List<QualityPolicy.Track> tracks) throws ReflectiveOperationException {
        String id = songId(item);
        if (id.isEmpty()) return;
        LinkedHashMap<String, QualityPolicy.Track> distinct = new LinkedHashMap<>();
        for (QualityPolicy.Track t : tracks) if (ControlPolicy.eligible(t, true)) distinct.put(ControlPolicy.id(t), t);
        List<QualityPolicy.Track> sorted = new ArrayList<>(distinct.values());
        Log.i("AppleMusicStrict", "Observed playlist: " + sorted.size() + " audio choices");
        sorted.sort((a, b) -> -ControlPolicy.compare(a, b, config.atmos()));
        synchronized (candidates) {
            candidates.put(id, List.copyOf(sorted));
            while (candidates.size() > 16) { String first = candidates.keySet().iterator().next(); candidates.remove(first); targets.remove(first); }
        }
    }
    void selected(Object item, QualityPolicy.Track track) throws ReflectiveOperationException {
        String song = songId(item); targets.put(song, new Selection(track, config.revision()));
        error = "等待播放器确认所选音轨";
    }
    QualityPolicy.Track choose(Object item, List<QualityPolicy.Track> tracks) throws ReflectiveOperationException {
        Config c = config;
        return ControlPolicy.choose(tracks, c.atmos(), c.manual(songId(item)));
    }
    private void tick() {
        try {
            Bundle b = application.getContentResolver().call(ControlProvider.URI, "read", null, null);
            if (b != null) config = new Config(b.getBoolean("enabled"), b.getBoolean("atmos"),
                    b.getString("manualSong", ""), b.getString("manualTrack", ""), b.getLong("revision"));
            Object c = controller.get();
            if (c != null) {
                Handler owner = (Handler) Host.get(c, "controllerHandler");
                owner.post(() -> sample(c));
            } else {
                Bundle out = new Bundle(); out.putString("message", "模块已连接，等待本地播放器"); report(out);
            }
        } catch (Exception e) { if (!loggedBridgeFailure) { loggedBridgeFailure = true; Log.w("AppleMusicStrict", "Control bridge unavailable", e); } }
        if (started) io.postDelayed(this::tick, 1500);
    }
    private void sample(Object c) {
        Bundle out = new Bundle();
        try {
            Object player = Host.get(c, "player");
            out.putString("engine", player.getClass().getSimpleName());
            Object queueItem = Host.call(player, "getCurrentItem");
            if (queueItem == null) { out.putString("message", "等待播放歌曲"); report(out); return; }
            Object item = Host.call(queueItem, "getItem");
            String song = songId(item);
            boolean controllable = Host.streamingSong(item) && !song.isEmpty();
            out.putString("song", song); out.putString("title", Objects.toString(Host.call(item, "getTitle"), ""));
            out.putString("artist", Objects.toString(Host.call(item, "getArtistName"), ""));
            out.putBoolean("playing", ((Number) Host.call(player, "getPlaybackRate")).floatValue() > 0);
            out.putBoolean("controllable", controllable);
            String exclusion = (Boolean) Host.call(item, "isDownloadedAsset") ? "下载歌曲：显示已下载版本的音质"
                    : "不支持的内容类型 " + Host.call(item, "getType");
            Config cfg = config;
            if (processed != cfg.revision()) {
                long old = processed; processed = cfg.revision();
                if (controllable && (old >= 0 || cfg.enabled())) {
                    long position = ((Number) Host.call(player, "getCurrentPosition")).longValue();
                    boolean playing = (Boolean) Host.get(player, "playWhenReady");
                    Host.field(player.getClass(), "pendingSeekPosition").setLong(player, Math.max(0, position));
                    Host.field(player.getClass(), "playWhenQueuePrepared").setBoolean(player, playing);
                    targets.remove(song);
                    Host.call(player, "preparePlayer");
                    error = "正在重新加载所选音质";
                }
            }
            Object format = Host.call(player, "getCurrentPlaybackFormat");
            QualityPolicy.Track actual = null;
            if (format != null) {
                actual = new QualityPolicy.Track((String) Host.get(format, "codecs"), (String) Host.get(format, "codecMimeType"),
                        (String) Host.get(format, "audioGroupId"), Host.integer(format, "bitRate"), Host.integer(format, "sampleRate"),
                        Host.integer(format, "bitDepth"), Host.integer(format, "channelCount"));
                Object input = Host.get(player, "currentTrackFormat");
                if (input != null) {
                    Class<?> util = Host.type(player.getClass().getClassLoader(), "com.apple.android.music.playback.util.MediaPlayerUtil");
                    String group = (String) Host.method(util, "getAudioGroupIdFromFormat", input.getClass()).invoke(null, input);
                    QualityPolicy.Track source = Host.track(input, group);
                    // Renderer bitRate can be decoded PCM (e.g. 1411 kbps for AAC).
                    // Only use the observed decoder input's encoded rate, never the PCM output rate.
                    if (source.alac() == actual.alac() && source.aac() == actual.aac()
                            && ControlPolicy.atmos(source) == ControlPolicy.atmos(actual)) {
                        actual = new QualityPolicy.Track(source.codecs(), source.mime(), source.group(), source.bitrate(),
                                source.sampleRate() > 0 ? source.sampleRate() : actual.sampleRate(), source.bitDepth(), source.channels());
                    } else {
                        actual = new QualityPolicy.Track(actual.codecs(), actual.mime(), actual.group(), -1,
                                actual.sampleRate(), actual.bitDepth(), actual.channels());
                    }
                } else {
                    actual = new QualityPolicy.Track(actual.codecs(), actual.mime(), actual.group(), -1,
                            actual.sampleRate(), actual.bitDepth(), actual.channels());
                }
                out.putString("actual", ControlPolicy.label(actual));
            }
            JSONArray list = new JSONArray();
            List<QualityPolicy.Track> options = candidates.getOrDefault(song, List.of());
            for (QualityPolicy.Track t : options) list.put(new JSONObject().put("id", ControlPolicy.id(t)).put("label", ControlPolicy.label(t)));
            out.putString("choices", list.toString());
            Selection selection = targets.get(song);
            QualityPolicy.Track target = selection == null ? null : selection.track();
            boolean confirmed = actual != null && target != null && sameAudio(actual, target)
                    && selection.revision() == cfg.revision();
            out.putLong("appliedRevision", !cfg.enabled() || confirmed ? cfg.revision() : -1);
            out.putString("message", !controllable ? exclusion : !cfg.enabled() ? "源音质由 Apple Music 管理"
                    : confirmed ? "已确认所选音源格式" : error);
        } catch (Exception e) {
            out.putString("message", "播放信息读取失败：" + e.getClass().getSimpleName());
            Log.w("AppleMusicStrict", "Playback control", e);
        }
        report(out);
    }
    private static boolean sameAudio(QualityPolicy.Track a, QualityPolicy.Track b) {
        return (a.alac() == b.alac()) && (a.aac() == b.aac()) && (ControlPolicy.atmos(a) == ControlPolicy.atmos(b))
                && (a.group() == null || a.group().isEmpty() || b.group() == null || b.group().isEmpty() || a.group().equals(b.group()))
                && (b.sampleRate() <= 0 || a.sampleRate() == b.sampleRate())
                && (b.bitDepth() <= 0 || a.bitDepth() == b.bitDepth());
    }
    private void report(Bundle value) {
        io.post(() -> { try { application.getContentResolver().call(ControlProvider.URI, "report", null, value); }
            catch (Exception e) { Log.w("AppleMusicStrict", "Cannot publish playback status"); } });
    }
}
