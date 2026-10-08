package dev.local.applemusicstrict;
import java.util.*;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist;

public final class ControlSmoke {
    static int checks;
    static void check(boolean b, String message) { checks++; if (!b) throw new AssertionError(message); }
    static QualityPolicy.Track t(String codec, String group, int rate, int bits, int bitrate) {
        return new QualityPolicy.Track(codec, codec.equals("alac") ? "audio/alac" : codec.equals("ec+3") ? "audio/eac3-joc" : "audio/mp4a-latm", group, bitrate, rate, bits, 2);
    }
    public static void main(String[] args) throws Exception {
        var aac = t("mp4a.40.2", "aac", 44100, 16, 256000);
        var cd = t("alac", "lossless", 44100, 16, 1000000);
        var hi = t("alac", "hires", 192000, 24, 7000000);
        var atmos = t("ec+3", "atmos", 48000, -1, 768000);
        var unknown = t("alac", "bad", -1, -1, 9000000);
        var tracks = new ArrayList<>(List.of(aac, cd, hi, atmos, unknown));
        Random r = new Random(1606);
        for (int i=0;i<100;i++) {
            Collections.shuffle(tracks,r);
            check(ControlPolicy.choose(tracks,false,"") == hi,"Highest lossless must not depend on list order or AAC bitrate");
            check(ControlPolicy.choose(tracks,true,"") == atmos,"Atmos preference");
            check(ControlPolicy.choose(tracks,true,ControlPolicy.id(cd)) == cd,"Manual override wins over auto and Atmos");
        }
        check(ControlPolicy.choose(List.of(aac,cd),true,"") == cd,"Atmos unavailable chooses highest lossless");
        check(ControlPolicy.choose(List.of(aac),false,"") == aac,"AAC-only catalog uses its actual maximum");
        try { ControlPolicy.choose(tracks,false,"missing"); throw new AssertionError("Manual disappearance silently downgraded"); } catch(IllegalStateException expected) { checks++; }
        try { ControlPolicy.choose(List.of(unknown),false,""); throw new AssertionError("Unknown lossless metadata"); } catch(IllegalStateException expected) { checks++; }
        check(!ControlPolicy.atmos(t("mp4a.40.2","atmos-downmix",48000,16,256000)),"Do not label AAC downmix Atmos");
        check(!ControlPolicy.eligible(t("mp4a.40.2","binaural",48000,16,256000),false),"Dolby off excludes binaural AAC");
        var item = new com.apple.android.music.playback.model.PlayerMediaItem();
        check(Host.streamingSong(item), "Actual song type 1 must enter enforcement");
        item.type=2; check(!Host.streamingSong(item), "Other types excluded"); item.type=1;
        item.downloaded=true; check(!Host.streamingSong(item), "Offline source cannot gain other qualities"); item.downloaded=false;
        item.video=true; check(!Host.streamingSong(item), "Video excluded"); item.video=false;
        item.live=true; check(!Host.streamingSong(item), "Radio excluded");
        var manifestAac = new QualityPolicy.Track("mp4a.40.2", null, "audio-stereo-256",268394,-1,0,-1);
        var rendererAac = new QualityPolicy.Track("mp4a.40.2", "audio/mp4a-latm", "audio-stereo-256",268394,44100,16,2);
        check(ControlPolicy.id(manifestAac).equals(ControlPolicy.id(rendererAac)), "Variant identity survives decoder metadata enrichment");
        check(ControlPolicy.choose(List.of(rendererAac), false, ControlPolicy.id(manifestAac))==rendererAac,"Manual AAC selection survives missing manifest metadata");
        var filter = new StrictManifest(ControlSmoke.class.getClassLoader());
        List<Object> variants = new ArrayList<>();
        for (var t: List.of(aac,cd,hi,atmos)) {
            var f = new Format(t.codecs(), t.bitrate(),t.sampleRate(),t.bitDepth());
            f.channelCount=2; f.group=t.group(); f.sampleMimeType=t.mime();
            variants.add(new HlsMasterPlaylist.Variant(f,t.group()));
        }
        var original = new HlsMasterPlaylist("https://fixture.invalid",List.of("tag"),variants,List.of(),List.of(),List.of(),List.of(),null,null,true,Map.of("v","x"),List.of("session"));
        for (var t:filter.tracks(original)) {
            var filtered=(HlsMasterPlaylist)filter.filterChoice(original,t);
            check(filtered.variants.size()==1,"No adaptive alternative survives");
            check(filtered.sessionKeyDrmInitData==original.sessionKeyDrmInitData,"Preserve DRM metadata without touching encryption");
            check(filtered.variableDefinitions==original.variableDefinitions,"Preserve HLS variables");
            check(original.variants.size()==4,"No mutation of source playlist");
        }
        Format video=new Format("avc1",100000,48000,16);video.height=1080;
        var invalid=new HlsMasterPlaylist("",List.of(),List.of(new HlsMasterPlaylist.Variant(video,"v")),List.of(),List.of(),List.of(),List.of(),null,null,true,Map.of(),List.of());
        try {filter.tracks(invalid);throw new AssertionError("Video scope");} catch(IllegalStateException expected) {checks++;}
        System.out.println("PASS: "+checks+" policy and manifest assertions (not a real-device integration test)");
    }
}
