package com.keywin.mall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO) // 自增主键

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private String contactPhone; // 联系电话
    private String description; // 描述

    @Version // 乐观锁版本号
    private Integer version;

    @TableLogic // 逻辑删除
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT) // 创建时间
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE) // 更新时间
    private LocalDateTime updateTime;
}
