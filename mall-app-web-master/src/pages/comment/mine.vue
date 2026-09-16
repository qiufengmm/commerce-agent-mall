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
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { getMyCommentListAPI } from '@/apis/comment'
import type { PmsCommentResult } from '@/types/comment'
import { useMemberStore } from '@/stores/member'

// ===== 页面数据 =====
// 评价列表
const commentList = ref<PmsCommentResult[]>([])
// 评价总数
const total = ref(0)
// 当前页码
const pageNum = ref(1)
// 每页数量
const pageSize = ref(10)
// 是否首次加载中
const loading = ref(true)
// 加载更多状态
const loadingType = ref<'more' | 'loading' | 'nomore'>('more')

// 会员store
const memberStore = useMemberStore()
// 是否已登录
const hasLogin = computed(() => memberStore.hasLogin)

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

// ===== 数据加载 =====
// 加载我的评价，type: refresh-下拉刷新 add-上拉加载
const loadData = async (type: 'refresh' | 'add' = 'add') => {
  if (!hasLogin.value) {
    loading.value = false
    return
  }
  if (type === 'add' && loadingType.value === 'nomore') return

  loadingType.value = 'loading'

  if (type === 'refresh') {
    pageNum.value = 1
    commentList.value = []
  }

  try {
    const res = await getMyCommentListAPI({ pageNum: pageNum.value, pageSize: pageSize.value })
    const dataList = res.data.list || []
    total.value = res.data.total || 0

    if (dataList.length < pageSize.value) {
      loadingType.value = 'nomore'
    } else {
      loadingType.value = 'more'
    }
    commentList.value = commentList.value.concat(dataList)
  } catch (error) {
    console.error('加载我的评价失败', error)
    // 加载失败回退页码，避免跳过某一页
    if (type === 'add' && pageNum.value > 1) {
      pageNum.value -= 1
    }
    loadingType.value = 'more'
  } finally {
    loading.value = false
    if (type === 'refresh') {
      uni.stopPullDownRefresh()
    }
  }
}

// ===== onLoad =====
onLoad(() => {
  // 未登录直接跳转登录，避免发起无意义的请求
  if (!hasLogin.value) {
    loading.value = false
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => {
      uni.navigateTo({ url: '/pages/public/login' })
    }, 600)
    return
  }
  loadData()
})

// ===== onPullDownRefresh =====
onPullDownRefresh(() => {
  loadData('refresh')
})

// ===== onReachBottom =====
onReachBottom(() => {
  if (loadingType.value === 'nomore') return
  pageNum.value += 1
  loadData()
})

// ===== 事件处理方法 =====
// 跳转登录页
const handleNavToLogin = () => {
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
