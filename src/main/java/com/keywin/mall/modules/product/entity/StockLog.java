package com.keywin.mall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("product_stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Integer changeAmount; // 变更数量

    private Integer beforeStock;
    private Integer afterStock;

    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
