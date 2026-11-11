package com.dan.kejian;

import android.os.Looper;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SigninIpv4RouteTest {
    static final class Provider implements SigninIpv4Route.ProxySettings {
        int enabled,disabled;final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
        public void enable(int port,Executor executor,Runnable done){assertTrue(port>0);enabled++;callbacks.add(()->executor.execute(done));}
        public void disable(Executor executor,Runnable done){disabled++;callbacks.add(()->executor.execute(done));}
        void complete(){callbacks.remove().run();shadowOf(Looper.getMainLooper()).idle();}
        void drain(){for(int i=0;i<10&&!callbacks.isEmpty();i++)complete();}
    }
    @Test public void providerChangesAreSerializedAndLastWindowReleasesTunnel(){
        SigninIpv4Route.Factory before=SigninIpv4Route.factory;Provider provider=new Provider();SigninIpv4Route.factory=()->provider;
        SigninIpv4Route first=new SigninIpv4Route(),second=new SigninIpv4Route();AtomicInteger done=new AtomicInteger();
        try{
            first.apply(true,done::incrementAndGet,message->{throw new AssertionError(message);});
            second.apply(false,done::incrementAndGet,message->{throw new AssertionError(message);});
            assertEquals(1,provider.enabled);assertEquals(0,provider.disabled);assertEquals(0,done.get());
            provider.complete();assertEquals(1,done.get());assertEquals(1,provider.disabled);
            provider.complete();assertEquals(2,done.get());assertTrue(second.describe().contains("系统线路"));
            first.close();assertEquals(1,provider.disabled);second.close();assertEquals(2,provider.disabled);provider.complete();
        }finally{first.close();second.close();provider.drain();SigninIpv4Route.factory=before;}
    }
    @Test public void closedOwnerCallbackCannotTriggerNavigation(){
        SigninIpv4Route.Factory before=SigninIpv4Route.factory;Provider provider=new Provider();SigninIpv4Route.factory=()->provider;
        SigninIpv4Route route=new SigninIpv4Route();AtomicInteger done=new AtomicInteger();
        try{
            route.apply(true,done::incrementAndGet,message->{});route.close();provider.complete();
            assertEquals(0,done.get());assertEquals(1,provider.disabled);provider.complete();
        }finally{route.close();provider.drain();SigninIpv4Route.factory=before;}
    }
}
