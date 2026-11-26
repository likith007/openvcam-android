package de.robv.android.xposed.callbacks;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Member method;
        public Object result;
        public Throwable throwable;

        public Object getResult() { return result; }
        public Object getResultOrNull() { return result; }
        public void setResult(Object result) { this.result = result; this.hasReturned = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; }
        public Object[] getArgs() { return args; }
        public Object getThisObject() { return thisObject; }
        public Member getMethod() { return method; }
        public boolean hasReturned() { return hasReturned; }

        public boolean hasReturned = false;
    }

    public static class Unhook {
        public void unhook() { }
    }
}
