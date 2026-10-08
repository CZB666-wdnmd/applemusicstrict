package dev.local.applemusicstrict;

import android.app.Application;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Exact-build, song-HLS module using the modern libxposed interceptor API. */
public final class StrictModule extends XposedModule {
    private static final String PACKAGE = "com.apple.android.music";
    private static final String TAG = "AppleMusicStrict";
    private static final Map<String, String> PROFILES = Map.of(
            "3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603", "7.0.0-beta/1606",
            "75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126", "7.0.0-beta/1607");
    private String processName = "";
    private boolean bootstrapInstalled;
    private boolean initializationStarted;
    private volatile boolean active;
    private final ThreadLocal<Object> constructingContext = new ThreadLocal<>();
    private final Map<Object, Object> factories = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Object> parsers = Collections.synchronizedMap(new WeakHashMap<>());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "Module loaded; process=" + param.getProcessName() + "; api=" + getApiVersion());
    }

    @Override public synchronized void onPackageReady(PackageReadyParam param) {
        if (bootstrapInstalled || !PACKAGE.equals(processName)
                || !PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            if (getApiVersion() < 102) throw new IllegalStateException("libxposed API 102 required");
            ClassLoader loader = param.getClassLoader();
            String name = param.getApplicationInfo().className;
            Class<?> applicationClass = name == null || name.isEmpty()
                    ? Application.class : Host.type(loader, name);
            if (!Application.class.isAssignableFrom(applicationClass))
                throw new IllegalStateException("Host application is not an Application");
            // Hook the nearest onCreate declaration only. Hooking both it and the
            // base class would initialize inside super.onCreate, before the host is ready.
            Method onCreate = Host.method(applicationClass, "onCreate");
            hook(onCreate).setId("application-bootstrap")
                    .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        // Preserve host exceptions; module installation failures are caught below.
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof Application application
                                && PACKAGE.equals(application.getPackageName())) {
                            initialize(application.getApplicationInfo().sourceDir, loader);
                        }
                        return result;
                    });
            bootstrapInstalled = true;
            log(Log.INFO, TAG, "WAITING: main process; playback hooks deferred until Application.onCreate returns");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "DISABLED: application bootstrap failed; no strict guarantee", error);
        }
    }

    private synchronized void initialize(String sourceDir, ClassLoader loader) {
        if (initializationStarted) return;
        initializationStarted = true;
        try {
            String hash = sha256(sourceDir);
            String profile = PROFILES.get(hash);
            if (profile == null) {
                log(Log.ERROR, TAG, "DISABLED: unrecognized base APK SHA-256=" + hash);
                return;
            }
            Binding b = new Binding(loader);
            install(b);
            // Publish only after every hook and its reflection binding is ready.
            active = true;
            log(Log.INFO, TAG, "ACTIVE: exact " + profile + " profile; HLS parser and final selection installed after Application.onCreate");
        } catch (Throwable error) {
            // The logical gate stays closed even if a framework unhook fails.
            active = false;
            for (XposedInterface.HookHandle h : handles) {
                try { h.unhook(); } catch (Throwable ignored) { }
            }
            handles.clear(); factories.clear(); parsers.clear();
            log(Log.ERROR, TAG, "DISABLED: hook installation failed; no strict guarantee", error);
        }
    }

    private void install(Binding b) {
        // This scope lives only while the app creates this song's actual HLS source.
        // The factory/parser maps carry it to the asynchronous loader thread.
        handles.add(hook(b.createUpstream).setId("song-source-context")
                .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!active) return chain.proceed();
                    Object period = chain.getThisObject();
                    Object item = b.periodItem.get(period);
                    Object asset = chain.getArgs().get(0);
                    if (!Host.streamingSong(item) || "DOWNLOADED".equals(Host.enumName(b.assetType.invoke(asset))))
                        return chain.proceed();
                    Object factory = b.periodDataFactory.get(period);
                    Object context = b.dataContext.invoke(factory);
                    String quality = Host.quality(context);
                    String assetType = Host.enumName(b.assetType.invoke(asset));
                    boolean hls = "HLS_FAST_PATH".equals(assetType)
                            || "HLS_SUBPLAYBACK_DISPATCH".equals(assetType)
                            || "HLS".equals(b.assetFlavor.invoke(asset));
                    if (!hls && ("LOSSLESS".equals(quality) || "HIGH_RES_LOSSLESS".equals(quality))) {
                        String message = "Strict quality: lossless requested but source is not HLS";
                        log(Log.ERROR, TAG, message);
                        b.setPrepareError.invoke(period, new IOException(message));
                        return null;
                    }
                    Object previous = constructingContext.get();
                    constructingContext.set(context);
                    try { return chain.proceed(); }
                    finally {
                        if (previous == null) constructingContext.remove(); else constructingContext.set(previous);
                    }
                }));

        handles.add(hook(b.parserFactoryCtor).setId("bind-parser-factory")
                .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!active) return chain.proceed();
                    Object result = chain.proceed();
                    Object context = constructingContext.get();
                    if (context != null) factories.put(chain.getThisObject(), context);
                    return result;
                }));
        for (Method method : b.createParsers) {
            handles.add(hook(method).setId("bind-parser-" + method.getParameterCount())
                    .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        if (!active) return chain.proceed();
                        Object result = chain.proceed();
                        Object context = factories.get(chain.getThisObject());
                        if (context != null && result != null) parsers.put(result, context);
                        return result;
                    }));
        }

        handles.add(hook(b.parse).setId("strict-master-playlist")
                .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!active) return chain.proceed();
                    Object result = chain.proceed();
                    Object context = parsers.get(chain.getThisObject());
                    if (context == null || !b.master.isInstance(result)) return result;
                    try {
                        String quality = Host.quality(context);
                        Object selected = b.manifest.filter(result, quality);
                        log(Log.INFO, TAG, "MASTER: quality=" + quality + "; one permitted variant retained");
                        return selected;
                    } catch (Exception error) {
                        // IOException is handled by the HLS loader. Protective mode would
                        // swallow this and return the unfiltered playlist, allowing AAC.
                        log(Log.ERROR, TAG, "BLOCKED: no usable strict playlist", error);
                        throw new IOException("AppleMusicStrict: no playlist satisfies the audio setting", error);
                    }
                }));

        handles.add(hook(b.selectAudio).setId("strict-final-audio-track")
                .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!active) return chain.proceed();
                    Object selector = chain.getThisObject();
                    List<Object> args = chain.getArgs();
                    Object item = b.currentItem.invoke(selector, args.get(5));
                    if (!Host.streamingSong(item)) return chain.proceed();
                    try {
                        return b.select(selector, item, args.get(0), (int[][]) args.get(1), args.get(3));
                    } catch (Exception error) {
                        log(Log.ERROR, TAG, "BLOCKED: no usable strict audio track", error);
                        // The supplied ExoPlayerImplInternal handles RuntimeException as
                        // a playback error. Do not return null: that invokes AAC fallback.
                        throw new IllegalStateException("AppleMusicStrict: audio setting cannot be satisfied", error);
                    }
                }));
        // The synthetic callback can otherwise contain an AOT-inlined copy.
        if (!deoptimize(b.createUpstreamCaller))
            log(Log.WARN, TAG, "Could not deoptimize upstream callback; verify MASTER logs on device");
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(path))) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private final class Binding {
        private static final String APP = "com.apple.android.music.playback.";
        private static final String EXO = "com.google.android.exoplayer2.";
        final Class<?> master;
        final StrictManifest manifest;
        final Method createUpstream, createUpstreamCaller, setPrepareError, dataContext, assetType, assetFlavor;
        final Field periodItem, periodDataFactory, selectorContext, targetedFormats;
        final Constructor<?> parserFactoryCtor, definitionCtor, scoreCtor;
        final List<Method> createParsers = new ArrayList<>();
        final Method parse, selectAudio, currentItem, getGroup, getFormat, audioGroupId;
        final Field groupCount, trackCount, withinConstraints, exceedConstraints;

        Binding(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> period = Host.type(loader, APP + "player.mediasource.PlaybackAssetMediaPeriod");
            Class<?> asset = Host.type(loader, APP + "model.MediaAssetInfo");
            Class<?> data = Host.type(loader, APP + "player.datasource.PlayerDataSourceFactory");
            Class<?> parserFactory = Host.type(loader, APP + "player.mediasource.AppleHlsPlaylistParserFactory");
            Class<?> parser = Host.type(loader, APP + "player.mediasource.AppleHlsPlaylistParser");
            Class<?> selector = Host.type(loader, APP + "player.PlayerTrackSelector");
            Class<?> groupArray = Host.type(loader, EXO + "source.TrackGroupArray");
            Class<?> group = Host.type(loader, EXO + "source.TrackGroup");
            Class<?> format = Host.type(loader, EXO + "Format");
            Class<?> params = Host.type(loader, EXO + "trackselection.DefaultTrackSelector$Parameters");
            Class<?> id = Host.type(loader, EXO + "source.MediaSource$MediaPeriodId");
            Class<?> score = Host.type(loader, EXO + "trackselection.DefaultTrackSelector$AudioTrackScore");
            Class<?> definition = Host.type(loader, EXO + "trackselection.TrackSelection$Definition");
            master = Host.type(loader, EXO + "source.hls.playlist.HlsMasterPlaylist");
            manifest = new StrictManifest(loader);
            createUpstream = Host.method(period, "createPeriodUpstream", asset);
            createUpstreamCaller = Host.method(period, "i", period, asset);
            setPrepareError = Host.method(period, "setPrepareError", IOException.class);
            periodItem = Host.field(period, "mediaItem");
            periodDataFactory = Host.field(period, "dataSourceFactory");
            dataContext = Host.method(data, "getPlayerContext");
            assetType = Host.method(asset, "getType"); assetFlavor = Host.method(asset, "getFlavor");
            parserFactoryCtor = parserFactory.getDeclaredConstructor(boolean.class);
            parserFactoryCtor.setAccessible(true);
            createParsers.add(Host.method(parserFactory, "createPlaylistParser"));
            createParsers.add(Host.method(parserFactory, "createPlaylistParser", master));
            // Ignore the synthetic Object-returning bridge to avoid double parsing.
            parse = Host.unique(parser, "parse");
            if (!Arrays.equals(parse.getParameterTypes(), new Class<?>[]{Uri.class, InputStream.class}))
                throw new NoSuchMethodException("Unexpected parser signature");
            selectAudio = Host.method(selector, "selectAudioTrack", groupArray, int[][].class,
                    int.class, params, boolean.class, id);
            currentItem = Host.method(selector, "getCurrentItem", id);
            selectorContext = Host.field(selector, "playerContext");
            targetedFormats = Host.field(selector, "targetedTrackFormat");
            getGroup = Host.method(groupArray, "get", int.class);
            getFormat = Host.method(group, "getFormat", int.class);
            groupCount = Host.field(groupArray, "length"); trackCount = Host.field(group, "length");
            withinConstraints = Host.field(score, "isWithinConstraints");
            exceedConstraints = Host.field(params, "exceedAudioConstraintsIfNecessary");
            definitionCtor = definition.getDeclaredConstructor(group, int[].class);
            definitionCtor.setAccessible(true);
            scoreCtor = score.getDeclaredConstructor(format, params, int.class); scoreCtor.setAccessible(true);
            audioGroupId = Host.method(Host.type(loader, APP + "util.MediaPlayerUtil"),
                    "getAudioGroupIdFromFormat", format);
            for (String f : new String[]{"codecs", "sampleMimeType", "bitrate", "sampleRate",
                    "bitDepth", "channelCount", "width", "height"}) Host.field(format, f);
        }

        @SuppressWarnings("unchecked")
        Object select(Object selector, Object item, Object groups, int[][] support, Object params)
                throws ReflectiveOperationException {
            String quality = Host.quality(selectorContext.get(selector));
            Object bestGroup = null, bestFormat = null, bestScore = null;
            QualityPolicy.Track bestTrack = null; int bestIndex = -1;
            int count = groupCount.getInt(groups);
            for (int g = 0; g < count; g++) {
                Object group = getGroup.invoke(groups, g);
                int length = trackCount.getInt(group);
                for (int t = 0; t < length; t++) {
                    if (g >= support.length || t >= support[g].length || (support[g][t] & 7) != 4) continue;
                    Object f = getFormat.invoke(group, t);
                    QualityPolicy.Track track = Host.track(f, (String) audioGroupId.invoke(null, f));
                    if (!QualityPolicy.allows(quality, track)) continue;
                    Object score = scoreCtor.newInstance(f, params, support[g][t]);
                    if (!withinConstraints.getBoolean(score) && !exceedConstraints.getBoolean(params)) continue;
                    if (bestFormat == null || QualityPolicy.compare(quality, track, bestTrack) > 0) {
                        bestGroup = group; bestFormat = f; bestScore = score;
                        bestTrack = track; bestIndex = t;
                    }
                }
            }
            if (bestFormat == null) throw new IllegalStateException("No renderer-supported " + quality + " audio track");
            String storeId = (String) Host.call(item, "getSubscriptionStoreId");
            long id;
            try { id = Long.parseLong(storeId); } catch (NumberFormatException ignored) { id = 0; }
            ((Map<Long, Object>) targetedFormats.get(selector)).put(id, bestFormat);
            Object definition = definitionCtor.newInstance(bestGroup, new int[]{bestIndex});
            log(Log.INFO, TAG, "SELECT: quality=" + quality + "; codec=" + bestTrack.codecs()
                    + "; rate=" + bestTrack.sampleRate() + "; depth=" + bestTrack.bitDepth()
                    + "; bitrate=" + bestTrack.bitrate() + "; tracks=1");
            return Pair.create(definition, bestScore);
        }
    }
}
