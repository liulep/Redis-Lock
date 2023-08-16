## 基于Redis实现分布式锁

> 在高并发电商系统中，如果对提交订单并减扣库存的逻辑设计不当，就可能造成意想不到的后果，甚至造成超卖后果。

### 超卖问题概述

超卖问题分为两种情况：

1. 在电商系统中真实卖出的商品数量超过库存量
2. 在校验库存时，导致多个线程拿到同样商品的相同库存进行了多次减扣

### 代码复现

使用SpringBoot + Redis的方式简单实现；

```java
/**
 * 测试订单的接口
 */

@RestController
@RequestMapping("/order/v1")
public class OrderV1Controller {

    private final Logger logger = LoggerFactory.getLogger(OrderV1Controller.class);
    /**
     * 商品Id为1001
     */
    private static final String PRODUCT_ID = "1001";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/submitOrder")
    public String submitOrder(){
        String stockStr = stringRedisTemplate.opsForValue().get(PRODUCT_ID);
        if(stockStr == null || "".equals(stockStr.trim())){
            logger.info("库存不足，减扣库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
        //将库存转化为int类型，进行减1操作
        int stock = Integer.parseInt(stockStr);
        if(stock > 0){
            stock -- ;
            stringRedisTemplate.opsForValue().set(PRODUCT_ID, String.valueOf(stock));
            logger.info("库存减扣成功， 当前库存为:{}", stock);
        }else{
            logger.info("库存不足， 缄口库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
        return "success";
    }
}
```

进行压测发现，在高并发的情况下，多条线程对同一商品的相同库存进行了多次的减扣，发生了超卖问题的第二种情况。

### 分布式锁的基本要求

- 支持互斥性

  支持多个线程操作同一共享变量的互斥性。

- 支持阻塞与非阻塞

  当某个线程获取分布式锁失败时，分布式锁能够支持当前线程时候阻塞还是非阻塞的特性

- 支持可重入性

  能够支持同一个线程同时获取多次获取同一个分布式锁的特性

- 支持锁超时

  为了避免出现获取到分布式锁的线程意外退出，进而无法正常释放锁，导致其他线程无法正常获取到锁的情况，分布式锁需要支持超时机制，若加锁时长超过一定的时间，锁就会自动释放。

- 支持高可用

  在分布式环境下，大部分是高并发，大流量的场景，多个线程同时访问分布式锁服务，要求分布式锁能够支持高可用性。

### Redis实现分布式锁的命令

> SETNX KEY VALUE

SETNX命令的含义是“SET if not Exists”,当Redis中不存在当前key时，也会将key的值设置为value并存储到Redis中，如果Redis中已经存在当前key，则不做任何操作。

如果Redis中不存在当前key，则设置key-value成功后返回1

如果Redis中存在当前key，则在设置key-value失败时返回0

### 改造上述代码

通过redis中的setnx命令实现；

#### 思路

假设此时线程A和线程B同时访问临界值（锁）代码，线程A先执行了SETNX命令向Redis中设置了锁状态，并返回结果1，继续执行。当线程B再次执行SETNX命令时，返回结果为0，线程B不能继续执行。只有线程A执行DEL命令将设置的锁状态删除后，线程B才会成功执行SETNX命令设置加锁状态并继续执行。

#### 引入分布式锁

```java
@GetMapping("/submitOrder/v1")
public String submitOrderV1(){
    //获取当前线程Id
    long threadId = Thread.currentThread().getId();
    //通过stringRedisTemplate调用redis命令
    //key为商品ID，value为线程ID
    Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_ID, String.valueOf(threadId));
    //如果获取锁失败，直接返回下单失败的结果
    if(!isLocked){
        return "failure";
    }
    String stockStr = stringRedisTemplate.opsForValue().get(PRODUCT_ID);
    if(stockStr == null || "".equals(stockStr.trim())){
        logger.info("库存不足，减扣库存失败");
        throw new RuntimeException("库存不足，减扣库存失败");
    }
    //将库存转化为int类型，进行减1操作
    int stock = Integer.parseInt(stockStr);
    if(stock > 0){
        stock -- ;
        stringRedisTemplate.opsForValue().set(PRODUCT_ID, String.valueOf(stock));
        logger.info("库存减扣成功， 当前库存为:{}", stock);
    }else{
        logger.info("库存不足， 缄口库存失败");
        throw new RuntimeException("库存不足，减扣库存失败");
    }
    //释放锁
    stringRedisTemplate.delete(PRODUCT_ID);
    return "success";
}
```

> 问题：如果线程A还未执行到释放锁的代码，此时程序抛出了异常，那么线程A获取到的锁一直存在redis中，导致其他线程都是获取不到锁，从而导致后续的所有下单操作都会失败，这就是分布式场景下死锁的问题。
>
> 为此需要引入try-finally代码块

#### 引入try-finally代码块

```java
@GetMapping("/submitOrder/v2")
public String submitOrderV2(){
    //获取当前线程Id
    long threadId = Thread.currentThread().getId();
    //通过stringRedisTemplate调用redis命令
    //key为商品ID，value为线程ID
    Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_ID, String.valueOf(threadId));
    //如果获取锁失败，直接返回下单失败的结果
    if(!isLocked){
        return "failure";
    }
    try {
        String stockStr = stringRedisTemplate.opsForValue().get(PRODUCT_ID);
        if(stockStr == null || "".equals(stockStr.trim())){
            logger.info("库存不足，减扣库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
        //将库存转化为int类型，进行减1操作
        int stock = Integer.parseInt(stockStr);
        if(stock > 0){
            stock -- ;
            stringRedisTemplate.opsForValue().set(PRODUCT_ID, String.valueOf(stock));
            logger.info("库存减扣成功， 当前库存为:{}", stock);
        }else{
            logger.info("库存不足， 缄口库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
    }finally {
        //释放锁
        stringRedisTemplate.delete(PRODUCT_ID);
        return "success";
    }
}
```

> 可能出现的问题，如果服务器宕机了呢，如何保证锁被释放呢？
>
> 需要引入超时机制

#### 引入Redis超时机制

```java
@GetMapping("/submitOrder/v3")
public String submitOrderV3(){
    //获取当前线程Id
    long threadId = Thread.currentThread().getId();
    //通过stringRedisTemplate调用redis命令
    //key为商品ID，value为线程ID
    Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_ID, String.valueOf(threadId));
    //如果获取锁失败，直接返回下单失败的结果
    if(!isLocked){
        return "failure";
    }
    try {
        //引入Redis超时机制
        stringRedisTemplate.expire(PRODUCT_ID, 30, TimeUnit.SECONDS);
        String stockStr = stringRedisTemplate.opsForValue().get(PRODUCT_ID);
        if(stockStr == null || "".equals(stockStr.trim())){
            logger.info("库存不足，减扣库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
        //将库存转化为int类型，进行减1操作
        int stock = Integer.parseInt(stockStr);
        if(stock > 0){
            stock -- ;
            stringRedisTemplate.opsForValue().set(PRODUCT_ID, String.valueOf(stock));
            logger.info("库存减扣成功， 当前库存为:{}", stock);
        }else{
            logger.info("库存不足， 缄口库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
    }finally {
        //释放锁
        stringRedisTemplate.delete(PRODUCT_ID);
        return "success";
    }
}
```

> 哈哈哈哈还是容易出现服务器宕机，导致死锁，为什么呢？因为如果服务器是刚好在设置超时时发生宕机的，锁标志未被设置超时，从而导致死锁的发生。
>
> 没事，redis还有一种设置超时的方法，可以在向Redis中保存数据的同时，指定数据的超时时间。

#### 加锁操作原子化

```java
@GetMapping("/submitOrder/v4")
public String submitOrderV4(){
    //获取当前线程Id
    long threadId = Thread.currentThread().getId();
    //通过stringRedisTemplate调用redis命令
    //key为商品ID，value为线程ID  引入Redis超时机制
    Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_ID, String.valueOf(threadId),
            30, TimeUnit.SECONDS);
    //如果获取锁失败，直接返回下单失败的结果
    if(!isLocked){
        return "failure";
    }
    try {
        //引入Redis超时机制
        String stockStr = stringRedisTemplate.opsForValue().get(PRODUCT_ID);
        if(stockStr == null || "".equals(stockStr.trim())){
            logger.info("库存不足，减扣库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
        //将库存转化为int类型，进行减1操作
        int stock = Integer.parseInt(stockStr);
        if(stock > 0){
            stock -- ;
            stringRedisTemplate.opsForValue().set(PRODUCT_ID, String.valueOf(stock));
            logger.info("库存减扣成功， 当前库存为:{}", stock);
        }else{
            logger.info("库存不足， 缄口库存失败");
            throw new RuntimeException("库存不足，减扣库存失败");
        }
    }finally {
        //释放锁
        stringRedisTemplate.delete(PRODUCT_ID);
        return "success";
    }
}
```

> 现在即使服务器宕机，Redis中的数据过期以后也会被自动删除，后续的线程在进入提交订单的方法后，会成功设置锁标志位，并执行下单流程，
>
> 但是在实际的开发中，实现分布式锁往往会将一些功能抽取成公共的类供系统其他类调用。

#### 抽取公共组件

接口：

```java
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
```

实现类：

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {
    
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    
    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, 
                this.getCurrentThreadId(),
                timeout,
                unit);
    }

    @Override
    public void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
    
    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

> 如果其他开发人员，并没有调用tryLock(),而是直接调用了releaseLock()，就会导致后续执行业务的线程会将之前线程添加的分布式锁删除，这里是有问题的。

#### 实现加锁和解锁归一体化

> 什么是加锁和解锁一体化呢？就是线程A加的锁，必须由线程A进行解锁，不能被线程B解锁。加锁和解锁都是由同一个线程完成。

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        String currentThreadId = this.getCurrentThreadId();
        //将加锁的线程id保存到ThreadLocal中
        threadLocal.set(currentThreadId);
        return stringRedisTemplate.opsForValue().setIfAbsent(key,
                currentThreadId,
                timeout,
                unit);
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        if(threadLocal.get().equals(stringRedisTemplate.opsForValue().get(key))){
            stringRedisTemplate.delete(key);
            //防止内存泄漏
            threadLocal.remove();
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

> 到这里时，已经很好的实现了加锁和解锁一体化。
>
> 但是如果在提交订单的接口方法中调用了服务A，服务A调用了服务B，而服务B中也有一个对同一个商品进行加锁解锁操作，在服务B成功设置好锁标记位后，回到提交订单的方法中，也不能成功设置锁标记位，也就是说当前实现的分布式锁不支持可重入性。

#### 可重入性分析

可重入性指的是同一个线程能够多次获取同一把锁，并且按照顺序进行解锁操作。

那么，在实现分布式锁的时候，如何支持锁的可重入性呢？

> 思路：如果当前线程没有绑定线程Id，则生成线程id绑定到当前线程，并且在Redis中设置锁标志位，如果当前线程之前就设置了锁标志位，也就是说明已经获取到了锁，直接返回true。

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if(threadLocal.get() == null){
            String currentThreadId = this.getCurrentThreadId();
            //将加锁的线程id保存到ThreadLocal中
            threadLocal.set(currentThreadId);
            isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                    currentThreadId,
                    timeout,
                    unit);
        }else{
            isLocked = true;
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        if(threadLocal.get().equals(stringRedisTemplate.opsForValue().get(key))){
            stringRedisTemplate.delete(key);
            //防止内存泄漏
            threadLocal.remove();
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

> 以上代码好像看起来已经没有问题了，但是仔细分析一下，如果方法A加了锁，调用方法B，方法B中也有加锁解锁操作，在执行完方法B，锁被释放，回到方法A后，发现锁已经被释放掉了，会导致方法A还未执行完就被其他线程所抢占。

#### 解决可重入性问题

通过ReentrantLock锁实现可重入锁的思路，在加锁和解锁中加入计数器。

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private ThreadLocal<Integer> threadCount = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if(threadLocal.get() == null){
            String currentThreadId = this.getCurrentThreadId();
            //将加锁的线程id保存到ThreadLocal中
            threadLocal.set(currentThreadId);
            isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                    currentThreadId,
                    timeout,
                    unit);
        }else{
            isLocked = true;
        }
        //加锁成功后，计数器的值加一
        if(isLocked){
            Integer count = threadCount.get() == null?0:threadCount.get();
            threadCount.set(count++);
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        if(threadLocal.get().equals(stringRedisTemplate.opsForValue().get(key))){
            Integer count = threadCount.get();
            if(count == null || --count <= 0){
                stringRedisTemplate.delete(key);
                //防止内存泄漏
                threadLocal.remove();
                //清除计数器
                threadCount.remove();
            }
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

> 现在，基本解决了可重入性的问题

#### 实现锁的阻塞性

> 在提交订单的方法中，当获取Reids分布式锁失败时，直接返回了failure来表示当前请求下单的操作失败了，在高并发环境下，一旦某个请求获得了分布式锁，那么在这个请求释放锁之前，其他请求调用下单方法时，都会返回下单失败的信息，在真实的场景中，这是非常不友好的，导致有那么多库存，最终因为持有锁时间过长，其他的下单操作全部作废，所以在实现上，可以将后续的请求进行阻塞，直到当前请求释放锁后，再唤醒阻塞的请求获得分布式锁来执行方法。
>
> 那么如何实现阻塞呢？一个简单的方式就是执行自旋操作。

```java
**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private ThreadLocal<Integer> threadCount = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if(threadLocal.get() == null){
            String currentThreadId = this.getCurrentThreadId();
            //将加锁的线程id保存到ThreadLocal中
            threadLocal.set(currentThreadId);
            isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                    currentThreadId,
                    timeout,
                    unit);
            //如果获取锁失败，执行自旋操作，直到获取锁成功
            if(!isLocked){
                for (;;){
                    isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                            currentThreadId,
                            timeout,
                            unit);
                    if(isLocked){
                        break;
                    }
                }
            }
        }else{
            isLocked = true;
        }
        //加锁成功后，计数器的值加一
        if(isLocked){
            Integer count = threadCount.get() == null?0:threadCount.get();
            threadCount.set(count++);
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        if(threadLocal.get().equals(stringRedisTemplate.opsForValue().get(key))){
            Integer count = threadCount.get();
            if(count == null || --count <= 0){
                stringRedisTemplate.delete(key);
                //防止内存泄漏
                threadLocal.remove();
                //清除计数器
                threadCount.remove();
            }
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

此时，实现的分布式锁的代码支持了锁的阻塞性

#### 解决锁失效问题

> 一旦程序执行业务的时间过长，超过了redis设置的锁过期时间，就会导致分布式锁的失效，后面的请求获取到分布式锁继续执行，无法保证业务的原子性。
>
> 如何解决这个问题呢？
>
> 必须保证在业务代码执行完后才释放分布式锁，通过一个定时策略来进行延长锁的过期时间。

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private ThreadLocal<Integer> threadCount = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if(threadLocal.get() == null){
            String currentThreadId = this.getCurrentThreadId();
            //将加锁的线程id保存到ThreadLocal中
            threadLocal.set(currentThreadId);
            isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                    currentThreadId,
                    timeout,
                    unit);
            //如果获取锁失败，执行自旋操作，直到获取锁成功
            if(!isLocked){
                for (;;){
                    isLocked = stringRedisTemplate.opsForValue().setIfAbsent(key,
                            currentThreadId,
                            timeout,
                            unit);
                    if(isLocked){
                        break;
                    }
                }
            }
            
            //防止锁过期
            while(true){
                Integer count = threadCount.get();
                //如果当前锁已被释放则退出循环
                if(count == null || count <= 0){
                    break;
                }
                stringRedisTemplate.expire(key, 30, TimeUnit.SECONDS);
                try {
                    //每10秒执行一次
                    Thread.sleep(10000);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
            }
        }else{
            isLocked = true;
        }
        //加锁成功后，计数器的值加一
        if(isLocked){
            Integer count = threadCount.get() == null?0:threadCount.get();
            threadCount.set(count++);
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        if(threadLocal.get().equals(stringRedisTemplate.opsForValue().get(key))){
            Integer count = threadCount.get();
            if(count == null || --count <= 0){
                stringRedisTemplate.delete(key);
                //防止内存泄漏
                threadLocal.remove();
                //清除计数器
                threadCount.remove();
            }
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

我们在代码中加入了一个while(true)死循环来延长锁的过期时间。但是也可以发现如果在tryLock()方法中执行这段代码，将会使当前线程一直处于死循环中无法返回结果，所以不能将当前线程进行阻塞，需要异步执行定时任务来更新锁的超时时间。

```java
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
                String.valueOf(Thread.currentThread().getId()));
        while(true){
            stringRedisTemplate.expire(key, 30, TimeUnit.SECONDS);
            try {
                Thread.sleep(10000);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

实现一个ThreadUtils工具类

```java
/**
 * 根据线程id获取线程对象的工具类
 */
public class ThreadUtils {

    //根据线程id获取线程句柄
    public static Thread getThreadByThreadId(long threadId){
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        while(threadGroup != null){
            Thread[] threads = new Thread[(int)(threadGroup.activeCount()* 1.0)];
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
```

实现

```java
/**
 * Redis分布式锁接口实现类
 */
@Service
public class RedisDistributeLockImpl implements RedisDistributeLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private ThreadLocal<Integer> threadCount = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Boolean isLocked = false;
        if(threadLocal.get() == null){
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
            while(true){
                Integer count = threadCount.get();
                //如果当前锁已被释放则退出循环
                if(count == null || count <= 0){
                    break;
                }
                stringRedisTemplate.expire(key, 30, TimeUnit.SECONDS);
                try {
                    //每10秒执行一次
                    Thread.sleep(10000);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
            }
        }else{
            isLocked = true;
        }
        //加锁成功后，计数器的值加一
        if(isLocked){
            Integer count = threadCount.get() == null?0:threadCount.get();
            threadCount.set(count++);
        }
        return isLocked;
    }

    @Override
    public void releaseLock(String key) {
        //如果加锁的线程与当前线程一致时，执行删除锁的操作
        String currentThreadId = stringRedisTemplate.opsForValue().get(key);
        if(threadLocal.get().equals(currentThreadId)){
            Integer count = threadCount.get();
            if(count == null || --count <= 0){
                stringRedisTemplate.delete(key);
                //防止内存泄漏
                threadLocal.remove();
                //清除计数器
                threadCount.remove();
                //通过当前线程的id从Redis中获取更新超时时间的线程Id
                String updateTimeThreadId = stringRedisTemplate.opsForValue().get(currentThreadId);
                if(updateTimeThreadId != null && !"".equals(updateTimeThreadId.trim())){
                    Thread updateTimeThread = ThreadUtils.getThreadByThreadId(Long.parseLong(updateTimeThreadId));
                    if(updateTimeThread != null){
                        //中断更新超时时间的线程
                        updateTimeThread.interrupt();
                        stringRedisTemplate.delete(currentThreadId);
                    }
                }
            }
        }
    }

    private String getCurrentThreadId(){
        return String.valueOf(Thread.currentThread().getId());
    }
}
```

#### 解锁操作原子化

在完成上锁的一系列操作后，可以发现解锁操作未进行原子化操作，会因为线程异常退出等一系列问题导致锁未被清除造成死锁问题，再或者因为key值在删除锁时提前过期。

```java
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
```



