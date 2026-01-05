package com.telegram.server.repository;

import com.telegram.server.entity.MassMessageLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 群发日志数据访问层
 *
 * @author sunhj
 */
@Repository
public interface MassMessageLogRepository extends MongoRepository<MassMessageLog, String> {

    /**
     * 根据任务ID查询日志
     */
    List<MassMessageLog> findByTaskId(String taskId);

    /**
     * 根据任务ID和状态查询
     */
    List<MassMessageLog> findByTaskIdAndStatus(String taskId, MassMessageLog.SendStatus status);
    
    /**
     * 根据任务ID删除所有日志
     * 注意：Spring Data MongoDB 可能不支持直接返回删除数量，
     * 这里先查询再删除，确保删除操作成功
     * 
     * @param taskId 任务ID
     */
    default void deleteByTaskId(String taskId) {
        List<MassMessageLog> logs = findByTaskId(taskId);
        if (!logs.isEmpty()) {
            deleteAll(logs);
        }
    }
}