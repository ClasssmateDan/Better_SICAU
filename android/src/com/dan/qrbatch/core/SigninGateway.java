package com.dan.qrbatch.core;

import java.util.List;

public interface SigninGateway {
    Account login(String phone, String password) throws Exception;
    List<ActivityContext> prepare(Account account) throws Exception;
    SignResult submit(Account account, ActivityContext activity, QrPayload qr, LocationValue location) throws Exception;
}
