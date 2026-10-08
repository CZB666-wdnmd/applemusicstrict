package dev.local.applemusicstrict;

import java.lang.reflect.*;

/** Reflection is confined to host app types; libxposed is called directly. */
final class Host {
    private Host() {}
    static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    static Object get(Object object, String name) throws ReflectiveOperationException {
        return field(object.getClass(), name).get(object);
    }
    static int integer(Object object, String name) throws ReflectiveOperationException {
        return ((Number) get(object, name)).intValue();
    }
    static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { }
        }
        Method m = type.getMethod(name, params); m.setAccessible(true); return m;
    }
    static Method unique(Class<?> type, String name) throws NoSuchMethodException {
        Method found = null;
        for (Method m : type.getDeclaredMethods()) {
            if (!name.equals(m.getName()) || m.isBridge() || m.isSynthetic()) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous " + name);
            found = m;
        }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true); return found;
    }
    static Object call(Object object, String name) throws ReflectiveOperationException {
        return method(object.getClass(), name).invoke(object);
    }
    static String enumName(Object value) { return ((Enum<?>) value).name(); }
    static QualityPolicy.Track track(Object format, String group) throws ReflectiveOperationException {
        return new QualityPolicy.Track((String) get(format, "codecs"),
                (String) get(format, "sampleMimeType"), group,
                integer(format, "bitrate"), integer(format, "sampleRate"),
                integer(format, "bitDepth"), integer(format, "channelCount"));
    }
    static boolean song(Object item) throws ReflectiveOperationException {
        return item != null && ((Number) call(item, "getType")).intValue() == 2
                && !((Boolean) call(item, "isMediaKindVideo"))
                && !((Boolean) call(item, "isLiveRadio"));
    }
    static boolean streamingSong(Object item) throws ReflectiveOperationException {
        return song(item) && !((Boolean) call(item, "isDownloadedAsset"));
    }
    static String quality(Object context) throws ReflectiveOperationException {
        String q = enumName(call(context, "getAudioQualitySetting"));
        if (!QualityPolicy.supportedQuality(q)) throw new IllegalStateException("Unsupported audio setting: " + q);
        return q;
    }
}
