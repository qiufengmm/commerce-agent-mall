<template>
  <view class="agent-page">
    <!-- 业务边界提示 -->
    <view class="agent-header">
      <view class="agent-header__main">
        <text class="agent-header__title">智能导购</text>
        <text class="agent-header__desc">只做商品查询与解释，不支持领券、加购、下单和支付</text>
      </view>
      <text class="agent-header__action" @click="handleClear">清空对话</text>
    </view>

    <scroll-view
      class="agent-scroll"
      scroll-y
      :scroll-into-view="scrollIntoView"
      scroll-with-animation
    >
      <view class="agent-body">
        <!-- 首次进入的空状态 -->
        <view v-if="!messages.length" class="agent-welcome">
          <text class="agent-welcome__title">您好，我是商城智能导购</text>
          <text class="agent-welcome__desc">
            可以帮您搜索商品、按预算筛选、比较 2 至 3 件商品、查看 SKU 库存；登录后还能解释您已领取且适用于该商品的优惠券。
          </text>
          <view class="agent-suggestions">
            <view
              v-for="item in WELCOME_QUESTIONS"
              :key="item"
              class="agent-suggestion"
              @click="handleSuggestion(item)"
            >
              {{ item }}
            </view>
          </view>
        </view>

        <!-- 登录回跳后恢复的最近一组商品 -->
        <view v-if="!messages.length && restoredProducts.length" class="agent-restored">
          <text class="agent-restored__title">上次查看的商品</text>
          <view class="agent-products">
            <view
              v-for="card in restoredProducts"
              :key="`restored-${card.id}`"
              class="agent-card"
              @click="handleOpenProduct(card)"
            >
              <image class="agent-card__pic" :src="resolveImageUrl(card.pic)" mode="aspectFill" />
              <view class="agent-card__info">
                <text class="agent-card__name">{{ card.name }}</text>
                <text v-if="card.subtitle" class="agent-card__subtitle">{{ card.subtitle }}</text>
                <view class="agent-card__row">
                  <text class="agent-card__price">{{ formatPrice(card.price) }}</text>
                  <text class="agent-card__stock">{{ formatStock(card) }}</text>
                </view>
              </view>
            </view>
          </view>
        </view>

        <view
          v-for="(message, index) in messages"
          :key="message.id"
          :id="`agent-message-${index}`"
          class="agent-message"
          :class="`agent-message--${message.role}`"
        >
          <view v-if="message.content" class="agent-bubble" :class="{ 'agent-bubble--error': message.failed }">
            <text class="agent-bubble__text">{{ message.content }}</text>
          </view>

          <view v-if="message.products && message.products.length" class="agent-products">
            <view
              v-for="card in message.products"
              :key="`${message.id}-${card.id}`"
              class="agent-card"
              @click="handleOpenProduct(card)"
            >
              <image class="agent-card__pic" :src="resolveImageUrl(card.pic)" mode="aspectFill" />
              <view class="agent-card__info">
                <text class="agent-card__name">{{ card.name }}</text>
                <text v-if="card.subtitle" class="agent-card__subtitle">{{ card.subtitle }}</text>
                <view class="agent-card__row">
                  <text class="agent-card__price">{{ formatPrice(card.price) }}</text>
                  <text class="agent-card__stock">{{ formatStock(card) }}</text>
                </view>
              </view>
            </view>
          </view>

          <view
            v-if="message.suggestedQuestions && message.suggestedQuestions.length"
            class="agent-suggestions"
          >
            <view
              v-for="item in message.suggestedQuestions"
              :key="`${message.id}-${item}`"
              class="agent-suggestion"
              @click="handleSuggestion(item)"
            >
              {{ item }}
            </view>
          </view>

          <view v-if="message.failed" class="agent-retry" @click="handleRetry">重新发送</view>
        </view>

        <view v-if="sending" class="agent-typing">
          <text>正在查询商城数据…</text>
        </view>

        <view id="agent-bottom" class="agent-bottom"></view>
      </view>
    </scroll-view>

    <view class="agent-input">
      <input
        v-model="draft"
        class="agent-input__field"
        :disabled="sending"
        :maxlength="1000"
        placeholder="例如：3000 元左右的手机"
        confirm-type="send"
        @confirm="handleSend"
      />
      <button class="agent-input__button" :disabled="sending || !draft.trim()" @click="handleSend">
        发送
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { chatWithAgentAPI, deleteAgentSessionAPI, getAgentSessionAPI } from '@/apis/agent'
import { useMemberStore } from '@/stores/member'
import { getAgentSessionId, resetAgentSessionId } from '@/utils/agentSession'
import { isAgentRequestError } from '@/utils/agentHttp'
import { resolveImageUrl } from '@/utils/image'
import { AGENT_CHAT_PAGE, buildLoginUrl } from '@/utils/navigation'
import type { AgentChatMessage, AgentProductCard } from '@/types/agent'

/** 首屏受控建议问题 */
const WELCOME_QUESTIONS = [
  '3000 元左右有哪些手机？',
  '比较一下前两款的价格和库存',
  '这个商品的 SKU 还有货吗？',
]

const PRODUCT_PATH_PATTERN = /^\/pages\/product\/product\?id=[1-9]\d*$/
const STOCK_LABELS: Record<string, string> = {
  IN_STOCK: '有货',
  LOW_STOCK: '库存紧张',
  OUT_OF_STOCK: '暂时缺货',
}

const memberStore = useMemberStore()

const sessionId = ref('')
const messages = ref<AgentChatMessage[]>([])
const restoredProducts = ref<AgentProductCard[]>([])
const draft = ref('')
const sending = ref(false)
const scrollIntoView = ref('')
/** 本次是否因为需要登录而跳转，用于登录回来后恢复会话 */
const pendingLogin = ref(false)

let messageSeed = 0
const nextMessageId = () => {
  messageSeed += 1
  return `agent-message-${Date.now()}-${messageSeed}`
}

const scrollToBottom = () => {
  nextTick(() => {
    scrollIntoView.value = ''
    setTimeout(() => {
      scrollIntoView.value = 'agent-bottom'
    }, 30)
  })
}

const formatPrice = (price?: string | null) => (price ? `¥${price}` : '价格待确认')

const formatStock = (card: AgentProductCard) => {
  const label = STOCK_LABELS[card.stockStatus] ?? '库存待确认'
  return `${label} · 可售 ${card.availableStock}`
}

/** 后端只返回 /pages/product/product?id=<id>，前端仍然复核一次再跳转 */
const handleOpenProduct = (card: AgentProductCard) => {
  const path = typeof card.detailPath === 'string' ? card.detailPath.trim() : ''
  if (!PRODUCT_PATH_PATTERN.test(path)) {
    uni.showToast({ icon: 'none', title: '商品链接不可用' })
    return
  }
  uni.navigateTo({ url: path })
}

const toLogin = () => {
  pendingLogin.value = true
  uni.showToast({ icon: 'none', title: '登录后可以查询本人优惠券' })
  setTimeout(() => {
    uni.navigateTo({ url: buildLoginUrl(AGENT_CHAT_PAGE) })
  }, 600)
}

const loadSession = async () => {
  if (!sessionId.value) return

  try {
    const response = await getAgentSessionAPI(sessionId.value)
    const data = response.data

    if (data.requiresLogin) {
      pendingLogin.value = true
      return
    }

    messages.value = (data.messages ?? []).map((item) => ({
      id: nextMessageId(),
      role: item.role,
      content: item.content,
    }))
    restoredProducts.value = data.products ?? []
    if (messages.value.length) {
      scrollToBottom()
    }
  } catch {
    uni.showToast({ icon: 'none', title: '会话恢复失败，可以直接继续提问' })
  }
}

const sendMessage = async (rawText: string) => {
  const text = rawText.trim()
  if (!text || sending.value) return

  messages.value = [...messages.value, { id: nextMessageId(), role: 'user', content: text }]
  restoredProducts.value = []
  draft.value = ''
  sending.value = true
  scrollToBottom()

  try {
    const response = await chatWithAgentAPI({ sessionId: sessionId.value, message: text })
    const data = response.data

    if (data.requiresLogin) {
      toLogin()
      return
    }

    messages.value = [
      ...messages.value,
      {
        id: nextMessageId(),
        role: 'assistant',
        content: data.answer,
        products: data.products ?? [],
        suggestedQuestions: data.suggestedQuestions ?? [],
      },
    ]
  } catch (error) {
    const retryable = !isAgentRequestError(error) || [0, 502, 503].includes(error.statusCode)
    const message =
      isAgentRequestError(error) && error.message
        ? error.message
        : '智能导购暂时不可用，请稍后再试'

    messages.value = [
      ...messages.value,
      {
        id: nextMessageId(),
        role: 'assistant',
        content: message,
        failed: retryable,
        retryText: retryable ? text : undefined,
      },
    ]
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

const handleSend = () => {
  void sendMessage(draft.value)
}

const handleSuggestion = (question: string) => {
  void sendMessage(question)
}

const handleRetry = () => {
  const failedIndex = messages.value.findIndex((item) => item.failed)
  if (failedIndex < 0) return

  const failedMessage = messages.value[failedIndex]
  messages.value = messages.value.filter((_, index) => index !== failedIndex)
  void sendMessage(failedMessage.retryText ?? '')
}

const handleClear = () => {
  if (sending.value) return

  uni.showModal({
    title: '清空对话',
    content: '清空后本地与服务端会话都会重置，是否继续？',
    success: async (result) => {
      if (!result.confirm) return
      try {
        await deleteAgentSessionAPI(sessionId.value)
      } catch {
        // 服务端清理失败时仍然重置本地会话，避免展示过期内容
      }
      messages.value = []
      restoredProducts.value = []
      sessionId.value = resetAgentSessionId()
      uni.showToast({ icon: 'none', title: '已清空对话' })
    },
  })
}

onLoad(() => {
  sessionId.value = getAgentSessionId()
  void loadSession()
})

onShow(() => {
  // 从登录页返回后重新拉取会话，登录前的提问不会丢失
  if (!pendingLogin.value || !memberStore.hasLogin) return
  pendingLogin.value = false
  void loadSession()
})
</script>

<style lang="scss" scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f6f7fb;
}

.agent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 30rpx;
  background: linear-gradient(135deg, #fa436a, #ff7f92);
  color: #fff;

  &__main {
    display: flex;
    flex-direction: column;
  }

  &__title {
    font-size: 34rpx;
    font-weight: 600;
  }

  &__desc {
    margin-top: 8rpx;
    font-size: 22rpx;
    opacity: 0.9;
  }

  &__action {
    flex-shrink: 0;
    padding: 10rpx 20rpx;
    font-size: 24rpx;
    border: 1rpx solid rgba(255, 255, 255, 0.7);
    border-radius: 30rpx;
  }
}

.agent-scroll {
  flex: 1;
  overflow: hidden;
}

.agent-body {
  padding: 24rpx 24rpx 12rpx;
}

.agent-welcome {
  padding: 40rpx 30rpx;
  background: #fff;
  border-radius: 20rpx;

  &__title {
    display: block;
    font-size: 32rpx;
    font-weight: 600;
    color: $font-color-dark;
  }

  &__desc {
    display: block;
    margin-top: 16rpx;
    font-size: 26rpx;
    line-height: 1.6;
    color: $font-color-base;
  }
}

.agent-restored {
  margin-bottom: 24rpx;

  &__title {
    display: block;
    margin-bottom: 16rpx;
    font-size: 26rpx;
    color: $font-color-base;
  }
}

.agent-message {
  display: flex;
  flex-direction: column;
  margin-bottom: 28rpx;

  &--user {
    align-items: flex-end;
  }

  &--assistant {
    align-items: flex-start;
  }
}

.agent-bubble {
  max-width: 560rpx;
  padding: 22rpx 26rpx;
  background: #fff;
  border-radius: 18rpx;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.04);

  &__text {
    font-size: 28rpx;
    line-height: 1.6;
    color: $font-color-dark;
    word-break: break-all;
    white-space: pre-wrap;
  }

  &--error {
    border: 1rpx solid #ffd7dd;
    background: #fff5f6;
  }

  .agent-message--user & {
    background: #fa436a;
    color: #fff;

    .agent-bubble__text {
      color: #fff;
    }
  }
}

.agent-products {
  width: 100%;
  margin-top: 18rpx;
}

.agent-card {
  display: flex;
  padding: 20rpx;
  margin-bottom: 18rpx;
  background: #fff;
  border-radius: 18rpx;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.04);

  &__pic {
    flex-shrink: 0;
    width: 160rpx;
    height: 160rpx;
    border-radius: 14rpx;
    background: #f2f3f7;
  }

  &__info {
    display: flex;
    flex: 1;
    flex-direction: column;
    justify-content: space-between;
    margin-left: 20rpx;
    overflow: hidden;
  }

  &__name {
    font-size: 28rpx;
    font-weight: 600;
    color: $font-color-dark;
  }

  &__subtitle {
    margin-top: 6rpx;
    font-size: 24rpx;
    color: $font-color-base;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 12rpx;
  }

  &__price {
    font-size: 32rpx;
    font-weight: 600;
    color: #fa436a;
  }

  &__stock {
    font-size: 22rpx;
    color: $font-color-base;
  }
}

.agent-suggestions {
  display: flex;
  flex-wrap: wrap;
  margin-top: 16rpx;
}

.agent-suggestion {
  margin: 0 16rpx 16rpx 0;
  padding: 12rpx 22rpx;
  font-size: 24rpx;
  color: #fa436a;
  background: #fff;
  border: 1rpx solid rgba(250, 67, 106, 0.3);
  border-radius: 30rpx;
}

.agent-retry {
  margin-top: 12rpx;
  padding: 10rpx 24rpx;
  font-size: 24rpx;
  color: #fff;
  background: #fa436a;
  border-radius: 30rpx;
}

.agent-typing {
  padding: 16rpx 0;
  font-size: 24rpx;
  color: $font-color-base;
}

.agent-bottom {
  height: 20rpx;
}

.agent-input {
  display: flex;
  align-items: center;
  padding: 18rpx 24rpx;
  background: #fff;
  border-top: 1rpx solid #eee;

  &__field {
    flex: 1;
    height: 76rpx;
    padding: 0 24rpx;
    font-size: 28rpx;
    background: #f5f6fa;
    border-radius: 38rpx;
  }

  &__button {
    width: 150rpx;
    height: 76rpx;
    margin-left: 18rpx;
    font-size: 28rpx;
    line-height: 76rpx;
    color: #fff;
    background: #fa436a;
    border-radius: 38rpx;

    &[disabled] {
      opacity: 0.5;
    }
  }
}
</style>
