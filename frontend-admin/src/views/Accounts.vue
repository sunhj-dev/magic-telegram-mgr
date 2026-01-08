<template>
  <div class="accounts-page">
    <div class="page-header">
      <h2>账号管理</h2>
      <el-button type="primary" @click="showAuthDialog">添加账号</el-button>
    </div>

    <el-table
      :data="accounts"
      border
      stripe
      style="width: 100%;"
      height="240"
    >
      <el-table-column prop="phoneNumber" label="手机号" />
      <el-table-column label="认证状态">
        <template slot-scope="scope">
          <el-tag :type="statusType(scope.row.authStatus)">
            {{ statusText(scope.row.authStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="活跃状态">
        <template slot-scope="scope">
          {{ scope.row.active ? '活跃' : '非活跃' }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间">
        <template slot-scope="scope">
          {{ formatDate(scope.row.createdAt) }}
        </template>
      </el-table-column>
    </el-table>

    <div class="summary-text">当前账号数：{{ accounts.length }}</div>

    <div class="pagination-wrapper">
      <el-pagination
        background
        layout="prev, pager, next, jumper"
        :page-size="pageSize"
        :total="total"
        :current-page.sync="page"
        @current-change="fetchAccounts"
      />
    </div>

    <el-dialog title="添加账号" :visible.sync="authVisible" width="520px">
      <auth-modal-form @success="onAuthSuccess" />
    </el-dialog>
  </div>
</template>

<script>
import api from '@/services/api';
import AuthModalForm from '@/components/AuthModalForm.vue';

export default {
  name: 'Accounts',
  components: { AuthModalForm },
  data() {
    return {
      accounts: [],
      loading: false,
      page: 1,
      pageSize: 10,
      total: 0,
      authVisible: false
    };
  },
  created() {
    this.fetchAccounts();
  },
  methods: {
    async fetchAccounts(page = this.page) {
      try {
        const res = await api.accounts.getList({ page: page - 1, size: this.pageSize });
        if (res.success) {
          this.accounts = res.data.content || [];
          this.total = res.data.totalElements || 0;
          this.page = page;
        }
      } catch (e) {
        this.$message.error('加载账号失败');
      }
    },
    showAuthDialog() {
      this.authVisible = true;
    },
    onAuthSuccess() {
      this.authVisible = false;
      this.$message.success('账号添加成功');
      this.fetchAccounts(1);
    },
    statusType(status) {
      const map = { READY: 'success', ACTIVE: 'success', INACTIVE: 'warning', BANNED: 'danger' };
      return map[status] || 'info';
    },
    statusText(status) {
      const map = { READY: '就绪', ACTIVE: '已认证', INACTIVE: '未认证', BANNED: '已封禁' };
      return map[status] || '未知';
    },
    formatDate(v) {
      if (!v) return '-';
      return new Date(v).toLocaleString();
    }
  }
};
</script>

<style scoped>
.accounts-page {
  padding-right: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.summary-text {
  margin: 12px 0;
  color: #666;
}

.pagination-wrapper {
  margin-top: 16px;
  text-align: right;
}
</style>
