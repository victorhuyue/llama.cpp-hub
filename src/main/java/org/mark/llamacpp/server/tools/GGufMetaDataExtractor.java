package org.mark.llamacpp.server.tools;



import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.crawler.NettyHttpUtils.Response;
import org.mark.llamacpp.server.LlamaServer;


//GGufMetaDataExtractor.java
//
//Cross-platform GGUF metadata extractor - downloads ONLY the header
//(KV metadata + tensor info) from a GGUF URL via HTTP Range requests,
//then writes a structurally valid metadata-only .meta.gguf file.
//
//No native dependencies. Runs on any JDK 11+.
//
//Usage:
//java GGufMetaExtractor.java https://huggingface.co/.../model.gguf [output.meta.gguf]
//java GGufMetaExtractor.java model.gguf [output.meta.gguf]
//
//Compile (optional):
//javac GGufMetaExtractor.java
//java GGufMetaExtractor https://huggingface.co/.../model.gguf
public class GGufMetaDataExtractor {

    // ------------------------------------------------------------------
    // GGUF constants
    // ------------------------------------------------------------------
    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" LE
    private static final int GGUF_DEFAULT_ALIGNMENT = 32;

    // GGUF value types
    private static final int TYPE_UINT8   = 0;
    private static final int TYPE_INT8    = 1;
    private static final int TYPE_UINT16  = 2;
    private static final int TYPE_INT16   = 3;
    private static final int TYPE_UINT32  = 4;
    private static final int TYPE_INT32   = 5;
    private static final int TYPE_FLOAT32 = 6;
    private static final int TYPE_BOOL    = 7;
    private static final int TYPE_STRING  = 8;
    private static final int TYPE_ARRAY   = 9;
    private static final int TYPE_UINT64  = 10;
    private static final int TYPE_INT64   = 11;
    private static final int TYPE_FLOAT64 = 12;

    // GGML quantized type IDs (subset - full list for size calculation)
    private static final int[] BLOCK_SIZES = new int[40];
    private static final int[] TYPE_SIZES  = new int[40];
    static {
        // type -> {block_size, type_size}
        int[][] bt = {
            { 1,  4}, // 0: F32
            { 1,  2}, // 1: F16
            {32, 18}, // 2: Q4_0
            {32, 20}, // 3: Q4_1
            { 0,  0}, // 4
            { 0,  0}, // 5
            {32, 22}, // 6: Q5_0
            {32, 24}, // 7: Q5_1
            {32, 34}, // 8: Q8_0
            {32, 36}, // 9: Q8_1
            {256,82}, //10: Q2_K
            {256,110},//11: Q3_K
            {256,144},//12: Q4_K
            {256,176},//13: Q5_K
            {256,210},//14: Q6_K
            {256,292},//15: Q8_K
            {256,66}, //16: IQ2_XXS
            {256,74}, //17: IQ2_XS
            {256,98}, //18: IQ3_XXS
            { 32,18}, //19: IQ1_S
            { 32,18}, //20: IQ4_NL
            {256,112},//21: IQ3_S
            {256,82}, //22: IQ2_S
            {256,136},//23: IQ4_XS
            { 1,  1}, //24: I8
            { 1,  2}, //25: I16
            { 1,  4}, //26: I32
            { 1,  8}, //27: I64
            { 1,  8}, //28: F64
            {256,82}, //29: IQ1_M
            { 1,  2}, //30: BF16
            {0,0}, {0,0}, {0,0},
            {256,98}, //34: TQ1_0
            {256,130},//35: TQ2_0
        };
        for (int i = 0; i < bt.length && i < BLOCK_SIZES.length; i++) {
            BLOCK_SIZES[i] = bt[i][0];
            TYPE_SIZES[i]  = bt[i][1];
        }
    }

    // ------------------------------------------------------------------
    // GGUF binary reader (little-endian)
    // ------------------------------------------------------------------
    static class GGufReader {
        final byte[] data;
        int pos;

        GGufReader(byte[] data) { this.data = data; this.pos = 0; }

        int readInt() {
            return (data[pos++] & 0xFF)
                 | ((data[pos++] & 0xFF) << 8)
                 | ((data[pos++] & 0xFF) << 16)
                 | ((data[pos++] & 0xFF) << 24);
        }

        long readLong() {
            return (data[pos++] & 0xFFL)
                 | ((data[pos++] & 0xFFL) << 8)
                 | ((data[pos++] & 0xFFL) << 16)
                 | ((data[pos++] & 0xFFL) << 24)
                 | ((data[pos++] & 0xFFL) << 32)
                 | ((data[pos++] & 0xFFL) << 40)
                 | ((data[pos++] & 0xFFL) << 48)
                 | ((data[pos++] & 0xFFL) << 56);
        }

        long readUInt64() { return readLong(); }

        short readShort() {
            return (short)((data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8));
        }

        float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        boolean readBool() {
            return data[pos++] != 0;
        }

        String readString() {
            long len = readLong();
            if (len < 0 || len > Integer.MAX_VALUE - 1) return "";
            String s = new String(data, pos, (int)len, StandardCharsets.UTF_8);
            pos += (int)len;
            return s;
        }
    }

    // ------------------------------------------------------------------
    // GGUF writer (little-endian)
    // ------------------------------------------------------------------
    static class GGufWriter {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        void writeRaw(byte[] b) throws IOException { bos.write(b); }

        void writeInt(int v) throws IOException {
            bos.write(v & 0xFF);
            bos.write((v >> 8) & 0xFF);
            bos.write((v >> 16) & 0xFF);
            bos.write((v >> 24) & 0xFF);
        }

        void writeLong(long v) throws IOException {
            bos.write((int)(v & 0xFF));
            bos.write((int)((v >> 8) & 0xFF));
            bos.write((int)((v >> 16) & 0xFF));
            bos.write((int)((v >> 24) & 0xFF));
            bos.write((int)((v >> 32) & 0xFF));
            bos.write((int)((v >> 40) & 0xFF));
            bos.write((int)((v >> 48) & 0xFF));
            bos.write((int)((v >> 56) & 0xFF));
        }

        void writeShort(short v) throws IOException {
            bos.write(v & 0xFF);
            bos.write((v >> 8) & 0xFF);
        }

        void writeFloat(float v) throws IOException {
            writeInt(Float.floatToRawIntBits(v));
        }

        void writeBool(boolean v) throws IOException {
            bos.write(v ? 1 : 0);
        }

        void writeString(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeLong(b.length);
            bos.write(b);
        }

        void writePadding() throws IOException {
            while (bos.size() % GGUF_DEFAULT_ALIGNMENT != 0) {
                bos.write(0);
            }
        }

        void writeKV(GGufReader r, int type) throws IOException {
            switch (type) {
                case TYPE_UINT8:    bos.write(r.data[r.pos++]); break;
                case TYPE_INT8:     bos.write(r.data[r.pos++]); break;
                case TYPE_UINT16:   writeShort(r.readShort()); break;
                case TYPE_INT16:    writeShort(r.readShort()); break;
                case TYPE_UINT32:   writeInt(r.readInt()); break;
                case TYPE_INT32:    writeInt(r.readInt()); break;
                case TYPE_FLOAT32:  writeFloat(r.readFloat()); break;
                case TYPE_BOOL:     writeBool(r.readBool()); break;
                case TYPE_STRING:   writeString(r.readString()); break;
                case TYPE_UINT64:   writeLong(r.readLong()); break;
                case TYPE_INT64:    writeLong(r.readLong()); break;
                case TYPE_FLOAT64:  writeLong(r.readLong()); break;
                case TYPE_ARRAY: {
                    int elemType = r.readInt();
                    long n = r.readLong();
                    writeInt(elemType);
                    writeLong(n);
                    for (long i = 0; i < n; i++) writeKV(r, elemType);
                    break;
                }
            }
        }

        byte[] toByteArray() { return bos.toByteArray(); }
    }

    // ------------------------------------------------------------------
    // Tensor size calculation
    // ------------------------------------------------------------------
    static long ggmlNbytes(int type, int[] ne, int nDims) {
        int blk = (type >= 0 && type < BLOCK_SIZES.length) ? BLOCK_SIZES[type] : 1;
        int tsz = (type >= 0 && type < TYPE_SIZES.length)  ? TYPE_SIZES[type]  : 1;
        if (blk == 0 || tsz == 0) return 0;
        long n = ne[0];
        for (int i = 1; i < nDims; i++) n *= ne[i];
        if (blk == 1) return n * tsz;
        return (n / blk) * tsz;
    }

    static long pad(long size, long alignment) {
        long a = alignment > 0 ? alignment : GGUF_DEFAULT_ALIGNMENT;
        return ((size + a - 1) / a) * a;
    }

    // ------------------------------------------------------------------
    // Incremental Range download via NettyHttpUtils
    // ------------------------------------------------------------------
    private static final long CHUNK_SIZE = 6L * 1024 * 1024;
    private static final long MAX_DOWNLOAD = 36L * 1024 * 1024;
    private static final String META_CACHE_DIR = "meta";

    
    public static String downloadHeader(String urlStr) throws Exception {
    	// 判断url还是别的
    	boolean isUrl = urlStr.startsWith("http://") || urlStr.startsWith("https://");
		String base;
		if (isUrl) {
			base = urlStr.substring(urlStr.lastIndexOf('/') + 1);
			int qm = base.indexOf('?');
			if (qm >= 0) base = base.substring(0, qm);
		} else {
			base = new File(urlStr).getName();
		}
		
        Path metaDir = LlamaServer.getCachePath().resolve(META_CACHE_DIR);
        if (!Files.exists(metaDir)) {
            Files.createDirectories(metaDir);
        }
        
        String outPath = metaDir.resolve(base + ".meta").toString();
        
        File file = new File(outPath);
		
		if(file.exists()) {
			return outPath;
		}
        
        Path tmpFile = Files.createTempFile(metaDir, "gguf-header-", ".bin");
        long downloaded = 0;

        try {
            while (downloaded < MAX_DOWNLOAD) {
                long start = downloaded;
                long end = start + CHUNK_SIZE - 1;

                System.err.printf("Downloading: bytes " + start + "-" + end + " (" + (CHUNK_SIZE / 1048576.0) + " MiB)... ");

                Response resp = NettyHttpUtils.request(urlStr)
                        .header("Range", "bytes=" + start + "-" + end)
                        .header("Accept-Encoding", "identity")
                        .connectTimeout(30)
                        .readTimeout(30)
                        .skipStatusValidation()
                        .execute();

                int status = resp.statusCode();
                if (status != 200 && status != 206) {
                    System.err.printf("FAILED (HTTP %d)\n", status);
                    return outPath;
                }

                byte[] chunk = resp.body();
                Files.write(tmpFile, chunk, StandardOpenOption.APPEND);
                downloaded += chunk.length;

                System.err.printf("got " + chunk.length + " bytes (total " + downloaded + "), status=" + status + "\n");

                if (chunk.length < CHUNK_SIZE) {
                    System.err.println("Server returned less than requested, reached end of file.");
                    break;
                }

                byte[] allData = Files.readAllBytes(tmpFile);
                try {
                    int[] nTensors = new int[1];
                    int[] nKv = new int[1];
                    if (validateHeader(allData, nTensors, nKv)) {
                        System.err.printf("Header OK: " + nKv[0] + " KV, " + nTensors[0] + " tensors\n");
                        GGufMetaDataExtractor.writeMetaFile(allData, outPath);
                        return outPath;
                    }
                } catch (Exception e) {
                    // header incomplete, continue
                }
                System.err.println("Header incomplete, downloading next chunk...");
            }
            throw new IOException("Could not parse GGUF header within " + (MAX_DOWNLOAD / 1048576) + " MiB");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    static boolean validateHeader(byte[] data, int[] outNTensors, int[] outNKv) {
        if (data.length < 24) return false;
        GGufReader r = new GGufReader(data);
        if (r.readInt() != GGUF_MAGIC) return false;
        r.readInt(); // version
        long nTensors = r.readLong();
        long nKv = r.readLong();
        if (nTensors < 0 || nKv < 0) return false;

        // Skip KV pairs
        try {
            for (long i = 0; i < nKv; i++) {
                r.readString(); // key
                skipValue(r, r.readInt()); // value
            }
            // Skip tensor info
            for (long i = 0; i < nTensors; i++) {
                r.readString(); // name
                int nDims = r.readInt();
                for (int d = 0; d < nDims; d++) r.readLong(); // dims
                r.readInt();  // type
                r.readLong(); // offset
            }
        } catch (Exception e) {
            return false;
        }
        outNTensors[0] = (int)nTensors;
        outNKv[0] = (int)nKv;
        return true;
    }

    static void skipValue(GGufReader r, int type) {
        switch (type) {
            case TYPE_UINT8:  case TYPE_INT8:  case TYPE_BOOL: r.pos++; break;
            case TYPE_UINT16: case TYPE_INT16: r.pos += 2; break;
            case TYPE_UINT32: case TYPE_INT32: case TYPE_FLOAT32: r.pos += 4; break;
            case TYPE_UINT64: case TYPE_INT64: case TYPE_FLOAT64: r.pos += 8; break;
            case TYPE_STRING:  r.readString(); break;
            case TYPE_ARRAY: {
                int et = r.readInt();
                long n = r.readLong();
                for (long i = 0; i < n; i++) skipValue(r, et);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Process a GGUF buffer and write metadata-only file
    // ------------------------------------------------------------------
    public static void writeMetaFile(byte[] headerData, String outPath) throws Exception {
        GGufReader r = new GGufReader(headerData);

        // Read and write header
        int magic = r.readInt();
        int version = r.readInt();
        long nTensors = r.readLong();
        long nKv = r.readLong();

        GGufWriter w = new GGufWriter();
        w.writeInt(magic);
        w.writeInt(version);
        w.writeLong(nTensors);
        w.writeLong(nKv);

        // --- Copy KV pairs ---
        for (long i = 0; i < nKv; i++) {
            w.writeString(r.readString());
            int type = r.readInt();
            w.writeInt(type);
            w.writeKV(r, type);

            // If this is the alignment key, re-read for the value
            // (simpler: just use default GGUF_DEFAULT_ALIGNMENT)
        }

        // --- Rewrite tensor info with sequential offsets ---
        long dataOffset = 0; // new sequential offset within data section
        for (long i = 0; i < nTensors; i++) {
            String name = r.readString();
            int nDims = r.readInt();
            int[] ne = new int[4];
            for (int d = 0; d < nDims; d++) {
                ne[d] = (int)r.readLong();
            }
            int type = r.readInt();
            r.readLong(); // skip old offset

            // Write tensor info with new sequential offset
            w.writeString(name);
            w.writeInt(nDims);
            for (int d = 0; d < nDims; d++) w.writeLong(ne[d]);
            w.writeInt(type);
            w.writeLong(dataOffset);

            // Advance data offset
            long nbytes = ggmlNbytes(type, ne, nDims);
            dataOffset = pad(dataOffset, GGUF_DEFAULT_ALIGNMENT);  // align each tensor
            dataOffset += nbytes;
        }

        // Alignment padding after tensor info (to data section start)
        w.writePadding();

        // Write file
        byte[] output = w.toByteArray();
        try (FileOutputStream fos = new FileOutputStream(outPath)) {
            fos.write(output);
        }

        System.err.printf("Wrote metadata: " + outPath + " (" + output.length + " bytes, " + output.length / 1024.0 + " KiB)\n");
    }

//    // ------------------------------------------------------------------
//    // Main
//    // ------------------------------------------------------------------
//    public static void main(String[] args) throws Exception {
//        if (args.length < 1) {
//            System.err.println("Usage: java GGufMetaExtractor.java <input.gguf | https://.../model.gguf>");
//            //System.exit(1);
//        }
//        
//        args = new String[] { "https://huggingface.co/ReadyArt/Melody1437-27B-v0.3-GGUF/resolve/main/Melody1437-27B-v0.3-Q6_K.gguf" };
//
//        String input = args[0];
//        boolean isUrl = input.startsWith("http://") || input.startsWith("https://");
//
//        // Output is always in cache/meta/
//        Path metaDir = LlamaServer.getCachePath().resolve(META_CACHE_DIR);
//        if (!Files.exists(metaDir)) {
//            Files.createDirectories(metaDir);
//        }
//        String base = isUrl ? input.substring(input.lastIndexOf('/') + 1) : input;
//        int qm = base.indexOf('?');
//        if (qm >= 0) base = base.substring(0, qm);
//        String outPath = metaDir.resolve(base + ".meta.gguf").toString();
//
//        byte[] headerData;
//        if (isUrl) {
//            System.err.println("Fetching header from URL...");
//            headerData = downloadHeader(input);
//            if (headerData == null) System.exit(1);
//        } else {
//            System.err.println("Reading local file...");
//            try (FileInputStream fis = new FileInputStream(input);
//                 ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
//                byte[] tmp = new byte[8192];
//                int n;
//                while ((n = fis.read(tmp)) != -1) buf.write(tmp, 0, n);
//                headerData = buf.toByteArray();
//            }
//        }
//
//        writeMetaFile(headerData, outPath);
//        System.err.println("Done.");
//    }
}
