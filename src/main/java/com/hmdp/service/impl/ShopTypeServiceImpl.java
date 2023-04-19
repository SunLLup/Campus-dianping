package com.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //todo 1 先访问缓存
        List<String> range = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPELIST_KEY, 0, -1);
        ArrayList<ShopType> shopTypes = new ArrayList<>();
        for (String s : range) {
            shopTypes.add(JSONUtil.toBean(s,ShopType.class));
        }
        //todo 2 是否命中
        System.out.println(range.isEmpty());
        if (!range.isEmpty()){
            return Result.ok(shopTypes);
        }

        //todo 3 查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        System.out.println("数据库查到的列表是："+typeList);
        List<String> stypelist=new ArrayList<>();
        //todo 4 判断是否存在
        if (typeList.isEmpty()){
            return Result.fail("未找到响应值");
        }
        //todo 5 存缓存
        String sjson=null;
        for (ShopType shopType : typeList) {
            sjson = JSONUtil.toJsonStr(shopType);
            stypelist.add(sjson);
        }

        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOPTYPELIST_KEY,stypelist);

        return Result.ok(typeList);
    }
}
