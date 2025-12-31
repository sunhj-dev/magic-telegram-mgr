package com.telegram.server.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建群发任务DTO
 * @author sunhj
 */
@Data
public class MassMessageTaskDTO {
    /** 任务名称 */
    private String taskName;

    /** 消息内容 */
    private String messageContent;

    /** 目标Chat ID列表 */
    private List<String> targetChatIds;

    /** 消息类型 */
    private String messageType = "TEXT";

    /** 定时执行时间（null表示立即执行） */
    private String scheduleTime;

    private String targetAccountPhone;
}