<template>
  <div>
    <div class="page-header">
      <h2>消息群发</h2>
      <el-button type="success" @click="dialogVisible = true">新建群发任务</el-button>
    </div>

<!--   s-->

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

    <el-dialog title="新建群发任务" :visible.sync="dialogVisible" width="650px">
      <create-task-form @success="onCreateSuccess" />
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
      const res = await api.massMessage.getTaskDetail(row.id);
      if (!res.success) {
        this.$message.error(res.message || '获取详情失败');
        return;
      }
      const task = res.data.task || res.data;
      const logs = res.data.logs || [];
      this.$alert(
        `<div style="max-height: 400px; overflow: auto; text-align:left;">
          <p><b>任务名称：</b>${task.taskName || '-'}</p>
          <p><b>发送账号：</b>${task.targetAccountPhone || '-'}</p>
          <p><b>消息类型：</b>${this.typeText(task.messageType)}</p>
          <p><b>状态：</b>${this.statusText(task.status)}</p>
          <p><b>目标数量：</b>${(task.targetChatIds || []).length}</p>
          <p><b>成功/失败：</b>${task.successCount || 0}/${task.failureCount || 0}</p>
          <p><b>创建时间：</b>${this.formatDate(task.createdTime)}</p>
          <p><b>最后执行时间：</b>${this.formatDate(task.lastExecuteTime)}</p>
          <hr />
          <p><b>日志条数：</b>${logs.length}</p>
        </div>`,
        '任务详情',
        { dangerouslyUseHTMLString: true }
      );
    },
    onCreateSuccess() {
      this.dialogVisible = false;
      this.fetchTasks(1);
    }
  }
};
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

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
</style>
