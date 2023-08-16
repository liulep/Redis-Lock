package com.liulep.redisLock.service;

import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁接口
 */
public interface RedisDistributeLock {

    /**
     * 加锁
     */
    public boolean tryLock(String key, long timeout, TimeUnit unit);

    /**
     * 解锁
     */
    public void releaseLock(String key);
}
