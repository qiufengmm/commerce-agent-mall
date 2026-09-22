package com.macro.mall.portal.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.client.MallSearchClient;
import com.macro.mall.portal.config.MallSearchClientProperties;
import com.macro.mall.portal.dao.PortalProductDao;
import com.macro.mall.portal.domain.EsProductDTO;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import com.macro.mall.portal.domain.PmsProductCategoryNode;
import com.macro.mall.portal.service.PmsPortalProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 前台订单管理Service实现类
 */
@Service
public class PmsPortalProductServiceImpl implements PmsPortalProductService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PmsPortalProductServiceImpl.class);
    /**
     * 对外分页默认值：1-based 页码
     */
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 5;
    /**
     * MySQL兜底排序，sort为0、空值或非法值时使用，避免没有ORDER BY导致分页结果不稳定。
     * 该排序只保证结果稳定，不代表ES相关度排序。
     */
    private static final String MYSQL_STABLE_ORDER_BY = "id desc";
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsProductCategoryMapper productCategoryMapper;
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private PmsProductAttributeMapper productAttributeMapper;
    @Autowired
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private PmsProductLadderMapper productLadderMapper;
    @Autowired
    private PmsProductFullReductionMapper productFullReductionMapper;
    @Autowired
    private PortalProductDao portalProductDao;
    @Autowired
    private MallSearchClient mallSearchClient;
    @Autowired
    private MallSearchClientProperties mallSearchClientProperties;

    @Override
    public CommonPage<PmsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize, Integer sort) {
        //对外统一使用 1-based 分页
        int currentPageNum = pageNum == null || pageNum < DEFAULT_PAGE_NUM ? DEFAULT_PAGE_NUM : pageNum;
        int currentPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("开始查询商品，参数: keyword={}, brandId={}, productCategoryId={}, pageNum={}, pageSize={}, sort={}",
                    keyword, brandId, productCategoryId, currentPageNum, currentPageSize, sort);
        }
        CommonPage<EsProductDTO> esProductPage;
        try {
            esProductPage = mallSearchClient.searchProduct(keyword, brandId, productCategoryId, currentPageNum, currentPageSize, sort);
        } catch (ApiException e) {
            //只捕获搜索服务客户端调用失败，其它异常不属于降级范围，继续向上暴露
            if (!mallSearchClientProperties.isMysqlFallbackEnabled()) {
                throw e;
            }
            LOGGER.warn("搜索服务调用失败，已按配置降级到MySQL查询，keyword:{}, brandId:{}, productCategoryId:{}, 原因:{}",
                    keyword, brandId, productCategoryId, e.getMessage());
            return searchByMySqlWithFallbackPage(keyword, brandId, productCategoryId, currentPageNum, currentPageSize, sort);
        }
        List<PmsProduct> productList = CollUtil.isEmpty(esProductPage.getList())
                ? Collections.emptyList()
                : esProductPage.getList().stream().map(this::convertEsProduct).collect(Collectors.toList());
        CommonPage<PmsProduct> result = new CommonPage<>();
        //搜索服务会对页码和每页数量做归一化与上限处理，回显搜索服务实际生效的值，避免响应与真实返回条数不一致
        result.setPageNum(esProductPage.getPageNum() == null ? currentPageNum : esProductPage.getPageNum());
        result.setPageSize(esProductPage.getPageSize() == null ? currentPageSize : esProductPage.getPageSize());
        result.setTotalPage(esProductPage.getTotalPage() == null ? 0 : esProductPage.getTotalPage());
        result.setTotal(esProductPage.getTotal() == null ? 0L : esProductPage.getTotal());
        result.setList(productList);
        return result;
    }

    /**
     * 将搜索服务返回的商品信息转换为前台商品模型
     */
    private PmsProduct convertEsProduct(EsProductDTO esProduct) {
        PmsProduct product = new PmsProduct();
        BeanUtils.copyProperties(esProduct, product);
        return product;
    }

    /**
     * 降级到MySQL查询时把MySQL结果包装成对外一致的1-based分页结构
     */
    private CommonPage<PmsProduct> searchByMySqlWithFallbackPage(String keyword, Long brandId, Long productCategoryId,
                                                                int pageNum, int pageSize, Integer sort) {
        List<PmsProduct> productList = searchByMySql(keyword, brandId, productCategoryId, pageNum, pageSize, sort);
        CommonPage<PmsProduct> result = new CommonPage<>();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        long total = resolveMySqlTotal(productList);
        result.setTotal(total);
        result.setTotalPage((int) Math.ceil((double) total / pageSize));
        result.setList(productList);
        LOGGER.info("MySQL降级查询完成，返回数量:{}，总数:{}", productList.size(), total);
        return result;
    }

    /**
     * PageHelper执行分页查询时返回的是com.github.pagehelper.Page，可读取真实总数；
     * 非分页场景按返回条数统计，保证降级结果仍然提供total、totalPage、pageNum、pageSize
     */
    private long resolveMySqlTotal(List<PmsProduct> productList) {
        if (productList instanceof com.github.pagehelper.Page<?> page) {
            return page.getTotal();
        }
        return productList.size();
    }

    /**
     * 原有 MySQL 综合搜索实现，作为降级方案使用，筛选条件与ES链路保持一致
     */
    @Override
    public List<PmsProduct> searchByMySql(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize, Integer sort) {
        int currentPageNum = pageNum == null || pageNum < DEFAULT_PAGE_NUM ? DEFAULT_PAGE_NUM : pageNum;
        int currentPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        PageHelper.startPage(currentPageNum, currentPageSize);
        PmsProductExample example = new PmsProductExample();
        PmsProductExample.Criteria criteria = example.createCriteria();
        criteria.andDeleteStatusEqualTo(0);
        criteria.andPublishStatusEqualTo(1);
        if (StrUtil.isNotEmpty(keyword)) {
            criteria.andNameLike("%" + keyword + "%");
        }
        if (brandId != null) {
            criteria.andBrandIdEqualTo(brandId);
        }
        if (productCategoryId != null) {
            criteria.andProductCategoryIdEqualTo(productCategoryId);
        }
        //sort为空时按0处理，避免空值参与比较时抛出异常
        int currentSort = sort == null ? 0 : sort;
        //1->按新品；2->按销量；3->价格从低到高；4->价格从高到低
        if (currentSort == 1) {
            example.setOrderByClause("id desc");
        } else if (currentSort == 2) {
            example.setOrderByClause("sale desc");
        } else if (currentSort == 3) {
            example.setOrderByClause("price asc");
        } else if (currentSort == 4) {
            example.setOrderByClause("price desc");
        } else {
            //sort为0、空值或非法值时必须有稳定的ORDER BY，避免没有排序导致分页结果重复或缺失
            example.setOrderByClause(MYSQL_STABLE_ORDER_BY);
        }
        return productMapper.selectByExample(example);
    }

    @Override
    public List<PmsProductCategoryNode> categoryTreeList() {
        PmsProductCategoryExample example = new PmsProductCategoryExample();
        List<PmsProductCategory> allList = productCategoryMapper.selectByExample(example);
        List<PmsProductCategoryNode> result = allList.stream()
                .filter(item -> item.getParentId().equals(0L))
                .map(item -> covert(item, allList))
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public PmsPortalProductDetail detail(Long id) {
        PmsPortalProductDetail result = new PmsPortalProductDetail();
        //获取商品信息
        PmsProduct product = productMapper.selectByPrimaryKey(id);
        result.setProduct(product);
        //获取品牌信息
        PmsBrand brand = brandMapper.selectByPrimaryKey(product.getBrandId());
        result.setBrand(brand);
        //获取商品属性信息
        PmsProductAttributeExample attributeExample = new PmsProductAttributeExample();
        attributeExample.createCriteria().andProductAttributeCategoryIdEqualTo(product.getProductAttributeCategoryId());
        List<PmsProductAttribute> productAttributeList = productAttributeMapper.selectByExample(attributeExample);
        result.setProductAttributeList(productAttributeList);
        //获取商品属性值信息
        if(CollUtil.isNotEmpty(productAttributeList)){
            List<Long> attributeIds = productAttributeList.stream().map(PmsProductAttribute::getId).collect(Collectors.toList());
            PmsProductAttributeValueExample attributeValueExample = new PmsProductAttributeValueExample();
            attributeValueExample.createCriteria().andProductIdEqualTo(product.getId())
                    .andProductAttributeIdIn(attributeIds);
            List<PmsProductAttributeValue> productAttributeValueList = productAttributeValueMapper.selectByExample(attributeValueExample);
            result.setProductAttributeValueList(productAttributeValueList);
        }
        //获取商品SKU库存信息
        PmsSkuStockExample skuExample = new PmsSkuStockExample();
        skuExample.createCriteria().andProductIdEqualTo(product.getId());
        List<PmsSkuStock> skuStockList = skuStockMapper.selectByExample(skuExample);
        result.setSkuStockList(skuStockList);
        //商品阶梯价格设置
        if(product.getPromotionType()==3){
            PmsProductLadderExample ladderExample = new PmsProductLadderExample();
            ladderExample.createCriteria().andProductIdEqualTo(product.getId());
            List<PmsProductLadder> productLadderList = productLadderMapper.selectByExample(ladderExample);
            result.setProductLadderList(productLadderList);
        }
        //商品满减价格设置
        if(product.getPromotionType()==4){
            PmsProductFullReductionExample fullReductionExample = new PmsProductFullReductionExample();
            fullReductionExample.createCriteria().andProductIdEqualTo(product.getId());
            List<PmsProductFullReduction> productFullReductionList = productFullReductionMapper.selectByExample(fullReductionExample);
            result.setProductFullReductionList(productFullReductionList);
        }
        //商品可用优惠券
        result.setCouponList(portalProductDao.getAvailableCouponList(product.getId(),product.getProductCategoryId()));
        return result;
    }


    /**
     * 初始对象转化为节点对象
     */
    private PmsProductCategoryNode covert(PmsProductCategory item, List<PmsProductCategory> allList) {
        PmsProductCategoryNode node = new PmsProductCategoryNode();
        BeanUtils.copyProperties(item, node);
        List<PmsProductCategoryNode> children = allList.stream()
                .filter(subItem -> subItem.getParentId().equals(item.getId()))
                .map(subItem -> covert(subItem, allList)).collect(Collectors.toList());
        node.setChildren(children);
        return node;
    }
}
