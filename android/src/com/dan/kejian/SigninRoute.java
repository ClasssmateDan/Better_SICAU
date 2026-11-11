package com.dan.kejian;

import java.util.function.Consumer;

interface SigninRoute {
    void apply(boolean ipv4,Runnable done,Consumer<String> failure);
    String describe();
    void close();
}
