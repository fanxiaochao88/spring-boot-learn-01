package com.keywin.mall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.keywin.mall.common.api.PageResult;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;
import com.keywin.mall.config.ProductProperties;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.dto.ProductQueryDTO;
import com.keywin.mall.modules.product.dto.ProductUpdateDTO;
import com.keywin.mall.modules.product.entity.Product;
import com.keywin.mall.modules.product.mapper.ProductMapper;
import com.keywin.mall.modules.product.service.ProductService;
import com.keywin.mall.modules.product.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductProperties productProperties;

    @Override
    public ProductVO create(ProductCreateDTO productCreateDTO) {
        log.info("创建新商品: name={}", productCreateDTO.getName());

        Product product = new Product();
        BeanUtils.copyProperties(productCreateDTO, product);
        product.setStatus(1);
        product.setVersion(0);
        product.setDeleted(0);

        productMapper.insert(product);
        log.info("创建新商品: id={}", product.getId());
        return toVO(product);
    }

    @Override
    public ProductVO getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return toVO(product);
    }

    @Override
    public PageResult<ProductVO> page(ProductQueryDTO productQueryDTO) {
        if (productQueryDTO.getMinPrice() != null && productQueryDTO.getMaxPrice() != null &&
        productQueryDTO.getMinPrice().compareTo(productQueryDTO.getMaxPrice()) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "最小价格不能大于最大价格");
        }

        // 对请求分页的最大页码进行一次兜底
        long pageSize = Math.min(productQueryDTO.getPageSize(), productProperties.getPageSizeLimit());
        Page<Product> page = new Page<>(productQueryDTO.getPageNum(), pageSize);

        // 构造wrapper
        LambdaQueryWrapper<Product> productLambdaQueryWrapper = new LambdaQueryWrapper<>();
        productLambdaQueryWrapper.like(StringUtils.hasText(productQueryDTO.getKeyword()), Product::getName, productQueryDTO.getKeyword())
                .ge(productQueryDTO.getMinPrice() != null, Product::getPrice, productQueryDTO.getMinPrice())
                .le(productQueryDTO.getMaxPrice() != null, Product::getPrice, productQueryDTO.getMaxPrice())
                .eq(productQueryDTO.getStatus() != null, Product::getStatus, productQueryDTO.getStatus())
                .orderByDesc(Product::getCreateTime)
                .orderByDesc(Product::getId);

        Page<Product> productPage = productMapper.selectPage(page, productLambdaQueryWrapper);
        List<ProductVO> list = productPage.getRecords().stream().map(this::toVO).toList();

        return PageResult.of(productPage.getTotal(), productPage.getCurrent(), productPage.getSize(), list);
    }

    @Override
    public ProductVO update(Long id, ProductUpdateDTO productUpdateDTO) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        BeanUtils.copyProperties(productUpdateDTO, product);
        int affected = productMapper.updateById(product);
        if (affected == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "商品已被其他请求修改");
        }

        Product updated = productMapper.selectById(id);
        if (updated == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        log.info("更新商品: id={}", updated.getId());
        return toVO(updated);
    }

    @Override
    public void delete(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        int affected = productMapper.deleteById(id);
        if (affected == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "商品已被其他请求修改");
        }

        log.info("删除商品: id={}", id);
    }

    private ProductVO toVO(Product product) {
        ProductVO productVO = new ProductVO();
        BeanUtils.copyProperties(product, productVO);
        // 不能直接使用 product.getStatus() == 1, 因为 status 是 Integer 类型
        productVO.setStatusText(Integer.valueOf(1).equals(product.getStatus()) ? "上架" : "下架");
        productVO.setContactPhone(maskPhone(product.getContactPhone()));
        return productVO;
    }
    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }
}
