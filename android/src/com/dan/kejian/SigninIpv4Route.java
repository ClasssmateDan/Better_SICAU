package com.dan.kejian;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Serializes process-wide WebView changes and keeps one foreground-owned loopback listener. */
final class SigninIpv4Route implements SigninRoute {
    interface ProxySettings { void enable(int port,Executor executor,Runnable done) throws Exception;void disable(Executor executor,Runnable done) throws Exception; }
    interface Factory { ProxySettings create() throws Exception; }
    static Factory factory=SigninProxyBridge::new;
    private static final Executor UI=task->new Handler(Looper.getMainLooper()).post(task);
    private static final ArrayDeque<Request> requests=new ArrayDeque<>();
    private static ProxySettings settings;
    private static SigninIpv4Tunnel tunnel;
    private static boolean busy,enabled;
    private static int owners;
    private boolean closed;
    private static final class Request {
        final SigninIpv4Route owner;final boolean ipv4;final Runnable done;final Consumer<String> fail;
        Request(SigninIpv4Route owner,boolean ipv4,Runnable done,Consumer<String> fail){this.owner=owner;this.ipv4=ipv4;this.done=done;this.fail=fail;}
    }
    SigninIpv4Route(){synchronized(SigninIpv4Route.class){owners++;}}
    public void apply(boolean ipv4,Runnable done,Consumer<String> failure){
        synchronized(SigninIpv4Route.class){if(closed)return;requests.add(new Request(this,ipv4,done,failure));pump();}
    }
    private static synchronized void pump(){
        if(busy)return;
        Request request=requests.poll();if(request==null)return;
        if(request.owner!=null&&request.owner.closed || request.owner==null&&owners>0){pump();return;}
        busy=true;
        try{
            if(request.ipv4){
                if(settings==null)settings=factory.create();
                if(tunnel==null)tunnel=new SigninIpv4Tunnel();
                settings.enable(tunnel.port(),UI,()->finish(request,null));
            }else if(settings!=null)settings.disable(UI,()->finish(request,null));
            else finish(request,null);
        }catch(Exception error){finish(request,error);}
    }
    private static synchronized void finish(Request request,Exception error){
        if(error==null)enabled=request.ipv4;
        busy=false;
        if(request.owner==null&&owners==0){if(tunnel!=null)tunnel.close();tunnel=null;settings=null;enabled=false;}
        if(request.owner!=null&&!request.owner.closed){
            if(error==null)request.done.run();else request.fail.accept("当前 WebView 无法启用此兼容选项，请更新系统 WebView 后重试。");
        }
        pump();
    }
    public String describe(){synchronized(SigninIpv4Route.class){return "浏览器线路："+(enabled?"IPv4 兼容（仅签到域名）":"系统线路")+"\n"+(enabled&&tunnel!=null?tunnel.summary():"");}}
    public void close(){synchronized(SigninIpv4Route.class){if(closed)return;closed=true;if(--owners==0){requests.add(new Request(null,false,()->{},ignored->{}));pump();}}}
}
