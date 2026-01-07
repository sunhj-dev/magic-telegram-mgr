package com.telegram.server.service;

import com.telegram.server.config.MassMessageProperties;
import com.telegram.server.dto.MassMessageTaskDTO;
import com.telegram.server.dto.PageResponseDTO;
import com.telegram.server.dto.TaskDetailVO;
import com.telegram.server.entity.MassMessageLog;
import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.repository.MassMessageLogRepository;
import com.telegram.server.repository.MassMessageTaskRepository;
import com.telegram.server.service.TelegramClientManager;
import com.telegram.server.service.scheduler.MassMessageTaskScheduler;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 群发消息服务
 *
 * @author sunhj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MassMessageService {

    private final MassMessageTaskRepository taskRepository;
    private final MassMessageLogRepository logRepository;
    private final MassMessageProperties properties;
    private final TelegramClientManager clientManager;
    private final MassMessageTaskScheduler taskScheduler;

    private final Map<String, AtomicInteger> taskExecutions = new ConcurrentHashMap<>();


    /**
     * 创建群发任务
     */
    @Transactional
    public String createTask(MassMessageTaskDTO dto, String createdBy) {
        validateTask(dto);

        if (dto.getTargetChatIds().size() > properties.getMaxTargetsPerTask()) {
            throw new RuntimeException("单次任务目标数量不能超过 " + properties.getMaxTargetsPerTask() + " 个");
        }

        // 验证账号可用性
        validateAccountReady(dto.getTargetAccountPhone());

        // 验证cron表达式（必须提供）
        String cronExpression = dto.getCronExpression();
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            throw new IllegalArgumentException("cron表达式不能为空");
        }
        validateCronExpression(cronExpression);

        MassMessageTask task = MassMessageTask.builder()
                .taskName(dto.getTaskName())
                .messageContent(dto.getMessageContent())
                .targetChatIds(new ArrayList<>(dto.getTargetChatIds()))
                .messageType(MassMessageTask.MessageType.valueOf(dto.getMessageType()))
                .cronExpression(cronExpression.trim())
                .targetAccountPhone(dto.getTargetAccountPhone())
                .createdBy(createdBy)
                .status(MassMessageTask.TaskStatus.PENDING)
                .createdTime(LocalDateTime.now())
                .build();

        MassMessageTask savedTask = taskRepository.save(task);
        log.info("创建群发任务: {} (ID: {}, 账号: {}, cron: {})",
                task.getTaskName(), savedTask.getId(), task.getTargetAccountPhone(), cronExpression);

        // 交给调度器管理
        taskScheduler.scheduleTask(savedTask);

        return savedTask.getId();
    }

    /**
     * 验证账号是否就绪
     */
    private void validateAccountReady(String phoneNumber) {
        // 检查账号是否存在且已认证
        if (!clientManager.getAvailableAccounts().contains(phoneNumber)) {
            throw new RuntimeException("账号不可用或未认证: " + phoneNumber);
        }

        // 尝试获取服务实例（会触发初始化）
        try {
            com.telegram.server.service.impl.TelegramServiceImpl service = clientManager.getAccountService(phoneNumber);
            if (!service.isClientReady()) {
                throw new RuntimeException("账号客户端未就绪: " + phoneNumber);
            }
        } catch (Exception e) {
            throw new RuntimeException("账号验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行群发任务（异步）
     */
    @Async("massMessageExecutor")
    public void executeTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));

        if (task.getStatus() != MassMessageTask.TaskStatus.RUNNING) {
            log.warn("任务状态不是运行中，跳过执行: {}", taskId);
            return;
        }

        AtomicInteger executionFlag = new AtomicInteger(1);
        taskExecutions.put(taskId, executionFlag);

        log.info("开始执行群发任务: {} (目标: {}个, 账号: {})", task.getTaskName(), task.getTargetChatIds().size(), task.getTargetAccountPhone());

        try {
            // 从管理器获取账号服务
            com.telegram.server.service.impl.TelegramServiceImpl accountService = clientManager.getAccountService(task.getTargetAccountPhone());
            SimpleTelegramClient client = accountService.getClient();

            List<MassMessageLog> batchLogs = new ArrayList<>();

            for (int i = 0; i < task.getTargetChatIds().size(); i++) {
                if (executionFlag.get() == 0) {
                    log.info("任务已暂停: {}", taskId);
                    updateTaskStatus(task, MassMessageTask.TaskStatus.PAUSED);
                    return;
                }

                String targetChatId = task.getTargetChatIds().get(i);
                try {
                    // 随机延迟
                    int delay = properties.getRateLimitDelay() + new Random().nextInt(properties.getRateLimitDelay());
                    log.debug("发送延迟: {}ms", delay);
                    TimeUnit.MILLISECONDS.sleep(delay);

                    // 发送消息
                    sendMessageToChat(client, targetChatId, task);

                    batchLogs.add(createLog(task, targetChatId, MassMessageLog.SendStatus.SUCCESS, null));
                    task.setSuccessCount(task.getSuccessCount() + 1);

                } catch (Exception e) {
                    // 提取更详细的错误信息
                    String errorMessage = e.getMessage();
                    if (errorMessage != null && errorMessage.contains("Chat not found")) {
                        errorMessage = "聊天不存在或无法访问: " + targetChatId;
                    }
                    log.error("发送失败 to {}: {}", targetChatId, errorMessage);
                    batchLogs.add(createLog(task, targetChatId, MassMessageLog.SendStatus.FAILED, errorMessage));
                    task.setFailureCount(task.getFailureCount() + 1);
                }

                // 批量保存
                if (batchLogs.size() >= properties.getBatchLogSize() || i == task.getTargetChatIds().size() - 1) {
                    logRepository.saveAll(batchLogs);
                    taskRepository.save(task);
                    batchLogs.clear();
                }
            }

            // 检查任务是否被删除（在执行过程中）
            if (!taskRepository.existsById(taskId)) {
                log.warn("任务在执行过程中被删除，停止执行: taskId={}", taskId);
                return;
            }

            // 执行完成后状态变回PENDING，等待下次调度（所有任务都是定时任务）
            task.setStatus(MassMessageTask.TaskStatus.PENDING);
            taskRepository.save(task);

            // 重新调度任务（更新 nextExecuteTime 并调度下次执行）
            taskScheduler.scheduleTask(task);

            log.info("定时群发任务执行完成，等待下次调度: taskId={}, nextExecuteTime={}",
                    taskId, task.getNextExecuteTime());

        } catch (Exception e) {
            log.error("任务执行失败: {}", taskId, e);
            // 检查任务是否还存在，如果已被删除则不更新状态
            if (taskRepository.existsById(taskId)) {
                updateTaskStatus(task, MassMessageTask.TaskStatus.FAILED, e.getMessage());
            }
        } finally {
            // 从执行标志映射中移除
            taskExecutions.remove(taskId);
        }
    }

    private void updateTaskStatus(MassMessageTask task, MassMessageTask.TaskStatus status) {
        updateTaskStatus(task, status, null);
    }

    /**
     * 更新任务状态（带错误信息）
     */
    private void updateTaskStatus(MassMessageTask task, MassMessageTask.TaskStatus status, String errorMessage) {
        task.setStatus(status);
        task.setLastExecuteTime(LocalDateTime.now());

        if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }

        taskRepository.save(task);

        log.info("任务状态更新: {} → {}", task.getTaskName(), status);
    }

    /**
     * 获取可用账号列表
     */
    public List<String> getAvailableAccounts() {
        return clientManager.getAvailableAccounts();
    }

    /**
     * 获取账号在线状态
     */
    public Map<String, Boolean> getAccountOnlineStatus() {
        return clientManager.getAvailableAccounts().stream()
                .collect(Collectors.toMap(
                        phone -> phone,
                        phone -> clientManager.isAccountOnline(phone)
                ));
    }


    /**
     * 启动任务
     */
    public void startTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));

        // 检查任务状态
        if (task.getStatus() == MassMessageTask.TaskStatus.RUNNING) {
            log.warn("任务已经在运行中: taskId={}", taskId);
            return;
        }

        if (task.getStatus() == MassMessageTask.TaskStatus.COMPLETED) {
            throw new RuntimeException("已完成的任务无法重新启动");
        }

        // 如果任务已经是PENDING状态，检查是否已经在调度器中
        if (task.getStatus() == MassMessageTask.TaskStatus.PENDING) {
            // 如果已经在调度器中，不需要重新调度
            // scheduleTask会处理重复调度的情况（先取消再重新调度）
            taskScheduler.scheduleTask(task);
            log.info("定时任务已重新调度: taskId={}, cron={}", taskId, task.getCronExpression());
            return;
        }

        // 如果任务状态是PAUSED或FAILED，需要重新启动
        // 所有任务都是定时任务，交给调度器管理
        task.setStatus(MassMessageTask.TaskStatus.PENDING);
        taskRepository.save(task);
        taskScheduler.scheduleTask(task);
        log.info("定时任务已启动: taskId={}, cron={}, 原状态={}", taskId, task.getCronExpression(), task.getStatus());
    }

    /**
     * 暂停任务
     */
    public void pauseTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));

        // 检查任务状态
        if (task.getStatus() == MassMessageTask.TaskStatus.PAUSED) {
            log.warn("任务已经处于暂停状态: taskId={}", taskId);
            return;
        }

        if (task.getStatus() == MassMessageTask.TaskStatus.COMPLETED) {
            throw new RuntimeException("已完成的任务无法暂停");
        }

        if (task.getStatus() == MassMessageTask.TaskStatus.FAILED) {
            throw new RuntimeException("失败的任务无法暂停");
        }

        // 如果任务正在运行，需要停止执行
        if (task.getStatus() == MassMessageTask.TaskStatus.RUNNING) {
            // 设置暂停标志，executeTask会检查并停止
            AtomicInteger executionFlag = taskExecutions.get(taskId);
            if (executionFlag != null) {
                executionFlag.set(0);
                log.info("已设置任务停止标志，等待任务执行完成: taskId={}", taskId);
            } else {
                log.warn("任务执行标志不存在，可能任务已经完成: taskId={}", taskId);
            }
        }

        // 取消定时任务调度（无论任务状态如何，都要取消调度）
        // 这样可以防止PENDING状态的任务被执行
        taskScheduler.cancelTask(taskId);

        // 更新任务状态为暂停
        task.setStatus(MassMessageTask.TaskStatus.PAUSED);
        taskRepository.save(task);

        log.info("暂停群发任务: taskId={}, taskName={}, 原状态={}", taskId, task.getTaskName(), task.getStatus());
    }

    /**
     * 删除任务
     *
     * @param taskId 任务ID
     * @throws RuntimeException 如果任务不存在或正在运行中
     */
    @Transactional
    public void deleteTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));

        // 如果任务正在运行中，先停止执行
        if (task.getStatus() == MassMessageTask.TaskStatus.RUNNING) {
            log.warn("尝试删除运行中的任务，先停止执行: taskId={}", taskId);

            // 设置暂停标志，停止正在执行的任务
            AtomicInteger executionFlag = taskExecutions.get(taskId);
            if (executionFlag != null) {
                executionFlag.set(0);
                log.info("已设置停止标志，等待任务执行停止: taskId={}", taskId);
            }

            // 等待一小段时间让任务停止（最多等待3秒）
            int waitCount = 0;
            while (executionFlag != null && executionFlag.get() == 0 && waitCount < 30) {
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待任务停止时被中断: taskId={}", taskId);
                    break;
                }
            }

            // 如果任务仍在运行，抛出异常
            if (taskExecutions.containsKey(taskId)) {
                throw new RuntimeException("任务正在执行中，无法立即删除。请稍后重试或先暂停任务。");
            }
        }

        // 取消定时任务调度
        try {
            taskScheduler.cancelTask(taskId);
            log.info("已取消定时任务调度: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("取消定时任务调度失败: taskId={}, error={}", taskId, e.getMessage());
        }

        // 删除关联日志
        try {
            List<MassMessageLog> logs = logRepository.findByTaskId(taskId);
            long logCount = logs.size();
            if (logCount > 0) {
                logRepository.deleteByTaskId(taskId);
                log.info("已删除任务关联日志: taskId={}, 日志数量={}", taskId, logCount);
            } else {
                log.debug("任务没有关联日志，跳过删除: taskId={}", taskId);
            }
        } catch (Exception e) {
            log.error("删除任务关联日志失败: taskId={}, error={}", taskId, e.getMessage(), e);
            // 不抛出异常，继续删除任务，但记录错误日志
        }

        // 从执行标志映射中移除（如果存在）
        taskExecutions.remove(taskId);

        // 删除任务
        taskRepository.delete(task);
        log.info("删除群发任务完成: taskId={}, taskName={}", taskId, task.getTaskName());
    }

    /**
     * 获取任务详情（含日志）
     */
    public TaskDetailVO getTaskDetail(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));

        // 检查任务是否正在执行中（异步执行可能导致数据库状态与实际状态不一致）
        if (taskExecutions.containsKey(taskId)) {
            // 如果任务正在执行，动态设置状态为RUNNING（不更新数据库）
            if (task.getStatus() != MassMessageTask.TaskStatus.RUNNING) {
                task.setStatus(MassMessageTask.TaskStatus.RUNNING);
                log.debug("任务正在执行中，动态设置状态为RUNNING: taskId={}", taskId);
            }
        }
        List<MassMessageLog> logs = new ArrayList<>();
        try {
            logs = logRepository.findByTaskId(taskId);
        } catch (Exception e) {
            log.info("获取任务日志失败：" + e);
        }

        return new TaskDetailVO(task, logs);
    }

    /**
     * 参数验证
     */
    private void validateTask(MassMessageTaskDTO dto) {
        if (dto.getTaskName() == null || dto.getTaskName().trim().isEmpty()) {
            throw new RuntimeException("任务名称不能为空");
        }

        if (dto.getMessageContent() == null || dto.getMessageContent().trim().isEmpty()) {
            throw new RuntimeException("消息内容不能为空");
        }

        if (dto.getTargetChatIds() == null || dto.getTargetChatIds().isEmpty()) {
            throw new RuntimeException("目标列表不能为空");
        }

        // 敏感词过滤
        if (containsSensitiveWords(dto.getMessageContent())) {
            throw new RuntimeException("消息内容包含敏感词");
        }
    }

    /**
     * 验证Cron表达式
     *
     * @param cronExpression cron表达式
     */
    private void validateCronExpression(String cronExpression) {
        try {
            org.springframework.scheduling.support.CronExpression.parse(cronExpression);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的Cron表达式: " + cronExpression + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 敏感词检测（简单实现）
     */
    private boolean containsSensitiveWords(String content) {
        String[] sensitiveWords = {"赌博", "色情", "诈骗", "洗钱", "毒品"};
        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerContent.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public PageResponseDTO<MassMessageTask> getTasks(int pageNum, int pageSize) {

        try {
            // 创建分页参数
            Pageable pageable = PageRequest.of(
                    pageNum - 1,
                    pageSize,
                    Sort.by(Sort.Direction.DESC, "updatedTime")
            );

            // 查询分页数据
            Page<MassMessageTask> sessionPage = taskRepository.findAll(pageable);

            // 检查任务是否正在执行中（异步执行可能导致数据库状态与实际状态不一致）
            // 注意：不更新数据库，只在返回时动态设置状态，避免频繁写数据库
            List<MassMessageTask> tasks = sessionPage.getContent();
            for (MassMessageTask task : tasks) {
                // 如果任务在taskExecutions中，说明正在执行，状态应该是RUNNING
                if (taskExecutions.containsKey(task.getId())) {
                    // 动态设置状态为RUNNING（不更新数据库，只在内存中修改）
                    // 这样可以确保前端显示的状态是准确的
                    task.setStatus(MassMessageTask.TaskStatus.RUNNING);
                    log.debug("任务正在执行中，动态设置状态为RUNNING: taskId={}", task.getId());
                }
            }

            // 构建分页响应
            return new PageResponseDTO<MassMessageTask>(
                    tasks,
                    pageNum, // 保持与前端一致的页码
                    pageSize,
                    sessionPage.getTotalElements()
            );

        } catch (Exception e) {
            log.error("分页获取账号列表失败", e);
            return new PageResponseDTO<MassMessageTask>(new ArrayList<MassMessageTask>(), pageNum, pageSize, 0L);
        }
    }

    /**
     * 获取任务统计信息
     *
     * @return 统计对象
     */
    public Object getStats() {
        return taskRepository.getStats();
    }

    /**
     * 发送消息到指定聊天
     */
    private void sendMessageToChat(SimpleTelegramClient client, String chatId, MassMessageTask task) throws Exception {
        long resolvedChatId = 0;
        try {
            // 解析Chat ID
            resolvedChatId = resolveChatId(client, chatId);

            // 根据消息类型创建内容
            TdApi.InputMessageContent content = createMessageContent(task.getMessageType(), task.getMessageContent());

            TdApi.SendMessage sendMessage = new TdApi.SendMessage();
            sendMessage.chatId = resolvedChatId;
            sendMessage.inputMessageContent = content;

            // 同步发送并等待结果（30秒超时）
            client.send(sendMessage).get(30, TimeUnit.SECONDS);

            log.debug("消息发送成功: chatId={}, resolvedChatId={}, taskId={}", chatId, resolvedChatId, task.getId());

        } catch (TelegramError e) {
            // 处理Telegram API错误
            int errorCode = e.getErrorCode();
            String errorMessage = e.getMessage();

            // 根据错误代码提供更详细的错误信息
            String detailedMessage;
            if (errorCode == 400) {
                if (errorMessage != null && errorMessage.contains("Chat not found")) {
                    detailedMessage = String.format("聊天不存在或无法访问 (原始Chat ID: %s, 解析后: %d). 可能原因: 1) 聊天已被删除 2) 账号没有权限访问该聊天 3) Chat ID格式错误",
                            chatId, resolvedChatId);
                } else {
                    detailedMessage = String.format("Telegram API错误 400: %s (Chat ID: %s, 解析后: %d)", errorMessage, chatId, resolvedChatId);
                }
            } else if (errorCode == 403) {
                detailedMessage = String.format("没有权限发送消息到该聊天 (Chat ID: %s, 解析后: %d). 可能原因: 1) 账号被踢出群组 2) 群组已禁止发送消息 3) 账号被限制",
                        chatId, resolvedChatId);
            } else if (errorCode == 429) {
                detailedMessage = String.format("发送频率过高，请稍后重试 (Chat ID: %s)", chatId);
            } else {
                detailedMessage = String.format("Telegram API错误 %d: %s (Chat ID: %s, 解析后: %d)", errorCode, errorMessage, chatId, resolvedChatId);
            }

            log.error("Telegram API错误: code={}, message={}, chatId={}, resolvedChatId={}",
                    errorCode, errorMessage, chatId, resolvedChatId);
            throw new RuntimeException(detailedMessage, e);

        } catch (NumberFormatException e) {
            throw new RuntimeException("无效的Chat ID格式: " + chatId, e);
        } catch (Exception e) {
            // 检查是否是TelegramError包装在其他异常中
            Throwable cause = e.getCause();
            if (cause instanceof TelegramError) {
                TelegramError telegramError = (TelegramError) cause;
                int errorCode = telegramError.getErrorCode();
                String errorMessage = telegramError.getMessage();

                String detailedMessage;
                if (errorCode == 400 && errorMessage != null && errorMessage.contains("Chat not found")) {
                    detailedMessage = String.format("聊天不存在或无法访问 (Chat ID: %s, 解析后: %d). 可能原因: 1) 聊天已被删除 2) 账号没有权限访问该聊天 3) Chat ID格式错误",
                            chatId, resolvedChatId);
                } else {
                    detailedMessage = String.format("Telegram API错误 %d: %s (Chat ID: %s, 解析后: %d)", errorCode, errorMessage, chatId, resolvedChatId);
                }

                log.error("Telegram API错误 (包装异常): code={}, message={}, chatId={}, resolvedChatId={}",
                        errorCode, errorMessage, chatId, resolvedChatId);
                throw new RuntimeException(detailedMessage, e);
            }

            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析不同类型的Chat ID
     * <p>
     * Telegram Chat ID 格式说明：
     * - 超级群组/频道：-100xxxxxxxxxx（完整的负数，如 -1003538112263）
     * - 基础群组：负数，如 -123456789
     * - 私聊：正数，如 123456789
     * - 用户名：@username
     */
    private long resolveChatId(SimpleTelegramClient client, String chatId) throws Exception {
        if (chatId.startsWith("-100")) {
            // 超级群组/频道：直接解析为完整的负数
            // 例如：-1003538112263 -> -1003538112263
            return Long.parseLong(chatId);

        } else if (chatId.startsWith("@")) {
            // 用户名：需要通过API解析
            String username = chatId.substring(1);
            TdApi.Chat chat = client.execute(new TdApi.SearchPublicChat(username)).get();

            if (chat == null) {
                throw new RuntimeException("未找到用户名: " + username);
            }

            log.info("用户名解析成功: {} -> chatId={}", username, chat.id);
            return chat.id;

        } else if (chatId.startsWith("-")) {
            // 基础群组或其他负数格式：直接解析
            return Long.parseLong(chatId);

        } else {
            // 用户ID（正数）
            return Long.parseLong(chatId);
        }
    }

    /**
     * 根据消息类型创建内容
     */
    private TdApi.InputMessageContent createMessageContent(MassMessageTask.MessageType type, String content) {
        return switch (type) {
            case TEXT -> new TdApi.InputMessageText(
                    new TdApi.FormattedText(content, null),
                    null,
                    false
            );

            case IMAGE -> createImageContent(content);
            case FILE -> createFileContent(content);
            default -> throw new RuntimeException("不支持的消息类型: " + type);
        };
    }

    /**
     * 创建图片消息内容
     */
    private TdApi.InputMessageContent createImageContent(String filePathOrUrl) {
        TdApi.InputFile inputFile;

        if (filePathOrUrl.startsWith("http")) {
            // TODO: 实现URL下载逻辑
            throw new UnsupportedOperationException("URL图片暂未支持，请使用本地文件路径");
        } else {
            inputFile = new TdApi.InputFileLocal(filePathOrUrl);
        }

        TdApi.InputMessagePhoto photo = new TdApi.InputMessagePhoto();
        photo.photo = inputFile;
        photo.thumbnail = new TdApi.InputThumbnail(); // 可选
        photo.addedStickerFileIds = new int[0];
        photo.width = 0;  // 自动检测
        photo.height = 0; // 自动检测
        photo.caption = new TdApi.FormattedText("", null);

        return photo;
    }

    /**
     * 创建文件消息内容
     */
    private TdApi.InputMessageContent createFileContent(String filePath) {
        TdApi.InputFile inputFile = new TdApi.InputFileLocal(filePath);

        TdApi.InputMessageDocument document = new TdApi.InputMessageDocument();
        document.document = inputFile;
        document.thumbnail = new TdApi.InputThumbnail();
        document.caption = new TdApi.FormattedText("", null);
        document.disableContentTypeDetection = false;

        return document;
    }

    /**
     * 创建日志实体
     */
    private MassMessageLog createLog(MassMessageTask task, String chatId,
                                     MassMessageLog.SendStatus status, String errorMsg) {
        return MassMessageLog.builder()
                .taskId(task.getId())
                .chatId(chatId)
                .status(status)
                .errorMessage(errorMsg)
                .build();
    }

}