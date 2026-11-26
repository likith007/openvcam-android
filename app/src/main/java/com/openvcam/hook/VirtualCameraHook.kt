package com.openvcam.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XC_MethodHook
import java.util.Collections
import java.util.IdentityHashMap

class VirtualCameraHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val loader = lpparam.classLoader ?: return
        handlePackage(packageName, loader)
    }

    companion object {

        private val installedTargets = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<Pair<String, ClassLoader>, Boolean>())
        )
        private val installedLoaders = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
        )

        /** Shared entry for both the legacy bridge and the libxposed 101 lifecycle. */
        @JvmStatic
        fun handlePackage(packageName: String, loader: ClassLoader) {
            if (packageName == "android") return
            if (!installedTargets.add(packageName to loader)) return

            val instrumentation = HookUtils.findClass("android.app.Instrumentation", loader)

            // Vector derives "module active" for legacy modules from a hook in the
            // module's own process. Keep that probe harmless and never install the
            // camera hooks inside OpenVCam itself.
            if (packageName == "com.openvcam") {
                HookUtils.hookAllNamed(
                    instrumentation,
                    listOf("callApplicationOnCreate"),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            HookLog.once("self", "legacy self-hook active in com.openvcam")
                        }
                    },
                    "OpenVCam.self"
                )
                XposedBridge.log("[OpenVCam] self hook installed")
                return
            }

            // Application.attach is the earliest reliable point with a Context. Apps that
            // open a camera from Application.onCreate() would otherwise be missed.
            val applicationClass = HookUtils.findClass("android.app.Application", loader)
            HookUtils.hookAllNamed(
                applicationClass,
                listOf("attach"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.args.getOrNull(0) as? Context ?: return
                        HookConfig.context = context.applicationContext
                        installOnce(loader)
                    }
                },
                "OpenVCam.attach"
            )

            HookUtils.hookAllNamed(
                instrumentation,
                listOf("callApplicationOnCreate"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = param.args.getOrNull(0) as? Application ?: return
                        HookConfig.context = app.applicationContext
                        installOnce(loader)
                    }
                },
                "OpenVCam.app"
            )

            XposedBridge.log("[OpenVCam] module loaded in $packageName")
            installOnce(loader)
        }

        private fun installOnce(loader: ClassLoader) {
            synchronized(installedLoaders) {
                if (!installedLoaders.add(loader)) return
            }
            runCatching { SurfaceSizeTracker.install(loader) }
                .onFailure { HookLog.error("surface-size-tracker", it) }
            runCatching { Camera1Hook.install(loader) }
                .onFailure { HookLog.error("camera1", it) }
            runCatching { Camera2Hook.install(loader) }
                .onFailure { HookLog.error("camera2", it) }
            HookLog.once("installed.$loader", "hooks installed in ${loader.javaClass.name}")
        }
    }
}
