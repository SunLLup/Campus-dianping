package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private String KEY_PRELOCK="lock:";
    private String KEY_UUID = UUID.randomUUID().toString(true);

    //加载lua脚本
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //添加标识
        String id =KEY_UUID+Thread.currentThread().getId();
        //添加锁
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PRELOCK + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(aBoolean);
    }

    @Override
    public void unlock() {
        //todo 利用lua脚本来释放锁，防止判断与执行之间的一个堵塞
        stringRedisTemplate.execute(UNLOCK_SCRIPT,Collections.singletonList(KEY_PRELOCK+name),KEY_UUID+Thread.currentThread().getId());


//        //获取标识
//        String id =KEY_UUID+Thread.currentThread().getId();
//        //获取当前锁标识
//        String s = stringRedisTemplate.opsForValue().get(KEY_PRELOCK + name);
//        //todo 判断，如果两个标识一样，标识处于同一线程
//        if (id.equals(s)){
//            stringRedisTemplate.delete(KEY_PRELOCK+name);
//        }

    }
}
