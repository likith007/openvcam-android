package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Minimal compatibility implementation of the classic Xposed callback.
 *
 * LSPosed supplies its own implementation of this class. Vector's libxposed API 101
 * module loader does not, so this local copy lets the shared hook engine run there too.
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    private final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    public void callBeforeHookedMethod(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public void callAfterHookedMethod(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;

        public MethodHookParam(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args == null ? new Object[0] : args;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public boolean hasResult() {
            return returnEarly;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        public Member getMethod() {
            return method;
        }

        public Object getThisObject() {
            return thisObject;
        }

        public Object[] getArgs() {
            return args;
        }
    }

    public class Unhook {
        private final Member hookMethod;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
            XposedBridge.unhookMethod(hookMethod, XC_MethodHook.this);
        }
    }
}
