package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {

        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //todo 1生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //当前时间为多少秒
        long nowsecond = now.toEpochSecond(ZoneOffset.UTC);
        long timetrep = nowsecond - COUNT_BITS;

        //todo 2 利用redis 生成序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        Long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + data);


        return timetrep<<COUNT_BITS | count;
    }
}
