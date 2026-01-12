<template>
  <div id="app" class="layout">
    <el-container style="min-height: 100vh; flex-direction: column;">
      <el-header class="top-header">
        <div class="header-left">
          <div class="logo">
            <i class="el-icon-connection" style="font-size: 24px; color: #409EFF; margin-right: 8px;"></i>
            <span class="logo-text">TG 管理</span>
          </div>
        </div>
        <div class="header-right">
          <el-dropdown>
              <span class="el-dropdown-link">
                <i class="el-icon-user" style="margin-right: 4px; color: #ffffff;"></i>
                管理员<i class="el-icon-arrow-down el-icon--right" style="color: #ffffff;"></i>
              </span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item @click.native="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </el-header>

      <el-container style="flex: 1; overflow: hidden;">
        <el-aside width="220px" class="sidebar">
          <el-menu
            class="sidebar-menu"
            :default-active="activeMenu"
            @select="handleSelect"
          >
            <!-- <el-menu-item index="/dashboard">
              <span class="menu-icon">📊</span>
              <span class="menu-text">仪表盘</span>
            </el-menu-item> -->
            <el-menu-item index="/accounts">
              <span class="menu-icon">👥</span>
              <span class="menu-text">账号管理</span>
            </el-menu-item>
            <!-- <el-menu-item index="/messages">
              <span class="menu-icon">💬</span>
              <span class="menu-text">消息管理</span>
            </el-menu-item> -->
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
  display: flex;
  flex-direction: column;
}

.sidebar {
  background: #ffffff !important;
  color: #333;
  /* box-shadow: 2px 0 8px rgba(0, 0, 0, 0.05); */
  height: 100% !important;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 60px);
}

.sidebar-menu {
  border-right: none;
  background-color: #ffffff !important;
  flex: 1;
  min-height: 100%;
}

.sidebar-menu .el-menu-item {
  color: #606266 !important;
  display: flex;
  align-items: center;
  height: 48px;
  line-height: 48px;
}

.sidebar-menu .el-menu-item.is-active {
  background-color: #409EFF !important;
  color: #ffffff !important;
}

.sidebar-menu .el-menu-item.is-active .menu-icon,
.sidebar-menu .el-menu-item.is-active .menu-text {
  color: #ffffff !important;
}

/* 左侧菜单 hover 颜色 - 与激活状态保持一致 */
.sidebar-menu .el-menu-item:hover:not(.is-active),
.sidebar-menu .el-menu-item:focus:not(.is-active) {
  background-color: #409EFF !important;
  color: #ffffff !important;
}

.sidebar-menu .el-menu-item:hover:not(.is-active) .menu-icon,
.sidebar-menu .el-menu-item:hover:not(.is-active) .menu-text,
.sidebar-menu .el-menu-item:focus:not(.is-active) .menu-icon,
.sidebar-menu .el-menu-item:focus:not(.is-active) .menu-text {
  color: #ffffff !important;
}

.menu-icon {
  width: 20px;
  font-size: 18px;
}

.menu-text {
  margin-left: 8px;
  font-size: 14px;
}

.running-badge {
  float: right;
}

.top-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 30px;
  height: 60px;
  background: linear-gradient(to right, #fff, #005DE9);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  width: 100%;
  flex-shrink: 0;
  position: relative;
  z-index: 10;
  color: #ffffff;
}

.header-left {
  display: flex;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
  color: #409EFF;
}

.header-right {
  display: flex;
  align-items: center;
}

.el-dropdown-link {
  color: #ffffff;
  cursor: pointer;
  display: flex;
  align-items: center;
}

.el-dropdown-link:hover {
  color: #ffffff;
  opacity: 0.8;
}

.page-container {
  background: #f5f7fa;
  padding: 8px;
  height: 100%;
  overflow-y: auto;
}
</style>
