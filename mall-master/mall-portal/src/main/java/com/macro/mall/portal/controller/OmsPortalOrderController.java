package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.ConfirmOrderResult;
import com.macro.mall.portal.domain.DirectBuyParam;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.domain.OrderParam;
import com.macro.mall.portal.service.OmsPortalOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单管理Controller
 */
@Controller
@Tag(name = "OmsPortalOrderController", description = "订单管理")
@RequestMapping("/order")
public class OmsPortalOrderController {
    @Autowired
    private OmsPortalOrderService portalOrderService;

    @Operation(summary = "根据购物车信息生成确认单")
    @RequestMapping(value = "/generateConfirmOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<ConfirmOrderResult> generateConfirmOrder(@RequestBody List<Long> cartIds) {
        ConfirmOrderResult confirmOrderResult = portalOrderService.generateConfirmOrder(cartIds);
        return CommonResult.success(confirmOrderResult);
    }

    @Operation(summary = "根据商品详情页SKU生成直购确认单")
    @RequestMapping(value = "/generateDirectConfirmOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<ConfirmOrderResult> generateDirectConfirmOrder(@RequestBody DirectBuyParam directBuyParam) {
        ConfirmOrderResult confirmOrderResult = portalOrderService.generateDirectConfirmOrder(directBuyParam);
        return CommonResult.success(confirmOrderResult);
    }

    @Operation(summary = "根据购物车信息生成订单")
    @RequestMapping(value = "/generateOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult generateOrder(@RequestBody OrderParam orderParam) {
        Map<String, Object> result = portalOrderService.generateOrder(orderParam);
        return CommonResult.success(result, "下单成功");
    }

    @Operation(summary = "用户支付成功的回调")
    @RequestMapping(value = "/paySuccess", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult paySuccess(@RequestParam Long orderId,@RequestParam Integer payType) {
        OrderOperationResult result = portalOrderService.paySuccess(orderId,payType);
        return toCommonResult(result, "支付成功", "订单不存在或无权支付", "订单当前状态不允许支付，或支付方式不合法");
    }

    @Operation(summary = "自动取消超时订单")
    @RequestMapping(value = "/cancelTimeOutOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult cancelTimeOutOrder() {
        portalOrderService.cancelTimeOutOrder();
        return CommonResult.success(null);
    }

    @Operation(summary = "按状态分页获取用户订单列表")
    @Parameter(name = "status", description = "订单状态：-1->全部；0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭",
            in = ParameterIn.QUERY, schema = @Schema(type = "integer",defaultValue = "-1",allowableValues = {"-1","0","1","2","3","4"}))
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<OmsOrderDetail>> list(@RequestParam Integer status,
                                                   @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                   @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        CommonPage<OmsOrderDetail> orderPage = portalOrderService.list(status,pageNum,pageSize);
        return CommonResult.success(orderPage);
    }

    @Operation(summary = "根据ID获取订单详情")
    @RequestMapping(value = "/detail/{orderId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<OmsOrderDetail> detail(@PathVariable Long orderId) {
        OmsOrderDetail orderDetail = portalOrderService.detail(orderId);
        return CommonResult.success(orderDetail);
    }

    @Operation(summary = "用户取消订单")
    @RequestMapping(value = "/cancelUserOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult cancelUserOrder(Long orderId) {
        OrderOperationResult result = portalOrderService.cancelMemberOrder(orderId);
        return toCommonResult(result, "取消成功", "订单不存在或无权取消", "订单当前状态不允许取消");
    }

    @Operation(summary = "用户确认收货")
    @RequestMapping(value = "/confirmReceiveOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult confirmReceiveOrder(Long orderId) {
        OrderOperationResult result = portalOrderService.confirmReceiveOrder(orderId);
        return toCommonResult(result, "确认收货成功", "订单不存在或无权操作", "订单当前状态不允许确认收货");
    }

    @Operation(summary = "用户删除订单")
    @RequestMapping(value = "/deleteOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult deleteOrder(Long orderId) {
        portalOrderService.deleteOrder(orderId);
        return CommonResult.success(null);
    }

    /**
     * 把服务层的统一业务结果映射为接口响应，幂等结果与首次成功同样返回成功
     */
    private CommonResult toCommonResult(OrderOperationResult result, String successMessage, String notFoundMessage, String rejectedMessage) {
        if (result == OrderOperationResult.SUCCESS) {
            return CommonResult.success(null, successMessage);
        }
        if (result == OrderOperationResult.IDEMPOTENT) {
            return CommonResult.success(null, "订单已处理，无需重复操作");
        }
        if (result == OrderOperationResult.NOT_FOUND) {
            return CommonResult.failed(notFoundMessage);
        }
        return CommonResult.failed(rejectedMessage);
    }
}
