package com.telegram.server.dto;

import com.telegram.server.entity.MassMessageLog;
import com.telegram.server.entity.MassMessageTask;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/**
 * 任务详情视图对象
 * @author sunhj
 */
@Data
@AllArgsConstructor
public class TaskDetailVO {
    /** 任务信息 */
    private MassMessageTask task;

    /** 发送日志列表 */
    private List<MassMessageLog> logs;
}