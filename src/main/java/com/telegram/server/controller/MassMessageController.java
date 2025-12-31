package com.telegram.server.controller;

import com.telegram.server.dto.MassMessageTaskDTO;
import com.telegram.server.dto.PageResponseDTO;
import com.telegram.server.dto.TaskDetailVO;
import com.telegram.server.entity.MassMessageTask;
import com.telegram.server.service.MassMessageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息群发管理接口
 *
 * @author sunhj
 */
@RestController
@RequestMapping("/admin/mass-message")
@RequiredArgsConstructor
public class MassMessageController {

    private final MassMessageService massMessageService;

    /**
     * 获取任务列表（分页）
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getTasks(@RequestParam(value = "page", defaultValue = "1") int page, @RequestParam(value = "size", defaultValue = "10") int size) {
        Map<String, Object> response = new HashMap<>();
        PageResponseDTO<MassMessageTask> taskPage = massMessageService.getTasks(page, size);

        // 获取统计信息
//        Object stats = massMessageService.getStats();
        response.put("success", true);
        response.put("data", taskPage);
        ResponseEntity.ok(response);
        return ResponseEntity.ok(response);
    }

    /**
     * 创建群发任务
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody MassMessageTaskDTO dto) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
//        String currentUser = SecurityContextHolder.getContext().getAuthentication().getName();
        String currentUser = "";
        String taskId = massMessageService.createTask(dto, currentUser);
        return ResponseEntity.ok(response);
    }

    /**
     * 启动任务
     */
    @PostMapping("/task/{taskId}/start")
    public ResponseEntity<Map<String, Object>> startTask(@PathVariable String taskId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        massMessageService.startTask(taskId);
        return ResponseEntity.ok(response);
    }

    /**
     * 暂停任务
     */
    @PostMapping("/task/{taskId}/pause")
    public ResponseEntity<Map<String, Object>> pauseTask(@PathVariable String taskId) {
        Map<String, Object> response = new HashMap<>();
        massMessageService.pauseTask(taskId);
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        massMessageService.deleteTask(taskId);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取任务详情（含日志）
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskDetail(@PathVariable String taskId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        TaskDetailVO detail = massMessageService.getTaskDetail(taskId);
        response.put("data", detail);
        return ResponseEntity.ok(response);
    }
}