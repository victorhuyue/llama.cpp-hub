package org.mark.llamacpp.server.service;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoLoadPolicyManager {

	private static final Logger logger = LoggerFactory.getLogger(AutoLoadPolicyManager.class);

	private static final AutoLoadPolicyManager INSTANCE = new AutoLoadPolicyManager();

	private final LlamaServerManager manager;
	private long autoLoadTimeoutMs = 120_000;

	private AutoLoadPolicyManager() {
		this.manager = LlamaServerManager.getInstance();
	}

	public static AutoLoadPolicyManager getInstance() {
		return INSTANCE;
	}

	/**
	 * 启动时加载配置
	 */
	public void loadConfig() {
		com.google.gson.JsonObject appConfig = LlamaServer.readApplicationConfig();
		if (appConfig != null && appConfig.has("autoLoadTimeoutMs")) {
			try {
				autoLoadTimeoutMs = appConfig.get("autoLoadTimeoutMs").getAsLong();
			} catch (Exception e) {
				logger.info("读取 autoLoadTimeoutMs 失败，使用默认值: {}", e.getMessage());
			}
		}
		logger.info("AutoLoadPolicyManager 已初始化, autoLoadTimeoutMs={}", autoLoadTimeoutMs);
	}

	/**
	 * 获取自动加载超时时间（毫秒）
	 */
	public long getAutoLoadTimeoutMs() {
		return autoLoadTimeoutMs;
	}

	/**
	 * 检查模型是否允许自动加载（委托给 LlamaServerManager）
	 */
	public boolean canAutoLoad(String modelId) {
		return manager.canAutoLoad(modelId);
	}

	/**
	 * 设置模型的自动加载策略（委托给 LlamaServerManager）
	 */
	public String setModelPolicy(String modelId, String mode) {
		return manager.setAutoLoadPolicy(modelId, mode);
	}

	/**
	 * 重置模型的自动加载策略（委托给 LlamaServerManager）
	 */
	public String resetModelPolicy(String modelId) {
		return manager.resetAutoLoadPolicy(modelId);
	}

	/**
	 * 获取所有模型的自动加载策略
	 */
	public Map<String, Object> getAllPolicies() {
		Map<String, Object> result = new HashMap<>();

		List<GGUFModel> models = manager.listModel();
		Map<String, Object> modelsMap = new HashMap<>();

		for (GGUFModel model : models) {
			String modelId = model.getModelId();
			String policy = manager.getAutoLoadPolicy(modelId);
			Map<String, Object> modelInfo = new HashMap<>();
			modelInfo.put("modelId", modelId);
			modelInfo.put("modelName", model.getName());
			modelInfo.put("policy", policy != null ? policy : "deny");
			modelsMap.put(modelId, modelInfo);
		}

		Map<String, Object> policiesMap = new HashMap<>();
		for (String modelId : modelsMap.keySet()) {
			String policy = manager.getAutoLoadPolicy(modelId);
			if (policy != null) {
				policiesMap.put(modelId, policy);
			}
		}

		result.put("policies", policiesMap);
		result.put("models", modelsMap);

		return result;
	}
}
