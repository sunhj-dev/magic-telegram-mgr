<template>
  <div>
    <el-steps :active="step - 1" finish-status="success" simple style="margin-bottom: 16px;">
      <el-step title="基本信息" />
      <el-step title="目标配置" />
    </el-steps>

    <div v-if="step === 1">
      <el-form label-width="120px">
        <el-form-item label="发送账号">
          <el-select v-model="form.targetAccountPhone" placeholder="请选择账号">
            <el-option
              v-for="acc in accounts"
              :key="acc.phoneNumber"
              :label="acc.phoneNumber + '（已认证）'"
              :value="acc.phoneNumber"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="任务名称">
          <el-input v-model="form.taskName" maxlength="50" />
        </el-form-item>
        <el-form-item label="消息内容">
          <el-input
            type="textarea"
            v-model="form.messageContent"
            :rows="6"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="nextStep">下一步</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-else>
      <el-form label-width="120px">
        <el-form-item label="目标 Chat ID">
          <el-input
            type="textarea"
            v-model="targetsText"
            :rows="8"
            placeholder="每行一个 Chat ID"
          />
          <div style="margin-top: 4px; font-size: 12px; color:#666;">共 {{ targetCount }} 个目标</div>
        </el-form-item>
        <el-form-item label="Cron 表达式">
          <el-input v-model="form.cronExpression" placeholder="留空则立即执行" />
        </el-form-item>
        <el-form-item>
          <el-button @click="step = 1">上一步</el-button>
          <el-button type="success" @click="submit">创建任务</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script>
import api from '@/services/api';

export default {
  name: 'CreateTaskForm',
  data() {
    return {
      step: 1,
      accounts: [],
      form: {
        targetAccountPhone: '',
        taskName: '',
        messageContent: '',
        cronExpression: ''
      },
      targetsText: ''
    };
  },
  computed: {
    targetCount() {
      return this.targetsText
        .split('\n')
        .map(v => v.trim())
        .filter(Boolean).length;
    }
  },
  created() {
    this.loadAccounts();
  },
  methods: {
    async loadAccounts() {
      const res = await api.accounts.getList({ page: 0, size: 100 });
      if (res.success) {
        this.accounts = res.data.content || [];
      }
    },
    nextStep() {
      if (!this.form.targetAccountPhone || !this.form.taskName || !this.form.messageContent) {
        this.$message.error('请填写完整信息');
        return;
      }
      this.step = 2;
    },
    async submit() {
      const targetChatIds = this.targetsText
        .split('\n')
        .map(v => v.trim())
        .filter(Boolean);
      if (targetChatIds.length === 0) {
        this.$message.error('请输入至少一个目标 Chat ID');
        return;
      }
      const payload = {
        taskName: this.form.taskName,
        messageContent: this.form.messageContent,
        targetChatIds,
        messageType: 'TEXT',
        cronExpression: this.form.cronExpression || null,
        targetAccountPhone: this.form.targetAccountPhone
      };
      const res = await api.massMessage.createTask(payload);
      if (res.success) {
        this.$message.success('任务创建成功');
        this.$emit('success');
      } else {
        this.$message.error(res.message || '创建失败');
      }
    }
  }
};
</script>
