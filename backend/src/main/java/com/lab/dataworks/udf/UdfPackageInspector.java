package com.lab.dataworks.udf;

import com.lab.dataworks.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * UDF 资源包解析：Python 顶层函数候选、requirements、wheel 包名。
 *
 * <p>只做「打包内容的结构化」：不编译、不执行、不安装依赖。解析结果用于界面提示
 * （注册 Python 函数时给入口候选）和排障；真正的依赖安装由 Flink Python 环境负责。</p>
 *
 * <p>压缩包按不可信输入处理：限制条目数、单文件读取上限和总解压上限，避免 zip bomb
 * 把后端内存/CPU 打满。</p>
 */
public class UdfPackageInspector {

    private static final int MAX_ENTRIES = 2000;
    private static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REQUIREMENTS_BYTES = 1 * 1024 * 1024;
    private static final int MAX_FUNCTIONS = 200;
    private static final int MAX_REQUIREMENTS = 200;

    /** 顶层 def / async def；缩进的方法不算入口候选。 */
    private static final Pattern PY_DEF = Pattern.compile(
            "(?m)^(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    public record Inspection(List<String> functions,
                             List<String> requirements,
                             String packageName,
                             String packageVersion,
                             String requirementsPath,
                             int pythonFiles,
                             boolean truncated) {
    }

    public Inspection inspect(String type, byte[] bytes, String filename) {
        String kind = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "jar" -> empty();
            case "py" -> inspectPython(bytes, filename);
            case "txt" -> inspectRequirements(bytes, filename);
            case "zip" -> inspectArchive(bytes, false);
            case "whl" -> inspectArchive(bytes, true);
            default -> throw ApiException.badRequest("不支持的资源类型: " + type);
        };
    }

    public java.util.Map<String, Object> toMap(Inspection inspection) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("functions", inspection.functions());
        out.put("requirements", inspection.requirements());
        out.put("pythonFiles", inspection.pythonFiles());
        out.put("truncated", inspection.truncated());
        if (inspection.packageName() != null) out.put("packageName", inspection.packageName());
        if (inspection.packageVersion() != null) out.put("packageVersion", inspection.packageVersion());
        if (inspection.requirementsPath() != null) {
            out.put("requirementsPath", inspection.requirementsPath());
        }
        return out;
    }

    private Inspection empty() {
        return new Inspection(List.of(), List.of(), null, null, null, 0, false);
    }

    private Inspection inspectPython(byte[] bytes, String filename) {
        String module = moduleOfPath(filename == null ? "module.py" : filename);
        List<String> functions = new ArrayList<>();
        for (String fn : parsePythonFunctions(prefix(bytes, MAX_TEXT_BYTES))) {
            functions.add(module + "." + fn);
        }
        return new Inspection(functions, List.of(), null, null, null, 1,
                functions.size() >= MAX_FUNCTIONS);
    }

    private Inspection inspectRequirements(byte[] bytes, String filename) {
        List<String> requirements = parseRequirements(prefix(bytes, MAX_REQUIREMENTS_BYTES));
        return new Inspection(List.of(), requirements, null, null,
                filename == null ? null : safeRelative(filename), 0,
                requirements.size() >= MAX_REQUIREMENTS);
    }

    private byte[] prefix(byte[] bytes, int limit) {
        if (bytes.length <= limit) {
            return bytes;
        }
        byte[] out = new byte[limit];
        System.arraycopy(bytes, 0, out, 0, limit);
        return out;
    }

    private Inspection inspectArchive(byte[] bytes, boolean wheel) {
        List<String> functions = new ArrayList<>();
        List<String> requirements = List.of();
        String packageName = null;
        String packageVersion = null;
        String requirementsPath = null;
        int pythonFiles = 0;
        boolean truncated = false;
        long[] total = {0};

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw ApiException.badRequest("压缩包条目过多（上限 " + MAX_ENTRIES + "）");
                }
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.isBlank() || name.startsWith("__MACOSX/")
                        || name.contains("..") || name.startsWith("/")) {
                    continue;
                }
                if (name.endsWith(".py")) {
                    pythonFiles++;
                    byte[] data = readLimited(zip, MAX_TEXT_BYTES, total);
                    if (functions.size() < MAX_FUNCTIONS) {
                        String module = moduleOfPath(name);
                        for (String fn : parsePythonFunctions(data)) {
                            if (functions.size() >= MAX_FUNCTIONS) {
                                truncated = true;
                                break;
                            }
                            functions.add(module + "." + fn);
                        }
                    }
                } else if (wheel && name.endsWith(".dist-info/METADATA")) {
                    String meta = new String(readLimited(zip, MAX_REQUIREMENTS_BYTES, total),
                            StandardCharsets.UTF_8);
                    for (String line : meta.split("\\R")) {
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("name:")) {
                            packageName = line.substring(5).trim();
                        } else if (lower.startsWith("version:")) {
                            packageVersion = line.substring(8).trim();
                        } else if (lower.startsWith("requires-dist:")) {
                            String dep = line.substring("requires-dist:".length()).trim();
                            if (!dep.isBlank() && requirements.size() < MAX_REQUIREMENTS) {
                                requirements = new ArrayList<>(requirements);
                                requirements.add(dep);
                            }
                        }
                    }
                } else if (name.equalsIgnoreCase("requirements.txt")
                        || name.toLowerCase(Locale.ROOT).endsWith("/requirements.txt")) {
                    List<String> parsed = parseRequirements(
                            readLimited(zip, MAX_REQUIREMENTS_BYTES, total));
                    // 根目录的 requirements.txt 优先于子目录里的
                    if (requirementsPath == null || !name.contains("/")) {
                        requirementsPath = name;
                        requirements = parsed;
                    }
                } else {
                    // 非文本条目也计入解压总量，防止「大二进制 + 小 py」绕过上限
                    drain(zip, total);
                }
            }
        } catch (IOException e) {
            throw ApiException.badRequest("读取压缩包失败: " + e.getMessage());
        }

        if (!wheel && pythonFiles == 0) {
            throw ApiException.badRequest("zip 包里必须至少包含一个 .py 文件");
        }
        return new Inspection(functions, requirements, packageName, packageVersion,
                requirementsPath, pythonFiles, truncated);
    }

    /**
     * 读取当前 entry 的前 {@code limit} 字节，同时把整个 entry 读完并累计到 total；
     * 超过总解压上限直接 400，避免 zip bomb。
     */
    private byte[] readLimited(InputStream in, int limit, long[] totalRef) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            totalRef[0] += n;
            if (totalRef[0] > MAX_UNCOMPRESSED) {
                throw ApiException.badRequest("压缩包解压后超过 "
                        + (MAX_UNCOMPRESSED / 1024 / 1024) + "MB，已拒绝");
            }
            if (out.size() < limit) {
                out.write(buffer, 0, Math.min(n, limit - out.size()));
            }
        }
        return out.toByteArray();
    }

    /** 丢弃当前 entry，累计到 totalRef。 */
    private void drain(InputStream in, long[] totalRef) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            totalRef[0] += n;
            if (totalRef[0] > MAX_UNCOMPRESSED) {
                throw ApiException.badRequest("压缩包解压后超过 "
                        + (MAX_UNCOMPRESSED / 1024 / 1024) + "MB，已拒绝");
            }
        }
    }

    private List<String> parsePythonFunctions(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        Matcher matcher = PY_DEF.matcher(text);
        while (matcher.find() && out.size() < MAX_FUNCTIONS) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private List<String> parseRequirements(byte[] bytes) {
        Set<String> out = new LinkedHashSet<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            int comment = line.indexOf(" #");
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > 500) {
                line = line.substring(0, 500);
            }
            out.add(line);
            if (out.size() >= MAX_REQUIREMENTS) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /** {@code pkg/mod.py} → {@code pkg.mod}；{@code pkg/__init__.py} → {@code pkg}。 */
    private String moduleOfPath(String path) {
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".py")) {
            value = value.substring(0, value.length() - 3);
        }
        String full = path.replace('\\', '/');
        if (full.toLowerCase(Locale.ROOT).endsWith(".py")) {
            full = full.substring(0, full.length() - 3);
        }
        full = full.replace('/', '.');
        if (full.endsWith(".__init__")) {
            full = full.substring(0, full.length() - 9);
        } else if (full.equals("__init__")) {
            full = "module";
        }
        return full.isBlank() ? value : full;
    }

    private String safeRelative(String filename) {
        String name = filename.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        return name;
    }
}
