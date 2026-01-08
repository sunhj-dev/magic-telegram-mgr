<template>
  <div>
    <el-steps :active="step - 1" finish-status="success" simple style="margin-bottom: 16px;">
      <el-step title="API配置" />
      <el-step title="手机号" />
      <el-step title="验证码" />
      <el-step title="密码" />
    </el-steps>

    <div v-if="step === 1">
      <el-form label-width="120px">
        <el-form-item label="手机号">
          <el-input v-model="form.phoneNumber" placeholder="如 +8613812345678" />
        </el-form-item>
        <el-form-item label="App ID">
          <el-input v-model="form.appId" />
        </el-form-item>
        <el-form-item label="App Hash">
          <el-input v-model="form.appHash" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitApi">下一步</el-button>
          <el-button @click="resetSession">重置 Session</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-else-if="step === 2">
      <el-form label-width="120px">
        <el-form-item label="手机号">
          <el-input v-model="form.phoneNumber" disabled />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="sendCode">发送验证码</el-button>
          <el-button @click="step = 1">上一步</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-else-if="step === 3">
      <el-form label-width="120px">
        <el-form-item label="验证码">
          <el-input v-model="form.code" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitCode">验证</el-button>
          <el-button @click="step = 2">上一步</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-else-if="step === 4">
      <el-form label-width="120px">
        <el-form-item label="二级密码">
          <el-input v-model="form.password" type="password" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitPassword">完成验证</el-button>
          <el-button @click="finishWithoutPassword">跳过</el-button>
          <el-button @click="step = 3">上一步</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script>
import api from '@/services/api';

export default {
  name: 'AuthModalForm',
  data() {
    return {
      step: 1,
      form: {
        phoneNumber: '',
        appId: '',
        appHash: '',
        code: '',
        password: ''
      }
    };
  },
  methods: {
    async submitApi() {
      if (!this.form.phoneNumber || !this.form.appId || !this.form.appHash) {
        this.$message.error('请填写完整信息');
        return;
      }
      try {
        // 创建账号
        await api.accounts.create({ phoneNumber: this.form.phoneNumber });
      } catch (e) {
        // 忽略已存在错误
      }
      const res = await api.telegram.config({
        phoneNumber: this.form.phoneNumber,
        appId: parseInt(this.form.appId, 10),
        appHash: this.form.appHash
      });
      if (res.success) {
        this.$message.success('API 配置成功');
        this.step = 2;
      } else {
        this.$message.error(res.message || '配置失败');
      }
    },
    async resetSession() {
      if (!this.form.phoneNumber) {
        this.$message.error('请先填写手机号');
        return;
      }
      const res = await api.telegram.resetSession(this.form.phoneNumber);
      if (res.success) {
        this.$message.success('Session 已重置');
        this.step = 1;
      } else {
        this.$message.error(res.message || '重置失败');
      }
    },
    async sendCode() {
      const res = await api.telegram.sendCode(this.form.phoneNumber);
      if (res.success) {
        this.$message.success('验证码已发送');
        this.step = 3;
      } else {
        this.$message.error(res.message || '发送失败');
      }
    },
    async submitCode() {
      const res = await api.telegram.checkCode({
        phoneNumber: this.form.phoneNumber,
        code: this.form.code
      });
      if (res.success) {
        if (res.needPassword) {
          this.$message.info('需要输入二级密码');
          this.step = 4;
        } else {
          this.$message.success('验证成功');
          this.$emit('success');
        }
      } else {
        this.$message.error(res.message || '验证失败');
      }
    },
    async submitPassword() {
      const res = await api.telegram.submitPassword({
        phoneNumber: this.form.phoneNumber,
        password: this.form.password
      });
      if (res.success) {
        this.$message.success('授权完成');
        this.$emit('success');
      } else {
        this.$message.error(res.message || '密码错误');
      }
    },
    finishWithoutPassword() {
      this.$message.info('已跳过二级密码');
      this.$emit('success');
    }
  }
};
</script>
