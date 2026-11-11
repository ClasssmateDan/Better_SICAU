package com.dan.kejian;

import java.lang.reflect.InvocationHandler;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SigninProxyBridgeTest {
    public interface Boundary {
        void setProxyOverride(String[][] rules,String[] origins,Runnable listener,Executor executor,boolean reverse);
        void clearProxyOverride(Runnable listener,Executor executor);
    }
    @Test public void onlyExactHttpsOriginUsesProxyAndCompletionFollowsProviderCallback() throws Exception{
        AtomicInteger completed=new AtomicInteger();Object[][] pending={null};
        InvocationHandler provider=(proxy,method,args)->{
            assertEquals("setProxyOverride",method.getName());
            assertArrayEquals(new String[]{"https","http://127.0.0.1:12345"},((String[][])args[0])[0]);
            assertArrayEquals(new String[]{"https://eol.sicau.edu.cn:443"},(String[])args[1]);assertEquals(Boolean.TRUE,args[4]);pending[0]=args;return null;
        };
        new SigninProxyBridge(provider,Boundary.class).enable(12345,Runnable::run,completed::incrementAndGet);
        assertEquals(0,completed.get());((Executor)pending[0][3]).execute((Runnable)pending[0][2]);assertEquals(1,completed.get());
    }
    @Test public void clearUsesOriginalSystemProxyConfiguration() throws Exception{
        AtomicInteger completed=new AtomicInteger();
        new SigninProxyBridge((proxy,method,args)->{assertEquals("clearProxyOverride",method.getName());((Executor)args[1]).execute((Runnable)args[0]);return null;},Boundary.class).disable(Runnable::run,completed::incrementAndGet);
        assertEquals(1,completed.get());
    }
    @Test public void providerFailureIsPropagatedInsteadOfReportingEnabled() throws Exception{
        AtomicInteger completed=new AtomicInteger();
        try{new SigninProxyBridge((p,m,a)->{throw new UnsupportedOperationException();},Boundary.class).enable(12345,Runnable::run,completed::incrementAndGet);fail();}
        catch(UnsupportedOperationException expected){}
        assertEquals(0,completed.get());
    }
}
