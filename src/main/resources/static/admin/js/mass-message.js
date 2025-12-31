// 群发消息管理模块
(function() {
    // 缓存DOM元素
    const elements = {
        taskTable: document.getElementById('mass-message-tasks-table'),
        taskTableBody: document.querySelector('#mass-message-tasks-table tbody'),
        pagination: document.getElementById('mass-message-pagination'),
        createBtn: document.getElementById('create-task-btn'),
        stats: {
            total: document.getElementById('total-tasks'),
            running: document.getElementById('running-tasks'),
            completed: document.getElementById('completed-tasks'),
            failed: document.getElementById('failed-tasks')
        },
        badge: document.getElementById('running-tasks-badge')
    };

    let currentPage = 1;
    let pageSize = 10;
    let autoRefreshInterval = null;

    // 初始化
    function init() {
        bindEvents();
        // 当切换到群发页面时加载数据
        document.addEventListener('pageChanged', (e) => {
            if (e.detail.page === 'mass-message') {
                loadTasks();
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });
    }

    // 事件绑定
    function bindEvents() {
        elements.createBtn.addEventListener('click', openCreateTaskModal);
    }

    // 加载任务列表
    async function loadTasks() {
        showLoading();
        try {
            const response = await API.get('/mass-message/tasks', {
                page: currentPage,
                size: pageSize
            });

            if (response.success) {
                renderTasks(response.data.content);
                renderPagination(response.data);
                updateStats(response.data.stats);
            }
        } catch (error) {
            showToast('加载任务失败: ' + error.message, 'error');
        } finally {
            hideLoading();
        }
    }

    // 渲染任务列表
    function renderTasks(tasks) {
        elements.taskTableBody.innerHTML = '';

        if (!tasks || tasks.length === 0) {
            elements.taskTableBody.innerHTML = `
                <tr>
                    <td colspan="7" style="text-align: center; color: #999; padding: 40px;">
                        <i class="fas fa-inbox" style="font-size: 48px; margin-bottom: 10px;"></i>
                        <p>暂无群发任务</p>
                    </td>
                </tr>
            `;
            return;
        }

        tasks.forEach(task => {
            const row = document.createElement('tr');
            row.innerHTML = `
                <td>${task.taskName}</td>
                <td><span class="badge badge-info">${getMessageTypeLabel(task.messageType)}</span></td>
                <td>${task.targetChatIds.length}</td>
                <td>${getStatusBadge(task.status)}</td>
                <td>
                    <span class="text-success">${task.successCount || 0}</span> / 
                    <span class="text-danger">${task.failureCount || 0}</span>
                </td>
                <td>${formatDateTime(task.createdTime)}</td>
                <td>
                    <div class="btn-group">
                        <button class="btn btn-sm btn-info" onclick="viewTaskDetail('${task.id}')" 
                                title="查看详情">
                            <i class="fas fa-eye"></i>
                        </button>
                        <button class="btn btn-sm ${task.status === 'RUNNING' ? 'btn-warning' : 'btn-success'}" 
                                onclick="toggleTask('${task.id}', '${task.status}')"
                                title="${task.status === 'RUNNING' ? '暂停' : '启动'}">
                            <i class="fas fa-${task.status === 'RUNNING' ? 'pause' : 'play'}"></i>
                        </button>
                        <button class="btn btn-sm btn-danger" onclick="deleteTask('${task.id}')"
                                title="删除">
                            <i class="fas fa-trash"></i>
                        </button>
                    </div>
                </td>
            `;
            elements.taskTableBody.appendChild(row);
        });
    }

    // 获取状态标签
    function getStatusBadge(status) {
        const statusMap = {
            'PENDING': { label: '待处理', class: 'badge-warning' },
            'RUNNING': { label: '运行中', class: 'badge-primary' },
            'COMPLETED': { label: '已完成', class: 'badge-success' },
            'FAILED': { label: '已失败', class: 'badge-danger' },
            'PAUSED': { label: '已暂停', class: 'badge-secondary' }
        };
        const config = statusMap[status] || { label: '未知', class: 'badge-secondary' };
        return `<span class="badge ${config.class}">${config.label}</span>`;
    }

    // 获取消息类型标签
    function getMessageTypeLabel(type) {
        const typeMap = {
            'TEXT': '文本',
            'IMAGE': '图片',
            'FILE': '文件'
        };
        return typeMap[type] || '未知';
    }

    // 更新统计信息
    function updateStats(stats) {
        if (!stats) return;
        elements.stats.total.textContent = stats.total || 0;
        elements.stats.running.textContent = stats.running || 0;
        elements.stats.completed.textContent = stats.completed || 0;
        elements.stats.failed.textContent = stats.failed || 0;

        // 更新侧边栏徽章
        const runningCount = stats.running || 0;
        if (runningCount > 0) {
            elements.badge.textContent = runningCount;
            elements.badge.style.display = 'inline-block';
        } else {
            elements.badge.style.display = 'none';
        }
    }

    // 打开创建任务模态框
    function openCreateTaskModal() {
        const modalContent = `
            <form id="create-task-form">
                <div class="form-group">
                    <label class="form-label">任务名称 <span class="required">*</span></label>
                    <input type="text" class="form-control" name="taskName" 
                           placeholder="例如：产品推广-2025-01" required maxlength="50">
                </div>
                
                <div class="form-group">
                    <label class="form-label">消息内容 <span class="required">*</span></label>
                    <textarea class="form-control" name="messageContent" rows="4" 
                              placeholder="请输入要发送的消息内容..." required maxlength="2000"></textarea>
                    <small class="form-text text-muted">支持纯文本，建议控制长度避免被识别为垃圾消息</small>
                </div>
                
                <div class="form-group">
                    <label class="form-label">目标Chat ID <span class="required">*</span></label>
                    <textarea class="form-control" name="targetChatIds" rows="6" 
                              placeholder="-1001234567890&#10;@channelusername&#10;123456789" required></textarea>
                    <small class="form-text text-muted">
                        每行一个，支持：群组ID（负数）、频道用户名、用户ID<br>
                        示例：-1001234567890 或 @channel 或 123456789
                    </small>
                </div>
                
                <div class="form-group">
                    <label class="form-label">消息类型</label>
                    <select class="form-control" name="messageType">
                        <option value="TEXT">文本消息</option>
                        <option value="IMAGE">图片消息（暂不支持）</option>
                        <option value="FILE">文件消息（暂不支持）</option>
                    </select>
                </div>
                
                <div class="form-group">
                    <label class="form-label">定时发送</label>
                    <input type="datetime-local" class="form-control" name="scheduleTime">
                    <small class="form-text text-muted">留空则立即发送（建议设置间隔5秒以上）</small>
                </div>
            </form>
        `;

        showModal({
            title: '新建群发任务',
            content: modalContent,
            onConfirm: createTask,
            confirmText: '创建任务'
        });
    }

    // 创建任务
    async function createTask() {
        const formData = new FormData(document.getElementById('create-task-form'));
        const data = {
            taskName: formData.get('taskName'),
            messageContent: formData.get('messageContent'),
            targetChatIds: formData.get('targetChatIds').split('\n').filter(id => id.trim()),
            messageType: formData.get('messageType'),
            scheduleTime: formData.get('scheduleTime') || null
        };

        // 验证
        if (!data.taskName || !data.messageContent || data.targetChatIds.length === 0) {
            showToast('请填写完整信息！', 'warning');
            return false;
        }

        showLoading();
        try {
            const response = await API.post('/mass-message/task', data);
            if (response.success) {
                showToast('任务创建成功！', 'success');
                closeModal();
                loadTasks();

                // 如果是立即执行，启动任务
                if (!data.scheduleTime) {
                    await API.post(`/mass-message/task/${response.data}/start`);
                    showToast('任务已开始执行！', 'info');
                }
                return true;
            }
        } catch (error) {
            showToast('创建失败: ' + error.message, 'error');
            return false;
        } finally {
            hideLoading();
        }
    }

    // 查看任务详情
    window.viewTaskDetail = async function(taskId) {
        showLoading();
        try {
            const response = await API.get(`/mass-message/task/${taskId}`);
            if (response.success) {
                const { task, logs } = response.data;
                const detailContent = `
                    <div class="task-detail-header" style="margin-bottom: 20px;">
                        <h4>${task.taskName}</h4>
                        <div class="task-meta" style="display: flex; gap: 20px; font-size: 14px; color: #666;">
                            <span>状态：${getStatusBadge(task.status)}</span>
                            <span>目标：${task.targetChatIds.length} 个</span>
                            <span>成功：${task.successCount || 0} 个</span>
                            <span>失败：${task.failureCount || 0} 个</span>
                        </div>
                    </div>
                    
                    <div class="task-content" style="background: #f8f9fa; padding: 15px; border-radius: 5px; margin-bottom: 20px;">
                        <h5>消息内容</h5>
                        <p style="white-space: pre-wrap; word-break: break-word;">${task.messageContent}</p>
                    </div>
                    
                    <h5>发送日志</h5>
                    <div class="logs-container" style="max-height: 400px; overflow-y: auto; border: 1px solid #ddd; border-radius: 5px;">
                        <table class="table table-sm">
                            <thead style="position: sticky; top: 0; background: white;">
                                <tr>
                                    <th>时间</th>
                                    <th>目标</th>
                                    <th>状态</th>
                                    <th>错误信息</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${logs.length === 0 ? '<tr><td colspan="4" style="text-align: center; padding: 20px; color: #999;">暂无日志</td></tr>' :
                    logs.map(log => `
                                    <tr>
                                        <td>${formatDateTime(log.sentTime)}</td>
                                        <td>${log.chatTitle || log.chatId}</td>
                                        <td>${log.status === 'SUCCESS' ?
                        '<span class="badge badge-success">成功</span>' :
                        '<span class="badge badge-danger">失败</span>'}</td>
                                        <td>${log.errorMessage || '-'}</td>
                                    </tr>
                                  `).join('')}
                            </tbody>
                        </table>
                    </div>
                `;

                document.getElementById('task-detail-body').innerHTML = detailContent;
                document.getElementById('task-detail-modal').style.display = 'flex';
            }
        } catch (error) {
            showToast('加载详情失败: ' + error.message, 'error');
        } finally {
            hideLoading();
        }
    };

    // 关闭任务详情模态框
    window.closeTaskDetailModal = function() {
        document.getElementById('modal-overlay').style.display = 'none';
    };

    // 启动/暂停任务
    window.toggleTask = async function(taskId, status) {
        const action = status === 'RUNNING' ? 'pause' : 'start';
        if (!confirm(`确定要${action === 'start' ? '启动' : '暂停'}该任务吗？`)) return;

        showLoading();
        try {
            const response = await API.post(`/mass-message/task/${taskId}/${action}`);
            if (response.success) {
                showToast(`任务已${action === 'start' ? '启动' : '暂停'}！`, 'success');
                loadTasks();
            }
        } catch (error) {
            showToast('操作失败: ' + error.message, 'error');
        } finally {
            hideLoading();
        }
    };

    // 删除任务
    window.deleteTask = async function(taskId) {
        if (!confirm('确定要删除该任务吗？此操作不可恢复！')) return;

        showLoading();
        try {
            const response = await API.delete(`/mass-message/task/${taskId}`);
            if (response.success) {
                showToast('任务已删除！', 'success');
                loadTasks();
            }
        } catch (error) {
            showToast('删除失败: ' + error.message, 'error');
        } finally {
            hideLoading();
        }
    };

    // 自动刷新运行中任务
    function startAutoRefresh() {
        stopAutoRefresh(); // 防止重复
        autoRefreshInterval = setInterval(() => {
            const runningCount = parseInt(elements.stats.running.textContent);
            if (runningCount > 0) {
                loadTasks();
            }
        }, 5000); // 每5秒刷新一次
    }

    function stopAutoRefresh() {
        if (autoRefreshInterval) {
            clearInterval(autoRefreshInterval);
            autoRefreshInterval = null;
        }
    }

    // 工具函数
    function formatDateTime(dateStr) {
        if (!dateStr) return '-';
        const date = new Date(dateStr);
        return date.toLocaleString('zh-CN');
    }

    function showLoading() {
        document.getElementById('loading').style.display = 'flex';
    }

    function hideLoading() {
        document.getElementById('loading').style.display = 'none';
    }

    function showToast(message, type = 'info') {
        // 复用现有toast组件
        if (window.showToast) {
            window.showToast(message, type);
        } else {
            alert(message);
        }
    }

    function showModal(options) {
        // 复用现有modal组件
        if (window.showModal) {
            window.showModal(options);
        } else {
            document.getElementById('modal-overlay').style.display = 'flex';
        }
    }

    function closeModal() {
        document.getElementById('modal-overlay').style.display = 'none';
    }

    // 初始化模块
    init();
})();