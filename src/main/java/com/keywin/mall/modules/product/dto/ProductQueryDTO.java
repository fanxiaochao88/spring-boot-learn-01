package com.keywin.mall.modules.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductQueryDTO {

    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "页大小不能小于1")
    @Max(value = 100, message = "页大小不能大于100")
    private Integer pageSize = 10;

    @Size(max = 100, message = "关键字不能超过100个字符")
    private String keyword;

    @DecimalMin(value = "0.00", message = "最小价格不能小于0.00元")
    private BigDecimal minPrice;

    @DecimalMin(value = "0.00", message = "最大价格不能小于0.00元")
    private BigDecimal maxPrice;

    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    private Integer status;
}
