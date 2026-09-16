package com.macro.mall.search.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.search.config.SearchSyncProperties;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import com.macro.mall.search.service.EsProductService;
import com.macro.mall.search.util.SearchPageUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 搜索商品管理Controller
 */
@Controller
@Tag(name = "EsProductController",description = "搜索商品管理")
@RequestMapping("/esProduct")
public class EsProductController {
    private static final Logger LOGGER = LoggerFactory.getLogger(EsProductController.class);

    @Autowired
    private EsProductService esProductService;
    @Autowired
    private SearchSyncProperties searchSyncProperties;

    @Operation(summary = "导入所有数据库中商品到ES")
    @RequestMapping(value = "/importAll", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> importAllList() {
        int count = esProductService.importAll();
        return CommonResult.success(count);
    }

    @Operation(summary = "根据id删除商品")
    @RequestMapping(value = "/delete/{id}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<Object> delete(@PathVariable Long id) {
        esProductService.delete(id);
        return CommonResult.success(null);
    }

    @Operation(summary = "根据id批量删除商品")
    @RequestMapping(value = "/delete/batch", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Object> delete(@RequestParam("ids") List<Long> ids) {
        esProductService.delete(ids);
        return CommonResult.success(null);
    }

    @Operation(summary = "根据id创建商品")
    @RequestMapping(value = "/create/{id}", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<EsProduct> create(@PathVariable Long id) {
        EsProduct esProduct = esProductService.create(id);
        if (esProduct != null) {
            return CommonResult.success(esProduct);
        } else {
            return CommonResult.failed();
        }
    }

    @Operation(summary = "根据商品id同步商品到ES，幂等操作")
    @RequestMapping(value = "/sync/{id}", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Object> sync(@PathVariable Long id) {
        esProductService.sync(id);
        return CommonResult.success(null);
    }

    @Operation(summary = "根据商品id批量同步商品到ES，幂等操作")
    @RequestMapping(value = "/sync/batch", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<CommonResult<Object>> sync(@RequestBody(required = false) List<Long> ids) {
        List<Long> distinctIds = ids == null ? Collections.emptyList()
                : ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            LOGGER.warn("批量同步商品到ES的参数为空，已拒绝请求");
            return ResponseEntity.badRequest().body(CommonResult.validateFailed("商品id列表不能为空"));
        }
        int maxSyncBatchSize = searchSyncProperties.getEffectiveMaxSyncBatchSize();
        if (distinctIds.size() > maxSyncBatchSize) {
            LOGGER.warn("批量同步商品到ES的数量超过上限，请求数量:{}，上限:{}", distinctIds.size(), maxSyncBatchSize);
            return ResponseEntity.badRequest()
                    .body(CommonResult.validateFailed("单次同步商品id数量不能超过" + maxSyncBatchSize));
        }
        esProductService.sync(distinctIds);
        return ResponseEntity.ok(CommonResult.success(null));
    }

    @Operation(summary = "简单搜索")
    @RequestMapping(value = "/search/simple", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<EsProduct>> search(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                      @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        Page<EsProduct> esProductPage = esProductService.search(keyword, pageNum, pageSize);
        return CommonResult.success(SearchPageUtils.restPageOneBased(esProductPage));
    }

    @Operation(summary = "综合搜索、筛选、排序")
    @Parameter(name = "sort", description = "排序字段:0->按相关度；1->按新品；2->按销量；3->价格从低到高；4->价格从高到低", in = ParameterIn.QUERY, schema = @Schema(type = "integer",defaultValue = "0",allowableValues = {"0","1","2","3","4"}))
    @RequestMapping(value = "/search", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<EsProduct>> search(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Long brandId,
                                                      @RequestParam(required = false) Long productCategoryId,
                                                      @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                      @RequestParam(required = false, defaultValue = "5") Integer pageSize,
                                                      @RequestParam(required = false, defaultValue = "0") Integer sort) {
        Page<EsProduct> esProductPage = esProductService.search(keyword, brandId, productCategoryId, pageNum, pageSize, sort);
        return CommonResult.success(SearchPageUtils.restPageOneBased(esProductPage));
    }

    @Operation(summary = "根据商品id推荐商品")
    @RequestMapping(value = "/recommend/{id}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<EsProduct>> recommend(@PathVariable Long id,
                                                         @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                         @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        Page<EsProduct> esProductPage = esProductService.recommend(id, pageNum, pageSize);
        return CommonResult.success(SearchPageUtils.restPageOneBased(esProductPage));
    }

    @Operation(summary = "获取搜索的相关品牌、分类及筛选属性")
    @RequestMapping(value = "/search/relate", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<EsProductRelatedInfo> searchRelatedInfo(@RequestParam(required = false) String keyword) {
        EsProductRelatedInfo productRelatedInfo = esProductService.searchRelatedInfo(keyword);
        return CommonResult.success(productRelatedInfo);
    }
}
