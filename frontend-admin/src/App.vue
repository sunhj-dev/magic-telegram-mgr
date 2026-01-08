<template>
  <div id="app" class="layout">
    <el-container style="min-height: 100vh;">
      <el-aside width="220px" class="sidebar">
        <div class="sidebar-header">
          <h2>✈️ TG 管理</h2>
        </div>
        <el-menu
          class="sidebar-menu"
          :default-active="activeMenu"
          @select="handleSelect"
        >
          <el-menu-item index="/dashboard">
            <span class="menu-icon">📊</span>
            <span class="menu-text">仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/accounts">
            <span class="menu-icon">👥</span>
            <span class="menu-text">账号管理</span>
          </el-menu-item>
          <el-menu-item index="/messages">
            <span class="menu-icon">💬</span>
            <span class="menu-text">消息管理</span>
          </el-menu-item>
          <el-menu-item index="/mass-message">
            <span class="menu-icon">📢</span>
            <span class="menu-text">消息群发</span>
            <el-badge
              v-if="runningTasks > 0"
              :value="runningTasks"
              class="running-badge"
              type="warning"
            />
          </el-menu-item>
          <el-menu-item index="/settings">
            <span class="menu-icon">⚙️</span>
            <span class="menu-text">系统设置</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-container>
        <el-header class="top-header">
          <div class="header-left">
            <h1 class="page-title">{{ pageTitle }}</h1>
          </div>
          <div class="header-right">
            <el-dropdown>
              <span class="el-dropdown-link">
                管理员<i class="el-icon-arrow-down el-icon--right"></i>
              </span>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item @click.native="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
          </div>
        </el-header>

        <el-main class="page-container">
          <router-view @update-running-tasks="runningTasks = $event" />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script>
export default {
  name: 'App',
  data() {
    return {
      runningTasks: 0
    };
  },
  computed: {
    activeMenu() {
      return this.$route.path;
    },
    pageTitle() {
      const map = {
        '/dashboard': '仪表盘',
        '/accounts': '账号管理',
        '/messages': '消息管理',
        '/mass-message': '消息群发',
        '/settings': '系统设置'
      };
      return map[this.$route.path] || 'Telegram 管理系统';
    }
  },
  methods: {
    handleSelect(path) {
      if (path !== this.$route.path) {
        this.$router.push(path);
      }
    },
    logout() {
      this.$confirm('确定要退出登录吗？', '提示', {
        type: 'warning'
      }).then(() => {
        // TODO: 清理本地登录状态
        window.location.reload();
      }).catch(() => {});
    }
  }
};
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
}

.layout {
  min-height: 100vh;
}

.sidebar {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
}

.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.sidebar-menu {
  border-right: none;
  background-color: transparent;
}

.sidebar-menu .el-menu-item {
  color: #fff !important;
  display: flex;
  align-items: center;
}

.sidebar-menu .el-menu-item.is-active {
  background-color: rgba(255, 255, 255, 0.2) !important;
}

.menu-icon {
  width: 20px;
}

.menu-text {
  margin-left: 8px;
}

.running-badge {
  float: right;
}

.top-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  margin: 0;
}

.page-container {
  background: #f5f5f5;
}
</style>
