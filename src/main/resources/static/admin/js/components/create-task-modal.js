/**
 * 新建群发任务弹窗组件（最终优化版）
 * @author sunhj
 * @date 2025-12-30
 */
class CreateTaskModal {
    constructor() {
        this.modal = null;
        this.onSuccess = null;
        this.onCancel = null;
        this.currentStep = 1;
        this.maxSteps = 2;
        this.formData = {};
        this.isSubmitting = false;
        this.availableAccounts = [];
        this.init();
    }

    init() {
        this.createModal();
        this.bindEvents();
    }

    createModal() {
        const modalHtml = `
            <div class="modal-overlay" id="create-task-modal-overlay">
                <div class="modal-container auth-modal" style="max-width: 650px;">
                    <div class="modal-header">
                        <h2>📢 新建群发任务</h2>
                        <p>创建一个新的消息群发任务</p>
                        <button class="modal-close" id="create-task-close">&times;</button>
                    </div>
                    
                    <div class="modal-body">
                        <!-- 步骤指示器 -->
                        <div class="step-indicator">
                            <div class="step active" id="task-step-1">
                                <div class="step-number">1</div>
                                <div class="step-label">基本信息</div>
                            </div>
                            <div class="step" id="task-step-2">
                                <div class="step-number">2</div>
                                <div class="step-label">目标配置</div>
                            </div>
                        </div>
                        
                        <!-- 消息提示区 -->
                        <div id="task-message" class="form-message"></div>
                        
                        <!-- 步骤1: 基本信息 -->
                        <div class="form-step active" id="task-form-step-1">
                            <div class="form-group">
                                <label>发送账号 <span class="required">*</span></label>
                                <select id="task-account" required>
                                    <option value="">加载中...</option>
                                </select>
                                <div class="help-text">选择已认证的Telegram账号</div>
                            </div>
                            
                            <div class="form-group">
                                <label for="task-name">任务名称 <span class="required">*</span></label>
                                <input type="text" id="task-name" placeholder="例如：产品推广-2025-01" maxlength="50">
                                <div class="help-text">便于识别的任务名称</div>
                            </div>
                            
                            <div class="form-group">
                                <label for="task-content">消息内容 <span class="required">*</span></label>
                                <textarea id="task-content" rows="6" placeholder="请输入要发送的消息内容..."></textarea>
                                <div class="help-text">支持文本，每条消息会自动添加随机后缀防重复</div>
                                <div class="char-counter"><span id="content-count">0</span> / 2000</div>
                            </div>
                            
                            <button class="btn btn-primary" id="task-next-1">下一步</button>
                        </div>
                        
                        <!-- 步骤2: 目标配置 -->
                        <div class="form-step" id="task-form-step-2">
                            <div class="form-group">
                                <label for="task-targets">目标Chat ID <span class="required">*</span> <span class="batch-import-btn" id="batch-import-btn">📋 批量导入</span></label>
                                <textarea id="task-targets" rows="8" placeholder="每行一个，支持：&#10;-1001234567890  （超级群组）&#10;@channelname      （频道用户名）&#10;123456789        （用户ID）"></textarea>
                                <div class="help-text">共 <span id="target-count">0</span> 个目标</div>
                            </div>
                            
                            <div class="form-group">
                                <label for="task-schedule">Cron表达式（可选）</label>
                                <input type="text" id="task-schedule" placeholder="例如：0 0 12 * * ?">
                                <div class="help-text">
                                    <div style="margin-bottom: 8px;">格式：秒 分 时 日 月 周，留空则立即发送</div>
                                    <div style="font-size: 12px; color: #666;">
                                        <strong>常用示例：</strong><br>
                                        • <code>0 0 12 * * ?</code> - 每天12点执行<br>
                                        • <code>0 0 9,18 * * ?</code> - 每天9点和18点执行<br>
                                        • <code>0 0 0 * * ?</code> - 每天0点执行<br>
                                        • <code>0 0 0 1 * ?</code> - 每月1号0点执行<br>
                                        • <code>0 0 0 ? * MON</code> - 每周一0点执行<br>
                                        • <code>0 */30 * * * ?</code> - 每30分钟执行一次
                                    </div>
                                </div>
                            </div>
                            
                            <div class="btn-group">
                                <button class="btn btn-secondary" id="task-previous-2">上一步</button>
                                <button class="btn btn-success" id="task-submit">创建任务</button>
                            </div>
                        </div>
                        
                        <!-- 完成页面 -->
                        <div class="form-step" id="task-form-step-complete">
                            <div class="message success" style="text-align: center; padding: 30px;">
                                <h3>🎉 任务创建成功！</h3>
                                <p id="complete-message">任务正在执行中...</p>
                            </div>
                            <div class="btn-group" style="justify-content: center;">
                                <button class="btn btn-primary" id="task-complete-close">关闭</button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        this.modal = document.getElementById('create-task-modal-overlay');
    }

    bindEvents() {
        // 关闭按钮
        document.getElementById('create-task-close').addEventListener('click', () => this.close());
        this.modal.addEventListener('click', (e) => {
            if (e.target === this.modal) this.close();
        });

        // 步骤导航
        document.getElementById('task-next-1').addEventListener('click', () => this.validateStep1());
        document.getElementById('task-previous-2').addEventListener('click', () => this.previousStep());

        // 提交按钮
        document.getElementById('task-submit').addEventListener('click', async () => {
            if (!this.isSubmitting) {
                await this.submitTask();
            }
        });

        // 完成关闭
        document.getElementById('task-complete-close').addEventListener('click', () => this.close(true));

        // 实时计数
        document.getElementById('task-content').addEventListener('input', (e) => {
            document.getElementById('content-count').textContent = e.target.value.length;
        });

        document.getElementById('task-targets').addEventListener('input', () => this.updateTargetCount());

        // 批量导入按钮
        document.getElementById('batch-import-btn').addEventListener('click', () => this.showBatchImport());
    }

    show(onSuccess, onCancel) {
        this.onSuccess = onSuccess;
        this.onCancel = onCancel;
        this.modal.style.display = 'flex';
        document.body.style.overflow = 'hidden';
        this.reset();
        this.loadAccounts();
    }

    close(isSuccess = false) {
        this.modal.style.display = 'none';
        document.body.style.overflow = '';

        if (isSuccess && this.onSuccess) {
            this.onSuccess();
        } else if (!isSuccess && this.onCancel) {
            this.onCancel();
        }

        this.reset();
    }

    reset() {
        this.currentStep = 1;
        this.formData = {};
        this.isSubmitting = false;
        this.updateStepIndicator();
        this.clearMessage();

        // 重置表单
        this.modal.querySelectorAll('input, textarea, select').forEach(input => {
            if (input.type === 'checkbox') {
                input.checked = true; // 默认勾选
            } else {
                input.value = '';
            }
        });

        // 重置计数
        document.getElementById('content-count').textContent = '0';
        document.getElementById('target-count').textContent = '0';

        // 隐藏完成页
        document.getElementById('task-form-step-complete').classList.remove('active');
        document.getElementById('task-form-step-1').classList.add('active');
    }

    async loadAccounts() {
        try {
            const response = await API.accounts.getList({page: 0, size: 100});
            const select = document.getElementById('task-account');

            if (response.success && response.data && response.data.content && response.data.content.length > 0) {
                // const readyAccounts = response.data.content.filter(account => account.authStatus === 'READY');
                const readyAccounts = response.data.content;

                if (readyAccounts.length === 0) {
                    select.innerHTML = '<option value="">暂无已认证账号</option>';
                    this.showMessage('请先添加并认证Telegram账号', 'error');
                    return;
                }

                this.availableAccounts = readyAccounts;
                select.innerHTML = '<option value="">请选择账号</option>' +
                    readyAccounts.map(account =>
                        `<option value="${account.phoneNumber}">${account.phoneNumber} (已认证)</option>`
                    ).join('');
            } else {
                select.innerHTML = '<option value="">暂无可用账号</option>';
                this.showMessage('请先添加并认证Telegram账号', 'error');
            }
        } catch (error) {
            console.error('加载账号失败:', error);
            document.getElementById('task-account').innerHTML = '<option value="">加载失败</option>';
            this.showMessage('加载账号失败: ' + error.message, 'error');
        }
    }

    showMessage(text, type) {
        const messageDiv = document.getElementById('task-message');
        messageDiv.innerHTML = `<div class="message ${type}">${text}</div>`;
        setTimeout(() => this.clearMessage(), 5000);
    }

    clearMessage() {
        const messageDiv = document.getElementById('task-message');
        if (messageDiv) messageDiv.innerHTML = '';
    }

    updateStepIndicator() {
        for (let i = 1; i <= this.maxSteps; i++) {
            const step = document.getElementById(`task-step-${i}`);
            const formStep = document.getElementById(`task-form-step-${i}`);

            if (step && formStep) {
                step.classList.toggle('active', i === this.currentStep);
                step.classList.toggle('completed', i < this.currentStep);
                formStep.classList.toggle('active', i === this.currentStep);
            }
        }
    }

    nextStep() {
        if (this.currentStep < this.maxSteps) {
            this.currentStep++;
            this.updateStepIndicator();
        }
    }

    previousStep() {
        if (this.currentStep > 1) {
            this.currentStep--;
            this.updateStepIndicator();
        }
    }

    updateTargetCount() {
        const targets = document.getElementById('task-targets').value;
        const count = targets.split('\n').filter(id => id.trim()).length;
        document.getElementById('target-count').textContent = count;
    }

    validateStep1() {
        const account = document.getElementById('task-account').value;
        const name = document.getElementById('task-name').value.trim();
        const content = document.getElementById('task-content').value.trim();

        if (!account) {
            this.showMessage('请选择发送账号', 'error');
            return;
        }

        if (!name) {
            this.showMessage('请输入任务名称', 'error');
            return;
        }

        if (!content) {
            this.showMessage('请输入消息内容', 'error');
            return;
        }

        // 保存数据
        this.formData.targetAccountPhone = account;
        this.formData.taskName = name;
        this.formData.messageContent = content;
        this.nextStep();
    }

    async submitTask() {
        const targets = document.getElementById('task-targets').value.trim();
        if (!targets) {
            this.showMessage('请输入至少一个目标Chat ID', 'error');
            return;
        }

        const targetList = targets.split('\n')
            .map(id => id.trim())
            .filter(id => id);

        if (targetList.length === 0) {
            this.showMessage('请输入有效的Chat ID', 'error');
            return;
        }

        // 收集第二步数据
        const cronExpression = document.getElementById('task-schedule').value.trim();

        // 验证cron表达式（如果提供）
        if (cronExpression) {
            // 简单的cron表达式格式验证（6个字段，用空格分隔）
            const cronParts = cronExpression.trim().split(/\s+/);
            if (cronParts.length !== 6) {
                this.showMessage('Cron表达式格式错误：应为6个字段（秒 分 时 日 月 周）', 'error');
                return;
            }
        }

        // 合并数据
        const taskData = {
            taskName: this.formData.taskName,
            messageContent: this.formData.messageContent,
            targetChatIds: targetList,
            messageType: 'TEXT', // 固定为TEXT
            cronExpression: cronExpression || null,
            targetAccountPhone: this.formData.targetAccountPhone
        };

        // 确认对话框
        const confirmMsg = `
        <div style="text-align: left; line-height: 1.8;">
            <p><strong>任务名称：</strong>${taskData.taskName}</p>
            <p><strong>发送账号：</strong>${taskData.targetAccountPhone}</p>
            <p><strong>目标数量：</strong>${taskData.targetChatIds.length} 个</p>
            <p><strong>消息长度：</strong>${taskData.messageContent.length} 字符</p>
            <p><strong>发送方式：</strong>${taskData.cronExpression ? '定时任务: ' + taskData.cronExpression : '立即发送'}</p>
            <p style="margin-top: 15px; color: #e74c3c; font-weight: bold;">
                ⚠️ 确认创建？此操作将开始发送消息。
            </p>
        </div>
    `;

        // 保存当前模态框的z-index
        const currentZIndex = parseInt(getComputedStyle(this.modal).zIndex) || 1000;

        Components.Modal.confirm(confirmMsg, async () => {
            try {
                this.isSubmitting = true;
                this.showMessage('创建任务中...', 'info');

                const response = await API.massMessage.createTask(taskData);

                if (response.success) {
                    this.showMessage('✅ 任务创建成功！', 'success');

                    // 显示完成页
                    document.getElementById('complete-message').textContent =
                        taskData.cronExpression
                            ? '定时任务已创建，将按照Cron表达式执行: ' + taskData.cronExpression
                            : '任务正在执行中...';

                    this.showComplete();

                    // 3秒后关闭
                    setTimeout(() => this.close(true), 3000);
                } else {
                    this.showMessage('❌ ' + response.message, 'error');
                    this.isSubmitting = false;
                }
            } catch (error) {
                console.error('创建失败:', error);
                this.showMessage('❌ 创建失败: ' + error.message, 'error');
                this.isSubmitting = false;
            }
        }, () => {
            this.isSubmitting = false;
        });

        // 等待确认弹窗渲染后调整层级
        setTimeout(() => {
            const confirmOverlays = document.querySelectorAll('.modal-overlay');
            confirmOverlays.forEach(overlay => {
                if (overlay !== this.modal && overlay.style.display !== 'none') {
                    // 设置确认弹窗的z-index比当前弹窗高
                    overlay.style.zIndex = currentZIndex + 10;

                    // 同时调整确认弹窗的内容容器
                    const modalContainer = overlay.querySelector('.modal-container');
                    if (modalContainer) {
                        modalContainer.style.zIndex = currentZIndex + 11;
                    }
                }
            });
        }, 10);
    }

    showComplete() {
        for (let i = 1; i <= this.maxSteps; i++) {
            document.getElementById(`task-form-step-${i}`).classList.remove('active');
            document.getElementById(`task-step-${i}`).classList.add('completed');
        }
        document.getElementById('task-form-step-complete').classList.add('active');
    }

    showBatchImport() {
        const input = prompt('批量导入Chat ID（每行一个）：', '');
        if (input) {
            const existing = document.getElementById('task-targets').value;
            document.getElementById('task-targets').value = existing
                ? existing + '\n' + input
                : input;
            this.updateTargetCount();
        }
    }
}

window.CreateTaskModal = CreateTaskModal;