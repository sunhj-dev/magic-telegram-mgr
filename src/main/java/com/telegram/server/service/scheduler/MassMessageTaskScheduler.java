package com.telegram.server.service.scheduler;

import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.repository.MassMessageTaskRepository;
import com.telegram.server.service.MassMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 群发消息任务调度器
 * 负责管理基于Cron表达式的定时任务
 * 
 * @author sunhj
 * @date 2026-01-05
 */
@Slf4j
@Component
public class MassMessageTaskScheduler {
    
    private final TaskScheduler taskScheduler;
    private final MassMessageTaskRepository taskRepository;
    private final MassMessageService massMessageService;
    
    /**
     * 使用@Lazy打破循环依赖
     * MassMessageService -> MassMessageTaskScheduler -> MassMessageService
     */
    public MassMessageTaskScheduler(
            TaskScheduler taskScheduler,
            MassMessageTaskRepository taskRepository,
            @Lazy MassMessageService massMessageService) {
        this.taskScheduler = taskScheduler;
        this.taskRepository = taskRepository;
        this.massMessageService = massMessageService;
    }
    
    /**
     * 存储所有已调度的任务
     * key: taskId, value: ScheduledFuture
     */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    
    /**
     * 初始化调度器
     * 统一处理程序重启后的任务恢复逻辑：
     * 1. 加载所有待执行的定时任务（PENDING状态）
     * 2. 恢复所有运行中的定时任务（RUNNING状态）
     */
    @PostConstruct
    public void init() {
        log.info("正在初始化群发消息任务调度器...");
        
        try {
            // 1. 加载所有待执行的定时任务（PENDING状态）
//            List<MassMessageTask> pendingTasks = taskRepository.findByStatusAndCronExpressionIsNotNull(
//                    MassMessageTask.TaskStatus.PENDING);
            
//            log.info("发现 {} 个待执行的定时任务", pendingTasks.size());
//
//            for (MassMessageTask task : pendingTasks) {
//                try {
//                    scheduleTask(task);
//                } catch (Exception e) {
//                    log.error("调度任务失败: taskId={}, error={}", task.getId(), e.getMessage(), e);
//                }
//            }
            
            // 2. 恢复所有运行中的任务（RUNNING状态）
            // 所有任务都有cron表达式，直接恢复调度
            List<MassMessageTask> runningTasks = taskRepository.findByStatusAndCronExpressionIsNotNull(
                    MassMessageTask.TaskStatus.RUNNING);
            
            log.info("发现 {} 个运行中的任务，需要恢复执行", runningTasks.size());
            
            int recoveredCount = 0;
            for (MassMessageTask task : runningTasks) {
                try {
                    // 将状态改为PENDING，然后重新调度
//                    task.setStatus(MassMessageTask.TaskStatus.PENDING);
//                    taskRepository.save(task);
                    scheduleTask(task);
                    log.info("恢复定时任务: taskId={}, cron={}", task.getId(), task.getCronExpression());
                    recoveredCount++;
                } catch (Exception e) {
                    log.error("恢复运行中任务失败: taskId={}, error={}", task.getId(), e.getMessage(), e);
                }
            }
            
            if (recoveredCount > 0) {
                log.info("已恢复 {} 个定时任务", recoveredCount);
            }
            
            log.info("群发消息任务调度器初始化完成");
        } catch (Exception e) {
            log.error("初始化群发消息任务调度器失败", e);
        }
    }
    
    /**
     * 调度任务
     * 
     * @param task 任务实体
     */
    public void scheduleTask(MassMessageTask task) {
        // 验证cron表达式（不允许为空）
        if (task.getCronExpression() == null || task.getCronExpression().trim().isEmpty()) {
            throw new IllegalArgumentException("任务必须有cron表达式: taskId=" + task.getId());
        }
        
        // 验证cron表达式格式
        try {
            CronExpression.parse(task.getCronExpression());
        } catch (Exception e) {
            log.error("无效的cron表达式: taskId={}, cron={}, error={}", 
                     task.getId(), task.getCronExpression(), e.getMessage());
            throw new IllegalArgumentException("无效的cron表达式: " + task.getCronExpression(), e);
        }
        
        // 如果任务已经调度，先取消（静默取消，不记录日志）
        ScheduledFuture<?> existingFuture = scheduledTasks.remove(task.getId());
        if (existingFuture != null && !existingFuture.isDone()) {
            existingFuture.cancel(false);
            log.debug("已取消现有任务调度: taskId={}", task.getId());
        }
        
        // 计算下次执行时间
        LocalDateTime nextExecuteTime = calculateNextExecuteTime(task.getCronExpression());
        task.setNextExecuteTime(nextExecuteTime);
        taskRepository.save(task);
        
        // 创建调度任务
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> executeScheduledTask(task.getId()),
                createTrigger(task.getCronExpression())
        );
        
        scheduledTasks.put(task.getId(), future);
        
        log.info("任务已调度: taskId={}, cron={}, nextExecuteTime={}", 
                task.getId(), task.getCronExpression(), nextExecuteTime);
    }
    
    /**
     * 执行定时任务
     * 
     * @param taskId 任务ID
     */
    private void executeScheduledTask(String taskId) {
        try {
            MassMessageTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
            
            // 检查任务状态
            // 只有PENDING状态的任务才能执行
            // PAUSED状态的任务应该跳过执行，不重新调度
            if (task.getStatus() == MassMessageTask.TaskStatus.PAUSED) {
                log.debug("任务已暂停，跳过执行: taskId={}", taskId);
                return;
            }
            
            if (task.getStatus() != MassMessageTask.TaskStatus.RUNNING) {
                log.warn("任务状态不是运行，跳过执行: taskId={}, status={}",
                        taskId, task.getStatus());
                return;
            }
            
            // 更新任务状态为运行中
            task.setStatus(MassMessageTask.TaskStatus.RUNNING);
            task.setLastExecuteTime(LocalDateTime.now());
            taskRepository.save(task);
            
            // 执行任务（异步执行）
            // 注意：任务执行完成后，会在 MassMessageService.executeTask 中更新 nextExecuteTime 并重新调度
            massMessageService.executeTask(taskId);
            
        } catch (Exception e) {
            log.error("执行定时任务失败: taskId={}", taskId, e);
            // 执行失败时，将任务状态设置为FAILED，不重新调度
            // 用户需要手动重新启动任务
            try {
                MassMessageTask task = taskRepository.findById(taskId).orElse(null);
                if (task != null && task.getStatus() == MassMessageTask.TaskStatus.RUNNING) {
                    task.setStatus(MassMessageTask.TaskStatus.FAILED);
                    task.setErrorMessage("执行失败: " + e.getMessage());
                    taskRepository.save(task);
                    log.error("任务执行失败，已标记为FAILED: taskId={}", taskId);
                }
            } catch (Exception ex) {
                log.error("更新任务状态失败: taskId={}", taskId, ex);
            }
        }
    }
    
    /**
     * 创建Cron触发器
     * 
     * @param cronExpression cron表达式
     * @return CronTrigger
     */
    private org.springframework.scheduling.support.CronTrigger createTrigger(String cronExpression) {
        return new org.springframework.scheduling.support.CronTrigger(cronExpression);
    }
    
    /**
     * 计算下次执行时间
     * 
     * @param cronExpression cron表达式
     * @return 下次执行时间
     */
    private LocalDateTime calculateNextExecuteTime(String cronExpression) {
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = cron.next(now);
            return next != null ? next : now.plusDays(1); // 如果计算失败，默认明天
        } catch (Exception e) {
            log.warn("计算下次执行时间失败: cron={}, error={}", cronExpression, e.getMessage());
            return LocalDateTime.now().plusDays(1);
        }
    }
    
    /**
     * 取消任务调度
     * 
     * @param taskId 任务ID
     */
    public void cancelTask(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("已取消任务调度: taskId={}", taskId);
        }
    }
    
    /**
     * 销毁调度器
     */
    @PreDestroy
    public void destroy() {
        log.info("正在关闭群发消息任务调度器...");
        
        // 取消所有调度任务
        scheduledTasks.values().forEach(future -> {
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        });
        
        scheduledTasks.clear();
        log.info("群发消息任务调度器已关闭");
    }
}
