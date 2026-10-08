#!/usr/bin/env python3
"""Verify exact supplied build and reflection targets; no APK modifications."""
import argparse, hashlib, json, pathlib, tempfile, zipfile
from dexscan import Dex

ap = argparse.ArgumentParser()
ap.add_argument('apks')
args = ap.parse_args()
with zipfile.ZipFile(args.apks) as bundle:
    base = bundle.read('base.apk')
profiles = {
    '3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603': '7.0.0-beta/1606',
    '75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126': '7.0.0-beta/1607',
}
expected = hashlib.sha256(base).hexdigest()
assert expected in profiles, 'Unsupported base APK'
methods, fields = set(), set()
with tempfile.TemporaryDirectory() as temp:
    basepath = pathlib.Path(temp, 'base.apk'); basepath.write_bytes(base)
    with zipfile.ZipFile(basepath) as apk:
        for name in apk.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                p = pathlib.Path(temp, name); p.write_bytes(apk.read(name)); d = Dex(p)
                for info in d.classes.values():
                    methods.update(m for m, flags, code in info['methods'])
                    fields.update(f for f, flags in info['fields'])

A = 'Lcom/apple/android/music/playback/'
E = 'Lcom/google/android/exoplayer2/'
checks = [
 A+'player/mediasource/PlaybackAssetMediaPeriod;->createPeriodUpstream('+A+'model/MediaAssetInfo;)V',
 A+'player/mediasource/PlaybackAssetMediaPeriod;->i('+A+'player/mediasource/PlaybackAssetMediaPeriod;'+A+'model/MediaAssetInfo;)V',
 A+'player/mediasource/PlaybackAssetMediaPeriod;->setPrepareError(Ljava/io/IOException;)V',
 A+'player/datasource/PlayerDataSourceFactory;->getPlayerContext()'+A+'player/MediaPlayerContext;',
 A+'model/MediaAssetInfo;->getType()'+A+'model/MediaAssetInfo$MediaAssetInfoType;',
 A+'model/MediaAssetInfo;->getFlavor()Ljava/lang/String;',
 A+'player/mediasource/AppleHlsPlaylistParserFactory;-><init>(Z)V',
 A+'player/mediasource/AppleHlsPlaylistParserFactory;->createPlaylistParser()'+E+'upstream/ParsingLoadable$Parser;',
 A+'player/mediasource/AppleHlsPlaylistParserFactory;->createPlaylistParser('+E+'source/hls/playlist/HlsMasterPlaylist;)'+E+'upstream/ParsingLoadable$Parser;',
 A+'player/mediasource/AppleHlsPlaylistParser;->parse(Landroid/net/Uri;Ljava/io/InputStream;)'+E+'source/hls/playlist/HlsPlaylist;',
 A+'player/PlayerTrackSelector;->selectAudioTrack('+E+'source/TrackGroupArray;[[II'+E+'trackselection/DefaultTrackSelector$Parameters;Z'+E+'source/MediaSource$MediaPeriodId;)Landroid/util/Pair;',
 A+'player/PlayerTrackSelector;->getCurrentItem('+E+'source/MediaSource$MediaPeriodId;)'+A+'model/PlayerMediaItem;',
 E+'source/TrackGroupArray;->get(I)'+E+'source/TrackGroup;',
 E+'source/TrackGroup;->getFormat(I)'+E+'Format;',
 E+'trackselection/TrackSelection$Definition;-><init>('+E+'source/TrackGroup;[I)V',
 E+'trackselection/DefaultTrackSelector$AudioTrackScore;-><init>('+E+'Format;'+E+'trackselection/DefaultTrackSelector$Parameters;I)V',
 A+'util/MediaPlayerUtil;->getAudioGroupIdFromFormat('+E+'Format;)Ljava/lang/String;',
 E+'source/hls/playlist/HlsMasterPlaylist;-><init>(Ljava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;'+E+'Format;Ljava/util/List;ZLjava/util/Map;Ljava/util/List;)V',
 A+'player/MediaPlayerContext;->getAudioQualitySetting()'+A+'model/AudioQuality;',
]
for n, ret in [('getType','I'),('isMediaKindVideo','Z'),('isLiveRadio','Z'),('isDownloadedAsset','Z'),('getSubscriptionStoreId','Ljava/lang/String;')]:
    checks.append(A+'model/PlayerMediaItem;->'+n+'()'+ret)
fieldchecks = [
 A+'player/mediasource/PlaybackAssetMediaPeriod;->mediaItem:'+A+'model/PlayerMediaItem;',
 A+'player/mediasource/PlaybackAssetMediaPeriod;->dataSourceFactory:'+A+'player/datasource/PlayerDataSourceFactory;',
 A+'player/PlayerTrackSelector;->playerContext:'+A+'player/MediaPlayerContext;',
 A+'player/PlayerTrackSelector;->targetedTrackFormat:Ljava/util/HashMap;',
 E+'source/TrackGroupArray;->length:I', E+'source/TrackGroup;->length:I',
 E+'trackselection/DefaultTrackSelector$AudioTrackScore;->isWithinConstraints:Z',
 E+'trackselection/DefaultTrackSelector$Parameters;->exceedAudioConstraintsIfNecessary:Z',
]
for n,t in [('codecs','Ljava/lang/String;'),('sampleMimeType','Ljava/lang/String;'),('bitrate','I'),('sampleRate','I'),('bitDepth','I'),('channelCount','I'),('width','I'),('height','I')]:
    fieldchecks.append(E+'Format;->'+n+':'+t)
for n,t in [('variants','Ljava/util/List;'),('videos','Ljava/util/List;'),('audios','Ljava/util/List;'),('subtitles','Ljava/util/List;'),('closedCaptions','Ljava/util/List;'),('muxedAudioFormat',E+'Format;'),('muxedCaptionFormats','Ljava/util/List;'),('variableDefinitions','Ljava/util/Map;'),('sessionKeyDrmInitData','Ljava/util/List;')]:
    fieldchecks.append(E+'source/hls/playlist/HlsMasterPlaylist;->'+n+':'+t)
for n,t in [('baseUri','Ljava/lang/String;'),('tags','Ljava/util/List;'),('hasIndependentSegments','Z')]:
    fieldchecks.append(E+'source/hls/playlist/HlsPlaylist;->'+n+':'+t)
for n,t in [('format',E+'Format;'),('audioGroupId','Ljava/lang/String;')]:
    fieldchecks.append(E+'source/hls/playlist/HlsMasterPlaylist$Variant;->'+n+':'+t)
for n,t in [('format',E+'Format;'),('groupId','Ljava/lang/String;')]:
    fieldchecks.append(E+'source/hls/playlist/HlsMasterPlaylist$Rendition;->'+n+':'+t)
missing = [m for m in checks if m not in methods] + [f for f in fieldchecks if f not in fields]
assert not missing, '\n'.join(missing)
print(json.dumps({'profile':profiles[expected],'base_sha256':expected,'method_signatures_checked':len(checks),
                  'field_signatures_checked':len(fieldchecks),'result':'PASS'}, indent=2))
