package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
@Slf4j
@Service
public class IVoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService iSeckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result killshop(Long voucherId) {
        //查找对应秒杀券
        SeckillVoucher killVoucher = iSeckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (killVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //秒杀未开始
            return Result.fail("秒杀未开始");
        }
        //开始  判断库存是否够，如果够 库存减一
        if(killVoucher.getStock()<=0){
            return Result.fail("已抢完");
        }
        Long aLong = UserHolder.getUser().getId();
        //todo 添加分布式锁
        //通过构建方法传值
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order"+aLong, stringRedisTemplate);

        try {

            //获取锁
            boolean lock = simpleRedisLock.tryLock(1200L);
            if (!lock){
                return Result.fail("每个用户只能买一次");
            }

            IVoucherOrderService proxy =(IVoucherOrderService)AopContext.currentProxy();
            return  proxy.creatVoucherOrder(voucherId, killVoucher);
        } catch (IllegalStateException e) {
            throw new RuntimeException();
        } finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public Result creatVoucherOrder(Long voucherId, SeckillVoucher killVoucher) {
        // todo 用户一对一秒杀券
        Long aLong = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", aLong).eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("用户已经抢到过一次");
        }


        //库存建议
        boolean update = iSeckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (update==false){
            return Result.fail("no vouter");
        }

        //生成订单id
        long killvoucher = redisIdWorker.nextId("killvoucher");

        //添加订单
        VoucherOrder voucherOrder1 = new VoucherOrder();
        voucherOrder1.setId(killvoucher);
        voucherOrder1.setVoucherId(killVoucher.getVoucherId());
        voucherOrder1.setUserId(aLong);
        voucherOrder1.setCreateTime(LocalDateTime.now());

        save(voucherOrder1);

        return Result.ok(killvoucher);
    }
}
