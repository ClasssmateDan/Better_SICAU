package com.dan.kejian;

import android.os.Build;
import android.webkit.WebView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;

/** Minimal adapter for the Chromium support boundary used by AndroidX ProxyController.
 * See android/signin/NOTICE.txt. No hidden Android framework API is accessed.
 */
final class SigninProxyBridge implements SigninIpv4Route.ProxySettings {
    private final InvocationHandler controller;
    private final Class<?> boundary;
    SigninProxyBridge(InvocationHandler controller,Class<?> boundary){this.controller=controller;this.boundary=boundary;}
    SigninProxyBridge() throws Exception {
        if(Build.VERSION.SDK_INT<28)throw new UnsupportedOperationException("Android version");
        ClassLoader loader=WebView.getWebViewClassLoader();
        Class<?> glue=Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil",false,loader);
        InvocationHandler factory=(InvocationHandler)glue.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
        Class<?> factoryBoundary=Class.forName("org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface",false,loader);
        String[] features=(String[])invoke(factory,factoryBoundary.getMethod("getSupportedFeatures"));
        if(!Arrays.asList(features).contains("PROXY_OVERRIDE:3") || !Arrays.asList(features).contains("PROXY_OVERRIDE_REVERSE_BYPASS"))
            throw new UnsupportedOperationException("WebView proxy feature");
        controller=(InvocationHandler)invoke(factory,factoryBoundary.getMethod("getProxyController"));
        boundary=Class.forName("org.chromium.support_lib_boundary.ProxyControllerBoundaryInterface",false,loader);
    }
    public void enable(int port,Executor executor,Runnable done) throws Exception {
        String[][] rules={{"https","http://127.0.0.1:"+port}};
        // Reverse bypass selects ONLY this origin; SSO and all other domains use system routing.
        String[] origins={"https://eol.sicau.edu.cn:443"};
        Method method=boundary.getMethod("setProxyOverride",String[][].class,String[].class,Runnable.class,Executor.class,boolean.class);
        invoke(controller,method,rules,origins,done,executor,true);
    }
    public void disable(Executor executor,Runnable done) throws Exception {
        invoke(controller,boundary.getMethod("clearProxyOverride",Runnable.class,Executor.class),done,executor);
    }
    private static Object invoke(InvocationHandler handler,Method method,Object...arguments) throws Exception {
        try{return handler.invoke(null,method,arguments);}
        catch(Throwable error){if(error instanceof Exception)throw (Exception)error;throw new IllegalStateException("WebView proxy interface unavailable",error);}
    }
}
