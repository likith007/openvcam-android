package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public class XC_LoadPackage extends XC_Callback {
    public static class LoadPackageParam extends XC_CallbackParam {
        public String packageName;
        public String processName;
        public ApplicationInfo appInfo;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
