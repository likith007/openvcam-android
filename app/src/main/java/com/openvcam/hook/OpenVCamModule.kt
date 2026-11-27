package com.openvcam.hook

import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Modern libxposed 101 entry.
 *
 * The hook implementation is shared with the legacy Xposed entry so LSPosed and
 * Vector can both load the same APK. Vector API 101 does not put the classic
 * de.robv bridge on the module classloader, so the framework interface is attached
 * to the local compatibility shim here before any package callback runs.
 */
class OpenVCamModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.attach(this)
        HookLog.once("api101.attach", "libxposed bridge attached")
    }

    /** Fallback for frameworks that deliver only the earlier package-loaded callback. */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        if (packageName.isEmpty()) return
        val loader = runCatching { param.defaultClassLoader }.getOrNull() ?: return
        VirtualCameraHook.handlePackage(packageName, loader)
        HookLog.once("api101.loaded", "libxposed 101 entry loaded in $packageName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName.isEmpty()) return
        val loader = runCatching { param.classLoader }.getOrNull() ?: return
        VirtualCameraHook.handlePackage(packageName, loader)
        HookLog.once("api101", "libxposed 101 entry ready in $packageName")
    }
}
