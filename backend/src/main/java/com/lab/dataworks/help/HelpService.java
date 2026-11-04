package com.lab.dataworks.help;

import com.lab.dataworks.common.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帮助中心内容服务（REQ-HELP-NAV-1）。
 *
 * <p>素材的单一事实源是仓库 docs/帮助/*.md；构建期由 scripts/ci/sync-help-docs.py
 * 同步进 classpath:help-docs/ 打进 jar（DEV 无外网，后端只读类路径，不读磁盘仓库目录）。
 * 每篇头部用 front-matter 声明 title / manual-date / order / sources，
 * 「## 二级标题」即目录树锚点（id 为出现序号 s1..sN，与前端 MarkdownView 同一规则：
 * 围栏代码块内的 ## 不算标题——手册里有 bash 注释）。
 */
@Service
public class HelpService {

    public record Section(String id, String title) {
    }

    public record Doc(String path, String title, String manualDate, int order,
                      String sources, String markdown, List<Section> sections, int size) {
    }

    private volatile List<Doc> docs;   // 类路径内容打进 jar 后不可变，一次加载长期缓存

    public List<Doc> all() {
        List<Doc> local = docs;
        if (local == null) {
            synchronized (this) {
                if (docs == null) {
                    docs = load();
                }
            }
        }
        return docs;
    }

    /** 目录树：按 order 排序，只带标题/日期/章节，正文另取。 */
    public Map<String, Object> tree() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Doc d : all()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("path", d.path());
            n.put("title", d.title());
            n.put("manualDate", d.manualDate());
            n.put("order", d.order());
            n.put("size", d.size());
            n.put("sections", d.sections().stream()
                    .map(s -> Map.of("id", s.id(), "title", s.title())).toList());
            nodes.add(n);
        }
        return Map.of("docs", nodes);
    }

    /** 按文件名取正文；path 只允许清单内文件名（防目录穿越，也防把任意 classpath 掏出来）。 */
    public Doc doc(String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path 不能为空");
        }
        String name = path.replace('\\', '/');
        if (name.contains("/")) {
            throw ApiException.notFound("帮助文档不存在: " + path);
        }
        return all().stream().filter(d -> d.path().equals(name)).findFirst()
                .orElseThrow(() -> ApiException.notFound("帮助文档不存在: " + path));
    }

    private List<Doc> load() {
        List<Doc> out = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:help-docs/*.md");
        } catch (IOException e) {
            throw ApiException.notFound("帮助内容未打包（help-docs 缺失）");
        }
        for (Resource r : resources) {
            String filename = r.getFilename() == null ? "" : r.getFilename();
            if (filename.isEmpty()) {
                continue;
            }
            String raw;
            try (InputStream in = r.getInputStream()) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            out.add(parse(filename, raw));
        }
        out.sort(Comparator.comparingInt(Doc::order).thenComparing(Doc::path));
        return List.copyOf(out);
    }

    private Doc parse(String filename, String raw) {
        String title = filename.replaceFirst("\\.md$", "");
        String manualDate = "";
        String sources = "";
        int order = 100;
        String body = raw;
        if (raw.startsWith("---\n")) {
            int end = raw.indexOf("\n---", 4);
            if (end > 0) {
                for (String line : raw.substring(4, end).split("\n")) {
                    int sep = line.indexOf(':');
                    if (sep <= 0) {
                        continue;
                    }
                    String key = line.substring(0, sep).trim();
                    String val = line.substring(sep + 1).trim();
                    switch (key) {
                        case "title" -> title = val;
                        case "manual-date" -> manualDate = val;
                        case "sources" -> sources = val;
                        case "order" -> {
                            try {
                                order = Integer.parseInt(val);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        default -> {
                        }
                    }
                }
                body = raw.substring(raw.indexOf('\n', end + 1) + 1);
            }
        }
        List<Section> sections = new ArrayList<>();
        int sNo = 0;
        boolean inFence = false;
        for (String line : body.split("\n")) {
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && line.startsWith("## ") && line.length() > 3) {
                sections.add(new Section("s" + (++sNo), line.substring(3).trim()));
            }
        }
        return new Doc(filename, title, manualDate, order, sources, body, sections, body.getBytes(StandardCharsets.UTF_8).length);
    }
}
