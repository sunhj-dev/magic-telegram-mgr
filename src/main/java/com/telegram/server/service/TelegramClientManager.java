package com.telegram.server.service;

import com.telegram.server.entity.TelegramSession;
import com.telegram.server.repository.TelegramSessionRepository;
import com.telegram.server.service.impl.TelegramServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author sunhuijun
 * @describe
 * @date 2025年12月31日
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramClientManager {
    private final ApplicationContext applicationContext;
    private final TelegramSessionRepository sessionRepository;

    /**
     * 缓存所有账号的 TelegramServiceImpl 实例
     */
    private final Map<String, TelegramServiceImpl> accountServices = new ConcurrentHashMap<>();

    /**
     * 获取指定账号的 TelegramService 实例
     * 如果不存在则自动创建并初始化
     *
     * @param phoneNumber 账号手机号
     * @return TelegramServiceImpl 实例
     * @throws RuntimeException 如果账号不存在或未认证
     */
    public TelegramServiceImpl getAccountService(String phoneNumber) {
        return accountServices.computeIfAbsent(phoneNumber, this::createAccountService);
    }

    /**
     * 创建账号服务实例
     */
    private TelegramServiceImpl createAccountService(String phoneNumber) {
        log.info("创建账号服务实例: {}", phoneNumber);

        // 从 MongoDB 获取账号信息
        TelegramSession session = sessionRepository.findByPhoneNumber(phoneNumber).orElseThrow(() -> new RuntimeException("账号不存在: " + phoneNumber));

        // 检查认证状态
        if (!"READY".equals(session.getAuthState())) {
            throw new RuntimeException("账号未完成认证: " + phoneNumber);
        }

        try {
            // 从 Spring 获取新的 TelegramServiceImpl 实例（原型作用域）
            TelegramServiceImpl service = applicationContext.getBean(TelegramServiceImpl.class);

            // 设置账号配置
            service.setRuntimeApiId(session.getApiId());
            service.setRuntimeApiHash(session.getApiHash());
            service.setRuntimePhoneNumber(session.getPhoneNumber());

            // 初始化客户端
            service.initializeClient();

            // 等待初始化完成
            Thread.sleep(3000);

            // 验证客户端是否就绪
            if (!service.isClientReady()) {
                throw new RuntimeException("账号客户端初始化失败: " + phoneNumber);
            }

            log.info("账号服务实例创建成功: {}", phoneNumber);
            return service;

        } catch (Exception e) {
            log.error("创建账号服务实例失败: {}", phoneNumber, e);
            // 清理缓存
            accountServices.remove(phoneNumber);
            throw new RuntimeException("创建账号服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 移除账号服务实例
     */
    public void removeAccountService(String phoneNumber) {
        TelegramServiceImpl service = accountServices.remove(phoneNumber);
        if (service != null) {
            try {
                service.shutdown();
                log.info("移除账号服务实例: {}", phoneNumber);
            } catch (Exception e) {
                log.error("移除账号服务实例时出错: {}", phoneNumber, e);
            }
        }
    }

    /**
     * 获取所有可用的账号列表
     */
    public List<String> getAvailableAccounts() {
        return sessionRepository.findAllByAuthState("READY")
                .stream()
                .map(TelegramSession::getPhoneNumber)
                .collect(Collectors.toList());
    }

    /**
     * 检查账号是否在线
     */
    public boolean isAccountOnline(String phoneNumber) {
        TelegramServiceImpl service = accountServices.get(phoneNumber);
        return service != null && service.isClientReady();
    }

    /**
     * 获取账号统计信息
     */
    public Map<String, Object> getAccountStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAccounts", accountServices.size());
        stats.put("onlineAccounts",
                accountServices.values().stream().filter(TelegramServiceImpl::isClientReady).count());
        stats.put("accountList", accountServices.keySet());
        return stats;
    }

    /**
     * 销毁所有账号服务
     */
    public void shutdownAll() {
        log.info("正在关闭所有账号服务...");
        new HashMap<>(accountServices).forEach((phone, service) -> {
            try {
                service.shutdown();
            } catch (Exception e) {
                log.error("关闭账号服务失败: {}", phone, e);
            }
        });
        accountServices.clear();
        log.info("所有账号服务已关闭");
    }
}
