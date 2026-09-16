<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Tickets } from '@element-plus/icons-vue'
import { formatDateTime } from '@/utils/datetime'
import {
  createCommentReplayAPI,
  getCommentListAPI,
  getCommentReplayListAPI,
  updateCommentShowStatusAPI,
} from '@/apis/comment'
import type { CommentQueryParam, PmsComment, PmsCommentReplay } from '@/types/comment'

// 列表查询参数
const listQuery = ref<CommentQueryParam>({
  pageNum: 1,
  pageSize: 10,
})
// 列表数据
const list = ref<PmsComment[]>([])
// 总数
const total = ref(0)
// 加载状态
const listLoading = ref(false)
// 显示状态选项
const showStatusOptions = [
  {
    label: '显示',
    value: 1,
  },
  {
    label: '隐藏',
    value: 0,
  },
]

// 获取列表
const getList = async () => {
  listLoading.value = true
  const res = await getCommentListAPI(listQuery.value)
  listLoading.value = false
  list.value = res.data.list
  total.value = res.data.total
}

// 组件挂载后获取列表
onMounted(() => {
  getList()
})

// 重置搜索
const handleResetSearch = () => {
  listQuery.value = { pageNum: 1, pageSize: 10 }
  getList()
}

// 搜索列表
const handleSearchList = () => {
  listQuery.value.pageNum = 1
  getList()
}

// 回复弹窗
const replyDialogVisible = ref(false)
// 回复内容
const replyContent = ref('')
// 回复提交状态
const replySubmitting = ref(false)
// 当前操作的评价
const currentComment = ref<PmsComment | null>(null)
// 历史回复列表
const replayList = ref<PmsCommentReplay[]>([])

// 打开回复弹窗
const handleShowReply = async (row: PmsComment) => {
  currentComment.value = row
  replyContent.value = ''
  replyDialogVisible.value = true
  const res = await getCommentReplayListAPI(row.id)
  replayList.value = res.data
}

// 关闭回复弹窗
const handleReplyClose = () => {
  replyDialogVisible.value = false
  currentComment.value = null
  replayList.value = []
}

// 提交回复
const handleSubmitReply = async () => {
  if (!replyContent.value || replyContent.value.trim() === '') {
    ElMessage({
      message: '请输入回复内容',
      type: 'warning',
      duration: 1000,
    })
    return
  }
  if (!currentComment.value) {
    return
  }
  const replyParam = {
    commentId: currentComment.value.id,
    content: replyContent.value.trim(),
  }
  replySubmitting.value = true
  await createCommentReplayAPI(replyParam)
  replySubmitting.value = false
  handleReplyClose()
  getList()
  ElMessage({
    type: 'success',
    message: '回复成功!',
  })
}

// 修改评价显示状态
const handleUpdateShowStatus = async (row: PmsComment) => {
  const targetStatus = row.showStatus === 1 ? 0 : 1
  await ElMessageBox.confirm(`是否要将该评价设置为${targetStatus === 1 ? '显示' : '隐藏'}?`, '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  })
  await updateCommentShowStatusAPI(row.id, { showStatus: targetStatus })
  getList()
  ElMessage({
    type: 'success',
    message: '修改成功!',
  })
}

// 处理每页大小变化
const handleSizeChange = (val: number) => {
  listQuery.value.pageNum = 1
  listQuery.value.pageSize = val
  getList()
}

// 处理当前页变化
const handleCurrentChange = (val: number) => {
  listQuery.value.pageNum = val
  getList()
}
</script>

<template>
  <div class="app-container">
    <el-card class="filter-container" shadow="never">
      <div>
        <el-icon class="el-icon-middle">
          <Search />
        </el-icon>
        <span>筛选搜索</span>
        <el-button style="float:right" type="primary" @click="handleSearchList()">
          查询搜索
        </el-button>
        <el-button style="float:right;margin-right: 15px" @click="handleResetSearch()">
          重置
        </el-button>
      </div>
      <div style="margin-top: 20px">
        <el-form :inline="true" :model="listQuery" label-width="100px">
          <el-form-item label="商品名称：">
            <el-input v-model="listQuery.productKeyword" class="input-width" placeholder="商品名称关键字"></el-input>
          </el-form-item>
          <el-form-item label="会员昵称：">
            <el-input v-model="listQuery.memberNickName" class="input-width" placeholder="会员昵称"></el-input>
          </el-form-item>
          <el-form-item label="审核状态：">
            <el-select v-model="listQuery.showStatus" placeholder="全部" clearable class="input-width">
              <el-option v-for="item in showStatusOptions" :key="item.value" :label="item.label" :value="item.value">
              </el-option>
            </el-select>
          </el-form-item>
        </el-form>
      </div>
    </el-card>
    <el-card class="operate-container" shadow="never">
      <el-icon class="el-icon-middle">
        <Tickets />
      </el-icon>
      <span>数据列表</span>
    </el-card>
    <div class="table-container">
      <el-table :data="list" style="width: 100%;" v-loading="listLoading" border>
        <el-table-column label="编号" width="80" align="center">
          <template #default="scope">{{ scope.row.id }}</template>
        </el-table-column>
        <el-table-column label="商品名称" min-width="160" align="center">
          <template #default="scope">{{ scope.row.productName }}</template>
        </el-table-column>
        <el-table-column label="会员昵称" width="140" align="center">
          <template #default="scope">{{ scope.row.memberNickName || '-' }}</template>
        </el-table-column>
        <el-table-column label="星级" width="140" align="center">
          <template #default="scope">
            <el-rate :model-value="scope.row.star" disabled></el-rate>
          </template>
        </el-table-column>
        <el-table-column label="评价内容" min-width="220">
          <template #default="scope">
            <div class="comment-content">{{ scope.row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="评价时间" width="180" align="center">
          <template #default="scope">{{ formatDateTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="显示状态" width="100" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.showStatus === 1 ? 'success' : 'info'">
              {{ scope.row.showStatus === 1 ? '显示' : '隐藏' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回复数" width="80" align="center">
          <template #default="scope">{{ scope.row.replayCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="scope">
            <el-button size="small" :type="scope.row.showStatus === 1 ? 'danger' : 'success'"
              @click="handleUpdateShowStatus(scope.row)">
              {{ scope.row.showStatus === 1 ? '隐藏' : '显示' }}
            </el-button>
            <el-button size="small" @click="handleShowReply(scope.row)">回复</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span>暂无评价数据</span>
        </template>
      </el-table>
    </div>
    <div class="pagination-container">
      <el-pagination background @size-change="handleSizeChange" @current-change="handleCurrentChange"
        layout="total, sizes,prev, pager, next,jumper" v-model:current-page="listQuery.pageNum"
        :page-size="listQuery.pageSize" :page-sizes="[5, 10, 15]" :total="total">
      </el-pagination>
    </div>

    <el-dialog title="商家回复" v-model="replyDialogVisible" width="640px" @close="handleReplyClose">
      <div class="reply-block">
        <span class="reply-label">评价内容：</span>
        <div class="reply-text">{{ currentComment?.content }}</div>
      </div>
      <div class="reply-block">
        <span class="reply-label">历史回复：</span>
        <div v-if="replayList.length > 0" class="reply-list">
          <div v-for="item in replayList" :key="item.id" class="reply-item">
            <div class="reply-item-head">
              <el-tag size="small" :type="item.type === 1 ? 'success' : 'info'">
                {{ item.type === 1 ? '商家' : '会员' }}
              </el-tag>
              <span class="reply-item-name">{{ item.memberNickName }}</span>
              <span class="reply-item-time">{{ formatDateTime(item.createTime) }}</span>
            </div>
            <div class="reply-item-content">{{ item.content }}</div>
          </div>
        </div>
        <div v-else class="reply-empty">暂无回复</div>
      </div>
      <el-input v-model="replyContent" type="textarea" :rows="3" maxlength="200" show-word-limit
        placeholder="请输入回复内容"></el-input>
      <template #footer>
        <el-button @click="handleReplyClose()">取消</el-button>
        <el-button type="primary" :loading="replySubmitting" @click="handleSubmitReply()">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.input-width {
  width: 203px
}

.comment-content {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  text-align: left;
  line-height: 20px;
}

.reply-block {
  margin-bottom: 15px;
}

.reply-label {
  color: #606266;
  font-weight: bold;
}

.reply-text {
  margin-top: 8px;
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  line-height: 20px;
}

.reply-list {
  margin-top: 8px;
  max-height: 200px;
  overflow-y: auto;
}

.reply-item {
  padding: 8px 10px;
  margin-bottom: 8px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.reply-item-head {
  display: flex;
  align-items: center;
}

.reply-item-name {
  margin-left: 10px;
  color: #303133;
}

.reply-item-time {
  margin-left: 10px;
  color: #909399;
}

.reply-item-content {
  margin-top: 5px;
  line-height: 20px;
}

.reply-empty {
  margin-top: 8px;
  color: #909399;
}
</style>
