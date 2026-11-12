package com.keywin.mall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    @Update("UPDATE product SET stock = stock - #{count}, update_time = NOW() " +
            "WHERE id = #{id} AND stock >= #{count} AND deleted = 0")
    int decreaseStock(@Param("id") Long id, @Param("count") int count);
}
