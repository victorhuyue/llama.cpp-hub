package com.mark.test.tools;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

import org.mark.llamacpp.gguf.MtpHelper;
import org.mark.llamacpp.gguf.MtpHelper.MtpInfo;

public class TestMtpHelper {

    public static void main(String[] args) throws Exception {
//    	{
//    		// 这里测试导出
//            File input = new File("/home/mark/MTP/newone.gguf");
//            File output = new File("test-mtp-java.gguf");
//
//            // 1. Detect MTP info
//            System.out.println("=== Detecting MTP info ===");
//            MtpInfo info = MtpHelper.detectMtpInfo(input);
//            System.out.println("  hasMtp: " + info.hasMtp());
//            System.out.println("  architecture: " + info.architecture());
//            System.out.println("  blockCount: " + info.blockCount());
//            System.out.println("  nextnPredictLayers: " + info.nextnPredictLayers());
//            System.out.println("  trunkCount: " + info.trunkCount());
//            System.out.println("  mtpBlockPrefixes: " + info.mtpBlockPrefixes());
//
//            if (!info.hasMtp()) {
//                System.out.println("No MTP layers found, exiting.");
//                return;
//            }
//
//            // 2. Extract MTP donor
//            System.out.println("\n=== Extracting MTP donor ===");
//            MtpHelper.extractDonor(input, output);
//            System.out.println("Donor written to: " + output.getAbsolutePath());
//            System.out.println("Donor size: " + output.length() / 1_000_000 + " MB");
//
//            // 3. Compute MD5
//            System.out.println("\n=== Computing MD5 ===");
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            byte[] digest = md.digest(Files.readAllBytes(output.toPath()));
//            StringBuilder sb = new StringBuilder();
//            for (byte b : digest) {
//                sb.append(String.format("%02x", b));
//            }
//            System.out.println("MD5: " + sb.toString());
//    	}

        
    	{
    		// 这里测试合并
    		File base = new File("/home/mark/MTP/Qwen3.6-27B-Q4_K_M.gguf");
    		File donor = new File("/home/mark/MTP/Qwen3.6-27B-MTP-Donor-Q8_0.gguf");
    		File merged = new File("/home/mark/MTP/test-merged-java.gguf");

    		System.out.println("\n=== Merging MTP donor ===");
    		System.out.println("  Base: " + base.getAbsolutePath());
    		System.out.println("  Donor: " + donor.getAbsolutePath());

    		MtpHelper.mergeDonor(base, donor, merged);
    		System.out.println("Merged written to: " + merged.getAbsolutePath());
    		System.out.println("Merged size: " + merged.length() / 1_000_000_000.0 + " GB");

    		System.out.println("\n=== Verifying merged file ===");
    		MtpInfo mergedInfo = MtpHelper.detectMtpInfo(merged);
    		System.out.println("  hasMtp: " + mergedInfo.hasMtp());
    		System.out.println("  architecture: " + mergedInfo.architecture());
    		System.out.println("  blockCount: " + mergedInfo.blockCount());
    		System.out.println("  nextnPredictLayers: " + mergedInfo.nextnPredictLayers());
    		System.out.println("  trunkCount: " + mergedInfo.trunkCount());
    		System.out.println("  mtpBlockPrefixes: " + mergedInfo.mtpBlockPrefixes());

    		System.out.println("\n=== Computing MD5 ===");
    		MessageDigest md = MessageDigest.getInstance("MD5");
    		byte[] digest = md.digest(Files.readAllBytes(merged.toPath()));
    		StringBuilder sb = new StringBuilder();
    		for (byte b : digest) {
    			sb.append(String.format("%02x", b));
    		}
    		System.out.println("MD5: " + sb.toString());
    	}
    }
}
