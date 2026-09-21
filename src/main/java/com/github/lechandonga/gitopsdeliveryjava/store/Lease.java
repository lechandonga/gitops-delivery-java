package com.github.lechandonga.gitopsdeliveryjava.store;

/** 目标级互斥租约：owner 标识 + 过期时间戳。 */
public class Lease {
    public String target;
    public String owner;
    public long acquiredAt;
    public long expiresAt;
    public int fenceToken;

    public Lease() {
    }
}
