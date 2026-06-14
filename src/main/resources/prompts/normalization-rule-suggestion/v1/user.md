请根据以下 JSON 输入分析归一化规则库缺口，并只返回 JSON。

输入 JSON：
{{inputJson}}

输出格式：
{
  "suggestions": [
    {
      "operation": "create_rule",
      "ruleCode": "body_wash",
      "normalizedName": "沐浴露",
      "category": "个护清洁",
      "standardUnit": "L",
      "unitFamily": "VOLUME",
      "priority": 80,
      "keywords": ["沐浴露", "沐浴乳"],
      "excludeKeywords": ["沐浴露瓶"],
      "confidence": 0.92,
      "reason": "候选样本稳定指向同一复购消耗品类",
      "evidence": ["舒肤佳红石榴啫喱沐浴露380g+400g"]
    },
    {
      "operation": "add_keyword",
      "ruleCode": "cat_litter",
      "keyword": "豆腐砂",
      "matchType": "include",
      "confidence": 0.9,
      "reason": "豆腐砂是猫砂规则下可泛化关键词",
      "evidence": ["pidan豆腐猫砂混合款"]
    }
  ],
  "warnings": []
}
