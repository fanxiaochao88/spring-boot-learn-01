package com.keywin.mall.modules.product.dto;

import com.keywin.mall.common.validation.Phone;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductUpdateDTO {

    @NotBlank(message = "商品名称不能为空")
    @Size(min = 2, max = 20, message = "商品名称长度在2到20个字符之间")
    private String name;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格不能小于0.01元")
    @Digits(integer = 10, fraction = 2, message = "商品价格格最多10位整数,2位小数")
    private BigDecimal price;

    @Phone
    private String contactPhone;

    @Size(max = 100, message = "商品描述长度不能超过100个字符")
    private String description;
}
