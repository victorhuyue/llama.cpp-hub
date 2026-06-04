package org.mark.llamacpp.server.service;

import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class SamplingInjectionBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SamplingInjectionBuilder.class);

    private SamplingInjectionBuilder() {
    }

    public static String buildInjectionString(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        appendSampling(sb, modelName);
        appendChatTemplateKwargs(sb, modelName);
        String result = sb.toString();
        if (result.isEmpty()) {
            return "";
        }
        logger.debug("采样注入字符串 [model={}]: {}", modelName, result);
        return result;
    }

    static void appendSampling(StringBuilder sb, String modelName) {
        JsonObject sampling = ModelSamplingService.getInstance().getOpenAISampling(modelName);
        if (sampling == null || sampling.entrySet().isEmpty()) {
            return;
        }
        boolean forceThinking = readBoolean(sampling, "force_enable_thinking");
        Boolean enableThinking = null;
        if (forceThinking) {
            enableThinking = readBooleanValue(sampling, "enable_thinking");
        }
        for (Map.Entry<String, JsonElement> entry : sampling.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key == null || value == null || value.isJsonNull()) {
                continue;
            }
            if ("enable_thinking".equals(key) || "force_enable_thinking".equals(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('"').append(key).append("\":").append(serializeValue(value));
        }
        if (enableThinking != null && !forceThinking) {
            JsonObject kwargs = buildMergedKwargs(modelName, enableThinking);
            if (kwargs != null && !kwargs.entrySet().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append("\"chat_template_kwargs\":").append(JsonUtil.toJson(kwargs));
            }
        }
    }

    static void appendChatTemplateKwargs(StringBuilder sb, String modelName) {
        JsonObject kwargs = ChatTemplateKwargsService.getInstance().getOpenAIChatTemplateKwargs(modelName);
        if (kwargs == null || kwargs.entrySet().isEmpty()) {
            return;
        }
        JsonObject sampling = ModelSamplingService.getInstance().getOpenAISampling(modelName);
        boolean forceThinking = sampling != null && readBoolean(sampling, "force_enable_thinking");
        if (forceThinking) {
            Boolean enableThinking = readBooleanValue(sampling, "enable_thinking");
            if (enableThinking != null) {
                kwargs = buildMergedKwargs(modelName, enableThinking);
                if (kwargs == null) {
                    return;
                }
            }
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append("\"chat_template_kwargs\":").append(JsonUtil.toJson(kwargs));
    }

    static JsonObject buildMergedKwargs(String modelName, boolean enableThinking) {
        JsonObject kwargs = ChatTemplateKwargsService.getInstance().getOpenAIChatTemplateKwargs(modelName);
        if (kwargs == null) {
            kwargs = new JsonObject();
        } else {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : kwargs.entrySet()) {
                copy.add(entry.getKey(), entry.getValue().deepCopy());
            }
            kwargs = copy;
        }
        kwargs.addProperty("enable_thinking", enableThinking);
        return kwargs;
    }

    static boolean readBoolean(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return false;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return false;
        }
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    static Boolean readBooleanValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            if (el.getAsJsonPrimitive().isBoolean()) {
                return el.getAsBoolean();
            }
            if (el.getAsJsonPrimitive().isString()) {
                return Boolean.parseBoolean(el.getAsString().trim());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    static String serializeValue(JsonElement value) {
        if (value.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean() ? "true" : "false";
            }
            if (primitive.isNumber()) {
                Number number = primitive.getAsNumber();
                if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
                    return number.toString();
                }
                return JsonUtil.toJson(value);
            }
            return "\"" + escapeJsonString(primitive.getAsString()) + "\"";
        }
        return JsonUtil.toJson(value);
    }

    static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
