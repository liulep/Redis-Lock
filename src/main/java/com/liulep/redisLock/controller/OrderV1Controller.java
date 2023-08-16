package com.liulep.redisLock.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;


/**
 * 测试订单的接口
 */

@RestController
@RequestMapping("/order")
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

    /**
     * 引入分布式锁
     * @return
     */
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

    /**
     * 引入try-finally代码块
     * @return
     */
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

    /**
     * 引入Redis超时机制
     * @return
     */
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

    /**
     * 加锁操作原子化
     * @return
     */
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
}
