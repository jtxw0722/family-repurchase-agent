package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationLlmTask;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationLlmTaskRepository;
import org.springframework.stereotype.Service;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 归一化 LLM 通用任务查询应用服务，负责统一查询旧商品归一化分析和新规则维护建议任务
 */
@Service
public class NormalizationLlmTaskService {
    /**
     * 数据库初始化组件，确保查询通用任务前新任务表已经存在。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 通用 LLM 任务仓储，负责 normalization_llm_tasks 表查询。
     */
    private final NormalizationLlmTaskRepository taskRepository;

    /**
     * 创建归一化 LLM 通用任务查询服务。
     *
     * @param databaseInitializer 数据库初始化组件，不能为空
     * @param taskRepository      通用任务仓储，不能为空
     */
    public NormalizationLlmTaskService(DatabaseInitializer databaseInitializer,
                                       NormalizationLlmTaskRepository taskRepository) {
        this.databaseInitializer = databaseInitializer;
        this.taskRepository = taskRepository;
    }

    /**
     * 查询归一化 LLM 通用任务详情。
     *
     * @param taskId 任务 ID，对应 normalization_llm_tasks.id
     * @return 通用任务详情
     * @throws IllegalArgumentException 指定任务不存在时抛出
     */
    public NormalizationLlmTask get(long taskId) {
        databaseInitializer.initialize();
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("归一化 LLM 任务不存在：" + taskId));
    }
}
