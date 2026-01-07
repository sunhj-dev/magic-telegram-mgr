package com.telegram.server.controller;

import com.telegram.server.dto.MassMessageTaskDTO;
import com.telegram.server.dto.PageResponseDTO;
import com.telegram.server.dto.TaskDetailVO;
import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.service.MassMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息群发管理接口
 *
 * @author sunhj
 */
@Slf4j
@RestController
@RequestMapping("/admin/mass-message")
@RequiredArgsConstructor
public class MassMessageController {

    private final MassMessageService massMessageService;

    /**
     * 获取任务列表（分页）
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getTasks(
            @RequestParam(value = "page", defaultValue = "1") int page, 
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Map<String, Object> response = new HashMap<>();
        try {
            PageResponseDTO<MassMessageTask> taskPage = massMessageService.getTasks(page, size);
            response.put("success", true);
            response.put("data", taskPage);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取任务列表失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 创建群发任务
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody MassMessageTaskDTO dto) {
        Map<String, Object> response = new HashMap<>();
        try {
            String currentUser = ""; // TODO: 从安全上下文获取当前用户
            String taskId = massMessageService.createTask(dto, currentUser);
            response.put("success", true);
            response.put("message", "任务创建成功");
            response.put("taskId", taskId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "参数错误: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "创建任务失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 查看任务详情（含日志）
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskDetail(@PathVariable(value = "taskId") String taskId) {
        Map<String, Object> response = new HashMap<>();
        try {
            TaskDetailVO detail = massMessageService.getTaskDetail(taskId);
            response.put("success", true);
            response.put("data", detail);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            log.error("获取任务详情失败: taskId={}", taskId, e);
            response.put("success", false);
            response.put("message", "获取任务详情失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 启动任务
     */
    @PostMapping("/task/{taskId}/start")
    public ResponseEntity<Map<String, Object>> startTask(@PathVariable("taskId") String taskId) {
        Map<String, Object> response = new HashMap<>();
        try {
            massMessageService.startTask(taskId);
            response.put("success", true);
            response.put("message", "任务已启动");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "启动任务失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 暂停任务
     */
    @PostMapping("/task/{taskId}/pause")
    public ResponseEntity<Map<String, Object>> pauseTask(@PathVariable("taskId") String taskId) {
        Map<String, Object> response = new HashMap<>();
        try {
            massMessageService.pauseTask(taskId);
            response.put("success", true);
            response.put("message", "任务已暂停");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "暂停任务失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable("taskId") String taskId) {
        Map<String, Object> response = new HashMap<>();
        try {
            massMessageService.deleteTask(taskId);
            response.put("success", true);
            response.put("message", "任务已删除");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "删除任务失败: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}