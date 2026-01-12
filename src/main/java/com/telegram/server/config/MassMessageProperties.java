package com.telegram.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 群发消息配置属性
 * @author sunhj
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "telegram.mass-message")
public class MassMessageProperties {

    /** 是否启用群发功能 */
    private boolean enabled = true;

    /** 每条消息间隔（毫秒），建议5000以上 */
    private int rateLimitDelay = 5000;

    /** 单次任务最大目标数量 */
    private int maxTargetsPerTask = 100;

    /** 每日单账号发送上限 */
    private int dailyLimitPerAccount = 100;

    /** 同群组最小发送间隔（秒），避免短时间内重复发送到同一群组 */
    private int minIntervalPerChat = 60;

    /** 429错误重试次数 */
    private int retryOn429 = 3;

    /** 429错误退避倍数 */
    private double backoffMultiplier = 2.0;

    /** 最大延迟时间（毫秒） */
    private int maxDelay = 60000;

    /** 启用内容审核 */
    private boolean enableModeration = true;

    private int batchLogSize = 100;

    /** 禁止发送的群组ID黑名单 */
    private List<String> restrictedChats = List.of();

    /** 敏感词列表 */
    private List<String> sensitiveWords = List.of("赌博", "色情", "诈骗");
}