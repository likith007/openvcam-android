package com.openvcam.hook

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XC_MethodHook
import java.util.Collections
import java.util.IdentityHashMap

class VirtualCameraHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        if (packageName == "com.openvcam" || packageName == "android") return

        val instrumentation = HookUtils.findClass("android.app.Instrumentation", lpparam.classLoader)
        HookUtils.hookAllNamed(
            instrumentation,
            listOf("callApplicationOnCreate"),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.args.getOrNull(0) as? Application ?: return
                    HookConfig.context = app.applicationContext
                    installOnce(lpparam.classLoader)
                }
            },
            "OpenVCam.app"
        )

        installOnce(lpparam.classLoader)
    }

    private fun installOnce(loader: ClassLoader) {
        if (!installed.add(loader)) return
        runCatching { Camera1Hook.install(loader) }
        runCatching { Camera2Hook.install(loader) }
    }

    companion object {
        private val installed = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    }
}
