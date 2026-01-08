<template>
  <div>
    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :md="6" v-for="card in cards" :key="card.key">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon">{{ card.icon }}</div>
          <div class="stat-content">
            <h3>{{ stats[card.key] || 0 }}</h3>
            <p>{{ card.label }}</p>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import api from '@/services/api';

export default {
  name: 'Dashboard',
  data() {
    return {
      stats: {},
      cards: [
        { key: 'totalAccounts', label: '总账号数', icon: '👥' },
        { key: 'activeAccounts', label: '活跃账号', icon: '✓' },
        { key: 'totalMessages', label: '总消息数', icon: '💬' },
        { key: 'todayMessages', label: '今日消息', icon: '📈' }
      ]
    };
  },
  created() {
    this.fetchStats();
  },
  methods: {
    async fetchStats() {
      try {
        const res = await api.dashboard.getStats();
        if (res.success) {
          this.stats = res.data || {};
        }
      } catch (e) {
        this.$message.error('加载仪表盘数据失败');
      }
    }
  }
};
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
}

.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
  font-size: 1.5rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
}

.stat-content h3 {
  margin: 0 0 4px;
}

.stat-content p {
  margin: 0;
  color: #666;
  font-size: 13px;
}
</style>
