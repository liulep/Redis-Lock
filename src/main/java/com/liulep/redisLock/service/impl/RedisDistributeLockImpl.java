package com.liulep.redisLock.service.impl;

import com.liulep.redisLock.service.RedisDistributeLock;
import com.liulep.redisLock.untils.ThreadUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private ThreadLocal<Integer> threadCount = new ThreadLocal<>();

    private final static String LUA_UN_LOCK = """
            if redis.call('get', KEYS[1]) == ARGV[1] then\n
                return redis.call('del', KEYS[1])\n
            else\n
                return 0\n
            end
            """;

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if (threadLocal.get() == null) {
            String currentThreadId = this.getCurrentThreadId();
            //将加锁的线程id保存到ThreadLocal中
            threadLocal.set(currentThreadId);
            isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                    currentThreadId,
                    timeout,
                    unit);
            //如果获取锁失败，执行自旋操作，直到获取锁成功
            new Thread(new UpdateLockTimeTask(currentThreadId, stringRedisTemplate, key)).start();

            //防止锁过期
            while (true) {
                Integer count = threadCount.get();
                //如果当前锁已被释放则退出循环
                if (count == null || count <= 0) {
                    break;
                }
                stringRedisTemplate.expire(key, 30, TimeUnit.SECONDS);
                try {
                    //每10秒执行一次
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } else {
            isLocked = true;
        }
        //加锁成功后，计数器的值加一
        if (isLocked) {
            Integer count = threadCount.get() == null ? 0 : threadCount.get();
            threadCount.set(count++);
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        String currentThreadId = stringRedisTemplate.opsForValue().get(key);
        if (threadLocal.get().equals(currentThreadId)) {
            Integer count = threadCount.get();
            if (count == null || --count <= 0) {
                if (releaseLockByLua(key, threadLocal.get())) {
                    //防止内存泄漏
                    threadLocal.remove();
                    //清除计数器
                    threadCount.remove();
                    //通过当前线程的id从Redis中获取更新超时时间的线程Id
                    String updateTimeThreadId = stringRedisTemplate.opsForValue().get(currentThreadId);
                    if (updateTimeThreadId != null && !"".equals(updateTimeThreadId.trim())) {
                        Thread updateTimeThread = ThreadUtils.getThreadByThreadId(Long.parseLong(updateTimeThreadId));
                        if (updateTimeThread != null && releaseLockByLua(currentThreadId, updateTimeThreadId)) {
                            //中断更新超时时间的线程
                            updateTimeThread.interrupt();
                        }
                    }
                }else{
                    releaseLock(key);
                }
            }
        }
    }

    private boolean releaseLockByLua(String key, String lockValue) {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(LUA_UN_LOCK);
        redisScript.setResultType(String.class);
        String execute = stringRedisTemplate.execute(redisScript, Collections.singletonList(key), lockValue);
        return "1".equals(execute);
    }

    private String getCurrentThreadId() {
        return String.valueOf(Thread.currentThread().getId());
    }
}
