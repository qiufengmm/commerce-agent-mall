<template>
  <view>
    <view class="notice-item">
      <text class="time">11:30</text>
      <view class="content">
        <text class="title">新品上市，全场满199减50</text>
        <view class="img-wrapper">
          <image
            class="pic"
            src="/static/notice/notice-banner-01.jpg"
          ></image>
        </view>
        <text class="introduce">
          虽然做了一件好事，但很有可能因此招来他人的无端猜测，例如被质疑是否藏有其他利己动机等，乃至谴责。即便如此，还是要做好事。
        </text>
        <view class="bot b-t">
          <text>查看详情</text>
          <text class="more-icon yticon icon-you"></text>
        </view>
      </view>
    </view>
    <view class="notice-item">
      <text class="time">昨天 12:30</text>
      <view class="content">
        <text class="title">新品上市，全场满199减50</text>
        <view class="img-wrapper">
          <image
            class="pic"
            src="/static/notice/notice-banner-02.jpg"
          ></image>
          <view class="cover">活动结束</view>
        </view>
        <view class="bot b-t">
          <text>查看详情</text>
          <text class="more-icon yticon icon-you"></text>
        </view>
      </view>
    </view>
    <view class="notice-item">
      <text class="time">2019-07-26 12:30</text>
      <view class="content">
        <text class="title">新品上市，全场满199减50</text>
        <view class="img-wrapper">
          <image
            class="pic"
            src="/static/notice/movie-ad.jpg"
          ></image>
          <view class="cover">活动结束</view>
        </view>
        <text class="introduce">
          新品上市全场2折起，新品上市全场2折起，新品上市全场2折起，新品上市全场2折起，新品上市全场2折起
        </text>
        <view class="bot b-t">
          <text>查看详情</text>
          <text class="more-icon yticon icon-you"></text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'

// 进入消息页即标记已读，隐藏导航栏消息按钮红点
onShow(() => {
  uni.setStorageSync('noticeRead', true)
  // #ifdef H5
  document.body.classList.add('notice-read')
  // #endif
})
</script>

<style lang="scss">
page {
  background-color: #f7f7f7;
  padding-bottom: 30rpx;
}
</style>

<style lang="scss" scoped>
.notice-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.time {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 80rpx;
  padding-top: 10rpx;
  font-size: 26rpx;
  color: #7d7d7d;
}

.content {
  width: 710rpx;
  padding: 0 24rpx;
  background-color: #fff;
  border-radius: 4rpx;
}

.title {
  display: flex;
  align-items: center;
  height: 90rpx;
  font-size: 32rpx;
  color: #303133;
}

.img-wrapper {
  width: 100%;
  height: 260rpx;
  position: relative;
}

.pic {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 6rpx;
}

.cover {
  display: flex;
  justify-content: center;
  align-items: center;
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.5);
  font-size: 36rpx;
  color: #fff;
}

.introduce {
  display: inline-block;
  padding: 16rpx 0;
  font-size: 28rpx;
  color: #606266;
  line-height: 38rpx;
}

.bot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 80rpx;
  font-size: 24rpx;
  color: #707070;
  position: relative;
}

.more-icon {
  font-size: 32rpx;
}
</style>
