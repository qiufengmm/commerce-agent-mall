<script setup lang="ts">
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'

onLaunch(() => {
  console.log('App Launch')
})
onShow(() => {
  console.log('App Show')
  // #ifdef H5
  // 消息已读过则恢复隐藏导航栏消息按钮红点（刷新后 body 类会丢失，从本地存储恢复）
  if (uni.getStorageSync('noticeRead')) {
    document.body.classList.add('notice-read')
  }
  // #endif
})
onHide(() => {
  console.log('App Hide')
})
</script>

<style lang="scss">
// 字体图标
@use '@/styles/fonts.scss';

view,
navigator,
input,
scroll-view {
  box-sizing: border-box;
}

button::after {
  border: none;
}

swiper,
scroll-view {
  flex: 1;
  height: 100%;
  overflow: hidden;
}

image {
  width: 100%;
  height: 100%;
  vertical-align: middle;
}

// 一行省略
.clamp {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

// 两行省略
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  line-clamp: 2;
  -webkit-line-clamp: 2;
}

// 边框样式（底部边框）
.b-b:after,
.b-t:after {
  position: absolute;
  z-index: 3;
  left: 0;
  right: 0;
  height: 0;
  content: '';
  transform: scaleY(0.5);
  border-bottom: 1px solid #e4e7ed;
}

.b-b:after {
  bottom: 0;
}

.b-t:after {
  top: 0;
}

/* #ifdef H5 */
// 消息已读后隐藏 H5 导航栏消息按钮红点
// 注意：uni-page-head-btn-red-dot 类挂在整个按钮上，红点是其 :after 伪元素，只隐藏伪元素
body.notice-read .uni-page-head-btn-red-dot:after {
  display: none !important;
}
/* #endif */
</style>
