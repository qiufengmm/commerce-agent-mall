<template>
  <view class="container">
    <!-- 自定义导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-content">
        <text class="nav-title">购物车</text>
        <text
          class="nav-clear"
          :class="{ disabled: checkedCount === 0 }"
          :style="{ right: navClearRight + 'rpx' }"
          @click="handleClearSelected"
          >清空选中</text
        >
      </view>
    </view>
    <!-- 空白页 -->
    <view v-if="!hasLogin || empty === true" class="empty">
      <image src="/static/emptyCart.jpg" mode="aspectFit"></image>
      <view v-if="hasLogin" class="empty-tips">
        空空如也
        <navigator class="navigator" url="../index/index" open-type="switchTab"
          >随便逛逛></navigator
        >
      </view>
      <view v-else class="empty-tips">
        空空如也
        <view class="navigator" @click="handleNavToLogin">去登陆</view>
      </view>
    </view>
    <view v-else class="page-body" :style="{ paddingTop: statusBarHeight + 44 + 'px' }">
      <!-- 列表（左滑删除） -->
      <view class="cart-list">
        <view v-for="(item, index) in cartList" :key="item.id" class="swipe-item">
          <view
            class="swipe-content"
            :class="{ 'no-anim': movingIndex === index }"
            :style="{ transform: `translateX(${offsets[index] || 0}rpx)` }"
            @touchstart="handleTouchStart($event, index)"
            @touchmove="handleTouchMove($event, index)"
            @touchend="handleTouchEnd($event, index)"
          >
            <view class="cart-item" :class="{ 'b-b': index !== cartList.length - 1 }">
              <view class="image-wrapper" @click="handleNavToProductDetail(item.productId)">
                <image
                  :src="item.productPic"
                  :class="[item.loaded]"
                  mode="aspectFill"
                  lazy-load
                  @load="handleImageLoad(index)"
                  @error="handleImageError(index)"
                ></image>
                <view
                  class="yticon icon-xuanzhong2 checkbox"
                  :class="{ checked: item.checked }"
                  @click.stop="handleCheck(index)"
                ></view>
              </view>
              <view class="item-right" @click="handleNavToProductDetail(item.productId)">
                <text class="clamp title">{{ item.productName }}</text>
                <text class="clamp attr">{{ item.spDataStr }}</text>
                <text class="price">¥{{ item.price }}</text>
                <uni-number-box
                  class="step"
                  :min="1"
                  :max="100"
                  :value="item.quantity"
                  :index="index"
                  @eventChange="handleNumberChange"
                  @click.stop
                ></uni-number-box>
              </view>
            </view>
          </view>
          <view class="swipe-del" @click.stop="handleDeleteCartItem(index)">删除</view>
        </view>
      </view>
      <!-- 底部菜单栏 -->
      <view class="action-section">
        <view class="checkbox" @click="handleCheckAll">
          <image
            :src="allChecked ? '/static/selected.png' : '/static/select.png'"
            mode="aspectFit"
          ></image>
          <text class="all-text">全选</text>
        </view>
        <view class="total-box">
          <text class="total-label">合计:</text>
          <text class="price">¥{{ totalPrice }}</text>
        </view>
        <button type="primary" class="no-border confirm-btn" @click="handleCreateOrder"
          >去结算({{ checkedCount }})</button
        >
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useMemberStore } from '@/stores/member'
import { getCartListAPI, deleteCartAPI, updateCartQuantityAPI } from '@/apis/cart'
import type { CartItem } from '@/types/cart'
import { removeCartItemsById } from '@/utils/cart'
import uniNumberBox from '@/components/uni-number-box.vue'

// ===== Store 相关 =====
// 获取会员store
const memberStore = useMemberStore()
// 是否登录
const hasLogin = computed(() => !!memberStore.memberInfo)

// ===== 页面数据 =====
// 购物车中商品总价
const totalPrice = ref(0)
// 购物车中商品是否全部选中
const allChecked = ref(false)
// 购物车中商品是否为空
const empty = ref(false)
// 购物车中商品列表
const cartList = ref<CartItem[]>([])
// 状态栏高度（仅小程序需要，H5 为 0）
const statusBarHeight = ref(0)
// 右侧清空按钮默认间距；微信小程序会根据右上角胶囊动态调整
const navClearRight = ref(30)
// 窗口宽度（touch 像素转 rpx 用）
const windowWidth = ref(375)
// 左滑：每条目当前横向偏移（rpx）
const offsets = ref<Record<number, number>>({})
// 左滑：当前处于滑开状态的条目索引
const openIndex = ref(-1)
// 左滑：正在拖拽中的条目索引（拖拽期间关闭过渡动画）
const movingIndex = ref(-1)
const touchState = { startX: 0, startY: 0, horizontal: false }

// 选中商品数量
const checkedCount = computed(() => cartList.value.filter((item) => item.checked).length)

// ===== 数据加载 =====
// 加载购物车数据
const loadData = async () => {
  if (!hasLogin.value) {
    return
  }
  try {
    const res = await getCartListAPI()
    const list = res.data as CartItem[]
    cartList.value = list.map((item) => {
      item.checked = true
      item.loaded = 'loaded'
      const spDataArr = JSON.parse(item.productAttr)
      let spDataStr = ''
      for (const attr of spDataArr) {
        spDataStr += attr.key
        spDataStr += ':'
        spDataStr += attr.value
        spDataStr += ';'
      }
      item.spDataStr = spDataStr
      return item
    })
    calcTotal()
  } catch (e) {
    console.error('加载购物车失败', e)
  }
}

// ===== 生命周期 =====
// 监听购物车列表变化
watch(
  () => cartList.value.length,
  (len) => {
    empty.value = len === 0
  },
)

// 页面显示时调用
onShow(() => {
  // 自定义导航栏需要在小程序与 App 中避开系统状态栏；H5 的高度为 0
  const systemInfo = uni.getSystemInfoSync()
  statusBarHeight.value = systemInfo.statusBarHeight || 0
  windowWidth.value = systemInfo.windowWidth || 375
  // #ifdef MP-WEIXIN
  const menuButton = uni.getMenuButtonBoundingClientRect()
  // 将按钮放在胶囊左侧并留出 8px 间隔，避免文字和点击区域被胶囊遮挡
  const safeRightPx = systemInfo.windowWidth - menuButton.left + 8
  navClearRight.value = Math.max(30, Math.ceil((safeRightPx * 750) / windowWidth.value))
  // #endif
  loadData()
})

// ===== 左滑删除手势 =====
// 按下
const handleTouchStart = (e: TouchEvent, index: number) => {
  const touch = e.touches[0]
  touchState.startX = touch.clientX
  touchState.startY = touch.clientY
  touchState.horizontal = false
  // 开始滑动其他条目时，收起已滑开的条目
  if (openIndex.value !== -1 && openIndex.value !== index) {
    offsets.value[openIndex.value] = 0
    openIndex.value = -1
  }
}

// 滑动
const handleTouchMove = (e: TouchEvent, index: number) => {
  const touch = e.touches[0]
  const dx = touch.clientX - touchState.startX
  const dy = touch.clientY - touchState.startY
  // 先判断滑动意图，垂直滚动时不干预
  if (!touchState.horizontal) {
    if (Math.abs(dx) < 8 && Math.abs(dy) < 8) {
      return
    }
    touchState.horizontal = Math.abs(dx) > Math.abs(dy)
    if (!touchState.horizontal) {
      return
    }
    movingIndex.value = index
  }
  // 像素转 rpx；已滑开的条目以打开位置为基准
  const base = openIndex.value === index ? -150 : 0
  const offsetRpx = (dx * 750) / windowWidth.value + base
  offsets.value[index] = Math.round(Math.min(0, Math.max(-150, offsetRpx)))
}

// 松手：超过一半吸附为打开，否则收回
const handleTouchEnd = (_e: TouchEvent, index: number) => {
  if (!touchState.horizontal) {
    return
  }
  movingIndex.value = -1
  const offset = offsets.value[index] || 0
  if (offset < -75) {
    offsets.value[index] = -150
    openIndex.value = index
  } else {
    offsets.value[index] = 0
    if (openIndex.value === index) {
      openIndex.value = -1
    }
  }
}

// ===== 事件处理方法 =====
// 图片加载完成
const handleImageLoad = (index: number) => {
  cartList.value[index].loaded = 'loaded'
}

// 图片加载失败
const handleImageError = (index: number) => {
  cartList.value[index].productPic = '/static/errorImage.jpg'
}

// 跳转登录
const handleNavToLogin = () => {
  uni.navigateTo({
    url: '/pages/public/login',
  })
}

// 选择商品
const handleCheck = (index: number) => {
  cartList.value[index].checked = !cartList.value[index].checked
  calcTotal()
}

// 全选商品
const handleCheckAll = () => {
  const checked = !allChecked.value
  cartList.value.forEach((item) => {
    item.checked = checked
  })
  allChecked.value = checked
  calcTotal()
}

// 修改商品数量
const handleNumberChange = async (data: { number: number; index: number }) => {
  const cartItem = cartList.value[data.index]
  try {
    await updateCartQuantityAPI({ id: cartItem.id, quantity: data.number })
    cartItem.quantity = data.number
    calcTotal()
  } catch (e) {
    console.error('更新数量失败', e)
  }
}

// 删除商品（左滑露出的删除按钮）
const handleDeleteCartItem = async (index: number) => {
  const row = cartList.value[index]
  try {
    await deleteCartAPI({ ids: row.id })
    cartList.value.splice(index, 1)
    offsets.value = {}
    openIndex.value = -1
    calcTotal()
  } catch (e) {
    console.error('删除失败', e)
  }
}

// 清空选中的商品（导航栏右上角按钮）
const handleClearSelected = () => {
  if (checkedCount.value === 0) {
    uni.showToast({
      title: '请先选择要删除的商品',
      duration: 1000,
    })
    return
  }
  const ids = cartList.value
    .filter((item) => item.checked)
    .map((item) => item.id)
    .join(',')
  uni.showModal({
    content: `删除已选中的 ${checkedCount.value} 件商品？`,
    success: async (e) => {
      if (e.confirm) {
        try {
          await deleteCartAPI({ ids })
          cartList.value = removeCartItemsById(cartList.value, ids)
          offsets.value = {}
          openIndex.value = -1
          calcTotal()
        } catch (err) {
          console.error('删除失败', err)
        }
      }
    },
  })
}

// 跳转到商品详情
const handleNavToProductDetail = (productId: number) => {
  uni.navigateTo({
    url: `/pages/product/product?id=${productId}`,
  })
}

// 去结算
const handleCreateOrder = () => {
  const list = cartList.value
  const cartIds: string[] = []
  list.forEach((item) => {
    if (item.checked) {
      cartIds.push(item.id)
    }
  })
  if (cartIds.length === 0) {
    uni.showToast({
      title: '您还未选择要下单的商品！',
      duration: 1000,
    })
    return
  }
  uni.navigateTo({
    url: `/pages/order/createOrder?cartIds=${JSON.stringify(cartIds)}`,
  })
}

// ===== 其他方法 =====
// 计算商品总价
const calcTotal = () => {
  const list = cartList.value
  if (list.length === 0) {
    empty.value = true
    totalPrice.value = 0
    return
  }
  let sum = 0
  let checked = true
  list.forEach((item) => {
    const price = item.price || 0
    const quantity = item.quantity || 0
    if (item.checked === true) {
      sum += price * quantity
    } else if (checked === true) {
      checked = false
    }
  })
  allChecked.value = checked
  totalPrice.value = Number(sum.toFixed(2))
}
</script>

<style lang="scss" scoped>
.container {
  padding-bottom: 134rpx;

  .empty {
    position: fixed;
    left: 0;
    top: 0;
    width: 100%;
    height: 100vh;
    padding-bottom: 100rpx;
    display: flex;
    justify-content: center;
    flex-direction: column;
    align-items: center;
    background: #fff;

    image {
      width: 240rpx;
      height: 160rpx;
      margin-bottom: 30rpx;
    }

    .empty-tips {
      display: flex;
      font-size: 26rpx;
      color: #c0c4cc;

      .navigator {
        color: #fa436a;
        margin-left: 16rpx;
      }
    }
  }
}

// 自定义导航栏
.nav-bar {
  position: fixed;
  left: 0;
  top: 0;
  width: 100%;
  z-index: 99;
  background: #fff;

  .nav-content {
    position: relative;
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;

    .nav-title {
      font-size: 32rpx;
      font-weight: bold;
      color: #303133;
    }

    .nav-clear {
      position: absolute;
      font-size: 28rpx;
      color: #fa436a;

      &.disabled {
        color: #c0c4cc;
      }
    }
  }
}

// 左滑删除
.swipe-item {
  position: relative;
  overflow: hidden;

  .swipe-content {
    position: relative;
    z-index: 2;
    background: #fff;
    transition: transform 0.25s ease;

    &.no-anim {
      transition: none;
    }
  }

  .swipe-del {
    position: absolute;
    right: 0;
    top: 0;
    bottom: 0;
    width: 150rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 30rpx;
    color: #fff;
    background: #fa436a;
  }
}

.cart-item {
  display: flex;
  position: relative;
  padding: 30rpx 40rpx;

  .image-wrapper {
    width: 230rpx;
    height: 230rpx;
    flex-shrink: 0;
    position: relative;

    image {
      width: 100%;
      height: 100%;
      border-radius: 8rpx;
    }
  }

  .checkbox {
    position: absolute;
    left: -16rpx;
    top: -16rpx;
    z-index: 8;
    font-size: 44rpx;
    line-height: 1;
    padding: 4rpx;
    color: #c0c4cc;
    background: #fff;
    border-radius: 50px;
  }

  .item-right {
    display: flex;
    flex-direction: column;
    flex: 1;
    overflow: hidden;
    position: relative;
    padding-left: 30rpx;

    .title,
    .price {
      font-size: 30rpx;
      color: #303133;
      height: 40rpx;
      line-height: 40rpx;
    }

    .attr {
      font-size: 26rpx;
      color: #909399;
      height: 50rpx;
      line-height: 50rpx;
    }

    .price {
      height: 50rpx;
      line-height: 50rpx;
    }
  }
}

.action-section {
  /* #ifdef H5 */
  margin-bottom: 100rpx;
  /* #endif */
  position: fixed;
  left: 30rpx;
  bottom: calc(30rpx + env(safe-area-inset-bottom));
  z-index: 95;
  display: flex;
  align-items: center;
  width: 690rpx;
  height: 100rpx;
  padding: 0 30rpx;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 20rpx 0 rgba(0, 0, 0, 0.5);
  border-radius: 16rpx;

  .checkbox {
    display: flex;
    align-items: center;
    height: 52rpx;
    position: relative;

    image {
      width: 52rpx;
      height: 100%;
      position: relative;
      z-index: 5;
    }

    .all-text {
      margin-left: 12rpx;
      font-size: 28rpx;
      color: #303133;
    }
  }

  .total-box {
    flex: 1;
    display: flex;
    justify-content: flex-end;
    align-items: center;
    padding-right: 40rpx;

    .total-label {
      font-size: 28rpx;
      color: #303133;
    }

    .price {
      font-size: 32rpx;
      color: #303133;
      margin-left: 8rpx;
    }
  }

  .confirm-btn {
    padding: 0 38rpx;
    margin: 0;
    border-radius: 100px;
    height: 76rpx;
    line-height: 76rpx;
    font-size: 30rpx;
    background: #fa436a;
    box-shadow: 1px 2px 5px rgba(217, 60, 93, 0.72);
  }
}

.action-section .checkbox.checked,
.cart-item .checkbox.checked {
  color: #fa436a;
}
</style>
