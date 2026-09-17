<template>
  <view class="container">
    <!-- 统计条 -->
    <view class="summary-card">
      <view class="summary-left">
        <text class="summary-title">商品评价</text>
        <text class="summary-total">共 {{ total }} 条</text>
      </view>
      <view class="summary-entry" @click="handleNavToMine">
        <text class="entry-text">我的评价</text>
        <text class="entry-arrow">›</text>
      </view>
    </view>

    <!-- 商品信息缺失 -->
    <view v-if="productMissing" class="empty">
      <text class="empty-text">商品信息缺失，请返回商品详情页重试</text>
    </view>

    <!-- 空状态 -->
    <view v-else-if="!loading && commentList.length === 0" class="empty">
      <text class="empty-text">还没有人评价，快来抢首评</text>
    </view>

    <!-- 评价列表 -->
    <view v-else class="comment-list">
      <view v-for="item in commentList" :key="item.id" class="comment-card">
        <image
          v-if="item.memberIcon"
          class="portrait"
          :src="item.memberIcon"
          mode="aspectFill"
        ></image>
        <view v-else class="portrait portrait-text">{{ formatFirstChar(item.memberNickName) }}</view>
        <view class="comment-right">
          <view class="comment-info">
            <text class="comment-name">{{ formatNickName(item.memberNickName) }}</text>
            <text class="comment-star">{{ formatStar(item.star) }}</text>
          </view>
          <text class="comment-time">{{ formatDateTime(item.createTime) }}</text>
          <text class="comment-content">{{ item.content }}</text>
          <view v-if="formatPics(item.pics).length > 0" class="pics-row">
            <image
              v-for="(pic, index) in formatPics(item.pics)"
              :key="index"
              class="pic-item"
              :src="pic"
              mode="aspectFill"
            ></image>
          </view>
        </view>
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
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { getCommentListAPI } from '@/apis/comment'
import type { PmsComment } from '@/types/comment'
import { useCommentPaging } from '@/composables/useCommentPaging'

// ===== 页面数据 =====
// 商品id
const productId = ref(0)
// 商品信息是否缺失
const productMissing = ref(false)

// 分页状态与并发保护统一交给控制器处理，页面只负责传参与渲染
const {
  list: commentList,
  total,
  loading,
  loadingType,
  load: loadData,
} = useCommentPaging<PmsComment>({
  pageSize: 10,
  // 商品id缺失时不发起请求
  canLoad: () => !!productId.value,
  fetcher: async ({ pageNum, pageSize }) => {
    const res = await getCommentListAPI({ productId: productId.value, pageNum, pageSize })
    return { list: res.data.list || [], total: res.data.total || 0 }
  },
  // 下拉刷新动画必须在成功、失败和提前返回时都结束
  // 只有最新的 refresh 请求才能结束动画，避免被抢占的旧请求提前关闭最新一次刷新
  onFinish: (type, isLatest) => {
    if (type === 'refresh' && isLatest) {
      uni.stopPullDownRefresh()
    }
  },
  onError: (error) => console.error('加载商品评价失败', error),
})

// ===== 格式化方法 =====
// 格式化星级展示
const formatStar = (star: number): string => {
  const num = star > 5 ? 5 : star < 0 ? 0 : star
  return '★'.repeat(num) + '☆'.repeat(5 - num)
}

// 昵称脱敏，保留首尾字符
const formatNickName = (name: string): string => {
  if (!name) return '匿名用户'
  if (name.length <= 2) return name
  return `${name[0]}***${name[name.length - 1]}`
}

// 取昵称首字符作为默认头像文字
const formatFirstChar = (name: string): string => {
  return name ? name[0] : '用'
}

// 评价图片按逗号分隔
const formatPics = (pics?: string): string[] => {
  if (!pics) return []
  return pics
    .split(',')
    .map((item) => item.trim())
    .filter((item) => !!item)
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
onLoad((options) => {
  productId.value = options?.productId ? Number(options.productId) : 0
  if (!productId.value) {
    productMissing.value = true
    loading.value = false
    uni.showToast({ title: '商品信息缺失', icon: 'none' })
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
  // 页码不在触底时自增，由 loadData 在请求成功后统一推进
  loadData('add')
})

// ===== 事件处理方法 =====
// 跳转我的评价
const handleNavToMine = () => {
  uni.navigateTo({ url: '/pages/comment/mine' })
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
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 30rpx;
  background: #fff;
  border-radius: 12rpx;

  .summary-left {
    display: flex;
    align-items: baseline;
  }

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

  .summary-entry {
    display: flex;
    align-items: center;
    padding: 8rpx 4rpx 8rpx 20rpx;

    .entry-text {
      font-size: $font-base;
      color: $base-color;
    }

    .entry-arrow {
      margin-left: 6rpx;
      font-size: $font-lg;
      color: $base-color;
    }
  }
}

.empty {
  display: flex;
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
}

.comment-list {
  margin-top: 20rpx;
}

.comment-card {
  display: flex;
  padding: 28rpx 30rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 12rpx;

  .portrait {
    flex-shrink: 0;
    width: 72rpx;
    height: 72rpx;
    border-radius: 50%;
  }

  .portrait-text {
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f0f1f3;
    color: $font-color-light;
    font-size: $font-base;
  }

  .comment-right {
    flex: 1;
    padding-left: 24rpx;
    overflow: hidden;
  }

  .comment-info {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .comment-name {
    font-size: $font-base;
    color: $font-color-dark;
  }

  .comment-star {
    font-size: $font-sm;
    color: #ffb400;
    letter-spacing: 2rpx;
  }

  .comment-time {
    display: block;
    margin-top: 8rpx;
    font-size: $font-sm;
    color: $font-color-light;
  }

  .comment-content {
    display: block;
    margin-top: 14rpx;
    font-size: $font-base;
    color: #303133;
    line-height: 1.6;
    word-break: break-all;
  }

  .pics-row {
    display: flex;
    flex-wrap: wrap;
    margin-top: 16rpx;
  }

  .pic-item {
    width: 150rpx;
    height: 150rpx;
    margin: 0 12rpx 12rpx 0;
    border-radius: 8rpx;
  }
}

.load-more {
  text-align: center;
  padding: 24rpx;
  font-size: 28rpx;
  color: $font-color-light;
}
</style>
