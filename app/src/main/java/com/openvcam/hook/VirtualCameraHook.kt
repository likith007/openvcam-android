package com.openvcam.hook

import android.app.Application
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

        private val installed = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
        )

        /** Shared entry for both the legacy bridge and the libxposed 101 lifecycle. */
        @JvmStatic
        fun handlePackage(packageName: String, loader: ClassLoader) {
            if (packageName == "android") return

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

            HookUtils.hookAllNamed(
                instrumentation,
                listOf("callApplicationOnCreate"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
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
            synchronized(installed) {
                if (!installed.add(loader)) return
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
