package com.keywin.mall.modules.product.controller;

import com.keywin.mall.common.api.PageResult;
import com.keywin.mall.common.api.Result;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.dto.ProductQueryDTO;
import com.keywin.mall.modules.product.dto.ProductUpdateDTO;
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

    /**
     * 分页查询
     */
    @GetMapping
    public Result<PageResult<ProductVO>> page(@Valid ProductQueryDTO productQueryDTO) {
        return Result.success(productService.page(productQueryDTO));
    }

    /**
     * 根据id修改
     */
    @PutMapping("/{id}")
    public Result<ProductVO> update(@PathVariable Long id,
                                    @Valid @RequestBody ProductUpdateDTO productUpdateDTO) {
        return Result.success(productService.update(id, productUpdateDTO));
    }

    /**
     * 根据id删除
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }
}
