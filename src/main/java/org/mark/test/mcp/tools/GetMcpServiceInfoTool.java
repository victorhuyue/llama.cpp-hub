package org.mark.test.mcp.tools;

import java.util.Map;

import org.mark.llamacpp.server.service.ComputerService;
import org.mark.llamacpp.server.tools.GPUInfoHelper;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	获取当前服务器的信息。
 */
public class GetMcpServiceInfoTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(GetMcpServiceInfoTool.class);

	public GetMcpServiceInfoTool() {
	}

	@Override
	public String getMcpName() {
		return "get_mcp_service_info";
	}

	@Override
	public String getMcpTitle() {
		return "获取mcp服务信息";
	}

	@Override
	public String getMcpDescription() {
		return "获取当前服务所在机器的 CPU、内存和 JVM 运行信息";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
		// logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		String cpuModel = ComputerService.getCPUModel();
		int cpuCoreCount = ComputerService.getCPUCoreCount();
		long ramKb = ComputerService.getPhysicalMemoryKB();
		double ramGb = ramKb > 0 ? ramKb / 1024.0 / 1024.0 : -1;
		String javaVersion = ComputerService.getJavaVersion();
		String javaVendor = ComputerService.getJavaVendor();
		String jvmName = ComputerService.getJvmName();
		String jvmVersion = ComputerService.getJvmVersion();
		String jvmVendor = ComputerService.getJvmVendor();
		String jvmInputArguments = ComputerService.getJvmInputArguments();
		long jvmStartTime = ComputerService.getJvmStartTime();
		long jvmMaxMemoryMb = ComputerService.getJvmMaxMemoryMB();
		long jvmTotalMemoryMb = ComputerService.getJvmTotalMemoryMB();
		long jvmFreeMemoryMb = ComputerService.getJvmFreeMemoryMB();
		long jvmUsedMemoryMb = ComputerService.getJvmUsedMemoryMB();
		int jvmAvailableProcessors = ComputerService.getJvmAvailableProcessors();
 
		StringBuilder info = new StringBuilder();
		info.append("服务信息如下：\n");
		info.append("CPU Model: ").append(cpuModel).append("\n");
		info.append("CPU Cores: ").append(cpuCoreCount).append("\n");
		info.append("RAM KB: ").append(ramKb).append("\n");
		info.append("RAM GB: ").append(ramGb > 0 ? String.format("%.2f", ramGb) : "无法获取").append("\n");
		info.append("Java Version: ").append(javaVersion).append("\n");
		info.append("Java Vendor: ").append(javaVendor).append("\n");
		info.append("JVM Name: ").append(jvmName).append("\n");
		info.append("JVM Version: ").append(jvmVersion).append("\n");
		info.append("JVM Vendor: ").append(jvmVendor).append("\n");
		info.append("JVM Input Arguments: ").append(jvmInputArguments).append("\n");
		info.append("JVM Start Time: ").append(jvmStartTime).append("\n");
		info.append("JVM Max Memory MB: ").append(jvmMaxMemoryMb).append("\n");
		info.append("JVM Total Memory MB: ").append(jvmTotalMemoryMb).append("\n");
		info.append("JVM Free Memory MB: ").append(jvmFreeMemoryMb).append("\n");
		info.append("JVM Used Memory MB: ").append(jvmUsedMemoryMb).append("\n");
		info.append("JVM Available Processors: ").append(jvmAvailableProcessors);

		JsonObject gpuData = GPUInfoHelper.getInstance().getInfo();
		if (gpuData != null && gpuData.has("system")) {
			info.append("\n\n--- 详细硬件信息 (GpuInfo) ---\n");
			JsonObject sys = gpuData.getAsJsonObject("system");
			if (sys.has("cpu")) {
				JsonObject cpu = sys.getAsJsonObject("cpu");
				info.append("CPU: ").append(JsonUtil.getJsonString(cpu, "name", "")).append("\n");
				info.append("物理核心: ").append(JsonUtil.getJsonInt(cpu, "cores", -1)).append(", 逻辑核心: ").append(JsonUtil.getJsonInt(cpu, "threads", -1)).append("\n");
			}
			if (sys.has("memory")) {
				JsonObject mem = sys.getAsJsonObject("memory");
				long totalBytes = JsonUtil.getJsonLong(mem, "total_bytes", -1L);
				long memGb = totalBytes > 0 ? totalBytes / 1024L / 1024L / 1024L : 0;
				info.append("内存: ").append(memGb).append(" GB\n");
			}

			if (gpuData.has("devices")) {
				com.google.gson.JsonArray devices = gpuData.getAsJsonArray("devices");
				int idx = 0;
				for (int i = 0; i < devices.size(); i++) {
					JsonObject gpu = devices.get(i).getAsJsonObject();
					info.append("GPU [").append(idx).append("]: ")
							.append(JsonUtil.getJsonString(gpu, "vendor", "")).append(" ")
							.append(JsonUtil.getJsonString(gpu, "name", ""));
					if (gpu.has("sensors")) {
						JsonObject sensors = gpu.getAsJsonObject("sensors");
						String driverVer = JsonUtil.getJsonString(sensors, "driver_version_str", null);
						if (driverVer != null) {
							info.append(" (Driver: ").append(driverVer).append(")");
						}
					}
					info.append("\n");
					if (gpu.has("memory")) {
						JsonObject gmem = gpu.getAsJsonObject("memory");
						long vramBytes = JsonUtil.getJsonLong(gmem, "dedicated_vram_bytes", -1L);
						if (vramBytes > 0) {
							long vramMb = vramBytes / 1024L / 1024L;
							info.append("  - 显存: ").append(vramMb).append(" MB\n");
						}
					}
					idx++;
				}
			}
		}

		return new McpMessage().addText(info.toString());
	}
}
