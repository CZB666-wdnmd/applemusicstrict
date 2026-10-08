package dev.local.applemusicstrict;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Lifecycle ordering and rollback tests; these do not simulate native ART crashes. */
final class BootstrapSmoke {
    public static class BaseApplication extends Application {
        public void onCreate() { Smoke.check(XposedModule.HOOKS.size()==1, "No playback hooks inside super.onCreate"); }
    }
    public static class HostApplication extends BaseApplication {
        boolean complete;
        @Override public void onCreate() { super.onCreate(); complete=true; }
    }
    static XposedModuleInterface.PackageReadyParam ready(String apk) {
        return new XposedModuleInterface.PackageReadyParam() {
            public String getPackageName(){return "com.apple.android.music";}
            public boolean isFirstPackage(){return true;}
            public ClassLoader getClassLoader(){return BootstrapSmoke.class.getClassLoader();}
            public ApplicationInfo getApplicationInfo(){
                ApplicationInfo info=new ApplicationInfo(); info.sourceDir=apk;
                info.className=HostApplication.class.getName(); return info;
            }
        };
    }
    static void reset() {
        XposedModule.HOOKS.clear(); XposedModule.FAIL_AFTER=-1;
        XposedModule.FAIL_UNHOOK=false; XposedModule.ON_REGISTER=()->{};
    }
    static Method entry() throws Exception { return HostApplication.class.getDeclaredMethod("onCreate"); }
    static void start(String apk) throws Throwable {
        HostApplication app=new HostApplication(); app.info.sourceDir=apk;
        Smoke.invokeFixture(entry(),app,List.of(),()->{app.onCreate();return null;});
        Smoke.check(app.complete,"Host onCreate completed");
    }
    static void run(String apk) throws Throwable {
        reset();
        StrictModule remote=new StrictModule();
        remote.onModuleLoaded(()->"com.apple.android.music:remote");
        remote.onPackageReady(ready(apk));
        Smoke.check(XposedModule.HOOKS.isEmpty(),"Secondary process never installs bootstrap");

        StrictModule module=new StrictModule();module.onModuleLoaded(()->"com.apple.android.music");
        module.onPackageReady(ready(apk));module.onPackageReady(ready(apk));
        Smoke.check(XposedModule.HOOKS.size()==1,"Duplicate package callbacks install one bootstrap");
        Smoke.check(XposedModule.HOOKS.containsKey(entry()),"Bootstrap hooks most-derived onCreate only");
        RuntimeException original=new RuntimeException("host error");
        try {
            Smoke.invokeFixture(entry(),new HostApplication(),List.of(),()->{throw original;});
            throw new AssertionError("Host exception swallowed");
        } catch(RuntimeException error){Smoke.check(error==original,"Host exception identity preserved");}
        Smoke.check(XposedModule.HOOKS.size()==1,"Failed host initialization cannot install playback hooks");

        XposedModule.ON_REGISTER=()->{
            for(Executable method:new ArrayList<>(XposedModule.HOOKS.keySet())) {
                try {
                    if(method.equals(entry()))continue;
                    Object result=Smoke.invokeFixture(method,null,List.of(),()->"original");
                    Smoke.check("original".equals(result),"Preparing callbacks are pure passthrough");
                } catch(Throwable error){throw new AssertionError(error);}
            }
        };
        // Fail the sixth playback registration, leave existing hooks physically present.
        XposedModule.FAIL_AFTER=5;XposedModule.FAIL_UNHOOK=true;
        start(apk);
        Smoke.check(XposedModule.HOOKS.size()==6,"Fixture retained partially installed hooks");
        XposedModule.ON_REGISTER.run();
        Smoke.check(XposedModule.HOOKS.size()==6,"Closed callbacks do not depend on successful unhook");
        XposedModule.FAIL_AFTER=-1;
        Smoke.invokeFixture(entry(),new HostApplication(),List.of(),()->null);
        Smoke.check(XposedModule.HOOKS.size()==6,"Failed installation is not retried in the same process");
        reset();

        StrictModule unknown=new StrictModule();unknown.onModuleLoaded(()->"com.apple.android.music");
        unknown.onPackageReady(ready(apk));
        java.nio.file.Path unknownApk=java.nio.file.Files.createTempFile("unknown-apple-music", ".apk");
        try {start(unknownApk.toString());Smoke.check(XposedModule.HOOKS.size()==1,"Unknown APK cannot install playback hooks");}
        finally {java.nio.file.Files.delete(unknownApk);}
        reset();
    }
}
