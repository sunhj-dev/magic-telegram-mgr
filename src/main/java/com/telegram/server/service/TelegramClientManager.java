package com.telegram.server.service;

import com.telegram.server.entity.TelegramSession;
import com.telegram.server.repository.TelegramSessionRepository;
import com.telegram.server.service.impl.TelegramServiceImpl;
import com.telegram.server.service.impl.TelegramSessionServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telegram客户端管理器
 * 统一管理所有Telegram账号的客户端实例，实现多账号管理
 * 
 * 主要功能：
 * - 系统启动时自动加载所有已认证的账号
 * - 按需创建和初始化账号服务实例
 * - 统一管理账号生命周期
 * 
 * @author sunhuijun
 * @date 2025年12月31日
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramClientManager {
    private final ApplicationContext applicationContext;
    private final TelegramSessionRepository sessionRepository;
    private final TelegramSessionServiceImpl sessionService;

    /**
     * 缓存所有账号的 TelegramServiceImpl 实例
     */
    private final Map<String, TelegramServiceImpl> accountServices = new ConcurrentHashMap<>();
    
    /**
     * 系统启动时初始化
     * 加载所有已认证的账号并初始化客户端
     */
    @PostConstruct
    public void init() {
        try {
            log.info("正在初始化Telegram客户端管理器...");
            
            // 确保TDLib已初始化
            if (!com.telegram.server.config.TDLibInitializer.isInitialized()) {
                log.warn("TDLib未初始化，等待初始化...");
                // 等待一小段时间，确保TDLibInitializer已完成初始化
                Thread.sleep(1000);
            }
            
            // 初始化Session管理服务
            sessionService.init();
            
            // 加载所有已认证的账号
            List<TelegramSession> readySessions = sessionRepository.findAllByAuthState("READY");
            
            if (readySessions.isEmpty()) {
                log.info("未找到已认证的账号，等待账号创建和认证");
                return;
            }
            
            log.info("发现 {} 个已认证的账号，开始初始化...", readySessions.size());
            
            // 异步初始化所有账号，避免阻塞启动
            for (TelegramSession session : readySessions) {
                String phoneNumber = session.getPhoneNumber();
                try {
                    log.info("正在初始化账号: {}", phoneNumber);
                    // 使用getAccountService会自动创建和初始化
                    getAccountService(phoneNumber);
                    log.info("账号初始化成功: {}", phoneNumber);
                } catch (Exception e) {
                    log.error("初始化账号失败: {}", phoneNumber, e);
                    // 继续初始化其他账号，不因单个账号失败而中断
                }
            }
            
            long onlineCount = accountServices.values().stream()
                    .filter(TelegramServiceImpl::isClientReady)
                    .count();
            
            log.info("Telegram客户端管理器初始化完成: 总账号数={}, 在线账号数={}", 
                    readySessions.size(), onlineCount);
            
        } catch (Exception e) {
            log.error("初始化Telegram客户端管理器失败", e);
            // 不抛出异常，允许应用继续启动
            log.warn("Telegram客户端管理器初始化失败，但应用将继续启动");
        }
    }

    /**
     * 获取指定账号的 TelegramService 实例
     * 如果不存在则自动创建并初始化
     * 根据账号状态选择合适的创建方法：
     * - READY状态：使用createAccountService（已认证账号）
     * - WAITING或其他状态：使用createAccountServiceForNewAccount（未认证账号）
     *
     * @param phoneNumber 账号手机号
     * @return TelegramServiceImpl 实例
     * @throws RuntimeException 如果账号不存在
     */
    public TelegramServiceImpl getAccountService(String phoneNumber) {
        // 先检查缓存
        TelegramServiceImpl service = accountServices.get(phoneNumber);
        if (service != null) {
            // 对于已认证的账号，需要检查客户端是否就绪
            // 对于未认证的账号，只要服务实例存在就可以使用
            Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
            if (sessionOpt.isPresent()) {
                String authState = sessionOpt.get().getAuthState();
                if ("READY".equals(authState)) {
                    // 已认证账号，需要客户端就绪
                    if (service.isClientReady()) {
                        return service;
                    }
                } else {
                    // 未认证账号，服务实例存在即可
                    return service;
                }
            } else if (service.isClientReady()) {
                // 如果MongoDB中没有记录但客户端就绪，也可以使用
                return service;
            }
        }
        
        // 如果缓存中没有或服务不可用，尝试创建新的
        synchronized (this) {
            // 双重检查
            service = accountServices.get(phoneNumber);
            if (service != null) {
                Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
                if (sessionOpt.isPresent()) {
                    String authState = sessionOpt.get().getAuthState();
                    if ("READY".equals(authState)) {
                        if (service.isClientReady()) {
                            return service;
                        }
                    } else {
                        return service;
                    }
                } else if (service.isClientReady()) {
                    return service;
                }
            }
            
            // 如果之前的服务存在但不可用，先清理
            if (service != null) {
                try {
                    log.info("检测到不可用的service实例，正在关闭: {}", phoneNumber);
                    service.shutdown();
                    // 等待一段时间确保资源释放，特别是文件锁
                    Thread.sleep(2000);
                    log.info("已关闭旧的service实例: {}", phoneNumber);
                } catch (Exception e) {
                    log.warn("关闭不可用的service实例时出错: {}", phoneNumber, e);
                }
                accountServices.remove(phoneNumber);
            }
            
            // 根据账号状态选择合适的创建方法
            try {
                Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
                if (sessionOpt.isPresent()) {
                    TelegramSession session = sessionOpt.get();
                    String authState = session.getAuthState();
                    
                    if ("READY".equals(authState)) {
                        // 已认证账号，使用标准方法
                        service = createAccountService(phoneNumber);
                    } else {
                        // 未认证账号，使用新账号方法
                        service = createAccountServiceForNewAccount(phoneNumber);
                    }
                } else {
                    // MongoDB中没有记录，尝试使用新账号方法（可能正在创建中）
                    log.warn("MongoDB中未找到账号记录，尝试创建新账号服务: {}", phoneNumber);
                    service = createAccountServiceForNewAccount(phoneNumber);
                }
                
                accountServices.put(phoneNumber, service);
                return service;
            } catch (Exception e) {
                // 确保失败时不会留下无效的缓存
                accountServices.remove(phoneNumber);
                throw e;
            }
        }
    }

    /**
     * 创建账号服务实例（用于已认证的账号）
     */
    private TelegramServiceImpl createAccountService(String phoneNumber) {
        log.info("创建账号服务实例: {}", phoneNumber);

        // 从 MongoDB 获取账号信息
        TelegramSession session = sessionRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("账号不存在: " + phoneNumber));

        // 检查认证状态
        if (!"READY".equals(session.getAuthState())) {
            throw new RuntimeException("账号未完成认证: " + phoneNumber + ", 当前状态: " + session.getAuthState());
        }

        TelegramServiceImpl service = null;
        try {
            // 从 Spring 获取新的 TelegramServiceImpl 实例（原型作用域）
            service = applicationContext.getBean(TelegramServiceImpl.class);

            // 设置账号配置（注意：setRuntimePhoneNumber必须在最后调用，因为它会设置账号特定的路径）
            service.setRuntimeApiId(session.getApiId());
            service.setRuntimeApiHash(session.getApiHash());
            // 设置手机号（这会自动设置账号特定的session路径）
            service.setRuntimePhoneNumber(session.getPhoneNumber());

            // 初始化客户端
            service.initializeClient();

            // 等待初始化完成，使用更智能的等待机制
            if (!waitForClientReady(service, 30)) {
                throw new RuntimeException("账号客户端初始化超时: " + phoneNumber + " (等待30秒后仍未就绪)");
            }

            log.info("账号服务实例创建成功: {}", phoneNumber);
            return service;

        } catch (Exception e) {
            log.error("创建账号服务实例失败: {}", phoneNumber, e);
            // 注意：不能在computeIfAbsent的回调中直接remove，会导致Recursive update错误
            // 如果初始化失败，让异常抛出，computeIfAbsent会自动处理
            if (service != null) {
                try {
                    service.shutdown();
                } catch (Exception shutdownEx) {
                    log.warn("关闭失败的service实例时出错: {}", phoneNumber, shutdownEx);
                }
            }
            throw new RuntimeException("账号验证失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建新账号的服务实例（用于未认证的账号）
     * 这个方法用于创建新账号，不需要等待客户端就绪
     * 
     * @param phoneNumber 手机号
     * @return TelegramServiceImpl实例
     */
    public TelegramServiceImpl createAccountServiceForNewAccount(String phoneNumber) {
        log.info("创建新账号服务实例: {}", phoneNumber);

        // 从 MongoDB 获取账号信息（应该已由createAccount创建）
        TelegramSession session = sessionRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("账号不存在: " + phoneNumber));

        TelegramServiceImpl service = null;
        try {
            // 从 Spring 获取新的 TelegramServiceImpl 实例（原型作用域）
            service = applicationContext.getBean(TelegramServiceImpl.class);

            // 设置账号配置
            // 对于新账号，apiId和apiHash可能为null（等待后续配置）
            if (session.getApiId() != null) {
                service.setRuntimeApiId(session.getApiId());
            }
            if (session.getApiHash() != null) {
                service.setRuntimeApiHash(session.getApiHash());
            }
            // 设置手机号（这会自动设置账号特定的session路径）
            service.setRuntimePhoneNumber(session.getPhoneNumber());

            // 对于新账号，不立即初始化客户端，等待API配置后再初始化
            // initializeAccount() 方法会处理初始化逻辑
            
            // 将服务实例添加到缓存
            accountServices.put(phoneNumber, service);
            
            log.info("新账号服务实例创建成功: {}", phoneNumber);
            return service;

        } catch (Exception e) {
            log.error("创建新账号服务实例失败: {}", phoneNumber, e);
            if (service != null) {
                try {
                    service.shutdown();
                } catch (Exception shutdownEx) {
                    log.warn("关闭失败的service实例时出错: {}", phoneNumber, shutdownEx);
                }
            }
            accountServices.remove(phoneNumber);
            throw new RuntimeException("创建新账号失败: " + e.getMessage(), e);
        }
    }

    /**
     * 等待客户端就绪
     * 
     * @param service TelegramServiceImpl实例
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否就绪
     */
    private boolean waitForClientReady(TelegramServiceImpl service, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (service.isClientReady()) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("客户端已就绪: 等待时间 {} ms", elapsed);
                return true;
            }
            
            try {
                Thread.sleep(500); // 每500ms检查一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待客户端就绪时被中断");
                return false;
            }
        }
        
        log.warn("等待客户端就绪超时: {} 秒", timeoutSeconds);
        return false;
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
