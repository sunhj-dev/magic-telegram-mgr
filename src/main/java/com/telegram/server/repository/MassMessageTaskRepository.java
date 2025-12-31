package com.telegram.server.repository;

import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.entity.TelegramMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 群发任务数据访问层
 *
 * @author sunhj
 */
@Repository
public interface MassMessageTaskRepository extends MongoRepository<MassMessageTask, String> {

//    long countByStatus(MassMessageTask.TaskStatus status);

    List<MassMessageTask> findByTargetAccountPhone(String phoneNumber);

    @Query("{'accountPhone': ?0, 'chatId': ?1}")
    Page<TelegramMessage> findLatestMessagesByAccountAndChat(
            String accountPhone, Long chatId, Pageable pageable);

    /**
     * 查询所有运行中的任务
     */
    List<MassMessageTask> findByStatus(MassMessageTask.TaskStatus status);

    /**
     * 统计各状态任务数量
     */
    default MassMessageStats getStats() {
        long total = count();
        long running = countByStatus(MassMessageTask.TaskStatus.RUNNING);
        long completed = countByStatus(MassMessageTask.TaskStatus.COMPLETED);
        long failed = countByStatus(MassMessageTask.TaskStatus.FAILED);

        return new MassMessageStats(total, running, completed, failed);
    }

    // 需要自定义实现统计查询
    long countByStatus(MassMessageTask.TaskStatus status);
}

/**
 * 统计结果包装类
 */
class MassMessageStats {
    private final long total;
    private final long running;
    private final long completed;
    private final long failed;

    public MassMessageStats(long total, long running, long completed, long failed) {
        this.total = total;
        this.running = running;
        this.completed = completed;
        this.failed = failed;
    }

    // Getters
    public long getTotal() {
        return total;
    }

    public long getRunning() {
        return running;
    }

    public long getCompleted() {
        return completed;
    }

    public long getFailed() {
        return failed;
    }
}
