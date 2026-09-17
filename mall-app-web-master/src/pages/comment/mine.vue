<template>
  <view class="container">
    <!-- 统计条 -->
    <view class="summary-card">
      <text class="summary-title">我的评价</text>
      <text class="summary-total">共 {{ total }} 条</text>
    </view>

    <!-- 未登录 -->
    <view v-if="!hasLogin" class="empty">
      <text class="empty-text">登录后才能查看我的评价</text>
      <button class="login-btn" @click="handleNavToLogin">去登录</button>
    </view>

    <!-- 无数据 -->
    <view v-else-if="!loading && commentList.length === 0" class="empty">
      <text class="empty-text">还没有评价过商品</text>
    </view>

    <!-- 评价列表 -->
    <view v-else class="comment-list">
      <view
        v-for="item in commentList"
        :key="item.id"
        class="comment-card"
        @click="handleNavToProduct(item.productId)"
      >
        <view class="goods-row">
          <image
            v-if="item.productPic"
            class="goods-img"
            :src="item.productPic"
            mode="aspectFill"
          ></image>
          <view v-else class="goods-img goods-img-text">商</view>
          <text class="goods-name clamp2">{{ item.productName }}</text>
          <text v-if="item.showStatus !== 1" class="status-tag">仅自己可见</text>
        </view>
        <view class="comment-info">
          <text class="comment-star">{{ formatStar(item.star) }}</text>
          <text class="comment-time">{{ formatDateTime(item.createTime) }}</text>
        </view>
        <text class="comment-content">{{ item.content }}</text>
      </view>
    </view>

    <!-- 加载状态 -->
    <view v-if="loadingType === 'loading'" class="load-more">加载中...</view>
    <view v-else-if="loadingType === 'nomore' && commentList.length > 0" class="load-more">
      没有更多了
    </view>
    <view v-else-if="commentList.length > 0" class="load-more">上拉加载更多</view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { onLoad, onShow, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { getMyCommentListAPI } from '@/apis/comment'
import type { PmsCommentResult } from '@/types/comment'
import { useMemberStore } from '@/stores/member'
import { useCommentPaging } from '@/composables/useCommentPaging'

// ===== Store 相关 =====
// 会员store
const memberStore = useMemberStore()
// 是否已登录
const hasLogin = computed(() => memberStore.hasLogin)

// ===== 页面数据 =====
// 是否因为未登录而跳过了首次加载
const needReloadAfterLogin = ref(false)

// 分页状态与并发保护统一交给控制器处理，页面只负责传参与渲染
const {
  list: commentList,
  total,
  loading,
  loadingType,
  load: loadData,
} = useCommentPaging<PmsCommentResult>({
  pageSize: 10,
  // 未登录时不发起请求
  canLoad: () => hasLogin.value,
  fetcher: async ({ pageNum, pageSize }) => {
    const res = await getMyCommentListAPI({ pageNum, pageSize })
    return { list: res.data.list || [], total: res.data.total || 0 }
  },
  // 下拉刷新动画必须在成功、失败和提前返回时都结束
  // 只有最新的 refresh 请求才能结束动画，避免被抢占的旧请求提前关闭最新一次刷新
  onFinish: (type, isLatest) => {
    if (type === 'refresh' && isLatest) {
      uni.stopPullDownRefresh()
    }
  },
  onError: (error) => console.error('加载我的评价失败', error),
})

// ===== 格式化方法 =====
// 格式化星级展示
const formatStar = (star: number): string => {
  const num = star > 5 ? 5 : star < 0 ? 0 : star
  return '★'.repeat(num) + '☆'.repeat(5 - num)
}

// 格式化日期时间
const formatDateTime = (time?: string): string => {
  if (!time) return 'N/A'
  const date = new Date(time)
  if (isNaN(date.getTime())) return 'N/A'
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

// ===== onLoad =====
onLoad(() => {
  // 未登录直接跳转登录，避免发起无意义的请求
  if (!hasLogin.value) {
    needReloadAfterLogin.value = true
    loading.value = false
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => {
      uni.navigateTo({ url: '/pages/public/login' })
    }, 600)
    return
  }
  loadData()
})

// ===== onShow =====
onShow(() => {
  // 登录完成后返回本页时补齐首次加载，避免停留在空状态、只能手动下拉刷新
  if (needReloadAfterLogin.value && hasLogin.value) {
    needReloadAfterLogin.value = false
    // 补加载期间重新进入首屏加载态，避免加载中闪现「还没有评价过商品」空状态
    loading.value = true
    // 用 add 类型补加载：列表此时为空，追加等价于替换，且不会触发多余的 stopPullDownRefresh
    loadData()
  }
})

// ===== onPullDownRefresh =====
onPullDownRefresh(() => {
  loadData('refresh')
})

// ===== onReachBottom =====
onReachBottom(() => {
  // 页码不在触底时自增，由 loadData 在请求成功后统一推进
  loadData('add')
})

// ===== 事件处理方法 =====
// 跳转登录页
const handleNavToLogin = () => {
  // 标记登录返回后需要补加载
  needReloadAfterLogin.value = true
  uni.navigateTo({ url: '/pages/public/login' })
}

// 跳转商品详情
const handleNavToProduct = (productId: number) => {
  if (!productId) return
  uni.navigateTo({ url: `/pages/product/product?id=${productId}` })
}
</script>

<style lang="scss" scoped>
.container {
  min-height: 100vh;
  padding: 20rpx 20rpx 40rpx;
  background: $page-color-base;
}

.summary-card {
  display: flex;
  align-items: baseline;
  padding: 24rpx 30rpx;
  background: #fff;
  border-radius: 12rpx;

  .summary-title {
    font-size: 32rpx;
    font-weight: 600;
    color: $font-color-dark;
  }

  .summary-total {
    margin-left: 16rpx;
    font-size: $font-sm;
    color: $font-color-light;
  }
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin-top: 40rpx;
  padding: 80rpx 0;
  background: #fff;
  border-radius: 12rpx;

  .empty-text {
    font-size: $font-base;
    color: $font-color-light;
  }

  .login-btn {
    width: 320rpx;
    height: 76rpx;
    line-height: 76rpx;
    margin-top: 40rpx;
    font-size: $font-lg;
    color: #fff;
    background: $base-color;
    border-radius: 38rpx;
  }
}

.comment-list {
  margin-top: 20rpx;
}

.comment-card {
  padding: 28rpx 30rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 12rpx;
}

.goods-row {
  display: flex;
  align-items: center;

  .goods-img {
    flex-shrink: 0;
    width: 120rpx;
    height: 120rpx;
    border-radius: 12rpx;
  }

  .goods-img-text {
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f0f1f3;
    color: $font-color-light;
    font-size: $font-base;
  }

  .goods-name {
    flex: 1;
    margin-left: 20rpx;
    font-size: $font-base;
    color: $font-color-dark;
    line-height: 1.4;
  }

  .status-tag {
    flex-shrink: 0;
    margin-left: 12rpx;
    padding: 4rpx 14rpx;
    font-size: 22rpx;
    color: $font-color-light;
    background: #f5f5f5;
    border-radius: 20rpx;
  }
}

.comment-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;

  .comment-star {
    font-size: $font-sm;
    color: #ffb400;
    letter-spacing: 2rpx;
  }

  .comment-time {
    font-size: $font-sm;
    color: $font-color-light;
  }
}

.comment-content {
  display: block;
  margin-top: 14rpx;
  font-size: $font-base;
  color: #303133;
  line-height: 1.6;
  word-break: break-all;
}

.clamp2 {
  display: -webkit-box;
  overflow: hidden;
  text-overflow: ellipsis;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.load-more {
  text-align: center;
  padding: 24rpx;
  font-size: 28rpx;
  color: $font-color-light;
}
</style>
