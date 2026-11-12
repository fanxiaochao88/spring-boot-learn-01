package com.keywin.mall.modules.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StockDecreaseDTO {

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量不能小于1")
    @Max(value = 100, message = "数量不能大于100")
    private Integer count;

    @Size(min = 1, max = 100, message = "原因长度在1到100个字符之间")
    private String reason;
}
