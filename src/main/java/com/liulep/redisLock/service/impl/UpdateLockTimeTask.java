package com.liulep.redisLock.service.impl;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 更新分布式锁的超时时间
 */
public class UpdateLockTimeTask implements Runnable{

    private String currentThreadId;
    private StringRedisTemplate stringRedisTemplate;
    private String key;

    public UpdateLockTimeTask(String currentThreadId, StringRedisTemplate stringRedisTemplate, String key){
        this.currentThreadId = currentThreadId;
        this.stringRedisTemplate = stringRedisTemplate;
        this.key = key;
    }

    @Override
    public void run() {
        //以传递的线程id为Key,当前执行更新超时时间的线程为value，保存到redis中
        stringRedisTemplate.opsForValue().set(currentThreadId,
                String.valueOf(Thread.currentThread().getId()), 30, TimeUnit.SECONDS);
        while(true){
            try {
                Thread.sleep(10000);
                stringRedisTemplate.expire(key, 30, TimeUnit.SECONDS);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }
    }
}
