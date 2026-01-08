<template>
  <div>
    <h2 style="margin-bottom: 16px;">系统设置</h2>
    <el-card>
      <el-form label-width="140px">
        <el-form-item label="自动清理过期消息">
          <el-switch v-model="autoCleanup" />
        </el-form-item>
        <el-form-item label="消息保留天数">
          <el-input-number v-model="retentionDays" :min="1" :max="365" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="save">保存设置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'Settings',
  data() {
    return {
      autoCleanup: false,
      retentionDays: 30
    };
  },
  methods: {
    save() {
      this.$message.success('设置已保存（示例，本地状态）');
    }
  }
};
</script>
