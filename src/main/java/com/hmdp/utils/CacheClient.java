package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //todo 自动序列化添加方案
    public void set(String key, Object value, long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //todo 逻辑过期，数据库添加方案
    public void setLuoJiTine(String key, Object value, long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    // todo 缓存穿透

    // todo 2.1 预防缓存穿透
    public <R,ID> R queryChuantou(String prekey, ID id, Class<R> type, Function<ID,R> dblife,long time,TimeUnit unit){
        //todo 1 先查缓存
        String s = stringRedisTemplate.opsForValue().get(prekey + id);

        //todo 2 判断是否命中
        /**
         * isnotblank
         * 有值  true
         * null false
         * '/t' false
         */
        if(StrUtil.isNotBlank(s)){
            R r = JSONUtil.toBean(s,type);
            return r;
        }

        //todo 2.2 如果缓存未命中，判断是否为空字符串
        if(s!=null){
            return null;
        }

        //todo 3 缓存没有去数据库查

        R r = dblife.apply(id);
        if(r==null){
            //todo 2.3 输入无效店铺请求，传入空字符串
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        set(prekey+id,r,time,unit);



        return r;
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //TODO 2.2 预防缓存击穿（逻辑过期解决法）
    public <R,ID> R queryRedisJcLjGq(String preKey,ID id,Class<R> type,Function<ID,R> dbfile,long time,TimeUnit unit) {
        //todo 1 先查缓存
        String s = stringRedisTemplate.opsForValue().get(preKey + id);
        //todo 2 判断是否命中
        /**
         * isnotblank
         * 有值  true
         * null false
         * '/t' false
         */
        if(StrUtil.isBlank(s)){
            return null;
        }
        //命中返回数据
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //过期时间再当前时间后面，未过期
            return r;
        }

        //过期，尝试获取互斥锁
        boolean b = hiveLock(LOCK_SHOP_KEY + id);
        if (b){
            //成功，重新建立
            CACHE_REBUILD_EXECUTOR.submit(
                    ()->{
                        try {
                            R apply = dbfile.apply(id);
                            setLuoJiTine(preKey+id,apply,time,unit);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            killLock(LOCK_SHOP_KEY+id);
                        }

                    }
            );
        }
        //返回过期的商品信息
        return r;
    }


    //todo 1 开启互斥锁
    private boolean hiveLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 1L, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(aBoolean);
    }

    //todo 2 关闭互斥锁
    private void killLock(String key){
        stringRedisTemplate.delete(key);
    }









}
