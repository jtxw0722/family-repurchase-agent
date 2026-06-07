package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationAnalyzeProgress;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:11:50
 * @Description: 商品归一化分析进度监听器，用于同步服务向异步任务表回传阶段性计数。
 */
@FunctionalInterface
public interface NormalizationAnalyzeProgressListener {
    /**
     * 接收商品归一化分析进度快照。
     *
     * @param progress 当前分析进度，包含候选总数、已分析数量、小批次序号和分类计数，不允许为空
     */
    void onProgress(NormalizationAnalyzeProgress progress);
}
