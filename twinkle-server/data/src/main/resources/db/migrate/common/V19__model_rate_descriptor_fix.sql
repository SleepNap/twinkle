-- ============================================================
-- Common V19: model_rate 模型键口径修正（三方言一致，纯数据迁移无结构变更）
-- ============================================================
-- 背景：计费入口传入的模型标识是 AiModelBundle.descriptor() = provider + "/" + modelName，
-- 而 V13 seed 写的是裸 modelName。BillingService.computePoints 用 findByModelKey 精确匹配，
-- 查不到即返回 0 —— 外部模型（DeepSeek/OpenAI-compatible）因此一路静默免费。
-- local-rule 同样匹配不上，但其倍率本就是 0，结果碰巧正确，所以本地开发看不出问题。
--
-- V13 已冻结（schema_version 保证不重跑），只能用新迁移 UPDATE 既有行。
-- descriptor 真值来自 AiModelFactory：local-rule 的 modelName 硬编码为 deterministic。
-- 幂等性：改名后 WHERE 条件不再命中，重复执行无副作用。
-- ============================================================
UPDATE model_rate SET model_key = 'local-rule/deterministic' WHERE model_key = 'local-rule';
UPDATE model_rate SET model_key = 'deepseek/deepseek-chat' WHERE model_key = 'deepseek-chat';
