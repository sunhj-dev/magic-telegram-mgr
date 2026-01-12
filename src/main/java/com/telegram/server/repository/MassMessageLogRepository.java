package com.telegram.server.repository;

import com.telegram.server.entity.MassMessageLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
     * 统计指定账号在指定群组中指定日期内的成功发送数量
     * 
     * @param accountPhone 账号手机号
     * @param chatId 群组ID
     * @param status 发送状态
     * @param startTime 开始时间（当天0点）
     * @param endTime 结束时间（当天23:59:59）
     * @return 成功发送的数量
     */
    long countByAccountPhoneAndChatIdAndStatusAndSentTimeBetween(
            String accountPhone, String chatId, MassMessageLog.SendStatus status, LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * 查询指定群组在指定时间范围内的最后一条成功发送记录（限制返回1条）
     * 
     * @param chatId 群组ID
     * @param status 发送状态
     * @param startTime 开始时间
     * @return 最后一条成功发送的日志列表（按时间倒序，最多1条）
     */
    List<MassMessageLog> findTop1ByChatIdAndStatusAndSentTimeAfterOrderBySentTimeDesc(
            String chatId, MassMessageLog.SendStatus status, LocalDateTime startTime);
    
    /**
     * 查询指定群组在指定时间范围内的最后一条成功发送记录
     * 
     * @param chatId 群组ID
     * @param status 发送状态
     * @param startTime 开始时间
     * @return 最后一条成功发送的日志列表（按时间倒序）
     */
    List<MassMessageLog> findFirstByChatIdAndStatusAndSentTimeAfterOrderBySentTimeDesc(
            String chatId, MassMessageLog.SendStatus status, LocalDateTime startTime);
    
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