/**
 * Telegram管理系统主要逻辑 - 完整修复版
 * @author sunhj
 * @date 2025-12-30
 */

// 全局应用对象
const TelegramAdmin = {
    currentPage: 'dashboard',
    components: {},
    cache: {},

    // 初始化应用
    init() {
        console.log('TelegramAdmin 初始化开始');

        // 确保依赖已加载
        if (!window.Components || !window.API || !window.Utils) {
            console.error('核心依赖未加载！顺序：utils.js -> api.js -> components.js -> admin.js');
            return;
        }

        this.components.authModal = new AuthModal();
        this.components.createTaskModal = new CreateTaskModal();
        this.initSidebar();

        // 延迟加载首页面
        setTimeout(() => {
            console.log('开始加载dashboard页面...');
            this.currentPage = null;
            this.loadPage('dashboard');
        }, 100);

        this.startAutoRefresh();
        console.log('TelegramAdmin 初始化完成');
    },

    // 初始化侧边栏
    initSidebar() {
        const menuItems = document.querySelectorAll('.menu-item');
        menuItems.forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                menuItems.forEach(mi => mi.classList.remove('active'));
                item.classList.add('active');
                this.loadPage(item.dataset.page);
            });
        });

        // 侧边栏折叠
        const toggle = document.querySelector('.sidebar-toggle');
        if (toggle) {
            toggle.addEventListener('click', () => {
                document.querySelector('.sidebar').classList.toggle('collapsed');
            });
        }

        // 退出登录
        const logoutBtn = document.querySelector('.logout-btn');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', () => this.logout());
        }
    },

    // 加载页面（核心修复：无container参数）
    async loadPage(page) {
        if (this.currentPage === page) return;

        this.currentPage = page;
        this.updatePageTitle(page);

        // 隐藏所有页面
        document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
        const targetPage = document.getElementById(`${page}-page`);
        if (targetPage) {
            targetPage.classList.add('active');
        }

        try {
            // 根据页面类型调用对应方法
            const loadMethod = {
                'dashboard': () => this.loadDashboardStats(),
                'accounts': () => this.loadAccounts(),
                'messages': () => this.loadMessages(),
                'mass-message': () => this.loadMassMessage(),
                'settings': () => this.bindSettingsEvents()
            };

            const method = loadMethod[page];
            if (method) await method();
        } catch (error) {
            console.error(`加载页面 ${page} 失败:`, error);
            Utils.showNotification(`页面加载失败: ${error.message}`, 'error');
        }
    },

    // 更新页面标题
    updatePageTitle(page) {
        const title = {
            'dashboard': '仪表盘',
            'accounts': '账号管理',
            'messages': '消息管理',
            'mass-message': '消息群发',
            'settings': '系统设置'
        };
        const el = document.querySelector('.page-title');
        if (el) el.textContent = title[page] || '未知页面';
    },

    // 退出登录
    logout() {
        Components.Modal.confirm('确定要退出登录吗？', () => {
            Utils.removeStorage('admin_token');
            location.reload();
        });
    },

    // ✅ 加载仪表盘
    async loadDashboardStats() {
        try {
            const res = await API.dashboard.getStats();
            if (res.success) {
                const data = res.data;
                document.getElementById('total-accounts').textContent = data.totalAccounts || 0;
                document.getElementById('active-accounts').textContent = data.activeAccounts || 0;
                document.getElementById('total-messages').textContent = data.totalMessages || 0;
                document.getElementById('today-messages').textContent = data.todayMessages || 0;
            }
        } catch (e) {
            console.error('仪表盘加载失败:', e);
        }
    },

    // ✅ 加载账号管理
    async loadAccounts() {
        const btn = document.getElementById('add-account-btn');
        if (btn) btn.onclick = () => this.showAddAccountAuth();

        await this.loadAccountsData();
    },

    // ✅ 账号数据加载
    async loadAccountsData() {
        const container = document.getElementById('accounts-table');
        if (!container) return;

        try {
            container.innerHTML = '<div class="loading-message">加载中...</div>';
            const res = await API.accounts.getList({page: 0, size: 10});

            if (res.success && res.data?.content) {
                const accounts = res.data.content;

                // 渲染表格
                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>手机号</th><th>认证状态</th><th>活跃状态</th>
                                <th>创建时间</th><th>操作</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${accounts.map(acc => `
                                <tr>
                                    <td>${acc.phoneNumber || acc.phone || '-'}</td>
                                    <td>${this.getStatusBadge(acc.authStatus)}</td>
                                    <td>${acc.active ? '活跃' : '非活跃'}</td>
                                    <td>${Utils.formatDateTime(acc.createdAt)}</td>
                                    <td>
                                        <button class="btn btn-sm btn-primary" onclick="TelegramAdmin.editAccount('${acc.id}')">
                                            <i class="fas fa-edit"></i>
                                        </button>
                                        <button class="btn btn-sm btn-danger" onclick="TelegramAdmin.deleteAccount('${acc.id}')">
                                            <i class="fas fa-trash"></i>
                                        </button>
                                    </td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            }
        } catch (e) {
            console.error('账号加载失败:', e);
            container.innerHTML = '<div class="error-message">加载失败</div>';
        }
    },

    // ✅ 显示添加账号弹窗（修复弹窗问题）
    showAddAccountAuth() {
        if (!this.components.authModal) {
            this.components.authModal = new AuthModal();
        }
        this.components.authModal.show(
            () => {
                Utils.showNotification('账号添加成功！', 'success');
                this.loadAccountsData();
            },
            () => Utils.showNotification('已取消', 'info')
        );
    },

    // 获取状态徽章
    getStatusBadge(status) {
        const map = {
            'READY': '<span class="badge badge-success">就绪</span>',
            'ACTIVE': '<span class="badge badge-success">已认证</span>',
            'INACTIVE': '<span class="badge badge-warning">未认证</span>',
            'BANNED': '<span class="badge badge-danger">已封禁</span>'
        };
        return map[status] || '<span class="badge">未知</span>';
    },

    // ✅ 加载消息管理
    async loadMessages() {
        const searchInput = document.getElementById('message-search');
        if (searchInput) {
            searchInput.oninput = () => {
                clearTimeout(this.searchTimeout);
                this.searchTimeout = setTimeout(() => this.loadMessagesData(), 500);
            };
        }

        await this.loadMessagesData();
    },

    // ✅ 消息数据加载（修复列表问题）
    async loadMessagesData() {
        const container = document.getElementById('messages-table');
        if (!container) return;

        try {
            container.innerHTML = '<div class="loading-message">加载中...</div>';
            const res = await API.messages.getList({page: 0, size: 10, search: '', type: '', date: ''});

            if (res.success && res.data?.content) {
                const messages = res.data.content;

                container.innerHTML = `
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>消息ID</th><th>群组</th><th>发送者</th>
                                <th>内容</th><th>类型</th><th>时间</th><th>操作</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${messages.map(msg => `
                                <tr>
                                    <td>${msg.messageId}</td>
                                    <td>${msg.chatTitle || '私聊'}</td>
                                    <td>${msg.senderName || '未知'}</td>
                                    <td>${Utils.truncateText(msg.textContent || '', 50)}</td>
                                    <td>${this.getMessageTypeLabel(msg.messageType)}</td>
                                    <td>${Utils.formatDateTime(msg.messageTime)}</td>
                                    <td>
                                        <button class="btn btn-sm btn-primary" onclick="TelegramAdmin.viewMessage('${msg.id}')">
                                            <i class="fas fa-eye"></i>
                                        </button>
                                    </td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
            }
        } catch (e) {
            console.error('消息加载失败:', e);
            container.innerHTML = '<div class="error-message">加载失败: ' + e.message + '</div>';
        }
    },

    // 获取消息类型标签
    getMessageTypeLabel(type) {
        const map = {
            'TEXT': '<span class="badge">文本</span>',
            'PHOTO': '<span class="badge badge-info">图片</span>',
            'VIDEO': '<span class="badge badge-warning">视频</span>'
        };
        return map[type] || '<span class="badge">其他</span>';
    },

    // ✅ 加载群发页面
    async loadMassMessage() {
        const container = document.getElementById('mass-message-page');
        if (!container) return;

        container.innerHTML = `
            <div class="page-header">
                <h2>消息群发</h2>
                <button class="btn btn-success" id="create-task-btn">
                    <i class="fas fa-plus"></i> 新建群发任务
                </button>
            </div>
            
            <div class="stats-grid">
                <div class="stat-card"><div class="stat-content"><h3 id="total-tasks">0</h3><p>总任务数</p></div></div>
                <div class="stat-card"><div class="stat-content"><h3 id="running-tasks">0</h3><p>运行中</p></div></div>
                <div class="stat-card"><div class="stat-content"><h3 id="completed-tasks">0</h3><p>已完成</p></div></div>
                <div class="stat-card"><div class="stat-content"><h3 id="failed-tasks">0</h3><p>已失败</p></div></div>
            </div>
            
            <div class="table-container">
                <div id="mass-message-tasks-table"></div>
            </div>
            <div id="mass-message-pagination"></div>
        `;

        document.getElementById('create-task-btn').onclick = () => {
            this.components.createTaskModal.show(
                () => {
                    // 成功回调：刷新列表
                    Utils.showNotification('任务创建成功！', 'success');
                    this.loadMassMessageTasks();
                },
                () => {
                    // 取消回调
                    Utils.showNotification('已取消创建任务', 'info');
                }
            );
        };
        await this.loadMassMessageTasks();
    },

    // 加载群发任务
    async loadMassMessageTasks() {
        try {
            const res = await API.massMessage.getTasks({page: 1, size: 10});
            if (res.success) {
                // 更新统计
                const stats = res.data.stats || {};
                document.getElementById('total-tasks').textContent = stats.total || 0;
                document.getElementById('running-tasks').textContent = stats.running || 0;
                document.getElementById('completed-tasks').textContent = stats.completed || 0;
                document.getElementById('failed-tasks').textContent = stats.failed || 0;

                // 更新徽章
                const badge = document.getElementById('running-tasks-badge');
                if (badge) {
                    badge.style.display = stats.running > 0 ? 'inline-block' : 'none';
                    badge.textContent = stats.running || 0;
                }

                // 渲染表格
                const container = document.getElementById('mass-message-tasks-table');
                if (container) {
                    const tasks = res.data.content || [];
                    container.innerHTML = `
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>任务名称</th><th>类型</th><th>目标数</th>
                                    <th>状态</th><th>成功/失败</th><th>创建时间</th><th>操作</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${tasks.map(task => `
                                    <tr>
                                        <td>${task.taskName}</td>
                                        <td>${this.getTaskTypeLabel(task.messageType)}</td>
                                        <td>${task.targetChatIds.length}</td>
                                        <td>${this.getTaskStatusBadge(task.status)}</td>
                                        <td>${task.successCount}/${task.failureCount}</td>
                                        <td>${Utils.formatDateTime(task.createdTime)}</td>
                                        <td>
                                            <button class="btn btn-sm btn-primary" onclick="TelegramAdmin.viewTaskDetail('${task.id}')">
                                                <i class="fas fa-eye"></i>
                                            </button>
                                            <button class="btn btn-sm btn-warning" onclick="TelegramAdmin.toggleTask('${task.id}', '${task.status}')">
                                                <i class="fas fa-${task.status === 'RUNNING' ? 'pause' : 'play'}"></i>
                                            </button>
                                        </td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    `;
                }
            }
        } catch (e) {
            console.error('群发任务加载失败:', e);
        }
    },

    // 获取任务类型标签
    getTaskTypeLabel(type) {
        const map = {'TEXT': '文本', 'IMAGE': '图片', 'FILE': '文件'};
        return map[type] || '未知';
    },

    // 获取任务状态徽章
    getTaskStatusBadge(status) {
        const map = {
            'PENDING': '<span class="badge badge-warning">待处理</span>',
            'RUNNING': '<span class="badge badge-primary">运行中</span>',
            'COMPLETED': '<span class="badge badge-success">已完成</span>',
            'FAILED': '<span class="badge badge-danger">已失败</span>'
        };
        return map[status] || '<span class="badge">未知</span>';
    },

    // 显示创建任务模态框（简化版）
    showCreateTaskModal() {
        Components.Modal.show({
            title: '新建群发任务',
            content: `
                <form id="create-task-form">
                    <div class="form-group">
                        <label>发送账号 <span class="required">*</span></label>
                        <select name="targetAccountPhone" required>
                            <option value="">请选择账号</option>
                            <option value="13800138000">13800138000（已认证）</option>
                            <option value="13800138001">13800138001（已认证）</option>
                        </select>
                        <div class="help-text">选择要用于发送消息的Telegram账号</div>
                    </div>
                    <div class="form-group">
                        <label>任务名称 *</label>
                        <input type="text" name="taskName" required maxlength="50">
                    </div>
                    <div class="form-group">
                        <label>消息内容 *</label>
                        <textarea name="messageContent" rows="4" required></textarea>
                    </div>
                    <div class="form-group">
                        <label>目标Chat ID（每行一个）</label>
                        <textarea name="targetChatIds" rows="6" required></textarea>
                    </div>
                </form>
            `,
            onConfirm: async () => {
                const formData = new FormData(document.getElementById('create-task-form'));
                const data = {
                    taskName: formData.get('taskName'),
                    messageContent: formData.get('messageContent'),
                    targetChatIds: formData.get('targetChatIds').split('\n').filter(id => id.trim()),
                    messageType: 'TEXT',
                    targetAccountPhone: formData.get('targetAccountPhone')
                };

                const res = await API.massMessage.createTask(data);
                if (res.success) {
                    Utils.showNotification('任务创建成功！', 'success');
                    await this.loadMassMessageTasks();
                    return true;
                }
                return false;
            }
        });
    },

    // 绑定设置页面事件
    bindSettingsEvents() {
        const btn = document.getElementById('save-settings-btn');
        if (btn) btn.onclick = () => Utils.showNotification('设置已保存', 'success');
    },

    // 开始自动刷新
    startAutoRefresh() {
        setInterval(() => {
            if (this.currentPage === 'dashboard') this.loadDashboardStats();
        }, 5 * 60 * 1000);

        setInterval(() => {
            if (this.currentPage === 'mass-message') {
                const running = parseInt(document.getElementById('running-tasks')?.textContent || 0);
                if (running > 0) this.loadMassMessageTasks();
            }
        }, 5000);
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    TelegramAdmin.init();
});