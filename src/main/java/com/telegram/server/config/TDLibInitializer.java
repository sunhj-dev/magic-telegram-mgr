package com.telegram.server.config;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.SimpleTelegramClientFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TDLib初始化器
 * 确保TDLib库只初始化一次，供所有TelegramServiceImpl实例共享
 * 
 * @author sunhj
 * @date 2026-01-05
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class TDLibInitializer {
    
    private static volatile boolean initialized = false;
    private static final Object lock = new Object();
    private static SimpleTelegramClientFactory sharedClientFactory;
    
    /**
     * 初始化TDLib库
     * 只初始化一次，后续调用直接返回已创建的工厂
     */
    @PostConstruct
    public void init() {
        synchronized (lock) {
            if (!initialized) {
                try {
                    log.info("正在初始化TDLight库...");
                    
                    // 初始化TDLight原生库
                    Init.init();
                    
                    // 设置日志级别
                    Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());
                    
                    // 创建客户端工厂
                    sharedClientFactory = new SimpleTelegramClientFactory();
                    
                    initialized = true;
                    log.info("TDLight库初始化完成");
                } catch (Exception e) {
                    log.error("初始化TDLight库失败", e);
                    throw new RuntimeException("TDLight库初始化失败", e);
                }
            } else {
                log.debug("TDLight库已经初始化，跳过重复初始化");
            }
        }
    }
    
    /**
     * 获取共享的客户端工厂
     * 
     * @return SimpleTelegramClientFactory实例
     * @throws IllegalStateException 如果TDLib未初始化
     */
    public static SimpleTelegramClientFactory getClientFactory() {
        if (!initialized || sharedClientFactory == null) {
            throw new IllegalStateException("TDLib未初始化，请确保TDLibInitializer已初始化");
        }
        return sharedClientFactory;
    }
    
    /**
     * 检查TDLib是否已初始化
     * 
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
