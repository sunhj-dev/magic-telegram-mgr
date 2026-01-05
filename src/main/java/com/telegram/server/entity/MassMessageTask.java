package com.telegram.server.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 群发消息任务实体
 * @author sunhj
 */
@Document(collection = "mass_message_tasks")
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndex(name = "account_phone_idx", def = "{'targetAccountPhone': 1, 'createdTime': -1}")
@Data
@Builder
public class MassMessageTask {
    /** 任务ID */
    @Id
    private String id;

    /** 目标发送账号（单个手机号） */
    private String targetAccountPhone;

    /** 任务名称 */
    private String taskName;

    /** 消息内容 */
    private String messageContent;

    /** 目标Chat ID列表（支持 -100xxx, @username, 123456） */
    private List<String> targetChatIds;

    /** 消息类型：TEXT, IMAGE, FILE */
    private MessageType messageType = MessageType.TEXT;

    /** 任务状态：PENDING, RUNNING, COMPLETED, FAILED, PAUSED */
    private TaskStatus status = TaskStatus.PENDING;

    /** Cron表达式（null表示立即执行，空字符串表示不执行） */
    private String cronExpression;
    
    /** 下次执行时间（由调度器计算） */
    private LocalDateTime nextExecuteTime;

    /** 创建时间 */
    private LocalDateTime createdTime = LocalDateTime.now();

    /** 创建人 */
    private String createdBy;

    /** 成功数量 */
    private int successCount = 0;

    /** 失败数量 */
    private int failureCount = 0;

    /** 最后执行时间 */
    private LocalDateTime lastExecuteTime;

    /** 错误信息（仅在FAILED状态时有效） */
    private String errorMessage;

    /** 消息类型枚举 */
    public enum MessageType {
        TEXT, IMAGE, FILE
    }

    /** 任务状态枚举 */
    public enum TaskStatus {
        PENDING,    // 待处理
        RUNNING,    // 运行中
        COMPLETED,  // 已完成
        FAILED,     // 已失败
        PAUSED      // 已暂停
    }
}