package com.telegram.server.service;

import com.telegram.server.config.MassMessageProperties;
import com.telegram.server.dto.MassMessageTaskDTO;
import com.telegram.server.dto.PageResponseDTO;
import com.telegram.server.dto.TaskDetailVO;
import com.telegram.server.entity.MassMessageLog;
import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.entity.TelegramSession;
import com.telegram.server.repository.MassMessageLogRepository;
import com.telegram.server.repository.MassMessageTaskRepository;
import com.telegram.server.repository.TelegramSessionRepository;
import com.telegram.server.service.impl.TelegramServiceImpl;
import it.tdlight.client.SimpleTelegramClient;
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
    private final TelegramSessionRepository sessionRepository;
    private final MassMessageProperties properties;
    private final TelegramClientManager clientManager;

    private final Map<String, AtomicInteger> taskExecutions = new ConcurrentHashMap<>();

    /**
     * ✅ 缓存账号服务实例
     */
    private final Map<String, TelegramServiceImpl> accountServices = new ConcurrentHashMap<>();

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

        MassMessageTask task = MassMessageTask.builder()
                .taskName(dto.getTaskName())
                .messageContent(dto.getMessageContent())
                .targetChatIds(new ArrayList<>(dto.getTargetChatIds()))
                .messageType(MassMessageTask.MessageType.valueOf(dto.getMessageType()))
                .scheduleTime(dto.getScheduleTime())
                .targetAccountPhone(dto.getTargetAccountPhone())
                .createdBy(createdBy)
                .status(dto.getScheduleTime() == null ?
                        MassMessageTask.TaskStatus.RUNNING :
                        MassMessageTask.TaskStatus.PENDING)
                .createdTime(LocalDateTime.now())
                .build();

        MassMessageTask savedTask = taskRepository.save(task);
        log.info("创建群发任务: {} (ID: {}, 账号: {})",
                task.getTaskName(), savedTask.getId(), task.getTargetAccountPhone());

        if (dto.getScheduleTime() == null) {
            startTask(savedTask.getId());
        }

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
            TelegramServiceImpl service = clientManager.getAccountService(phoneNumber);
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
            TelegramServiceImpl accountService = clientManager.getAccountService(task.getTargetAccountPhone());
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
                    log.error("发送失败 to {}: {}", targetChatId, e.getMessage());
                    batchLogs.add(createLog(task, targetChatId, MassMessageLog.SendStatus.FAILED, e.getMessage()));
                    task.setFailureCount(task.getFailureCount() + 1);
                }

                // 批量保存
                if (batchLogs.size() >= properties.getBatchLogSize() || i == task.getTargetChatIds().size() - 1) {
                    logRepository.saveAll(batchLogs);
                    taskRepository.save(task);
                    batchLogs.clear();
                }
            }

            updateTaskStatus(task, MassMessageTask.TaskStatus.COMPLETED);
            log.info("群发任务完成: {}", task.getTaskName());

        } catch (Exception e) {
            log.error("任务执行失败: {}", taskId, e);
            updateTaskStatus(task, MassMessageTask.TaskStatus.FAILED, e.getMessage());
        } finally {
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

    private TelegramServiceImpl getAccountService(String phone) {
        return accountServices.computeIfAbsent(phone, this::createAccountService);
    }

    private TelegramServiceImpl createAccountService(String phone) {
        log.info("初始化账号服务: {}", phone);

        TelegramSession session = sessionRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new RuntimeException("账号不存在: " + phone));

        TelegramServiceImpl service = new TelegramServiceImpl();
        service.setRuntimeApiId(session.getApiId());
        service.setRuntimeApiHash(session.getApiHash());
        service.setRuntimePhoneNumber(session.getPhoneNumber());
        service.initializeClient(); // 调用初始化
        return service;
    }

    /**
     * 启动任务
     */
    public void startTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));
        task.setStatus(MassMessageTask.TaskStatus.RUNNING);
        taskRepository.save(task);
        // 异步执行任务
        executeTask(taskId);
    }

    /**
     * 暂停任务
     */
    public void pauseTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));
        task.setStatus(MassMessageTask.TaskStatus.PAUSED);
        taskRepository.save(task);
        log.info("暂停群发任务: {}", task.getTaskName());
    }

    /**
     * 删除任务
     */
    @Transactional
    public void deleteTask(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));
        if (task.getStatus() == MassMessageTask.TaskStatus.RUNNING) {
            throw new RuntimeException("任务正在运行中，无法删除");
        }
        // 删除关联日志
//        logRepository.deleteByTaskId(taskId);

        taskRepository.delete(task);
        log.info("删除群发任务: {}", task.getTaskName());
    }

    /**
     * 获取任务详情（含日志）
     */
    public TaskDetailVO getTaskDetail(String taskId) {
        MassMessageTask task = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在"));

        List<MassMessageLog> logs = logRepository.findByTaskId(taskId);

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
            // 构建分页响应
            return new PageResponseDTO<MassMessageTask>(
                    sessionPage.getContent(),
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
        try {
            // 解析Chat ID
            long resolvedChatId = resolveChatId(client, chatId);

            // 根据消息类型创建内容
            TdApi.InputMessageContent content = createMessageContent(task.getMessageType(), task.getMessageContent());

            TdApi.SendMessage sendMessage = new TdApi.SendMessage();
            sendMessage.chatId = resolvedChatId;
            sendMessage.inputMessageContent = content;

            // 同步发送并等待结果（30秒超时）
            client.send(sendMessage).get(30, TimeUnit.SECONDS);

            log.debug("消息发送成功: chatId={}, taskId={}", chatId, task.getId());

        } catch (NumberFormatException e) {
            throw new RuntimeException("无效的Chat ID格式: " + chatId, e);
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析不同类型的Chat ID
     */
    private long resolveChatId(SimpleTelegramClient client, String chatId) throws Exception {
        if (chatId.startsWith("-100")) {
            // 超级群组：-100xxx -> -xxx
            long groupId = Long.parseLong(chatId.replace("-100", ""));
            return -groupId;

        } else if (chatId.startsWith("@")) {
            // 用户名：需要通过API解析
            String username = chatId.substring(1);
            TdApi.Chat chat = client.execute(new TdApi.SearchPublicChat(username)).get();

            if (chat == null) {
                throw new RuntimeException("未找到用户名: " + username);
            }

            log.info("用户名解析成功: {} -> chatId={}", username, chat.id);
            return chat.id;

        } else {
            // 用户ID
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