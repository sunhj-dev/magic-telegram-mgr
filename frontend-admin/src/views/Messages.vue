<template>
  <div>
    <div class="page-header">
      <h2>消息管理</h2>
      <div>
        <el-input
          v-model="search"
          placeholder="搜索消息..."
          size="small"
          style="width: 260px; margin-right: 8px;"
          @keyup.enter.native="fetchMessages(1)"
        />
        <el-button type="primary" size="small" @click="fetchMessages(1)">搜索</el-button>
      </div>
    </div>

    <el-table :data="messages" border stripe v-loading="loading">
      <el-table-column prop="messageId" label="消息ID" width="120" />
      <el-table-column prop="chatTitle" label="群组" />
      <el-table-column prop="senderName" label="发送者" />
      <el-table-column label="内容">
        <template slot-scope="scope">
          {{ truncate(scope.row.textContent, 50) }}
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template slot-scope="scope">
          <el-tag size="small">{{ typeText(scope.row.messageType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="messageTime" label="时间" width="180">
        <template slot-scope="scope">
          {{ formatDate(scope.row.messageTime) }}
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
        @current-change="fetchMessages"
      />
    </div>
  </div>
</template>

<script>
import api from '@/services/api';

export default {
  name: 'Messages',
  data() {
    return {
      loading: false,
      messages: [],
      search: '',
      page: 1,
      pageSize: 10,
      total: 0
    };
  },
  created() {
    this.fetchMessages();
  },
  methods: {
    async fetchMessages(page = this.page) {
      this.loading = true;
      try {
        const res = await api.messages.getList({
          page: page - 1,
          size: this.pageSize,
          search: this.search,
          type: '',
          date: ''
        });
        if (res.success) {
          this.messages = res.data.content || [];
          this.total = res.data.totalElements || 0;
          this.page = page;
        }
      } catch (e) {
        this.$message.error('加载消息失败');
      } finally {
        this.loading = false;
      }
    },
    truncate(text, len) {
      if (!text) return '';
      return text.length > len ? text.slice(0, len) + '...' : text;
    },
    typeText(t) {
      const map = { TEXT: '文本', PHOTO: '图片', VIDEO: '视频' };
      return map[t] || '其他';
    },
    formatDate(v) {
      if (!v) return '-';
      return new Date(v).toLocaleString();
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

.pagination-wrapper {
  margin-top: 16px;
  text-align: right;
}
</style>
