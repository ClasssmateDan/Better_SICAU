package com.dan.qrbatch.network;

import com.dan.qrbatch.core.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class BatchPreparationTest {
    private static List<Account> accounts(){
        List<Account> result=new ArrayList<>();for(int i=0;i<4;i++){Map<String,String> cookies=new LinkedHashMap<>();cookies.put("_uid",String.valueOf(10001+i));result.add(new Account("19900000000",String.valueOf(10001+i),"测试账号","-1",cookies));}return result;
    }
    private static final PreparationRunner.Listener IGNORE=new PreparationRunner.Listener(){
        public void progress(int index,int total,Account account,String detail){}
        public void finished(int finished,int total,PreparationRunner.Result result){}
    };
    private static final class Script implements ChaoxingClient.Transport {
        int courses=6,delayMs;boolean active;
        CountDownLatch release;
        final CountDownLatch sixQueries=new CountDownLatch(6);
        final AtomicInteger inFlight=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();
        final Map<String,AtomicInteger> byUid=new ConcurrentHashMap<>();
        final Map<String,List<String>> steps=new ConcurrentHashMap<>();
        public ChaoxingClient.Response send(String method,String address,Map<String,String> values,Map<String,String> cookies)throws IOException{
            String uid; synchronized(cookies){uid=cookies.get("_uid");}
            assertNotNull(uid);String path=URI.create(address).getPath();
            if(path.equals("/visit/courselistdata")){
                StringBuilder html=new StringBuilder();for(int i=1;i<=courses;i++)html.append("<div id='course_").append(i).append('_').append(i+100).append("'></div>");return reply(html.toString());
            }
            Map<String,String> query=query(address);
            if(path.endsWith("/activelist")){
                int count=inFlight.incrementAndGet();peak.accumulateAndGet(count,Math::max);calls.incrementAndGet();
                AtomicInteger perAccount=byUid.computeIfAbsent(uid,key->new AtomicInteger());perAccount.incrementAndGet();sixQueries.countDown();
                try{
                    if(release!=null&&!release.await(3,TimeUnit.SECONDS))throw new IOException("测试屏障超时");
                    if(delayMs>0)Thread.sleep(delayMs);
                    synchronized(cookies){assertEquals(uid,cookies.get("_uid"));cookies.put("query_"+query.get("courseId"),uid);}
                    String list=active&&query.get("courseId").equals("1")?"{\"id\":\"9001\",\"status\":1,\"otherId\":2,\"nameOne\":\"测试活动\"}":"";
                    return reply("{\"data\":{\"activeList\":["+list+"]}}");
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("测试请求取消");}
                finally{perAccount.decrementAndGet();inFlight.decrementAndGet();}
            }
            assertEquals("All own course reads precede presign",0,byUid.get(uid).get());
            List<String> accountSteps=steps.computeIfAbsent(uid,key->Collections.synchronizedList(new ArrayList<>()));
            if(path.equals("/newsign/preSign")){assertEquals(uid,query.get("uid"));accountSteps.add("pre");return reply("ok");}
            if(path.equals("/pptSign/analysis")){accountSteps.add("analysis");return reply("code='+'SYNTHETIC'");}
            if(path.equals("/pptSign/analysis2")){accountSteps.add("analysis2");return reply("ok");}
            throw new AssertionError("Preparation must never send a final signup request");
        }
    }
    private static ChaoxingClient.Response reply(String body){return new ChaoxingClient.Response(200,body,null);}
    private static Map<String,String> query(String address){
        Map<String,String> result=new LinkedHashMap<>();String query=URI.create(address).getRawQuery();
        if(query!=null)for(String item:query.split("&")){String[] parts=item.split("=",2);result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}return result;
    }
    @Test public void totalCourseQueryConcurrencyIsBoundedAtSixAndCookiesRemainIsolated()throws Exception{
        Script script=new Script();script.release=new CountDownLatch(1);List<Account> accounts=accounts();ChaoxingClient client=new ChaoxingClient(script);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<List<PreparationRunner.Result>> work=caller.submit(()->new PreparationRunner(client::prepare).run(accounts,()->false,IGNORE));
            assertTrue(script.sixQueries.await(3,TimeUnit.SECONDS));assertEquals(6,script.peak.get());assertEquals(6,script.calls.get());script.release.countDown();
            List<PreparationRunner.Result> results=work.get(5,TimeUnit.SECONDS);assertEquals(4,results.size());assertEquals(24,script.calls.get());assertEquals(6,script.peak.get());
            for(Account account:accounts)for(int i=1;i<=6;i++)assertEquals(account.uid,account.cookies.get("query_"+i));
            assertEquals(0,script.inFlight.get());
        }finally{script.release.countDown();caller.shutdownNow();}
    }
    @Test public void eachAccountsPresignStepsKeepTheirOwnOrder()throws Exception{
        Script script=new Script();script.active=true;ChaoxingClient client=new ChaoxingClient(script);
        List<PreparationRunner.Result> results=new PreparationRunner(client::prepare).run(accounts(),()->false,IGNORE);
        for(PreparationRunner.Result result:results){assertNull(result.error);assertEquals(1,result.activities.size());assertEquals(Arrays.asList("pre","analysis","analysis2"),script.steps.get(result.account.uid));}
    }
    @Test public void benchmarkAgainstPreviousSequentialAccountPreparation()throws Exception{
        Script old=new Script();old.courses=12;old.delayMs=60;ChaoxingClient previous=new ChaoxingClient(old);long start=System.nanoTime();
        for(Account account:accounts())previous.prepare(account);long sequential=(System.nanoTime()-start)/1_000_000;
        Script fast=new Script();fast.courses=12;fast.delayMs=60;ChaoxingClient current=new ChaoxingClient(fast);start=System.nanoTime();
        List<PreparationRunner.Result> results=new PreparationRunner(current::prepare).run(accounts(),()->false,IGNORE);long parallel=(System.nanoTime()-start)/1_000_000;
        for(PreparationRunner.Result result:results)assertNull(result.error);assertEquals(48,fast.calls.get());assertTrue(fast.peak.get()<=6);
        System.out.println("Simulated query benchmark (4 accounts x 12 courses x 60ms, no network): previous="+sequential+"ms current="+parallel+"ms");
    }
}
