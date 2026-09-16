<template>
  <view class="container">
    <!-- 商品信息 -->
    <view class="goods-section">
      <image class="goods-img" :src="productPic" mode="aspectFill"></image>
      <view class="goods-right">
        <text class="goods-title clamp">{{ productName }}</text>
      </view>
    </view>

    <!-- 商品评分 -->
    <view class="eva-section">
      <text class="section-tit">商品评分</text>
      <view class="star-row">
        <text
          v-for="i in 5"
          :key="i"
          class="star"
          :class="{ on: i <= star }"
          @click="handleSelectStar(i)"
          >★</text
        >
        <text class="star-tip">{{ starText }}</text>
      </view>
    </view>

    <!-- 评价内容 -->
    <view class="content-section">
      <textarea
        class="content-input"
        v-model="content"
        placeholder="说说这件商品的使用感受吧（至少5个字）"
        :maxlength="500"
      />
      <text class="word-count">{{ content.length }}/500</text>
    </view>

    <button class="submit-btn" :loading="submitting" @click="handleSubmit">提交评价</button>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { addCommentAPI } from '@/apis/comment'
import { useMemberStore } from '@/stores/member'

// ===== 页面数据 =====
// 订单id
const orderId = ref(0)
// 订单明细id
const orderItemId = ref(0)
// 商品名称
const productName = ref('')
// 商品图片
const productPic = ref('')
// 评价星数
const star = ref(5)
// 评价内容
const content = ref('')
// 是否正在提交
const submitting = ref(false)

// 会员store
const memberStore = useMemberStore()

// 星级文案
const starTextMap: Record<number, string> = {
  1: '很差',
  2: '较差',
  3: '一般',
  4: '满意',
  5: '非常满意',
}

// 当前星级对应文案
const starText = computed(() => starTextMap[star.value] || '')

// ===== onLoad =====
// 页面加载，接收订单与商品参数，并校验登录态
onLoad((options) => {
  orderId.value = Number(options?.orderId || 0)
  orderItemId.value = Number(options?.orderItemId || 0)
  productName.value = options?.productName ? decodeURIComponent(options.productName) : ''
  productPic.value = options?.productPic ? decodeURIComponent(options.productPic) : ''
  // 未登录提前拦截，避免用户填写完内容提交时才发现登录态失效
  if (!memberStore.hasLogin) {
    uni.showToast({ title: '请先登录后再评价', icon: 'none' })
    setTimeout(() => {
      uni.navigateTo({ url: '/pages/public/login' })
    }, 600)
  }
})

// ===== 事件处理方法 =====

// 选择星级
const handleSelectStar = (value: number) => {
  star.value = value
}

// 提交评价
const handleSubmit = async () => {
  if (submitting.value) {
    return
  }
  if (orderId.value === 0 || orderItemId.value === 0) {
    uni.showToast({ title: '订单信息缺失', icon: 'none' })
    return
  }
  const text = content.value.trim()
  if (text.length < 5) {
    uni.showToast({ title: '评价内容至少5个字', icon: 'none' })
    return
  }
  submitting.value = true
  uni.showLoading({ title: '提交中' })
  try {
    await addCommentAPI({
      orderId: orderId.value,
      orderItemId: orderItemId.value,
      star: star.value,
      content: text,
    })
    uni.hideLoading()
    uni.showToast({ title: '评价成功' })
    uni.$emit('commentSubmitted')
    setTimeout(() => {
      uni.navigateBack()
    }, 800)
  } catch (e) {
    uni.hideLoading()
    console.error('提交评价失败', e)
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.container {
  padding: 20rpx 30rpx;
  background: $page-color-base;
  min-height: 100vh;
}

.goods-section {
  display: flex;
  padding: 24rpx;
  background: #fff;
  border-radius: 12rpx;

  .goods-img {
    width: 140rpx;
    height: 140rpx;
    border-radius: 8rpx;
  }

  .goods-right {
    flex: 1;
    margin-left: 20rpx;
    overflow: hidden;

    .goods-title {
      font-size: $font-base;
      color: $font-color-dark;
      line-height: 1.4;
    }
  }
}

.eva-section {
  margin-top: 20rpx;
  padding: 24rpx;
  background: #fff;
  border-radius: 12rpx;

  .section-tit {
    font-size: $font-base;
    color: $font-color-dark;
  }

  .star-row {
    display: flex;
    align-items: center;
    margin-top: 20rpx;

    .star {
      font-size: 48rpx;
      color: $font-color-disabled;
      margin-right: 12rpx;

      &.on {
        color: #ffb400;
      }
    }

    .star-tip {
      margin-left: 16rpx;
      font-size: $font-sm;
      color: $font-color-light;
    }
  }
}

.content-section {
  position: relative;
  margin-top: 20rpx;
  padding: 24rpx;
  background: #fff;
  border-radius: 12rpx;

  .content-input {
    width: 100%;
    height: 240rpx;
    font-size: $font-base;
    color: $font-color-dark;
  }

  .word-count {
    display: block;
    text-align: right;
    font-size: $font-sm;
    color: $font-color-light;
  }
}

.submit-btn {
  margin-top: 60rpx;
  height: 88rpx;
  line-height: 88rpx;
  font-size: $font-lg;
  color: #fff;
  background: $base-color;
  border-radius: 44rpx;
}
</style>
