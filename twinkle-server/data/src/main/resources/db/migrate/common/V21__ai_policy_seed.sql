-- ============================================================
-- AI 治理 seed：运行期全局开关与全局模型白名单（配置中心可调）。
-- ============================================================
-- twinkle.ai.enabled 是 Micronaut 装配期条件（AiEnabledCondition），运行期改不了；
-- 这里的 ai.runtime.enabled 是运行期软开关，关闭后 AI bean 仍装配，只是治理层拒绝调用。
-- 两者命名空间不同，互不干扰。
--
-- ai.allowed.models：全局模型白名单，逗号分隔的 descriptor（provider/modelName，
-- 与 model_rate.model_key 同口径）。留空 = 不限制。对管理员凭据同样生效。
--
-- 预置为默认值是为了让运营在配置中心能直接看到这两个键（否则查不到就不知道可调）。
INSERT INTO param_config (config_key, config_value, version) VALUES
    ('ai.runtime.enabled', 'true', 1),
    ('ai.allowed.models', '', 1);
