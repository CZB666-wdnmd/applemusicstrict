package dev.local.applemusicstrict;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;

/** Narrow IPC: only this app changes settings; only Apple Music reports playback. */
public final class ControlProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://dev.local.applemusicstrict.control");
    private Bundle snapshot = new Bundle();
    private SharedPreferences preferences;
    @Override public boolean onCreate() {
        preferences = getContext().getSharedPreferences("control", 0);
        return true;
    }
    private boolean self() { return Binder.getCallingUid() == android.os.Process.myUid(); }
    private void requireHost() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String p : packages)
            if ("com.apple.android.music".equals(p)) return;
        throw new SecurityException("Apple Music caller required");
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle input) {
        if (!self()) requireHost();
        if ("report".equals(method)) {
            if (self()) throw new SecurityException("Host report required");
            snapshot = new Bundle();
            if (input != null) {
                for (String key : new String[]{"song", "title", "artist", "actual", "choices", "message", "engine"}) {
                    String value = input.getString(key, "");
                    if (value.length() > 32768) throw new IllegalArgumentException("Oversized report");
                    snapshot.putString(key, value);
                }
                snapshot.putBoolean("playing", input.getBoolean("playing"));
                snapshot.putBoolean("controllable", input.getBoolean("controllable"));
                snapshot.putLong("appliedRevision", input.getLong("appliedRevision", -1));
            }
            snapshot.putLong("seen", SystemClock.elapsedRealtime());
            return Bundle.EMPTY;
        }
        if ("write".equals(method)) {
            if (!self()) throw new SecurityException("Settings are private to the control app");
            if (input == null) throw new IllegalArgumentException("Missing settings");
            String song = input.getString("manualSong", ""), track = input.getString("manualTrack", "");
            if (!track.isEmpty()) {
                if (!song.equals(snapshot.getString("song")) || !snapshot.getBoolean("controllable")
                        || SystemClock.elapsedRealtime() - snapshot.getLong("seen") > 6000)
                    throw new IllegalStateException("歌曲已变化，请刷新后重试");
                boolean found = false;
                try {
                    JSONArray choices = new JSONArray(snapshot.getString("choices", "[]"));
                    for (int i = 0; i < choices.length(); i++)
                        if (track.equals(choices.getJSONObject(i).getString("id"))) found = true;
                } catch (JSONException e) { throw new IllegalStateException("音轨列表不可用", e); }
                if (!found) throw new IllegalArgumentException("音轨已不可用");
            }
            boolean ok = preferences.edit().putBoolean("enabled", input.getBoolean("enabled"))
                    .putBoolean("atmos", input.getBoolean("atmos"))
                    .putString("manualSong", song).putString("manualTrack", track)
                    .putLong("revision", preferences.getLong("revision", 0) + 1).commit();
            if (!ok) throw new IllegalStateException("设置保存失败");
        } else if (!"read".equals(method)) throw new IllegalArgumentException("Unknown operation");
        Bundle out = self() ? new Bundle(snapshot) : new Bundle();
        out.putBoolean("enabled", preferences.getBoolean("enabled", false));
        out.putBoolean("atmos", preferences.getBoolean("atmos", false));
        out.putString("manualSong", preferences.getString("manualSong", ""));
        out.putString("manualTrack", preferences.getString("manualTrack", ""));
        out.putLong("revision", preferences.getLong("revision", 0));
        return out;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
