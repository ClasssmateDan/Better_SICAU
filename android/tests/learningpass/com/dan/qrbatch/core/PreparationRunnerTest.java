package com.dan.qrbatch.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fake preparation with barriers; no business URL is opened. */
public class PreparationRunnerTest {
    private static Account account(String id){Map<String,String> cookie=new LinkedHashMap<>();cookie.put("_uid",id);return new Account("19900000000",id,"测试账号","-1",cookie);}
    private static List<Account> accounts(){return Arrays.asList(account("10001"),account("10002"),account("10003"),account("10004"));}
    private static final PreparationRunner.Listener IGNORE=new PreparationRunner.Listener(){
        public void progress(int index,int total,Account account,String detail){}
        public void finished(int finished,int total,PreparationRunner.Result result){}
    };
    @Test public void atMostTwoAccountsOverlapAndResultsKeepTheOriginalQueueOrder() throws Exception {
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();CountDownLatch firstTwo=new CountDownLatch(2),release=new CountDownLatch(1);
        PreparationRunner runner=new PreparationRunner((account,progress,cancelled)->{
            calls.incrementAndGet();int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);firstTwo.countDown();
            try{assertTrue(release.await(3,TimeUnit.SECONDS));if(account.uid.equals("10001"))Thread.sleep(40);return Collections.singletonList(new ActivityContext(account.uid,"1","101","测试"));}
            finally{active.decrementAndGet();}
        });
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<List<PreparationRunner.Result>> future=caller.submit(()->runner.run(accounts(),()->false,IGNORE));
            assertTrue(firstTwo.await(3,TimeUnit.SECONDS));assertEquals(2,calls.get());assertEquals(2,peak.get());release.countDown();
            List<PreparationRunner.Result> result=future.get(3,TimeUnit.SECONDS);assertEquals(4,result.size());
            for(int i=0;i<result.size();i++){assertEquals(accounts().get(i).uid,result.get(i).account.uid);assertEquals(i,result.get(i).index);assertNull(result.get(i).error);}
            assertEquals(2,peak.get());assertEquals(0,active.get());
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void cancellationStopsNewAccountsAndDrainsCookieUpdatesBeforeReturning() throws Exception {
        AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger();AtomicBoolean stopped=new AtomicBoolean();CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        List<Account> accounts=accounts();PreparationRunner runner=new PreparationRunner((account,progress,cancelled)->{
            calls.incrementAndGet();active.incrementAndGet();started.countDown();
            try{assertTrue(release.await(3,TimeUnit.SECONDS));synchronized(account.cookies){account.cookies.put("late","finished");}return Collections.emptyList();}
            finally{active.decrementAndGet();}
        });
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<List<PreparationRunner.Result>> future=caller.submit(()->runner.run(accounts,stopped::get,IGNORE));
            assertTrue(started.await(3,TimeUnit.SECONDS));stopped.set(true);assertFalse(future.isDone());release.countDown();
            List<PreparationRunner.Result> result=future.get(3,TimeUnit.SECONDS);assertEquals(2,calls.get());assertEquals(0,active.get());
            assertEquals("finished",accounts.get(0).cookies.get("late"));assertFalse(accounts.get(2).cookies.containsKey("late"));
            assertNotNull(result.get(2).error);assertNotNull(result.get(3).error);
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void cancellationBeforeStartMakesNoRequest() throws Exception {
        AtomicInteger calls=new AtomicInteger();List<PreparationRunner.Result> result=new PreparationRunner((a,p,c)->{calls.incrementAndGet();return Collections.emptyList();}).run(accounts(),()->true,IGNORE);
        assertEquals(0,calls.get());assertEquals(4,result.size());for(PreparationRunner.Result entry:result)assertNotNull(entry.error);
    }
    @Test public void accountFailureDoesNotDiscardOtherPreparedActivities() throws Exception {
        List<PreparationRunner.Result> result=new PreparationRunner((a,p,c)->{
            if(a.uid.equals("10002"))throw new IOException("测试登录失效");
            return Collections.singletonList(new ActivityContext(a.uid,"1","101","测试活动"));
        }).run(accounts(),()->false,IGNORE);
        assertNotNull(result.get(1).error);assertEquals(1,result.get(0).activities.size());assertEquals(1,result.get(3).activities.size());
    }
    @Test public void duplicateAccountsFailBeforeAnyRequestAndUnselectedAccountsAreSkipped() throws Exception {
        AtomicInteger calls=new AtomicInteger();PreparationRunner runner=new PreparationRunner((a,p,c)->{calls.incrementAndGet();return Collections.emptyList();});
        Account first=account("10001"),other=account("10002");other.selected=false;
        assertThrows(IllegalArgumentException.class,()->runner.run(Arrays.asList(first,first),()->false,IGNORE));assertEquals(0,calls.get());
        List<PreparationRunner.Result> result=runner.run(Arrays.asList(first,other),()->false,IGNORE);assertEquals(1,result.size());assertEquals(1,calls.get());
    }
    @Test public void preparedActivityListsAreImmutableCopies() throws Exception {
        List<ActivityContext> mutable=new ArrayList<>();mutable.add(new ActivityContext("9001","1","101","测试"));
        List<PreparationRunner.Result> result=new PreparationRunner((a,p,c)->mutable).run(Collections.singletonList(account("10001")),()->false,IGNORE);
        mutable.clear();assertEquals(1,result.get(0).activities.size());assertThrows(UnsupportedOperationException.class,()->result.get(0).activities.clear());
    }
}
