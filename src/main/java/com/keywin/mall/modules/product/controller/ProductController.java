package com.keywin.mall.modules.product.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.service.ProductService;
import com.keywin.mall.modules.product.vo.ProductVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/product")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    /**
     * 创建商品
     * @param productCreateDTO
     * @return
     */
    @PostMapping
    public Result<ProductVO> create(@Valid @RequestBody ProductCreateDTO productCreateDTO) {
        return Result.success(productService.create(productCreateDTO));
    }

    /**
     * 获取商品详情
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result<ProductVO> get(@PathVariable Long id) {
        return Result.success(productService.getById(id));
    }
}
