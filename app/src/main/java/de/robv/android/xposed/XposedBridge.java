package de.robv.android.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridge from the classic Xposed hooking surface to libxposed API 101.
 *
 * On LSPosed the framework's own class wins classloader resolution. On Vector API 101
 * this implementation is used after {@link OpenVCamModule} attaches its framework context.
 */
public final class XposedBridge {

    private static volatile XposedInterface framework;

    private static final ConcurrentMap<Member, ConcurrentMap<XC_MethodHook, HookHandle>> HANDLES =
            new ConcurrentHashMap<>();

    private XposedBridge() {
    }

    public static void attach(XposedInterface api) {
        if (api != null) framework = api;
    }

    public static void log(String message) {
        XposedInterface api = framework;
        if (api != null) {
            try {
                api.log(Log.INFO, "Xposed", message);
                return;
            } catch (Throwable ignored) {
                // Fall through to logcat.
            }
        }
        Log.i("Xposed", message);
    }

    public static void log(Throwable throwable) {
        XposedInterface api = framework;
        if (api != null) {
            try {
                api.log(Log.ERROR, "Xposed", "OpenVCam", throwable);
                return;
            } catch (Throwable ignored) {
                // Fall through to logcat.
            }
        }
        Log.e("Xposed", "OpenVCam", throwable);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked");
        }
        XposedInterface api = framework;
        if (api == null) {
            throw new IllegalStateException("libxposed interface is not attached");
        }

        final Executable executable = (Executable) hookMethod;
        HookHandle handle =
                api.hook(executable)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(
                                chain -> {
                                    List<Object> chainArgs = chain.getArgs();
                                    Object[] args =
                                            chainArgs == null
                                                    ? new Object[0]
                                                    : chainArgs.toArray();
                                    XC_MethodHook.MethodHookParam param =
                                            new XC_MethodHook.MethodHookParam(
                                                    hookMethod, chain.getThisObject(), args);

                                    callback.callBeforeHookedMethod(param);
                                    if (param.returnEarly) {
                                        if (param.throwable != null) throw param.throwable;
                                        return param.result;
                                    }

                                    Object result;
                                    try {
                                        result = chain.proceed(param.args);
                                    } catch (Throwable t) {
                                        param.throwable = t;
                                        try {
                                            callback.callAfterHookedMethod(param);
                                        } catch (Throwable afterError) {
                                            Log.e(
                                                    "Xposed",
                                                    "[OpenVCam] after-hook error on " + hookMethod,
                                                    afterError);
                                        }
                                        if (param.returnEarly && param.throwable == null) {
                                            return param.result;
                                        }
                                        throw t;
                                    }

                                    param.result = result;
                                    callback.callAfterHookedMethod(param);
                                    if (param.throwable != null) throw param.throwable;
                                    return param.returnEarly ? param.result : result;
                                });

        ConcurrentMap<XC_MethodHook, HookHandle> byCallback =
                HANDLES.computeIfAbsent(hookMethod, key -> new ConcurrentHashMap<>());
        byCallback.put(callback, handle);
        return callback.new Unhook(hookMethod);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        ConcurrentMap<XC_MethodHook, HookHandle> byCallback = HANDLES.get(hookMethod);
        if (byCallback == null) return;
        HookHandle handle = byCallback.remove(callback);
        if (handle != null) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // Nothing useful to do while unhooking.
            }
        }
    }
}
