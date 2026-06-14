INSERT OR IGNORE INTO normalization_rules(rule_code, normalized_name, category, standard_unit, unit_family, priority, enabled, source, created_at, updated_at)
VALUES ('cat_litter', '猫砂', '宠物用品', 'kg', 'weight', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rules(rule_code, normalized_name, category, standard_unit, unit_family, priority, enabled, source, created_at, updated_at)
VALUES ('cat_food', '猫粮', '宠物用品', 'kg', 'weight', 90, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rules(rule_code, normalized_name, category, standard_unit, unit_family, priority, enabled, source, created_at, updated_at)
VALUES ('tissue', '纸巾', '日用品', '抽', 'draw_count', 80, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rules(rule_code, normalized_name, category, standard_unit, unit_family, priority, enabled, source, created_at, updated_at)
VALUES ('laundry_detergent', '洗衣液', '日用品', 'L', 'volume', 80, 1, 'system', datetime('now'), datetime('now'));

INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫砂', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫沙', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '豆腐砂', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '膨润土', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '矿砂', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '混合砂', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '植物砂', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫砂盆', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫厕所', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫屎盆', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '猫砂铲', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_litter'), '防带砂', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));

INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '猫粮', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '幼猫粮', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '成猫粮', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '全价猫粮', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '猫粮勺', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'cat_food'), '储粮桶', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));

INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '纸巾', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '抽纸', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '面巾纸', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '纸巾盒', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '收纳盒', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '湿巾', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'tissue'), '卷纸', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));

INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'laundry_detergent'), '洗衣液', 'include', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'laundry_detergent'), '洗衣液瓶', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
INSERT OR IGNORE INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled, source, created_at, updated_at)
VALUES ((SELECT id FROM normalization_rules WHERE rule_code = 'laundry_detergent'), '分装瓶', 'exclude', 100, 1, 'system', datetime('now'), datetime('now'));
