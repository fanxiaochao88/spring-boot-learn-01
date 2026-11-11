package com.keywin.mall.modules.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * version 是内部使用乐观锁字段
 * deleted 是内部使用的逻辑删除字段, 也不需要返回前端
 */

@Data
public class ProductVO {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private String statusText;
    private String contactPhone;
    private String description;
    private Boolean stockWarning;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
