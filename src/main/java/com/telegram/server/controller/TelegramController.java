package com.telegram.server.controller;

import com.telegram.server.service.TelegramClientManager;
import com.telegram.server.service.impl.TelegramServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 多账号Telegram控制器
 * 
 * 提供完整的多账号Telegram管理功能，包括：
 * 1. 账号创建和初始化
 * 2. API配置管理
 * 3. 认证流程管理（手机号、验证码、密码）
 * 4. 消息监听控制
 * 5. Session数据管理
 * 6. 服务状态监控
 * 
 * 所有操作都需要指定phoneNumber参数来标识目标账号
 * 
 * API路径前缀：/api/telegram
 * 支持跨域访问，适用于前端Web应用调用
 * 
 * @author sunhj
 * @version 2.0
 * @since 2025-08-05
 */
@RestController
@RequestMapping("/telegram")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class TelegramController {
    
    /**
     * Telegram客户端管理器
     * 统一管理所有账号的TelegramServiceImpl实例
     */
    private final TelegramClientManager clientManager;
    
    /**
     * 获取指定账号的服务实例
     * 
     * @param phoneNumber 手机号
     * @return TelegramServiceImpl实例
     */
    private TelegramServiceImpl getAccountService(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("手机号不能为空");
        }
        return clientManager.getAccountService(phoneNumber.trim());
    }
    
    /**
     * 创建并初始化Telegram账号
     * 
     * 初始化单个Telegram账号实例，准备进行API配置和认证流程。
     * 这是使用系统的第一步操作。
     * 
     * @param request 包含phoneNumber的请求体
     * @return ResponseEntity 包含创建结果
     *         - success: 是否创建成功
     *         - message: 操作结果消息
     *         - status: 账号状态信息
     */
    @PostMapping("/account/create")
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 通过TelegramClientManager获取账号服务（如果不存在会自动创建）
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            
            // 初始化账号
            service.initializeAccount();
            
            response.put("success", true);
            response.put("message", "账号创建成功，请配置API信息");
            response.put("status", service.getAuthStatus());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "创建账号失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 配置Telegram API信息
     * 
     * 设置Telegram API的appId和appHash，这是连接Telegram服务的必要凭证。
     * 需要在Telegram官网申请获得这些凭证信息。
     * 
     * @param request 包含phoneNumber、appId和appHash的请求体
     * @return ResponseEntity包含配置操作的结果信息
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> configApi(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            Integer appId = (Integer) request.get("appId");
            String appHash = (String) request.get("appHash");
            
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (appId == null || appHash == null || appHash.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "App ID和App Hash不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            boolean success = service.configApi(appId, appHash);
            if (success) {
                response.put("success", true);
                response.put("message", "API配置成功");
            } else {
                response.put("success", false);
                response.put("message", "API配置失败");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "配置API时发生错误: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 提交手机号码进行认证
     * 
     * Telegram认证流程的第一步，提交手机号码以接收短信验证码。
     * 手机号需要包含国家代码，例如：+8613800138000
     * 
     * @param request 包含phoneNumber字段的请求体
     * @return ResponseEntity包含手机号提交结果
     */
    @PostMapping("/auth/phone")
    public ResponseEntity<Map<String, Object>> submitPhone(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            boolean success = service.submitPhoneNumber(phoneNumber.trim());
            if (success) {
                response.put("success", true);
                response.put("message", "手机号提交成功，请输入验证码");
            } else {
                response.put("success", false);
                response.put("message", "手机号提交失败");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "提交手机号时发生错误: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 提交短信验证码
     * 
     * Telegram认证流程的第二步，提交收到的短信验证码进行验证。
     * 如果账号开启了两步验证，验证码通过后还需要提交密码。
     * 
     * @param request 包含phoneNumber和code字段的请求体
     * @return ResponseEntity包含验证码验证结果，可能需要进一步密码验证
     */
    @PostMapping("/auth/code")
    public ResponseEntity<Map<String, Object>> submitCode(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            String code = (String) request.get("code");
            
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (code == null || code.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "验证码不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            Map<String, Object> result = service.submitAuthCode(code.trim());
            response.putAll(result);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "提交验证码时发生错误: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 提交两步验证密码
     * 
     * Telegram认证流程的第三步（可选），当账号开启两步验证时需要提交密码。
     * 只有在submitCode返回需要密码验证的情况下才需要调用此接口。
     * 
     * @param request 包含phoneNumber和password字段的请求体
     * @return ResponseEntity包含密码验证结果
     */
    @PostMapping("/auth/password")
    public ResponseEntity<Map<String, Object>> submitPassword(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            String password = (String) request.get("password");
            
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (password == null || password.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "密码不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            boolean success = service.submitPassword(password.trim());
            if (success) {
                response.put("success", true);
                response.put("message", "密码验证成功，认证完成");
            } else {
                response.put("success", false);
                response.put("message", "密码验证失败");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "提交密码时发生错误: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取当前认证授权状态
     * 
     * 查询指定账号的认证状态，包括是否已登录、认证进度等信息。
     * 可用于判断是否需要进行认证流程或认证是否已完成。
     * 
     * @param phoneNumber 手机号（查询参数）
     * @return ResponseEntity包含详细的认证状态信息
     */
    @GetMapping("/auth/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus(@RequestParam(required = false) String phoneNumber) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            Map<String, Object> authStatus = service.getAuthStatus();
            response.putAll(authStatus);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取授权状态时发生错误: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 开始消息监听
     * 
     * 启动Telegram消息监听功能，开始接收和处理群组消息。
     * 只有在认证完成后才能成功启动监听。
     * 
     * @param request 包含phoneNumber的请求体
     * @return ResponseEntity包含监听启动结果
     */
    @PostMapping("/listening/start")
    public ResponseEntity<Map<String, Object>> startListening(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            service.startListening();
            response.put("success", true);
            response.put("message", "消息监听已启动");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "启动消息监听失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 停止消息监听
     * 
     * 停止Telegram消息监听功能。
     * 
     * @param request 包含phoneNumber的请求体
     * @return ResponseEntity包含监听停止结果
     */
    @PostMapping("/listening/stop")
    public ResponseEntity<Map<String, Object>> stopListening(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String phoneNumber = (String) request.get("phoneNumber");
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            service.stopListening();
            response.put("success", true);
            response.put("message", "消息监听已停止");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "停止消息监听失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 清理Session数据
     * 
     * 清除指定账号的所有Session数据，包括认证信息和缓存数据。
     * 清理后需要重新进行认证流程。
     * 
     * @param phoneNumber 手机号（查询参数或请求体）
     * @param request 请求体（可选，如果phoneNumber不在查询参数中）
     * @return ResponseEntity包含清理结果
     */
    @DeleteMapping("/session/clear")
    public ResponseEntity<Map<String, Object>> clearSession(
            @RequestParam(required = false) String phoneNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 从查询参数或请求体中获取phoneNumber
            if (phoneNumber == null && request != null) {
                phoneNumber = (String) request.get("phoneNumber");
            }
            
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            service.clearSession();
            
            // 从管理器中移除该账号服务
            clientManager.removeAccountService(phoneNumber.trim());
            
            response.put("success", true);
            response.put("message", "Session数据已清理");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "清理Session失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取服务状态
     * 
     * 返回指定账号的Telegram服务运行状态，包括连接状态、认证状态等信息。
     * 主要用于监控和调试目的。
     * 
     * @param phoneNumber 手机号（查询参数）
     * @return ResponseEntity包含服务状态信息和时间戳
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(required = false) String phoneNumber) {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "Magic Telegram Server");
        status.put("status", "running");
        status.put("description", "多账号Telegram消息监听服务");
        status.put("timestamp", System.currentTimeMillis());
        
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
            try {
                TelegramServiceImpl service = getAccountService(phoneNumber.trim());
                status.put("authStatus", service.getAuthStatus());
                status.put("phoneNumber", phoneNumber);
            } catch (Exception e) {
                status.put("error", "获取账号状态失败: " + e.getMessage());
            }
        } else {
            // 如果没有指定账号，返回所有账号的统计信息
            Map<String, Object> accountStats = clientManager.getAccountStats();
            status.put("accountStats", accountStats);
        }
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 健康检查接口
     * 
     * 提供服务健康状态检查，用于负载均衡器和监控系统检测服务可用性。
     * 返回简单的UP状态表示服务正常运行。
     * 
     * @return ResponseEntity包含健康状态信息
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "telegram-listener");
        
        return ResponseEntity.ok(health);
    }
    
    /**
     * 检查MongoDB中的session数据状态
     * 
     * 用于诊断session数据完整性问题，检查数据库中存储的session信息。
     * 包括认证状态、文件数据、活跃状态等关键信息。
     * 
     * @param phoneNumber 手机号（查询参数）
     * @return ResponseEntity 包含session数据检查结果
     *         - sessions: session列表及详细信息
     *         - summary: 数据统计摘要
     *         - issues: 发现的数据问题
     * 
     * @author sunhj
     * @since 2025-01-20
     */
    @GetMapping("/session/check")
    public ResponseEntity<Map<String, Object>> checkSessionData(@RequestParam(required = false) String phoneNumber) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "手机号不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            TelegramServiceImpl service = getAccountService(phoneNumber.trim());
            Map<String, Object> checkResult = service.checkSessionDataIntegrity();
            response.put("success", true);
            response.put("data", checkResult);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "检查session数据失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}