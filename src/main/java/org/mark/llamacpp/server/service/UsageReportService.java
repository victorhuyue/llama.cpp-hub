package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.mark.llamacpp.server.struct.DailyTokenEntry;
import org.mark.llamacpp.server.struct.RequestLogEntry;
import org.mark.llamacpp.server.struct.TokenSummaryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public class UsageReportService {

	private static final UsageReportService INSTANCE = new UsageReportService();
	private final Gson gson = new Gson();
	private static final String RECORD_DIR = "cache/record/";

	public static UsageReportService getInstance() {
		return INSTANCE;
	}

	/**
	 * 读取 cache/record/{modelId}.json 中的累计 Token 数据。
	 * 返回所有有记录的模型概览。
	 */
	public List<TokenSummaryEntry> getTokenSummary() {
		List<TokenSummaryEntry> result = new ArrayList<>();
		java.nio.file.Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return result;
		}
		try (Stream<java.nio.file.Path> paths = Files.list(dir)) {
			paths.filter(path -> path.toString().endsWith(".json"))
				 .forEach(path -> {
					 try {
						 String fileName = path.getFileName().toString();
						 String modelId = fileName.substring(0, fileName.length() - 5);
						 String content = new String(Files.readAllBytes(path));
						 JsonObject obj = gson.fromJson(content, JsonObject.class);
						 if (obj == null) return;

						 TokenSummaryEntry entry = new TokenSummaryEntry();
						 entry.setModelId(modelId);
						 long cacheN = getJsonLong(obj, "cache_n", 0);
						 long promptN = getJsonLong(obj, "prompt_n", 0);
						 long predictedN = getJsonLong(obj, "predicted_n", 0);
						 entry.setTotalCacheTokens(cacheN);
						 entry.setTotalPromptTokens(promptN);
						 entry.setTotalPredictedTokens(predictedN);
						 entry.setTotalTokens(promptN + predictedN);
						 entry.setTotalPromptMs(getJsonDouble(obj, "prompt_ms", 0));
						 entry.setTotalPredictedMs(getJsonDouble(obj, "predicted_ms", 0));
						 entry.setTotalDraftTokens(getJsonLong(obj, "draft_n", 0));
						 entry.setTotalDraftAccepted(getJsonLong(obj, "draft_n_accepted", 0));
						 result.add(entry);
					 } catch (Exception e) {
						 e.printStackTrace();
					 }
				 });
		} catch (IOException e) {
			e.printStackTrace();
		}
		return result;
	}

	/**
	 * 读取 cache/record/{modelId}.requests.log 中的逐行请求记录。
	 * 返回所有请求的明细列表。
	 */
	public List<RequestLogEntry> getRequestLogs() {
		List<RequestLogEntry> result = new ArrayList<>();
		java.nio.file.Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return result;
		}
		try (Stream<java.nio.file.Path> paths = Files.list(dir)) {
			paths.filter(path -> path.toString().endsWith(".requests.log"))
				 .forEach(path -> {
					 try {
						 List<String> lines = Files.readAllLines(path);
						 for (String line : lines) {
							 if (line == null || line.trim().isEmpty()) continue;
							 try {
								 JsonObject obj = gson.fromJson(line, JsonObject.class);
								 if (obj == null) continue;
								 result.add(parseRequestLogLine(obj));
							 } catch (Exception ignore) {
							 }
						 }
					 } catch (IOException e) {
						 e.printStackTrace();
					 }
				 });
		} catch (IOException e) {
			e.printStackTrace();
		}
		result.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));
		return result;
	}

	/**
	 * 基于请求日志，聚合指定月份的每日 Token 用量。
	 */
	public List<DailyTokenEntry> getDailyTokenUsage(int year, int month) {
		LocalDate firstDay = LocalDate.of(year, month, 1);
		LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

		List<RequestLogEntry> logs = getRequestLogs();
		if (logs.isEmpty()) {
			return buildEmptyMonthlyEntries(firstDay, lastDay);
		}

		Map<String, DailyTokenEntry> dayMap = new LinkedHashMap<>();
		for (RequestLogEntry log : logs) {
			if (log.getStartTime() <= 0) continue;
			LocalDate day = Instant.ofEpochMilli(log.getStartTime()).atZone(ZoneId.systemDefault()).toLocalDate();
			if (day.isBefore(firstDay) || day.isAfter(lastDay)) continue;
			DailyTokenEntry entry = dayMap.get(day.toString());
			if (entry == null) {
				entry = new DailyTokenEntry();
				entry.setDate(day.toString());
				dayMap.put(day.toString(), entry);
			}
			entry.setPromptTokens(entry.getPromptTokens() + log.getPromptTokens());
			entry.setPredictedTokens(entry.getPredictedTokens() + log.getPredictedTokens());
			entry.setCacheTokens(entry.getCacheTokens() + log.getCacheTokens());
		}

		List<DailyTokenEntry> result = new ArrayList<>();
		for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
			String key = d.toString();
			if (dayMap.containsKey(key)) {
				result.add(dayMap.get(key));
			} else {
				DailyTokenEntry empty = new DailyTokenEntry();
				empty.setDate(key);
				result.add(empty);
			}
		}
		return result;
	}

	/**
	 * 获取有数据的所有年份（去重，升序）。
	 */
	public List<Integer> getAvailableYears() {
		List<RequestLogEntry> logs = getRequestLogs();
		Set<Integer> years = new TreeSet<>();
		for (RequestLogEntry log : logs) {
			if (log.getStartTime() <= 0) continue;
			LocalDate day = Instant.ofEpochMilli(log.getStartTime()).atZone(ZoneId.systemDefault()).toLocalDate();
			years.add(day.getYear());
		}
		// 确保包含当前年份
		years.add(LocalDate.now().getYear());
		return new ArrayList<>(years);
	}

	private List<DailyTokenEntry> buildEmptyMonthlyEntries(LocalDate firstDay, LocalDate lastDay) {
		List<DailyTokenEntry> result = new ArrayList<>();
		for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
			DailyTokenEntry entry = new DailyTokenEntry();
			entry.setDate(d.toString());
			result.add(entry);
		}
		return result;
	}

	private RequestLogEntry parseRequestLogLine(JsonObject obj) {
		RequestLogEntry entry = new RequestLogEntry();
		entry.setRequestId(getJsonString(obj, "requestId"));
		entry.setModelId(getJsonString(obj, "modelId"));
		entry.setEndpoint(getJsonString(obj, "endpoint"));
		long wallTime = getJsonLong(obj, "wallTime", 0);
		if (wallTime > 0) {
			entry.setStartTime(wallTime);
		} else {
			entry.setStartTime(getJsonLong(obj, "startTime", 0));
		}
		entry.setElapsedMs(getJsonLong(obj, "elapsedMs", 0));

		if (obj.has("timing") && obj.get("timing").isJsonObject()) {
			JsonObject timing = obj.getAsJsonObject("timing");
			int cacheN = getJsonInt(timing, "cache_n", 0);
			int promptN = getJsonInt(timing, "prompt_n", 0);
			int predictedN = getJsonInt(timing, "predicted_n", 0);
			entry.setCacheTokens(cacheN);
			entry.setPromptTokens(promptN);
			entry.setPredictedTokens(predictedN);
			entry.setTotalTokens(promptN + predictedN);
			entry.setPromptPerSecond(getJsonDouble(timing, "prompt_per_second", 0));
			entry.setPredictedPerSecond(getJsonDouble(timing, "predicted_per_second", 0));
			entry.setDraftTokens(getJsonInt(timing, "draft_n", 0));
			entry.setDraftAccepted(getJsonInt(timing, "draft_n_accepted", 0));
		}

		return entry;
	}

	private String getJsonString(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString();
		} catch (Exception e) {
			return "";
		}
	}

	private long getJsonLong(JsonObject obj, String key, long fallback) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsLong();
		} catch (Exception e) {
			return fallback;
		}
	}

	private int getJsonInt(JsonObject obj, String key, int fallback) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	private double getJsonDouble(JsonObject obj, String key, double fallback) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsDouble();
		} catch (Exception e) {
			return fallback;
		}
	}
}
