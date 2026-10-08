package dev.local.applemusicstrict;

import android.content.pm.ApplicationInfo;
import android.util.Pair;
import com.apple.android.music.playback.model.*;
import com.apple.android.music.playback.player.*;
import com.apple.android.music.playback.player.mediasource.*;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.source.hls.playlist.*;
import com.google.android.exoplayer2.trackselection.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

public final class Smoke {
    static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    static HlsMasterPlaylist master(List<?> variants, List<?> audios) {
        return new HlsMasterPlaylist("https://fixture.invalid/master", List.of("tag"), variants,
                List.of(), audios, List.of(), List.of(), null, null, true, Map.of("v","x"), List.of("key"));
    }
    static HlsMasterPlaylist.Variant v(String codec, int bitrate, int rate, int depth) {
        return new HlsMasterPlaylist.Variant(new Format(codec,bitrate,rate,depth),null);
    }
    static QualityPolicy.Track t(String codec, int bitrate, int rate, int depth) {
        return new QualityPolicy.Track(codec,"alac".equals(codec)?"audio/alac":"audio/mp4a-latm",null,bitrate,rate,depth,2);
    }
    interface Original { Object call() throws Throwable; }
    @SuppressWarnings("unchecked")
    static Object invokeFixture(Executable executable, Object receiver, List<Object> args, Original original) throws Throwable {
        XposedInterface.Hooker<Object> hook=(XposedInterface.Hooker<Object>)XposedModule.HOOKS.get(executable);
        if(hook==null) return original.call();
        return hook.intercept(new XposedInterface.Chain<>(){
            public Object getThisObject(){return receiver;} public List<Object> getArgs(){return args;}
            public Object proceed() throws Throwable{return original.call();}
        });
    }
    public static void main(String[] args) throws Throwable {
        BootstrapSmoke.run(args[0]);
        check(!QualityPolicy.allows("HIGH_RES_LOSSLESS",t("mp4a.40.2",256000,44100,16)),"AAC rejected for HiRes");
        check(!QualityPolicy.allows("LOSSLESS",t("mp4a.40.2",64000,44100,16)),"AAC rejected for Lossless");
        check(QualityPolicy.allows("HIGH_RES_LOSSLESS",t("alac",900000,44100,16)),"Ordinary ALAC master accepted for HiRes");
        check(QualityPolicy.allows("LOSSLESS",t("alac",1400000,48000,24)),"Lossless ceiling");
        check(!QualityPolicy.allows("LOSSLESS",t("alac",2800000,96000,24)),"Lossless sample ceiling enforced");
        check(!QualityPolicy.allows("HIGH_RES_LOSSLESS",t("alac",6000000,384000,24)),"HiRes sample ceiling enforced");
        check(!QualityPolicy.allows("HIGH_RES_LOSSLESS",t("alac",2800000,96000,0)),"Unknown depth rejected");
        check(!QualityPolicy.allows("HIGH_RES_LOSSLESS",t("alac",2800000,-1,24)),"Unknown sample rate rejected");
        check(QualityPolicy.allows("HIGH_QUALITY",t("mp4a.40.2",256000,44100,16)),"HQ AAC accepted");
        check(!QualityPolicy.allows("HIGH_QUALITY",t("mp4a.40.2",64000,44100,16)),"HQ does not start with low AAC");
        check(!QualityPolicy.allows("HIGH_EFFICIENCY",t("mp4a.40.2",256000,44100,16)),"Efficiency does not select HQ");
        check(QualityPolicy.compare("HIGH_EFFICIENCY",t("mp4a.40.2",64000,44100,16),t("mp4a.40.2",128000,44100,16))>0,"Efficiency minimizes bandwidth");
        StrictManifest filter=new StrictManifest(Smoke.class.getClassLoader());
        List<HlsMasterPlaylist.Variant> variants=new ArrayList<>(List.of(v("mp4a.40.2",64000,44100,16),v("mp4a.40.2",256000,44100,16),v("alac",1000000,44100,16),v("alac",1400000,48000,24),v("alac",5000000,192000,24)));
        Random random=new Random(1607);
        for(int i=0;i<200;i++) {
            Collections.shuffle(variants,random);
            HlsMasterPlaylist original=master(List.copyOf(variants),List.of());
            for(String q:List.of("HIGH_EFFICIENCY","HIGH_QUALITY","LOSSLESS","HIGH_RES_LOSSLESS")) {
                HlsMasterPlaylist result=(HlsMasterPlaylist)filter.filter(original,q);
                check(result.variants.size()==1,"Only one variant survives");
                Format chosen=((HlsMasterPlaylist.Variant)result.variants.get(0)).format;
                check(QualityPolicy.allows(q,Host.track(chosen,null)),"Allowed codec only");
                int expected=switch(q){case "LOSSLESS"->48000;case "HIGH_RES_LOSSLESS"->192000;default->44100;};
                check(chosen.sampleRate==expected,"Best sample rate independent of playlist order");
                check(result.sessionKeyDrmInitData==original.sessionKeyDrmInitData && result.variableDefinitions==original.variableDefinitions,"Key/variable objects preserved");
                check(original.variants.size()==5,"Original playlist unmodified");
            }
        }
        try {filter.filter(master(List.of(v("mp4a.40.2",64000,44100,16)),List.of()),"LOSSLESS");throw new AssertionError("Unexpected fallback");}
        catch(IllegalStateException expected){checks++;}
        Object media=new Object(); check(filter.filter(media,"LOSSLESS")==media,"Media playlist untouched");
        PlayerMediaItem item=new PlayerMediaItem();check(Host.streamingSong(item),"Stream song scope");
        item.video=true;check(!Host.streamingSong(item),"Video excluded");item.video=false;item.live=true;check(!Host.streamingSong(item),"Live excluded");item.live=false;item.downloaded=true;check(!Host.streamingSong(item),"Downloaded file excluded");

        StrictModule module=new StrictModule();
        module.onModuleLoaded(()->"com.apple.android.music");
        module.onPackageReady(BootstrapSmoke.ready(args[0]));
        check(XposedModule.HOOKS.size()==1,"Only lifecycle bootstrap installed before Application.onCreate");
        BootstrapSmoke.start(args[0]);
        check(XposedModule.HOOKS.size()==7,"Bootstrap and six playback hooks installed after Application.onCreate");
        PlaybackAssetMediaPeriod period=new PlaybackAssetMediaPeriod();
        Method sourceMethod=PlaybackAssetMediaPeriod.class.getDeclaredMethod("createPeriodUpstream",MediaAssetInfo.class);
        Constructor<?> factoryCtor=AppleHlsPlaylistParserFactory.class.getDeclaredConstructor(boolean.class);
        Method createParser=AppleHlsPlaylistParserFactory.class.getDeclaredMethod("createPlaylistParser");
        Method parse=AppleHlsPlaylistParser.class.getDeclaredMethod("parse",android.net.Uri.class,java.io.InputStream.class);
        final AppleHlsPlaylistParser[] parserHolder={null};
        invokeFixture(sourceMethod,period,List.of(new MediaAssetInfo()),()->{
            AppleHlsPlaylistParserFactory factory=new AppleHlsPlaylistParserFactory(false);
            invokeFixture(factoryCtor,factory,List.of(false),()->null);
            parserHolder[0]=(AppleHlsPlaylistParser)invokeFixture(createParser,factory,List.of(),factory::createPlaylistParser);
            return null;
        });
        HlsMasterPlaylist supplied=master(List.of(v("mp4a.40.2",64000,44100,16),v("alac",4000000,96000,24)),List.of());
        final Object[] parsed={null}; final Throwable[] failed={null};
        Thread loader=new Thread(()->{
            try{parsed[0]=invokeFixture(parse,parserHolder[0],List.of(),()->supplied);}catch(Throwable e){failed[0]=e;}
        }); loader.start();loader.join();
        if(failed[0]!=null)throw failed[0];
        check(((HlsMasterPlaylist)parsed[0]).variants.size()==1,"Async parser receives per-source context");
        check("alac".equals(((HlsMasterPlaylist.Variant)((HlsMasterPlaylist)parsed[0]).variants.get(0)).format.codecs),"AAC removed before loader sees master");
        try{invokeFixture(parse,parserHolder[0],List.of(),()->master(List.of(v("mp4a.40.2",64000,44100,16)),List.of()));throw new AssertionError("AAC master accepted");}
        catch(java.io.IOException expected){checks++;}
        AppleHlsPlaylistParserFactory unrelated=new AppleHlsPlaylistParserFactory(false);
        invokeFixture(factoryCtor,unrelated,List.of(false),()->null);
        AppleHlsPlaylistParser unrelatedParser=(AppleHlsPlaylistParser)invokeFixture(createParser,unrelated,List.of(),unrelated::createPlaylistParser);
        check(invokeFixture(parse,unrelatedParser,List.of(),()->supplied)==supplied,"Context scope cleaned up after construction");
        Method select=PlayerTrackSelector.class.getDeclaredMethod("selectAudioTrack",TrackGroupArray.class,int[][].class,int.class,DefaultTrackSelector.Parameters.class,boolean.class,MediaSource.MediaPeriodId.class);
        @SuppressWarnings("unchecked") XposedInterface.Hooker<Object> hook=(XposedInterface.Hooker<Object>)XposedModule.HOOKS.get(select);
        PlayerTrackSelector selector=new PlayerTrackSelector();
        TrackGroupArray groups=new TrackGroupArray(new TrackGroup(new Format("mp4a.40.2",64000,44100,16),new Format("alac",4000000,96000,24)));
        List<Object> params=List.of(groups,new int[][]{{4,4}},0,new DefaultTrackSelector.Parameters(),false,new MediaSource.MediaPeriodId());
        final int[] originalCalls={0};
        XposedInterface.Chain<Object> chain=new XposedInterface.Chain<>(){
            public Object getThisObject(){return selector;} public List<Object> getArgs(){return params;}
            public Object proceed(){originalCalls[0]++;return "original";}
        };
        Object result=hook.intercept(chain);
        int[] selected=((TrackSelection.Definition)((Pair<?,?>)result).first).tracks;
        check(selected.length==1 && selected[0]==1,"Final selection pins ALAC");
        check(originalCalls[0]==0,"Original AAC fallback bypassed");
        check(selector.targetedTrackFormat.containsKey(123L),"Target reporting updated to real selection");
        selector.playerContext.quality=AudioQuality.LOSSLESS;
        try{hook.intercept(chain);throw new AssertionError("Unexpected downgrade");}catch(IllegalStateException expected){checks++;}
        check(originalCalls[0]==0,"Failed strict selection never calls original");
        selector.item.downloaded=true;check("original".equals(hook.intercept(chain)),"Offline path preserved");
        System.out.println("PASS: "+checks+" invariant checks. API-shaped fixtures only; Android and real libxposed build not tested.");
    }
}
