package com.liulep.redisLock.untils;

/**
 * 根据线程id获取线程对象的工具类
 */
public class ThreadUtils {

    //根据线程id获取线程句柄
    public static Thread getThreadByThreadId(long threadId){
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        while(threadGroup != null){
            Thread[] threads = new Thread[(int)(threadGroup.activeCount()* 1.2)];
            int count = threadGroup.enumerate(threads, true);
            for(int i =0; i< count; i++){
                if(threadId == threads[i].getId()){
                    return threads[i];
                }
            }
        }
        return null;
    }
}
