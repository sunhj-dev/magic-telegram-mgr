<template>
  <div>
    <div class="page-header">
      <span class="page-title">消息群发</span>
    </div>
    <div class="page-body">
      <div style="margin-bottom: 16px;">
        <el-button type="primary" @click="dialogVisible = true">新建群发任务</el-button>
      </div>

      <el-table :data="tasks" border stripe v-loading="loading">
      <el-table-column prop="taskName" label="任务名称" />
      <el-table-column label="类型" width="80">
        <template slot-scope="scope">
          {{ typeText(scope.row.messageType) }}
        </template>
      </el-table-column>
      <el-table-column label="目标数" width="80">
        <template slot-scope="scope">
          {{ (scope.row.targetChatIds || []).length }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template slot-scope="scope">
          <el-tag :type="statusType(scope.row)">
            {{ statusText(scope.row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="成功/失败" width="120">
        <template slot-scope="scope">
          {{ scope.row.successCount || 0 }}/{{ scope.row.failureCount || 0 }}
        </template>
      </el-table-column>
      <el-table-column label="下次执行" width="180">
        <template slot-scope="scope">
          {{ formatDate(scope.row.nextExecuteTime) }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template slot-scope="scope">
          {{ formatDate(scope.row.createdTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template slot-scope="scope">
          <el-button type="text" size="mini" @click="viewDetail(scope.row)">详情</el-button>
          <el-button
            v-if="canPause(scope.row)"
            type="text"
            size="mini"
            @click="pause(scope.row)"
          >暂停</el-button>
          <el-button
            v-if="['PAUSED','PENDING','FAILED'].includes(scope.row.status)"
            type="text"
            size="mini"
            @click="start(scope.row)"
          >启动</el-button>
          <el-button
            v-if="!['RUNNING','COMPLETED'].includes(scope.row.status)"
            type="text"
            size="mini"
            style="color: #f56c6c;"
            @click="remove(scope.row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          background
          layout="prev, pager, next, jumper"
          :page-size="pageSize"
          :total="total"
          :current-page.sync="page"
          @current-change="fetchTasks"
        />
      </div>
    </div>

    <el-dialog
      title="新建群发任务"
      :visible.sync="dialogVisible"
      width="650px"
      class="common-form-dialog"
    >
      <create-task-form @success="onCreateSuccess" />
    </el-dialog>

    <!-- 任务详情弹窗 -->
    <el-dialog
      title="📋 任务详情"
      :visible.sync="detailDialogVisible"
      width="800px"
      class="task-detail-dialog"
    >
      <div v-if="currentTaskDetail" class="task-detail-content">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="任务名称" :span="2">
            <span style="font-weight: 600; color: #303133; font-size: 16px;">
              {{ currentTaskDetail.task.taskName || '-' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="发送账号">
            <span style="color: #606266;">{{ currentTaskDetail.task.targetAccountPhone || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="任务状态">
            <el-tag :type="getStatusTagType(currentTaskDetail.task)" size="small">
              {{ statusText(currentTaskDetail.task) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="消息内容" :span="2">
            <div style="min-height: 80px; max-height: 200px; overflow-y: auto; padding: 8px 8px 8px 0; background: #f5f7fa; border-radius: 4px; white-space: pre-wrap; word-break: break-word; line-height: 1.6; text-align: left;">{{ currentTaskDetail.task.messageContent || '-' }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="目标Chat IDs" :span="2">
            <div style="max-height: 150px; overflow-y: auto; background: #f5f7fa; padding: 8px; border-radius: 4px;">
              <div
                v-for="(chatId, index) in (currentTaskDetail.task.targetChatIds || [])"
                :key="index"
                style="padding: 2px 0; color: #606266; font-family: monospace; font-size: 13px; line-height: 1.4;"
              >
                {{ chatId }}
              </div>
              <div v-if="!currentTaskDetail.task.targetChatIds || currentTaskDetail.task.targetChatIds.length === 0" style="color: #909399; font-size: 14px;">
                暂无目标
              </div>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="Cron表达式">
            <code style="background: #f5f7fa; padding: 3px 6px; border-radius: 3px; font-size: 13px;">
              {{ currentTaskDetail.task.cronExpression || '立即执行' }}
            </code>
          </el-descriptions-item>
          <el-descriptions-item label="成功/失败数量">
            <span style="color: #67C23A; font-weight: 500;">{{ currentTaskDetail.task.successCount || 0 }}</span>
            <span style="color: #909399; margin: 0 4px;">/</span>
            <span style="color: #F56C6C; font-weight: 500;">{{ currentTaskDetail.task.failureCount || 0 }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            <span style="color: #909399;">{{ formatDate(currentTaskDetail.task.createdTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最后执行时间">
            <span style="color: #909399;">{{ formatDate(currentTaskDetail.task.lastExecuteTime) || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="currentTaskDetail.task.errorMessage" label="错误信息" :span="2">
            <span style="color: #F56C6C;">{{ currentTaskDetail.task.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <div v-else style="text-align: center; padding: 40px;">
        <i class="el-icon-loading" style="font-size: 24px; color: #409EFF; animation: rotating 2s linear infinite;"></i>
        <p style="margin-top: 12px; color: #909399;">加载中...</p>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import api from '@/services/api';
import CreateTaskForm from '@/components/CreateTaskForm.vue';

export default {
  name: 'MassMessage',
  components: { CreateTaskForm },
  data() {
    return {
      loading: false,
      tasks: [],
      stats: {},
      page: 1,
      pageSize: 10,
      total: 0,
      dialogVisible: false,
      detailDialogVisible: false,
      currentTaskDetail: null,
      cards: [
        { key: 'total', label: '总任务数' },
        { key: 'running', label: '运行中' },
        { key: 'completed', label: '已完成' },
        { key: 'failed', label: '已失败' }
      ]
    };
  },
  created() {
    this.fetchTasks();
  },
  methods: {
    async fetchTasks(page = this.page) {
      this.loading = true;
      try {
        // 后端 MassMessageController 使用的是 1-based 页码（默认值 page=1），
        // 且内部会再做一次 page-1 转为 0-based，这里直接传 1,2,3... 避免出现 page<0 错误
        const res = await api.massMessage.getTasks({ page, size: this.pageSize });
        if (res.success) {
          this.tasks = res.data.content || [];
          this.stats = res.data.stats || {};
          this.total = res.data.totalElements || 0;
          this.page = page;
          this.$emit('update-running-tasks', this.stats.running || 0);
        }
      } catch (e) {
        this.$message.error('加载任务失败');
      } finally {
        this.loading = false;
      }
    },
    typeText(t) {
      const map = { TEXT: '文本', IMAGE: '图片', FILE: '文件' };
      return map[t] || '未知';
    },
    // 根据任务是否是定时任务，对 PENDING 做更细致的区分
    statusText(row) {
      const s = row.status;
      const map = {
        PENDING: '待处理',
        RUNNING: '运行中',
        COMPLETED: '已完成',
        FAILED: '已失败',
        PAUSED: '已暂停'
      };
      return map[s] || '未知';
    },
    statusType(row) {
      const s = row.status;
      if (s === 'PENDING' && row.cronExpression) {
        return 'info'; // 定时中的任务显示为信息色
      }
      const map = {
        PENDING: 'warning',
        RUNNING: 'primary',
        COMPLETED: 'success',
        FAILED: 'danger',
        PAUSED: 'info'
      };
      return map[s] || 'info';
    },
    // 前端“运行中”视角下可以暂停的条件：
    // 1) 真正 RUNNING 的任务
    // 2) 有 cron 表达式且状态为 PENDING（定时中），此时暂停意味着取消调度
    canPause(row) {
      if (!row) return false;
      if (row.status === 'RUNNING') return true;
      if (row.status === 'PENDING' && row.cronExpression) return true;
      return false;
    },
    formatDate(v) {
      if (!v) return '-';
      return new Date(v).toLocaleString();
    },
    async start(row) {
      await this.$confirm('确认启动此任务？', '提示', { type: 'warning' });
      const res = await api.massMessage.startTask(row.id);
      if (res.success) {
        this.$message.success('任务已启动');
        this.fetchTasks(this.page);
      }
    },
    async pause(row) {
      await this.$confirm('确认暂停此任务？', '提示', { type: 'warning' });
      const res = await api.massMessage.pauseTask(row.id);
      if (res.success) {
        this.$message.success('任务已暂停');
        this.fetchTasks(this.page);
      }
    },
    async remove(row) {
      await this.$confirm('确认删除此任务？此操作不可恢复！', '提示', { type: 'warning' });
      const res = await api.massMessage.deleteTask(row.id);
      if (res.success) {
        this.$message.success('任务已删除');
        this.fetchTasks(this.page);
      }
    },
    async viewDetail(row) {
      try {
        this.detailDialogVisible = true;
        this.currentTaskDetail = null; // 先清空，显示加载状态
        
        const res = await api.massMessage.getTaskDetail(row.id);
        if (!res.success) {
          this.$message.error(res.message || '获取详情失败');
          this.detailDialogVisible = false;
          return;
        }
        
        // 设置详情数据
        this.currentTaskDetail = {
          task: res.data.task || res.data,
          logs: res.data.logs || []
        };
      } catch (e) {
        this.$message.error('获取任务详情失败: ' + (e.message || '未知错误'));
        this.detailDialogVisible = false;
      }
    },
    getStatusTagType(task) {
      const s = task.status;
      if (s === 'PENDING' && task.cronExpression) {
        return 'info';
      }
      const map = {
        RUNNING: 'primary',
        COMPLETED: 'success',
        FAILED: 'danger',
        PAUSED: 'info'
      };
      return map[s] || 'warning';
    },
    onCreateSuccess() {
      this.dialogVisible = false;
      this.fetchTasks(1);
    }
  }
};
</script>

<style scoped>

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
}

.stat-content h3 {
  margin: 0 0 4px;
}

.stat-content p {
  margin: 0;
  color: #666;
  font-size: 13px;
}

.pagination-wrapper {
  margin-top: 16px;
  text-align: right;
}

.task-detail-content {
  padding: 5px 0;
}

.task-detail-dialog .el-dialog__body {
  padding: 15px 20px;
}

.task-detail-dialog .el-descriptions {
  margin-bottom: 0;
}

.task-detail-dialog .el-descriptions__label {
  font-weight: 600;
  color: #606266;
  font-size: 15px;
}

.task-detail-dialog .el-descriptions__content {
  font-size: 15px;
  text-align: left;
}

.task-detail-dialog .el-descriptions-item {
  padding-bottom: 8px;
}

.task-detail-dialog .el-descriptions-item:last-child {
  padding-bottom: 0;
}

@keyframes rotating {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
</style>
