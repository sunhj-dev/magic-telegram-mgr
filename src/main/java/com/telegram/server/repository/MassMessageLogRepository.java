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
}