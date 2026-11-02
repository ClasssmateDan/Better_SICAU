package com.dan.qrbatch.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Preparation only. Results retain account order; no final signup request is made here. */
public final class PreparationRunner {
    public static final int PARALLEL_ACCOUNTS = 2;
    public interface Preparer {
        List<ActivityContext> prepare(Account account, Consumer<String> progress, BooleanSupplier cancelled) throws Exception;
    }
    public interface Listener {
        void progress(int index, int total, Account account, String detail);
        void finished(int finished, int total, Result result);
    }
    public static final class Result {
        public final int index;
        public final Account account;
        public final List<ActivityContext> activities;
        public final Exception error;
        Result(int index, Account account, List<ActivityContext> activities, Exception error) {
            this.index=index; this.account=account; this.error=error;
            this.activities=Collections.unmodifiableList(new ArrayList<>(activities));
        }
    }
    private final Preparer preparer;
    public PreparationRunner(Preparer preparer) {
        if(preparer==null)throw new IllegalArgumentException("活动准备客户端不能为空。");
        this.preparer=preparer;
    }
    public List<Result> run(List<Account> accounts, BooleanSupplier cancelled, Listener listener) throws Exception {
        if(accounts==null||cancelled==null||listener==null)throw new IllegalArgumentException("账号、取消状态和进度回调不能为空。");
        List<Account> queue=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(Account account:accounts) {
            if(account==null||account.uid.isEmpty()||!ids.add(account.uid))throw new IllegalArgumentException("准备队列包含无效或重复账号。");
            if(account.selected)queue.add(account);
        }
        if(queue.isEmpty())return Collections.emptyList();
        List<Result> ordered=new ArrayList<>(Collections.nCopies(queue.size(),null));
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(PARALLEL_ACCOUNTS,queue.size()));
        CompletionService<Result> completed=new ExecutorCompletionService<>(pool);
        int submitted=0,finished=0;
        try {
            while(submitted<Math.min(PARALLEL_ACCOUNTS,queue.size())&&!cancelled.getAsBoolean())submit(completed,queue,submitted++,cancelled,listener);
            while(finished<submitted) {
                // Drain in-flight preparation even after cancellation, before the caller saves cookies.
                Future<Result> next=completed.poll(100,TimeUnit.MILLISECONDS);if(next==null)continue;
                Result result=next.get();ordered.set(result.index,result);finished++;
                listener.finished(finished,queue.size(),result);
                if(submitted<queue.size()&&!cancelled.getAsBoolean())submit(completed,queue,submitted++,cancelled,listener);
            }
            for(int index=0;index<queue.size();index++)if(ordered.get(index)==null) {
                Result stopped=new Result(index,queue.get(index),Collections.emptyList(),new IOException("准备已停止，未查询此账号。"));
                ordered.set(index,stopped);listener.finished(++finished,queue.size(),stopped);
            }
            return Collections.unmodifiableList(ordered);
        } finally {
            pool.shutdownNow();boolean interrupted=false;
            for(;;) {
                try { if(pool.awaitTermination(100,TimeUnit.MILLISECONDS))break; }
                catch(InterruptedException error) { interrupted=true; }
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private void submit(CompletionService<Result> completed,List<Account> queue,int index,BooleanSupplier cancelled,Listener listener) {
        Account account=queue.get(index);
        completed.submit(()->{
            try {
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("准备已停止。");
                List<ActivityContext> activities=preparer.prepare(account,detail->listener.progress(index,queue.size(),account,detail),cancelled);
                if(activities==null)throw new IOException("活动准备未返回有效结果。");
                return new Result(index,account,activities,null);
            } catch(Exception error) { return new Result(index,account,Collections.emptyList(),error); }
        });
    }
}
