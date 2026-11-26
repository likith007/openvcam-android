package com.openvcam.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Modern libxposed 101 entry.
 *
 * The hook implementation is shared with the legacy Xposed entry so LSPosed and
 * Vector can both load the same APK. API 101 allows the legacy bridge to remain
 * available, while API 102 would block it.
 */
class OpenVCamModule : XposedModule() {

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
