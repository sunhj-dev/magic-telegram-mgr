package com.telegram.server.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * 群发消息发送日志
 *
 * @author sunhj
 */
@Document(collection = "mass_message_logs")
@CompoundIndex(name = "task_id_idx", def = "{'taskId': 1, 'createdTime': -1}")
@Data
@Builder
public class MassMessageLog {
    /**
     * 日志ID
     */
    @Id
    private String id;

    /**
     * 关联的任务ID
     */
    private String taskId;

    /**
     * 目标Chat ID
     */
    private String chatId;

    /**
     * 群组/频道名称（如果可用）
     */
    private String chatTitle;

    /**
     * 发送状态：SUCCESS, FAILED
     */
    private SendStatus status;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 发送时间
     */
    private LocalDateTime sentTime = LocalDateTime.now();

    /**
     * 发送状态枚举
     */
    public enum SendStatus {
        SUCCESS, FAILED
    }
}