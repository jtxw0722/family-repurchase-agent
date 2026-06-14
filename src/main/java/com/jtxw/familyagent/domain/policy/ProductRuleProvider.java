package com.jtxw.familyagent.domain.policy;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 商品归一化规则提供接口，负责向领域匹配器屏蔽规则来源是 SQLite 还是 legacy 配置文件
 */
public interface ProductRuleProvider {
    /**
     * 查询当前启用的商品归一化规则。
     *
     * @return 已启用的商品规则列表；为空时返回空集合，不返回 null
     */
    List<ProductRule> listEnabledRules();
}
