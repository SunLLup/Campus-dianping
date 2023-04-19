package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    CacheClient cacheClient;


    @Override
    public Result queryBid(Long id) {
        Shop shop = cacheClient.queryChuantou(CACHE_SHOP_KEY,id,Shop.class,this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop==null){
            return Result.fail("店铺未找到");
        }
        return Result.ok(shop);

    }
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // todo 预防缓存击穿（逻辑过期解决发）
    public Shop queryRedisJcLjGq(Long id) {
        //todo 1 先查缓存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
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
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //过期时间再当前时间后面，未过期
            return shop;
        }

        //过期，尝试获取互斥锁
        boolean b = hiveLock(LOCK_SHOP_KEY + id);
        if (b){
            //成功，重新建立
            CACHE_REBUILD_EXECUTOR.submit(
                    ()->{
                        try {
                            saveRedisShop(id,20L);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            killLock(LOCK_SHOP_KEY+id);
                        }

                    }
            );
        }
        //返回过期的商品信息
        return shop;
    }


    // todo 预防缓存击穿
    public Shop queryRedisJc(Long id) {
        //todo 1 先查缓存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //todo 2 判断是否命中
        /**
         * isnotblank
         * 有值  true
         * null false
         * '/t' false
         */
        if(StrUtil.isNotBlank(s)){
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return shop;
        }

        //todo 2.2 如果缓存未命中，判断是否为空字符串
        if(s!=null){
            return null;
        }
        Shop byId = null;
        try {
            // todo 3.1 添加反斥锁的机制
            boolean b = hiveLock(LOCK_SHOP_KEY + id);
            if(b==false){
                //已经上锁，延迟等待，回头重新
                Thread.sleep(50);
                return queryRedisJc(id);
            }

            // tode 3.2 延迟机制


            //todo 3 缓存没有去数据库查

            byId = getById(id);
            if(byId==null){
                //todo 2.3 输入无效店铺请求，传入空字符串
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(byId));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            killLock(LOCK_SHOP_KEY + id);
        }


        return byId;
    }


    // todo 2.1 预防缓存穿透
    public Shop queryChuantou(Long id){
        //todo 1 先查缓存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


        //todo 2 判断是否命中
        /**
         * isnotblank
         * 有值  true
         * null false
         * '/t' false
         */
        if(StrUtil.isNotBlank(s)){
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return shop;
        }

        //todo 2.2 如果缓存未命中，判断是否为空字符串
        if(s!=null){
            return null;
        }

        //todo 3 缓存没有去数据库查

        Shop byId = getById(id);
        if(byId==null){
            //todo 2.3 输入无效店铺请求，传入空字符串
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(byId));



        return byId;
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

    //todo 封装添加延迟 shop类
    public void saveRedisShop(long id, Long ttl) throws InterruptedException {
        Thread.sleep(200);
        //数据库查询商品
        Shop byId = getById(id);
        //创建封装类实例
        RedisData redisData = new RedisData();
        redisData.setData(byId);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(ttl));
        //添加redis 记得序列化转换
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result RedisUpdate(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("商品id不能为空");
        }
        //todo 1 先更新数据库
        updateById(shop);

        //todo 2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok("更新成功");
    }


}
