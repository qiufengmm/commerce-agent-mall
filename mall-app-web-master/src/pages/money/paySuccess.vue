<template>
  <view class="content">
    <text class="success-icon yticon icon-xuanzhong2"></text>
    <text class="tit">{{ payText }}</text>

    <view class="btn-group">
      <navigator url="/pages/order/order?state=0" open-type="redirect" class="mix-btn">
        查看订单
      </navigator>
      <navigator url="/pages/index/index" open-type="switchTab" class="mix-btn hollow">
        返回首页
      </navigator>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { fetchAliapyStatusAPI } from '@/apis/order'

// 是否使用支付宝支付（H5端启用，通过环境变量配置）
const USE_ALIPAY = import.meta.env.VITE_USE_ALIPAY === 'true'

// 服务端返回的状态串代表「本地订单已支付成功」，只有这一个值才显示支付成功
const LOCAL_PAY_SUCCESS = 'TRADE_SUCCESS'
// 支付宝交易已创建但买家还未付款，属于处理中
const ALIPAY_WAIT_BUYER_PAY = 'WAIT_BUYER_PAY'

// ===== 页面数据 =====
// 支付结果文字
const payText = ref('')

// ===== 数据加载 =====
// 查询支付宝交易状态
// 注意：不能只依据支付宝原始交易状态判断成功。
// 服务端只有在「订单归属校验通过 + 金额一致 + 本地支付成功」时才会返回 TRADE_SUCCESS，
// 本地校验失败会返回 PAYMENT_VERIFY_FAILED / PAYMENT_PROCESS_FAILED，
// 这些值一律按未成功处理，避免订单仍是待付款却显示支付成功。
const loadData = async (outTradeNo: string) => {
  try {
    const res = await fetchAliapyStatusAPI({ outTradeNo })
    const tradeStatus = res.data
    if (tradeStatus === LOCAL_PAY_SUCCESS) {
      payText.value = '支付成功'
    } else if (tradeStatus === ALIPAY_WAIT_BUYER_PAY) {
      payText.value = '支付处理中'
    } else {
      // 包含 null、TRADE_CLOSED、TRADE_FINISHED、PAYMENT_VERIFY_FAILED、PAYMENT_PROCESS_FAILED
      payText.value = '支付失败'
    }
  } catch (e) {
    console.error('查询支付宝状态失败', e)
    payText.value = '支付失败'
  }
}

// ===== 生命周期 =====
// 页面加载
onLoad((options: any) => {
  if (!USE_ALIPAY) {
    payText.value = '支付成功'
    return
  }
  const outTradeNo = options.out_trade_no
  if (outTradeNo) {
    loadData(outTradeNo)
  }
})
</script>

<style lang="scss" scoped>
.content {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.success-icon {
  font-size: 160rpx;
  color: #fa436a;
  margin-top: 100rpx;
}

.tit {
  font-size: 38rpx;
  color: #303133;
}

.btn-group {
  padding-top: 100rpx;
}

.mix-btn {
  margin-top: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 600rpx;
  height: 80rpx;
  font-size: $font-lg;
  color: #fff;
  background-color: $base-color;
  border-radius: 10rpx;

  &.hollow {
    background: #fff;
    color: #303133;
    border: 1px solid #ccc;
  }
}
</style>
