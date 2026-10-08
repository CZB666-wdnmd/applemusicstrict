package dev.local.applemusicstrict;

import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.snackbar.Snackbar;
import org.json.*;
import java.util.concurrent.*;

/** Native Material 3 components, dynamic color, dark theme and accessible touch targets. */
public final class MainActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root, options;
    private MaterialSwitch enabled, atmos;
    private TextView connection, title, artist, actual, detail, selectionHint;
    private MaterialButton apply;
    private Bundle state = new Bundle();
    private boolean rendering, busy, resumed;
    private String selected = "", renderedChoices = "", renderedSong = "";
    private final Runnable refresh = new Runnable() { public void run() { read(); if (resumed) ui.postDelayed(this, 1500); } };
    @Override public void onCreate(Bundle saved) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(saved);
        try { grantUriPermission("com.apple.android.music", ControlProvider.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (RuntimeException ignored) { /* The target app may not be installed. */ }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(i.left, i.top, i.right, i.bottom); return insets;
        });
        MaterialToolbar toolbar = new MaterialToolbar(this); toolbar.setTitle("音质控制"); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(20); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView intro = text("让每首歌，以你选择的音质播放。", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        body.addView(intro); space(body, 20);
        LinearLayout controls = card(body, true);
        enabled = new MaterialSwitch(this); enabled.setText("接管音质选择"); enabled.setMinHeight(dp(56)); controls.addView(enabled);
        controls.addView(text("开启后忽略 Apple Music 的流媒体音质设置，自动选择当前歌曲的最高可用音质。", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium));
        space(controls, 12);
        atmos = new MaterialSwitch(this); atmos.setText("优先杜比全景声"); atmos.setMinHeight(dp(56)); controls.addView(atmos);
        controls.addView(text("有杜比版本时优先播放；没有时选择最高无损。杜比与无损是不同格式。", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium));
        space(body, 20);
        connection = text("等待 Apple Music", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); body.addView(connection);
        space(body, 12);
        LinearLayout now = card(body, false);
        now.addView(text("当前播放", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)); space(now, 12);
        title = text("尚未读取到歌曲", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall); now.addView(title);
        artist = text("打开 Apple Music 播放歌曲", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); now.addView(artist); space(now, 20);
        actual = text("音质待确认", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge); now.addView(actual); space(now, 8);
        detail = text("这里显示播放器实际格式，不以设置值代替。", com.google.android.material.R.style.TextAppearance_Material3_BodySmall); now.addView(detail);
        space(body, 24);
        body.addView(text("本曲可选音质", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)); space(body, 4);
        selectionHint = text("播放清单载入后显示；手动选择只用于当前歌曲。", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); body.addView(selectionHint); space(body, 12);
        options = column(0); body.addView(options);
        apply = new MaterialButton(this); apply.setText("应用所选音质"); apply.setMinHeight(dp(48)); body.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        space(body, 12);
        MaterialButton open = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        open.setText("打开 Apple Music"); open.setMinHeight(dp(48)); body.addView(open, new LinearLayout.LayoutParams(-1, -2));
        space(body, 12);
        body.addView(text("切换时会重新加载当前歌曲，可能短暂停顿。下载歌曲、视频和电台不强制切换。显示的是音源格式，不代表耳机或 DAC 的最终输出。", com.google.android.material.R.style.TextAppearance_Material3_BodySmall));
        setContentView(root);
        enabled.setOnCheckedChangeListener((b, checked) -> { if (!rendering) save(false); });
        atmos.setOnCheckedChangeListener((b, checked) -> { if (!rendering) save(false); });
        apply.setOnClickListener(v -> save(true));
        open.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.apple.android.music");
            if (launch != null) startActivity(launch); else message("未安装 Apple Music");
        });
        if (saved != null) selected = saved.getString("selected", "");
    }
    @Override protected void onResume() { super.onResume(); resumed = true; ui.post(refresh); }
    @Override protected void onPause() { resumed = false; ui.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("selected", selected); super.onSaveInstanceState(out); }
    private void read() {
        if (busy || io.isShutdown()) return;
        io.execute(() -> {
            try { Bundle b = getContentResolver().call(ControlProvider.URI, "read", null, null); ui.post(() -> { if (!busy && !isDestroyed()) render(b); }); }
            catch (Exception e) { ui.post(() -> { if (!isDestroyed()) connection.setText("读取失败，请重新打开界面"); }); }
        });
    }
    private void save(boolean manual) {
        if (busy) return;
        Bundle request = new Bundle();
        request.putBoolean("enabled", enabled.isChecked()); request.putBoolean("atmos", atmos.isChecked());
        request.putString("manualSong", manual && !selected.isEmpty() ? state.getString("song", "") : "");
        request.putString("manualTrack", manual ? selected : "");
        busy = true; apply.setEnabled(false); enabled.setEnabled(false); atmos.setEnabled(false);
        io.execute(() -> {
            try {
                Bundle b = getContentResolver().call(ControlProvider.URI, "write", null, request);
                ui.post(() -> { busy = false; selected = manual ? selected : ""; renderedChoices = ""; render(b); message("已保存，等待播放器应用"); });
            } catch (Exception e) { ui.post(() -> { busy = false; render(state); message(e.getMessage() == null ? "应用失败" : e.getMessage()); }); }
        });
    }
    private void render(Bundle b) {
        if (b == null) return;
        state = b; rendering = true;
        enabled.setChecked(b.getBoolean("enabled")); enabled.setEnabled(!busy);
        atmos.setChecked(b.getBoolean("atmos")); atmos.setEnabled(enabled.isChecked() && !busy);
        rendering = false;
        boolean fresh = b.containsKey("seen") && SystemClock.elapsedRealtime() - b.getLong("seen") < 6000;
        String song = b.getString("song", ""), choices = fresh ? b.getString("choices", "[]") : "[]";
        title.setText(fresh && !b.getString("title", "").isEmpty() ? b.getString("title") : "尚未读取到歌曲");
        artist.setText(fresh ? b.getString("artist", "") : "打开 Apple Music 播放歌曲");
        String observed = fresh ? b.getString("actual", "") : "";
        actual.setText(observed.isEmpty() ? "音质待确认" : observed);
        boolean pending = b.getLong("appliedRevision", -1) < b.getLong("revision", 0);
        connection.setText(!fresh ? "等待 Apple Music · 请确认模块已启用" : pending ? "设置待应用" : enabled.isChecked() ? "音质接管已开启" : "使用 Apple Music 设置");
        detail.setText(fresh ? (b.getBoolean("playing") ? "播放中" : "暂停或缓冲中") + " · " + b.getString("message", "") : "暂无实时回报；不会沿用旧歌曲的音质。 ");
        boolean usable = fresh && b.getBoolean("controllable") && enabled.isChecked() && !busy;
        apply.setEnabled(usable && !choices.equals("[]"));
        if (!song.equals(renderedSong)) selected = b.getString("manualSong", "").equals(song) ? b.getString("manualTrack", "") : "";
        String key = choices + usable;
        if (!key.equals(renderedChoices) || !song.equals(renderedSong)) {
            options.removeAllViews(); renderedSong = song; renderedChoices = key;
            RadioGroup group = new RadioGroup(this); group.setOrientation(LinearLayout.VERTICAL); options.addView(group);
            addChoice(group, "", "自动 · 最高可用音质", usable);
            try {
                JSONArray array = new JSONArray(choices);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.getJSONObject(i);
                    addChoice(group, track.getString("id"), track.getString("label"), usable);
                }
                selectionHint.setText(array.length() == 0 ? "尚无当前歌曲的可选音轨。请播放一首在线歌曲。" : "已发现 " + array.length() + " 个音源版本 · 规格来自清单，实际以播放回报为准");
            } catch (JSONException e) { selectionHint.setText("音轨列表读取失败"); apply.setEnabled(false); }
        }
    }
    private void addChoice(RadioGroup group, String id, String label, boolean usable) {
        MaterialRadioButton radio = new MaterialRadioButton(this); radio.setId(View.generateViewId()); radio.setText(label);
        radio.setMinHeight(dp(64)); radio.setPadding(dp(4), dp(8), dp(12), dp(8)); radio.setEnabled(usable);
        group.addView(radio, new RadioGroup.LayoutParams(-1, -2)); radio.setChecked(id.equals(selected));
        radio.setOnCheckedChangeListener((button, checked) -> { if (checked) selected = id; });
    }
    private LinearLayout card(LinearLayout parent, boolean primary) {
        MaterialCardView card = new MaterialCardView(this); card.setRadius(dp(24)); card.setCardElevation(0); card.setStrokeWidth(0);
        card.setCardBackgroundColor(color(primary ? com.google.android.material.R.attr.colorPrimaryContainer : com.google.android.material.R.attr.colorSurfaceContainer));
        LinearLayout child = column(20); card.addView(child); parent.addView(card, new LinearLayout.LayoutParams(-1, -2)); return child;
    }
    private LinearLayout column(int padding) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return l; }
    private TextView text(String value, int style) { TextView t = new TextView(this); t.setTextAppearance(style); t.setText(value); return t; }
    private void space(LinearLayout p, int height) { p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int color(int attr) { return MaterialColors.getColor(this, attr, "theme"); }
    private void message(String text) { if (!isDestroyed()) Snackbar.make(root, text, Snackbar.LENGTH_LONG).show(); }
}
