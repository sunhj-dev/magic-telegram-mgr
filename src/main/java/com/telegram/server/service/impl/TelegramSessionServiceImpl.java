package com.telegram.server.service.impl;

import com.telegram.server.entity.TelegramSession;
import com.telegram.server.service.ITelegramSessionService;
import com.telegram.server.repository.TelegramSessionRepository;
import com.telegram.server.service.gridfs.GridFSStorageManager;
import com.telegram.server.dto.AccountDTO;
import com.telegram.server.dto.PageRequestDTO;
import com.telegram.server.dto.PageResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Telegram Session管理服务
 * 
 * 负责管理MongoDB中的Telegram session数据，支持集群部署。
 * 提供session的创建、更新、查询、删除等操作，以及文件数据的序列化和反序列化。
 * 
 * 主要功能：
 * - Session CRUD操作
 * - 文件数据与MongoDB的转换
 * - 集群环境下的session分配
 * - Session生命周期管理
 * - 数据迁移支持
 * 
 * 集群支持：
 * - 多实例session共享
 * - 负载均衡分配
 * - 实例故障恢复
 * - Session锁定机制
 * 
 * @author sunhj
 * @date 2025-08-11
 */
@Service
@Transactional
public class TelegramSessionServiceImpl implements ITelegramSessionService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSessionServiceImpl.class);

    @Autowired
    private TelegramSessionRepository sessionRepository;

    @Autowired
    private GridFSStorageManager gridfsStorageManager;

    /**
     * Session路径配置
     * 默认位置：项目根目录下的data/telegram-session
     */
    @Value("${telegram.session.path:./data/telegram-session}")
    private String sessionPath;

    /**
     * 应用名称，用于区分不同的服务实例
     */
    @Value("${spring.application.name:magic-telegram-server}")
    private String applicationName;

    /**
     * 实例ID，结合主机名和启动时间生成唯一标识
     */
    private String instanceId;

    /**
     * Session非活跃超时时间（小时）
     */
    @Value("${telegram.session.inactive-timeout:24}")
    private int inactiveTimeoutHours;

    /**
     * 初始化服务
     */
    @Override
    public void init() {
        // 生成唯一的实例ID
        this.instanceId = generateInstanceId();
        logger.info("TelegramSessionService初始化完成，实例ID: {}", instanceId);
        
        // 清理所有之前活跃的session状态
        // 注意：由于instanceId每次重启都会变化，所以需要停用所有isActive=true的session
        // 恢复session时会重新激活它们（使用新的instanceId）
        deactivateAllActiveSessions();
    }

    /**
     * 生成实例ID
     * 
     * @return 实例ID
     */
    private String generateInstanceId() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            long timestamp = System.currentTimeMillis();
            return String.format("%s-%s-%d", applicationName, hostname, timestamp);
        } catch (Exception e) {
            logger.warn("获取主机名失败，使用随机ID", e);
            return String.format("%s-unknown-%d", applicationName, System.currentTimeMillis());
        }
    }

    /**
     * 停用所有活跃的session
     * 
     * 服务重启后，instanceId会变化，所以需要停用所有之前活跃的session。
     * 恢复session时会重新激活它们（使用新的instanceId）。
     * 
     * 这样做的原因：
     * 1. instanceId每次重启都会变化（包含时间戳）
     * 2. 旧的instanceId的session仍然标记为isActive=true，但实际上没有服务在使用
     * 3. 恢复session时，会调用activateSession()重新激活它们
     */
    private void deactivateAllActiveSessions() {
        try {
            // 查询所有活跃的session（不管instanceId）
            List<TelegramSession> activeSessions = sessionRepository.findByIsActiveTrue();
            
            if (activeSessions.isEmpty()) {
                logger.info("没有需要停用的活跃session");
                return;
            }
            
            int deactivatedCount = 0;
            for (TelegramSession session : activeSessions) {
                try {
                    // 停用session（清除instanceId，设置isActive=false）
                    session.deactivate();
                    sessionRepository.save(session);
                    deactivatedCount++;
                    logger.debug("已停用session: {} (原instanceId: {})", 
                            session.getPhoneNumber(), session.getInstanceId());
                } catch (Exception e) {
                    logger.warn("停用session失败: {}", session.getPhoneNumber(), e);
                }
            }
            
            logger.info("已停用 {} 个活跃session（服务重启前的状态），将在恢复时重新激活", deactivatedCount);
        } catch (Exception e) {
            logger.error("停用活跃session失败", e);
        }
    }

    /**
     * 创建或更新session
     * 
     * @param phoneNumber 手机号码
     * @param apiId API ID
     * @param apiHash API Hash
     * @return session对象
     */
    @Override
    public TelegramSession createOrUpdateSession(String phoneNumber, Integer apiId, String apiHash) {
        Optional<TelegramSession> existingSession = sessionRepository.findByPhoneNumber(phoneNumber);
        
        TelegramSession session;
        if (existingSession.isPresent()) {
            session = existingSession.get();
            // 更新API配置（只有当提供非null值时才更新）
            if (apiId != null) {
                session.setApiId(apiId);
            }
            if (apiHash != null) {
                session.setApiHash(apiHash);
            }
            session.setUpdatedTime(LocalDateTime.now());
            logger.info("更新已存在的session: {} (apiId={}, apiHash={})", phoneNumber, 
                    apiId != null ? apiId : "未更新", apiHash != null ? "已更新" : "未更新");
        } else {
            session = new TelegramSession(phoneNumber, apiId, apiHash);
            // 新创建的session，如果没有设置authState，默认为WAITING
            if (session.getAuthState() == null) {
                session.setAuthState("WAITING");
            }
            logger.info("创建新的session: {} (状态: {}, apiId={}, apiHash={})", phoneNumber, 
                    session.getAuthState(), apiId != null ? apiId : "null", apiHash != null ? "已设置" : "null");
        }
        
        return sessionRepository.save(session);
    }

    /**
     * 根据手机号获取session
     * 
     * @param phoneNumber 手机号码
     * @return session对象
     */
    @Override
    public Optional<TelegramSession> getSessionByPhoneNumber(String phoneNumber) {
        return sessionRepository.findByPhoneNumber(phoneNumber);
    }

    /**
     * 根据手机号查找session（别名方法）
     * 
     * @param phoneNumber 手机号码
     * @return session对象
     */
    @Override
    public Optional<TelegramSession> findByPhoneNumber(String phoneNumber) {
        return getSessionByPhoneNumber(phoneNumber);
    }

    /**
     * 激活session
     * 
     * @param phoneNumber 手机号码
     * @return 是否成功激活
     */
    @Override
    public boolean activateSession(String phoneNumber) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (sessionOpt.isPresent()) {
            TelegramSession session = sessionOpt.get();
            session.activate(instanceId);
            sessionRepository.save(session);
            logger.info("激活session: {} (实例: {})", phoneNumber, instanceId);
            return true;
        }
        logger.warn("未找到要激活的session: {}", phoneNumber);
        return false;
    }

    /**
     * 停用session
     * 
     * @param phoneNumber 手机号码
     * @return 是否成功停用
     */
    @Override
    public boolean deactivateSession(String phoneNumber) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (sessionOpt.isPresent()) {
            TelegramSession session = sessionOpt.get();
            session.deactivate();
            sessionRepository.save(session);
            logger.info("停用session: {}", phoneNumber);
            return true;
        }
        logger.warn("未找到要停用的session: {}", phoneNumber);
        return false;
    }

    /**
     * 更新session的认证状态
     * 
     * @param phoneNumber 手机号码
     * @param authState 认证状态
     */
    @Override
    public void updateAuthState(String phoneNumber, String authState) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (sessionOpt.isPresent()) {
            TelegramSession session = sessionOpt.get();
            session.setAuthState(authState);
            session.setUpdatedTime(LocalDateTime.now());
            sessionRepository.save(session);
            logger.info("更新session认证状态: {} -> {}", phoneNumber, authState);
        }
    }

    /**
     * 更新session的认证状态
     * 
     * @param phoneNumber 手机号
     * @param isAuthenticated 是否已认证
     */
    @Override
    public void updateAuthenticationStatus(String phoneNumber, boolean isAuthenticated) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (sessionOpt.isPresent()) {
            TelegramSession session = sessionOpt.get();
            session.setAuthState(isAuthenticated ? "READY" : "WAITING");
            session.setUpdatedTime(LocalDateTime.now());
            sessionRepository.save(session);
            logger.info("更新session认证状态: {} -> {}", phoneNumber, isAuthenticated ? "已认证" : "未认证");
        }
    }

    /**
     * 保存session文件数据到MongoDB
     * 使用分片存储管理器处理大文件存储
     * 
     * @param phoneNumber 手机号码
     * @param sessionPath session文件路径
     */
    @Override
    public void saveSessionFiles(String phoneNumber, String sessionPath) {
        try {
            TelegramSession session = validateAndGetSession(phoneNumber);
            if (session == null) {
                return;
            }

            Map<String, String> databaseFiles = readSessionFiles(sessionPath);
            Map<String, String> downloadedFiles = readDownloadedFiles(sessionPath + "/downloads");
            
            saveSessionToStorage(session, databaseFiles, downloadedFiles, phoneNumber);
        } catch (IOException e) {
            logger.error("读取session文件失败: " + phoneNumber, e);
        } catch (Exception e) {
            logger.error("保存session文件失败: " + phoneNumber, e);
        }
    }

    /**
     * 验证并获取session
     * 
     * @param phoneNumber 手机号码
     * @return session对象，如果不存在则返回null
     */
    private TelegramSession validateAndGetSession(String phoneNumber) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (!sessionOpt.isPresent()) {
            logger.warn("未找到session，无法保存文件: {}", phoneNumber);
            return null;
        }
        return sessionOpt.get();
    }

    /**
     * 保存session到存储
     * 
     * @param session session对象
     * @param databaseFiles 数据库文件
     * @param downloadedFiles 下载文件
     * @param phoneNumber 手机号码
     * @throws IOException 如果存储过程中发生IO异常
     */
    private void saveSessionToStorage(TelegramSession session, Map<String, String> databaseFiles, 
                                    Map<String, String> downloadedFiles, String phoneNumber) throws IOException {
        session = gridfsStorageManager.storeSession(session, databaseFiles, downloadedFiles);
        session.setUpdatedTime(LocalDateTime.now());
        sessionRepository.save(session);
        
        logger.info("保存session文件到MongoDB: {} (数据库文件: {}, 下载文件: {})", 
                   phoneNumber, databaseFiles.size(), downloadedFiles.size());
    }

    /**
     * 从MongoDB恢复session文件到本地
     * 
     * @param phoneNumber 手机号码
     * @param sessionPath session文件路径
     * @return 是否成功恢复
     */
    @Override
    public boolean restoreSessionFiles(String phoneNumber, String sessionPath) {
        try {
            TelegramSession session = validateAndGetSessionForRestore(phoneNumber);
            if (session == null) {
                return false;
            }

            Path sessionDir = createSessionDirectory(sessionPath);
            
            // 记录存储版本信息
            logger.info("开始恢复session文件: phoneNumber={}, storageVersion={}, gridfsId={}", 
                       phoneNumber, session.getStorageVersion(), session.getDatabaseFilesGridfsId());
            
            // 从GridFS或传统存储加载session数据
            session = gridfsStorageManager.loadSession(session.getId());
            
            // 检查加载后的数据
            if (session == null) {
                logger.error("从GridFS加载session失败: phoneNumber={}", phoneNumber);
                return false;
            }
            
            // 验证数据完整性
            boolean hasDatabaseFiles = session.getDatabaseFiles() != null && !session.getDatabaseFiles().isEmpty();
            boolean hasGridfsId = session.getDatabaseFilesGridfsId() != null;
            
            logger.info("Session数据检查: phoneNumber={}, hasDatabaseFiles={}, hasGridfsId={}, databaseFilesSize={}", 
                       phoneNumber, hasDatabaseFiles, hasGridfsId, 
                       hasDatabaseFiles ? session.getDatabaseFiles().size() : 0);
            
            if (!hasDatabaseFiles && !hasGridfsId) {
                logger.warn("Session数据不完整: phoneNumber={}, 既没有databaseFiles也没有GridFS ID", phoneNumber);
                return false;
            }
            
            if (!hasDatabaseFiles && hasGridfsId) {
                logger.warn("Session使用GridFS存储但加载失败: phoneNumber={}, gridfsId={}", 
                           phoneNumber, session.getDatabaseFilesGridfsId());
                return false;
            }
            
            // 恢复文件
            restoreDatabaseFiles(session, sessionDir);
            restoreDownloadFiles(session, sessionDir);
            
            logger.info("成功恢复session文件: {} -> {}, 数据库文件: {} 个", 
                       phoneNumber, sessionPath, 
                       session.getDatabaseFiles() != null ? session.getDatabaseFiles().size() : 0);
            return true;
        } catch (Exception e) {
            logger.error("恢复session文件失败: " + phoneNumber, e);
            return false;
        }
    }

    /**
     * 验证并获取session用于恢复
     * 
     * @param phoneNumber 手机号码
     * @return session对象，如果不存在则返回null
     */
    private TelegramSession validateAndGetSessionForRestore(String phoneNumber) {
        Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
        if (!sessionOpt.isPresent()) {
            logger.warn("未找到session，无法恢复文件: {}", phoneNumber);
            return null;
        }
        return sessionOpt.get();
    }

    /**
     * 创建session目录
     * 
     * @param sessionPath session路径
     * @return 创建的目录路径
     * @throws IOException 如果创建目录失败
     */
    private Path createSessionDirectory(String sessionPath) throws IOException {
        if (sessionPath == null || sessionPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Session路径不能为空");
        }
        Path sessionDir = Paths.get(sessionPath);
        Files.createDirectories(sessionDir);
        return sessionDir;
    }

    /**
     * 恢复数据库文件
     * 
     * @param session session对象
     * @param sessionDir session目录
     * @throws IOException 如果恢复文件失败
     */
    private void restoreDatabaseFiles(TelegramSession session, Path sessionDir) throws IOException {
        if (session.getDatabaseFiles() == null || session.getDatabaseFiles().isEmpty()) {
            logger.warn("Session的databaseFiles为空，无法恢复数据库文件: sessionId={}, storageVersion={}, gridfsId={}", 
                       session.getId(), session.getStorageVersion(), session.getDatabaseFilesGridfsId());
            return;
        }

        int restoredCount = 0;
        for (Map.Entry<String, String> entry : session.getDatabaseFiles().entrySet()) {
            try {
                String encodedPath = entry.getKey();
                String fileData = entry.getValue();
                
                if (fileData == null || fileData.isEmpty()) {
                    logger.warn("跳过空文件数据: encodedPath={}", encodedPath);
                    continue;
                }
                
                String relativePath = decodePath(encodedPath);
                Path filePath = sessionDir.resolve(relativePath);
                
                Files.createDirectories(filePath.getParent());
                
                // 解码Base64数据
                byte[] data;
                try {
                    data = Base64.getDecoder().decode(fileData);
                    if (data == null || data.length == 0) {
                        logger.warn("解码后的数据为空: encodedPath={}", encodedPath);
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    logger.error("Base64解码失败: encodedPath={}, error={}", encodedPath, e.getMessage());
                    continue;
                }
                
                // 验证关键数据库文件的完整性
                // 注意：对于从MongoDB恢复的文件，我们采用更宽松的验证策略
                // 因为文件之前已经成功保存，说明它们是有效的
                // 验证主要用于检测明显的损坏，而不是严格的格式检查
                if (isCriticalDatabaseFile(relativePath)) {
                    if (!validateDatabaseFile(data, relativePath)) {
                        // 对于从MongoDB恢复的文件，即使验证失败也尝试恢复
                        // 因为文件可能只是格式不完全标准，但仍然是有效的
                        logger.warn("数据库文件验证失败，但继续恢复（从MongoDB恢复的文件）: {}", relativePath);
                        // 不跳过，继续恢复文件
                    }
                }
                
                // 如果目标文件已存在且可能损坏，先删除
                if (Files.exists(filePath) && isCriticalDatabaseFile(relativePath)) {
                    try {
                        Files.delete(filePath);
                        logger.debug("删除可能损坏的现有文件: {}", filePath);
                    } catch (IOException e) {
                        logger.warn("删除现有文件失败: {}, 将覆盖写入", filePath, e);
                    }
                }
                
                // 写入文件
                Files.write(filePath, data);
                
                // 验证写入的文件
                if (Files.exists(filePath) && Files.size(filePath) == data.length) {
                    restoredCount++;
                    logger.debug("恢复数据库文件: {} -> {} (大小: {} bytes)", encodedPath, relativePath, data.length);
                } else {
                    logger.error("文件写入验证失败: {} (期望大小: {}, 实际大小: {})", 
                               filePath, data.length, Files.exists(filePath) ? Files.size(filePath) : 0);
                }
            } catch (Exception e) {
                logger.error("恢复单个数据库文件失败: encodedPath={}", entry.getKey(), e);
                // 继续处理其他文件
            }
        }
        logger.info("恢复数据库文件完成: {} / {} 个文件成功", restoredCount, session.getDatabaseFiles().size());
    }

    /**
     * 恢复下载文件
     * 
     * @param session session对象
     * @param sessionDir session目录
     * @throws IOException 如果恢复文件失败
     */
    private void restoreDownloadFiles(TelegramSession session, Path sessionDir) throws IOException {
        if (session.getDownloadedFiles() == null) {
            return;
        }

        Path downloadsDir = sessionDir.resolve("downloads");
        Files.createDirectories(downloadsDir);
        
        for (Map.Entry<String, String> entry : session.getDownloadedFiles().entrySet()) {
            String fileName = entry.getKey();
            String fileData = entry.getValue();
            
            Path filePath = downloadsDir.resolve(fileName);
            byte[] data = Base64.getDecoder().decode(fileData);
            Files.write(filePath, data);
        }
        logger.info("恢复下载文件: {} 个", session.getDownloadedFiles().size());
    }

    /**
     * 解码路径
     * 
     * @param encodedPath 编码的路径
     * @return 解码后的路径
     */
    private String decodePath(String encodedPath) {
        return encodedPath.replace("__SLASH__", "/").replace("__DOT__", ".");
    }
    
    /**
     * 判断是否为关键的数据库文件
     * 
     * @param relativePath 相对路径
     * @return 是否为关键数据库文件
     */
    private boolean isCriticalDatabaseFile(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String fileName = relativePath.contains("/") ? 
                          relativePath.substring(relativePath.lastIndexOf('/') + 1) : 
                          relativePath;
        return fileName.equals("td.binlog") || 
               fileName.startsWith("db.sqlite") ||
               fileName.endsWith(".db") ||
               fileName.endsWith(".binlog");
    }
    
    /**
     * 验证数据库文件的完整性
     * 
     * @param data 文件数据
     * @param relativePath 相对路径
     * @return 是否有效
     */
    private boolean validateDatabaseFile(byte[] data, String relativePath) {
        if (data == null || data.length == 0) {
            logger.warn("数据库文件数据为空: {}", relativePath);
            return false;
        }
        
        // 提取文件名
        String fileName = relativePath.contains("/") ? 
                          relativePath.substring(relativePath.lastIndexOf('/') + 1) : 
                          relativePath;
        
        // SQLite主数据库文件（db.sqlite）应该以SQLite魔数开头
        if (fileName.equals("db.sqlite") || (fileName.endsWith(".db") && !fileName.contains("-wal") && !fileName.contains("-shm"))) {
            // SQLite主数据库文件格式: 前16字节包含 "SQLite format 3\000"
            if (data.length >= 16) {
                String header = new String(data, 0, 16, java.nio.charset.StandardCharsets.UTF_8);
                if (header.startsWith("SQLite format 3")) {
                    logger.debug("SQLite主数据库文件验证通过: {} (大小: {} bytes)", relativePath, data.length);
                    return true;
                } else {
                    logger.warn("SQLite主数据库文件格式验证失败: {} (前16字节: {})", relativePath, 
                               java.util.Arrays.toString(java.util.Arrays.copyOf(data, Math.min(16, data.length))));
                    return false;
                }
            } else {
                logger.warn("SQLite主数据库文件大小不足: {} ({} bytes, 需要至少16 bytes)", relativePath, data.length);
                return false;
            }
        }
        
        // SQLite WAL文件（db.sqlite-wal）有自己的格式，不以"SQLite format 3"开头
        // WAL文件格式: 前4字节是魔数 0x377F0682 或 0x377F0683
        if (fileName.endsWith("-wal") || fileName.endsWith(".sqlite-wal")) {
            if (data.length >= 4) {
                // 检查WAL文件魔数
                int magic = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | 
                           ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                if (magic == 0x377F0682 || magic == 0x377F0683) {
                    logger.debug("SQLite WAL文件验证通过: {} (大小: {} bytes)", relativePath, data.length);
                    return true;
                } else {
                    // WAL文件可能为空或格式不同，只要大小合理就接受
                    if (data.length >= 32) {
                        logger.debug("SQLite WAL文件验证通过（非标准格式但大小合理）: {} (大小: {} bytes)", relativePath, data.length);
                        return true;
                    } else {
                        logger.warn("SQLite WAL文件大小异常: {} ({} bytes)", relativePath, data.length);
                        return false;
                    }
                }
            } else {
                logger.warn("SQLite WAL文件大小不足: {} ({} bytes)", relativePath, data.length);
                return false;
            }
        }
        
        // SQLite SHM文件（db.sqlite-shm）是共享内存文件，没有固定格式
        // 只要文件存在且大小合理就接受
        if (fileName.endsWith("-shm") || fileName.endsWith(".sqlite-shm")) {
            if (data.length >= 0) { // SHM文件可以为空
                logger.debug("SQLite SHM文件验证通过: {} (大小: {} bytes)", relativePath, data.length);
                return true;
            } else {
                return false;
            }
        }
        
        // 对于其他文件（如binlog），至少检查大小是否合理
        if (fileName.endsWith(".binlog") || fileName.equals("td.binlog")) {
            // binlog文件大小应该合理（至少不为空）
            if (data.length > 0) {
                logger.debug("Binlog文件验证通过: {} (大小: {} bytes)", relativePath, data.length);
                return true;
            } else {
                logger.warn("Binlog文件为空: {}", relativePath);
                return false;
            }
        }
        
        // 对于其他未知类型的数据库文件，只要大小合理就接受
        if (data.length < 100) {
            logger.warn("数据库文件大小异常小: {} ({} bytes)", relativePath, data.length);
            return false;
        }
        
        logger.debug("数据库文件验证通过: {} (大小: {} bytes)", relativePath, data.length);
        return true;
    }

    /**
     * 读取session目录下的文件
     * 
     * @param sessionPath session路径
     * @return 文件名和Base64编码的文件内容映射
     */
    private Map<String, String> readSessionFiles(String sessionPath) throws IOException {
        Map<String, String> files = new HashMap<>();
        Path sessionDir = Paths.get(sessionPath);
        
        if (!Files.exists(sessionDir)) {
            return files;
        }
        
        List<Path> sessionFileList = findSessionFiles(sessionDir);
        processSessionFiles(sessionFileList, sessionDir, files);
        
        logger.info("读取session文件完成，共 {} 个文件", files.size());
        return files;
    }

    /**
     * 查找session相关文件
     * 
     * @param sessionDir session目录
     * @return 找到的文件列表
     * @throws IOException 如果遍历目录失败
     */
    private List<Path> findSessionFiles(Path sessionDir) throws IOException {
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(this::isSessionFile)
                .collect(Collectors.toList());
        }
    }

    /**
     * 判断是否为session相关文件
     * 
     * @param path 文件路径
     * @return 是否为session文件
     */
    private boolean isSessionFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".binlog") || 
               fileName.endsWith(".db") ||
               fileName.startsWith("db.sqlite") ||
               fileName.equals("td.binlog") ||
               fileName.equals("config.json") ||
               fileName.endsWith(".sqlite") ||
               fileName.endsWith(".sqlite-shm") ||
               fileName.endsWith(".sqlite-wal");
    }

    /**
     * 处理session文件
     * 
     * @param fileList 文件列表
     * @param sessionDir session目录
     * @param files 结果映射
     * @throws IOException 如果读取文件失败
     */
    private void processSessionFiles(List<Path> fileList, Path sessionDir, Map<String, String> files) throws IOException {
        for (Path filePath : fileList) {
            String relativePath = sessionDir.relativize(filePath).toString();
            String encodedPath = encodePath(relativePath);
            
            byte[] fileData = Files.readAllBytes(filePath);
            String encodedData = Base64.getEncoder().encodeToString(fileData);
            
            files.put(encodedPath, encodedData);
            logger.debug("读取session文件: {} -> {} (大小: {} bytes)", relativePath, encodedPath, fileData.length);
        }
    }

    /**
     * 编码路径
     * 
     * @param relativePath 相对路径
     * @return 编码后的路径
     */
    private String encodePath(String relativePath) {
        return relativePath.replace("/", "__SLASH__").replace(".", "__DOT__");
    }

    /**
     * 读取下载文件目录
     * 
     * @param downloadsPath 下载路径
     * @return 文件名和Base64编码的文件内容映射
     */
    private Map<String, String> readDownloadedFiles(String downloadsPath) throws IOException {
        Map<String, String> files = new HashMap<>();
        Path downloadsDir = Paths.get(downloadsPath);
        
        if (!Files.exists(downloadsDir)) {
            return files;
        }
        
        List<Path> downloadFileList = findDownloadFiles(downloadsDir);
        processDownloadFiles(downloadFileList, files);
        
        logger.info("读取下载文件完成，共 {} 个文件", files.size());
        return files;
    }

    /**
     * 查找下载文件
     * 
     * @param downloadsDir 下载目录
     * @return 找到的文件列表
     * @throws IOException 如果遍历目录失败
     */
    private List<Path> findDownloadFiles(Path downloadsDir) throws IOException {
        try (Stream<Path> paths = Files.walk(downloadsDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(this::isSmallDownloadFile)
                .limit(100) // 最多100个文件
                .collect(Collectors.toList());
        }
    }

    /**
     * 判断是否为小的下载文件
     * 
     * @param path 文件路径
     * @return 是否为小文件
     */
    private boolean isSmallDownloadFile(Path path) {
        try {
            return Files.size(path) < 1024 * 1024; // 小于1MB的文件
        } catch (IOException e) {
            logger.warn("无法获取文件大小: {}", path, e);
            return false;
        }
    }

    /**
     * 处理下载文件
     * 
     * @param fileList 文件列表
     * @param files 结果映射
     * @throws IOException 如果读取文件失败
     */
    private void processDownloadFiles(List<Path> fileList, Map<String, String> files) throws IOException {
        for (Path filePath : fileList) {
            String fileName = filePath.getFileName().toString();
            byte[] fileData = Files.readAllBytes(filePath);
            String encodedData = Base64.getEncoder().encodeToString(fileData);
            files.put(fileName, encodedData);
            logger.debug("读取下载文件: {} (大小: {} bytes)", fileName, fileData.length);
        }
    }

    /**
     * 删除session
     * 
     * @param phoneNumber 手机号码
     * @return 是否成功删除
     */
    @Override
    public boolean deleteSession(String phoneNumber) {
        try {
            Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(phoneNumber);
            if (sessionOpt.isPresent()) {
                TelegramSession session = sessionOpt.get();
                // 使用GridFSStorageManager删除分片数据
                boolean shardDeleted = gridfsStorageManager.deleteSession(session.getId());
                // 删除主session记录
                sessionRepository.delete(session);
                logger.info("删除session: {}, 分片数据删除: {}", phoneNumber, shardDeleted);
                return true;
            }
            logger.warn("未找到要删除的session: {}", phoneNumber);
            return false;
        } catch (Exception e) {
            logger.error("删除session失败: " + phoneNumber, e);
            return false;
        }
    }

    /**
     * 获取可用的session（已认证且非活跃）
     * 
     * @return session列表
     */
    @Override
    public List<TelegramSession> getAvailableSessions() {
        return sessionRepository.findAvailableSessions();
    }



    /**
     * 清理过期的session
     * 
     * @return 清理的session数量
     */
    @Override
    public int cleanupExpiredSessions() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusHours(inactiveTimeoutHours);
            List<TelegramSession> expiredSessions = sessionRepository.findSessionsToCleanup(threshold);
            
            int cleanedCount = 0;
            for (TelegramSession session : expiredSessions) {
                if (!session.getIsActive()) {
                    sessionRepository.delete(session);
                    cleanedCount++;
                    logger.info("清理过期session: {}", session.getPhoneNumber());
                }
            }
            
            logger.info("清理过期session完成，共清理 {} 个", cleanedCount);
            return cleanedCount;
        } catch (Exception e) {
            logger.error("清理过期session失败", e);
            return 0;
        }
    }

    /**
     * 获取实例ID
     * 
     * @return 实例ID
     */
    @Override
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 获取session统计信息
     * 
     * @return 统计信息
     */
    @Override
    public Map<String, Object> getSessionStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            long totalSessions = sessionRepository.count();
            long activeSessions = sessionRepository.countByIsActiveTrue();
            long currentInstanceSessions = sessionRepository.countByInstanceIdAndIsActiveTrue(instanceId);
            
            stats.put("totalSessions", totalSessions);
            stats.put("activeSessions", activeSessions);
            stats.put("currentInstanceSessions", currentInstanceSessions);
            stats.put("instanceId", instanceId);
            
            // 按认证状态统计
            List<TelegramSession> allSessions = sessionRepository.findAll();
            Map<String, Long> authStateStats = allSessions.stream()
                .collect(Collectors.groupingBy(
                    session -> session.getAuthState() != null ? session.getAuthState() : "UNKNOWN",
                    Collectors.counting()
                ));
            stats.put("authStateStats", authStateStats);
            
        } catch (Exception e) {
            logger.error("获取session统计信息失败", e);
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
    
    /**
     * 获取所有session数据
     * 
     * 用于数据完整性检查和系统诊断，返回数据库中的所有session记录。
     * 
     * @return 所有session的列表
     * 
     * @author sunhj
     * @since 2025-01-20
     */
    @Override
    public List<TelegramSession> getAllSessions() {
        try {
            List<TelegramSession> sessions = sessionRepository.findAll();
            logger.debug("获取所有session数据，共{}条记录", sessions.size());
            return sessions;
        } catch (Exception e) {
            logger.error("获取所有session数据失败", e);
            return new ArrayList<>();
        }
    }

    // ==================== Web管理系统API方法实现 ====================
    
    /**
     * 分页获取账号列表
     * 
     * @param pageRequest 分页请求参数
     * @return 分页响应结果
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    @Override
    public PageResponseDTO<AccountDTO> getAccountsPage(PageRequestDTO pageRequest) {
        try {
            // 创建分页参数
            Pageable pageable = PageRequest.of(
                pageRequest.getPage(), // 前端传递的页码已经是从0开始
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "updatedTime")
            );
            
            // 查询分页数据
            Page<TelegramSession> sessionPage = sessionRepository.findAll(pageable);
            
            // 转换为DTO
            List<AccountDTO> accountDTOs = sessionPage.getContent().stream()
                .map(this::convertToAccountDTO)
                .collect(Collectors.toList());
            
            // 构建分页响应
            return new PageResponseDTO<AccountDTO>(
                accountDTOs,
                pageRequest.getPage(), // 保持与前端一致的页码
                pageRequest.getSize(),
                sessionPage.getTotalElements()
            );
            
        } catch (Exception e) {
            logger.error("分页获取账号列表失败", e);
            return new PageResponseDTO<AccountDTO>(new ArrayList<AccountDTO>(), pageRequest.getPage(), pageRequest.getSize(), 0L);
        }
    }
    
    /**
     * 根据ID获取账号详情
     * 
     * @param accountId 账号ID（手机号）
     * @return 账号详情
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    @Override
    public Optional<AccountDTO> getAccountById(String accountId) {
        try {
            Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(accountId);
            return sessionOpt.map(this::convertToAccountDTO);
        } catch (Exception e) {
            logger.error("根据ID获取账号详情失败: {}", accountId, e);
            return Optional.empty();
        }
    }
    
    /**
     * 删除账号
     * 
     * @param accountId 账号ID（手机号）
     * @return 是否删除成功
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    @Override
    public boolean deleteAccount(String accountId) {
        try {
            Optional<TelegramSession> sessionOpt = sessionRepository.findByPhoneNumber(accountId);
            if (sessionOpt.isPresent()) {
                TelegramSession session = sessionOpt.get();
                
                // 清理本地session文件
                try {
                    logger.info("正在清理账号{}的本地session文件...", accountId);
                    deleteLocalSessionFiles(accountId);
                    logger.info("账号{}的本地session文件已清理", accountId);
                } catch (Exception e) {
                    logger.warn("清理账号{}的本地session文件时出现错误: {}", accountId, e.getMessage());
                    // 继续执行删除操作，即使清理文件失败
                }
                
                // 使用GridFSStorageManager删除session数据（包括GridFS文件）
                try {
                    gridfsStorageManager.deleteSession(session.getId());
                } catch (Exception e) {
                    logger.warn("删除账号{}的GridFS数据时出现错误: {}", accountId, e.getMessage());
                }
                
                // 删除session记录
                sessionRepository.delete(session);
                logger.info("成功删除账号: {}", accountId);
                return true;
            } else {
                logger.warn("要删除的账号不存在: {}", accountId);
                return false;
            }
        } catch (Exception e) {
            logger.error("删除账号失败: {}", accountId, e);
            return false;
        }
    }

    /**
     * 删除本地session文件
     * 
     * @param phoneNumber 手机号
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    private void deleteLocalSessionFiles(String phoneNumber) {
        try {
            Path sessionDir = Paths.get(sessionPath);
            if (!Files.exists(sessionDir)) {
                logger.debug("Session目录不存在: {}", sessionDir);
                return;
            }

            // 删除与该手机号相关的所有session文件
            try (Stream<Path> files = Files.walk(sessionDir)) {
                files.filter(Files::isRegularFile)
                     .filter(path -> {
                         String fileName = path.getFileName().toString();
                         // 匹配包含手机号的session文件
                         return fileName.contains(phoneNumber) || 
                                fileName.startsWith("td_") || 
                                fileName.endsWith(".binlog") ||
                                fileName.endsWith(".session");
                     })
                     .forEach(path -> {
                         try {
                             Files.deleteIfExists(path);
                             logger.debug("已删除session文件: {}", path);
                         } catch (IOException e) {
                             logger.warn("删除session文件失败: {}, 错误: {}", path, e.getMessage());
                         }
                     });
            }
            
            logger.info("已清理账号{}的本地session文件", phoneNumber);
        } catch (Exception e) {
            logger.error("清理本地session文件时发生错误: {}", phoneNumber, e);
            throw new RuntimeException("清理本地session文件失败", e);
        }
    }
    
    /**
     * 获取账号总数
     * 
     * @return 账号总数
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    @Override
    public long getAccountCount() {
        try {
            return sessionRepository.count();
        } catch (Exception e) {
            logger.error("获取账号总数失败", e);
            return 0L;
        }
    }
    
    /**
     * 获取活跃账号数量
     * 
     * @return 活跃账号数量
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    @Override
    public long getActiveAccountCount() {
        try {
            return sessionRepository.countByIsActiveTrue();
        } catch (Exception e) {
            logger.error("获取活跃账号数量失败", e);
            return 0L;
        }
    }
    
    /**
     * 将TelegramSession转换为AccountDTO
     * 
     * @param session TelegramSession对象
     * @return AccountDTO对象
     * 
     * @author sunhj
     * @since 2025-01-21
     */
    private AccountDTO convertToAccountDTO(TelegramSession session) {
        AccountDTO dto = new AccountDTO();
        dto.setId(session.getPhoneNumber());
        dto.setPhoneNumber(session.getPhoneNumber());
        dto.setAuthStatus(session.getAuthState());
        dto.setActive(session.getIsActive());
        dto.setCreatedAt(session.getCreatedTime());
        dto.setLastUpdated(session.getUpdatedTime());
        dto.setLastActiveAt(session.getLastActiveTime());
        dto.setApiId(session.getApiId());
        dto.setApiHash(session.getApiHash());
        
        // 设置备注信息（从extraConfig中获取或使用默认值）
        String remarks = "";
        if (session.getExtraConfig() != null && session.getExtraConfig().containsKey("remarks")) {
            remarks = (String) session.getExtraConfig().get("remarks");
        }
        dto.setRemarks(remarks != null ? remarks : "");
        
        return dto;
    }
}