# Magic Telegram Admin 前端工程

基于 **Vue 2 + Element UI + Webpack** 的前端管理后台，用于替换 `src/main/resources/static/admin` 下的纯静态页面。

## 目录结构

- `frontend-admin/`
  - `src/`
    - `main.js`：入口文件
    - `App.vue`：整体布局（侧边栏 + 顶部导航 + 内容区）
    - `router/`：路由配置，对应原来的仪表盘/账号管理/消息管理/消息群发/系统设置
    - `views/`：各业务页面组件
    - `components/`：复用组件（账号授权弹窗、群发任务表单等）
    - `services/api.js`：对接当前后端接口的封装（通过 `/api` 代理转发）
  - `build/webpack.*.js`：Webpack 打包配置
  - `public/index.html`：HTML 模板

## 启动方式

1. 进入前端目录并安装依赖：

```bash
cd frontend-admin
npm install
```

2. 启动开发环境（默认端口 `8080`，通过 devServer 代理访问后端 `http://localhost:8090`）：

```bash
npm run dev
```

3. 生产构建：

```bash
npm run build
```

构建产物会输出到 `frontend-admin/dist` 目录，后续可以根据需要集成到 Spring Boot 静态资源目录或通过 Nginx 独立部署。
