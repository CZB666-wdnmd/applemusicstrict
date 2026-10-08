#!/usr/bin/env python3
"""JVM behavior tests and syntax compile against explicit API-shaped fixtures.

This is NOT compilation against the real libxposed AAR or an Android APK build.
Run: python3 tests/run.py
Legacy Smoke/BootstrapSmoke document v0.1.1 and are not run by this v0.2 test entry.
"""
import os, pathlib, shutil, subprocess, sys
root = pathlib.Path(__file__).resolve().parents[1]
work = root / '.test-build'
assert work.resolve().parent == root.resolve() and work.name == ".test-build"
if work.exists(): shutil.rmtree(work)
fixtures = work / 'fixtures'
def source(package, name, body):
    p = fixtures / pathlib.Path(package.replace('.', '/')) / (name + '.java')
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text('package ' + package + ';\n' + body)

source('android.net','Uri','public class Uri {}')
source('android.util','Log','public class Log { public static final int INFO=4, ERROR=6, WARN=5; }')
source('android.util','Pair','public class Pair<A,B> { public final A first; public final B second; public Pair(A a,B b){first=a;second=b;} public static <A,B> Pair<A,B> create(A a,B b){return new Pair<>(a,b);} }')
source('android.content.pm','ApplicationInfo','public class ApplicationInfo { public String sourceDir, className; }')
source('android.app','Application','''public class Application {
 public android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
 public String getPackageName(){return "com.apple.android.music";}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return info;}
 public void onCreate(){}
}''')
source('io.github.libxposed.api','XposedModuleInterface','''public interface XposedModuleInterface {
 interface ModuleLoadedParam { String getProcessName(); }
 interface PackageReadyParam { String getPackageName(); boolean isFirstPackage(); ClassLoader getClassLoader(); android.content.pm.ApplicationInfo getApplicationInfo(); }
 default void onModuleLoaded(ModuleLoadedParam p) {} default void onPackageReady(PackageReadyParam p) {}
}''')
source('io.github.libxposed.api','XposedInterface','''import java.lang.reflect.*; import java.util.*;
public interface XposedInterface {
 enum ExceptionMode { PASSTHROUGH, PROTECTIVE }
 interface Chain<T> { Object getThisObject(); List<Object> getArgs(); T proceed() throws Throwable; }
 interface Hooker<T> { T intercept(Chain<T> chain) throws Throwable; }
 interface HookHandle { void unhook(); }
 interface HookBuilder { HookBuilder setId(String s); HookBuilder setExceptionMode(ExceptionMode m); <T> HookHandle intercept(Hooker<T> h); }
}''')
source('io.github.libxposed.api','XposedModule','''import java.lang.reflect.*; import java.util.*;
public class XposedModule implements XposedInterface, XposedModuleInterface {
 public static final Map<Executable,Hooker<?>> HOOKS = new HashMap<>();
 public static int FAIL_AFTER = -1;
 public static boolean FAIL_UNHOOK;
 public static Runnable ON_REGISTER = () -> {};
 public HookBuilder hook(Executable e) { return new HookBuilder(){ public HookBuilder setId(String s){return this;} public HookBuilder setExceptionMode(ExceptionMode m){return this;} public <T> HookHandle intercept(Hooker<T> h){if(FAIL_AFTER==0)throw new IllegalStateException("fixture registration failure");if(FAIL_AFTER>0)FAIL_AFTER--;HOOKS.put(e,h);ON_REGISTER.run();return ()->{if(FAIL_UNHOOK)throw new IllegalStateException("fixture unhook failure");HOOKS.remove(e);};} }; }
 public int getApiVersion(){return 102;} public boolean deoptimize(Executable e){return true;}
 public void log(int p,String t,String m){System.out.println(m);} public void log(int p,String t,String m,Throwable e){System.out.println(m+": "+e);}
}''')
exo='com.google.android.exoplayer2'
app='com.apple.android.music.playback'
source(exo,'Format','''public class Format {
 public String codecs, sampleMimeType, group; public int bitrate, sampleRate, bitDepth, channelCount=2, width=-1,height=-1;
 public Format(String c,int b,int s,int d){codecs=c;bitrate=b;sampleRate=s;bitDepth=d;sampleMimeType="alac".equals(c)?"audio/alac":"audio/mp4a-latm";}
}''')
source(exo+'.source.hls.playlist','HlsPlaylist','''public class HlsPlaylist {
 public String baseUri; public java.util.List<?> tags; public boolean hasIndependentSegments;
 public HlsPlaylist(String b,java.util.List<?> t,boolean h){baseUri=b;tags=t;hasIndependentSegments=h;}
}''')
source(exo+'.source.hls.playlist','HlsMasterPlaylist','''import java.util.*; import com.google.android.exoplayer2.Format;
public class HlsMasterPlaylist extends HlsPlaylist {
 public List<?> variants,videos,audios,subtitles,closedCaptions,muxedCaptionFormats,sessionKeyDrmInitData; public Format muxedAudioFormat; public Map<?,?> variableDefinitions;
 public HlsMasterPlaylist(String b,List<?> tags,List<?> v,List<?> vid,List<?> a,List<?> s,List<?> c,Format f,List<?> mf,boolean h,Map<?,?> vars,List<?> keys){super(b,tags,h);variants=v;videos=vid;audios=a;subtitles=s;closedCaptions=c;muxedAudioFormat=f;muxedCaptionFormats=mf;variableDefinitions=vars;sessionKeyDrmInitData=keys;}
 public static class Variant { public Format format; public String audioGroupId; public Variant(Format f,String g){format=f;audioGroupId=g;} }
 public static class Rendition { public Format format; public String groupId; public Rendition(Format f,String g){format=f;groupId=g;} }
}''')
source(exo+'.source','TrackGroup','''import com.google.android.exoplayer2.Format;
public class TrackGroup { public int length; private Format[] formats; public TrackGroup(Format... f){formats=f;length=f.length;} public Format getFormat(int i){return formats[i];} }''')
source(exo+'.source','TrackGroupArray','''public class TrackGroupArray { public int length; private TrackGroup[] groups; public TrackGroupArray(TrackGroup... g){groups=g;length=g.length;} public TrackGroup get(int i){return groups[i];} }''')
source(exo+'.source','MediaSource','public interface MediaSource { class MediaPeriodId {} }')
source(exo+'.trackselection','DefaultTrackSelector','''import com.google.android.exoplayer2.Format;
public class DefaultTrackSelector {
 public static class Parameters { public boolean exceedAudioConstraintsIfNecessary=false; }
 public static class AudioTrackScore { public boolean isWithinConstraints=true; public AudioTrackScore(Format f,Parameters p,int support){} }
}''')
source(exo+'.trackselection','TrackSelection','''import com.google.android.exoplayer2.source.TrackGroup;
public interface TrackSelection { class Definition { public TrackGroup group; public int[] tracks; public Definition(TrackGroup g,int[] t){group=g;tracks=t;} } }''')
source(exo+'.upstream','ParsingLoadable','public class ParsingLoadable { public interface Parser<T> {} }')
source(app+'.model','AudioQuality','public enum AudioQuality {HIGH_EFFICIENCY,HIGH_QUALITY,LOSSLESS,HIGH_RES_LOSSLESS}')
source(app+'.model','PlayerMediaItem','''public class PlayerMediaItem {
 public boolean downloaded, video, live; public int type=1; public int getType(){return type;} public boolean isMediaKindVideo(){return video;} public boolean isLiveRadio(){return live;} public boolean isDownloadedAsset(){return downloaded;} public String getSubscriptionStoreId(){return "123";}
}''')
source(app+'.model','MediaAssetInfo','''public class MediaAssetInfo { public enum MediaAssetInfoType {DOWNLOADED,HLS_FAST_PATH,HLS_SUBPLAYBACK_DISPATCH,OTHER} public MediaAssetInfoType type=MediaAssetInfoType.HLS_FAST_PATH; public MediaAssetInfoType getType(){return type;} public String getFlavor(){return "HLS";} }''')
source(app+'.player','MediaPlayerContext','''import com.apple.android.music.playback.model.AudioQuality;
public class MediaPlayerContext { public AudioQuality quality=AudioQuality.HIGH_RES_LOSSLESS; public AudioQuality getAudioQualitySetting(){return quality;} }''')
source(app+'.player.datasource','PlayerDataSourceFactory','''public class PlayerDataSourceFactory { public com.apple.android.music.playback.player.MediaPlayerContext context=new com.apple.android.music.playback.player.MediaPlayerContext(); public com.apple.android.music.playback.player.MediaPlayerContext getPlayerContext(){return context;} }''')
source(app+'.player.mediasource','PlaybackAssetMediaPeriod','''import com.apple.android.music.playback.model.*; import com.apple.android.music.playback.player.datasource.*;
public class PlaybackAssetMediaPeriod { public PlayerMediaItem mediaItem=new PlayerMediaItem(); public PlayerDataSourceFactory dataSourceFactory=new PlayerDataSourceFactory(); public void createPeriodUpstream(MediaAssetInfo a){} public static void i(PlaybackAssetMediaPeriod p,MediaAssetInfo a){p.createPeriodUpstream(a);} public void setPrepareError(java.io.IOException e){} }''')
source(app+'.player.mediasource','AppleHlsPlaylistParserFactory','''import com.google.android.exoplayer2.source.hls.playlist.*; import com.google.android.exoplayer2.upstream.*;
public class AppleHlsPlaylistParserFactory { public AppleHlsPlaylistParserFactory(boolean b){} public ParsingLoadable.Parser<?> createPlaylistParser(){return new AppleHlsPlaylistParser();} public ParsingLoadable.Parser<?> createPlaylistParser(HlsMasterPlaylist p){return new AppleHlsPlaylistParser();} }''')
source(app+'.player.mediasource','AppleHlsPlaylistParser','''import android.net.Uri; import java.io.*; import com.google.android.exoplayer2.source.hls.playlist.*; import com.google.android.exoplayer2.upstream.*;
public class AppleHlsPlaylistParser implements ParsingLoadable.Parser<HlsPlaylist> { public HlsPlaylist parse(Uri u,InputStream i){return null;} }''')
source(app+'.player','PlayerTrackSelector','''import java.util.*; import android.util.Pair; import com.apple.android.music.playback.model.*; import com.google.android.exoplayer2.source.*; import com.google.android.exoplayer2.trackselection.*;
public class PlayerTrackSelector {
 public MediaPlayerContext playerContext=new MediaPlayerContext(); public HashMap<Long,Object> targetedTrackFormat=new HashMap<>(); public PlayerMediaItem item=new PlayerMediaItem();
 public PlayerMediaItem getCurrentItem(MediaSource.MediaPeriodId i){return item;}
 public Pair<?,?> selectAudioTrack(TrackGroupArray g,int[][] support,int a,DefaultTrackSelector.Parameters p,boolean b,MediaSource.MediaPeriodId i){return null;}
}''')
source(app+'.util','MediaPlayerUtil','public class MediaPlayerUtil { public static String getAudioGroupIdFromFormat(com.google.android.exoplayer2.Format f){return f.group;} }')
java = os.environ.get('STRICT_JAVA', shutil.which('java') or 'java')
env = os.environ.copy()
jdk = pathlib.Path('/usr/lib/jvm/java-17-openjdk-amd64')
if (jdk/'lib/libjli.so').exists():
    java=str(jdk/'bin/java'); env['LD_LIBRARY_PATH']=str(jdk/'lib')+':'+str(jdk/'lib/server')
production = root/'app/src/main/java/dev/local/applemusicstrict'
sources = list(fixtures.rglob('*.java')) + [production / (n+'.java') for n in ['Host','QualityPolicy','ControlPolicy','StrictManifest']] + [root/'tests/ControlSmoke.java']
classes=work/'classes'; classes.mkdir(parents=True)
subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-d',str(classes),*map(str,sources)],check=True,env=env)
subprocess.run([java,'-ea','-cp',str(classes),'dev.local.applemusicstrict.ControlSmoke'],check=True,env=env)
