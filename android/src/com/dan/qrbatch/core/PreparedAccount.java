package com.dan.qrbatch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PreparedAccount {
    public final Account account;
    public final List<ActivityContext> activities;

    public PreparedAccount(Account account, List<ActivityContext> activities) {
        if (account == null) throw new IllegalArgumentException("账号不能为空。");
        this.account = account;
        List<ActivityContext> copy = new ArrayList<ActivityContext>();
        if (activities != null) {
            for (ActivityContext activity : activities) if (activity != null) copy.add(activity);
        }
        this.activities = Collections.unmodifiableList(copy);
    }

    public ActivityContext findActivity(String id) {
        if (id == null) return null;
        for (ActivityContext activity : activities) {
            if (id.equals(activity.activeId)) return activity;
        }
        return null;
    }
}
