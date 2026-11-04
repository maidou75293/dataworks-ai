package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SeaTunnel 2.3.9 连接器目录（来自 connectors.json）。
 * 目录只表示“能否配置”；运行前还要看 status 是否为 READY（JAR 已挂载）。
 */
@Service
public class ConnectorCatalog {

    private final ObjectMapper mapper;
    private List<Map<String, Object>> connectors = List.of();
    private final Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    private String version = "2.3.9";

    public ConnectorCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("connectors.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            version = root.path("version").asText("2.3.9");
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode node : root.path("connectors")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = mapper.convertValue(node, Map.class);
                list.add(item);
                byKey.put(key(String.valueOf(item.get("name"))), item);
            }
            connectors = list;
        } catch (Exception e) {
            throw new IllegalStateException("加载 connectors.json 失败", e);
        }
    }

    public List<Map<String, Object>> list() {
        return connectors;
    }

    public String version() {
        return version;
    }

    public boolean has(String name) {
        return name != null && byKey.containsKey(key(name));
    }

    public Map<String, Object> require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("连接器类型不能为空");
        }
        Map<String, Object> item = byKey.get(key(name));
        if (item == null) {
            throw ApiException.badRequest("未知连接器: " + name);
        }
        return item;
    }

    public String canonical(String name) {
        return String.valueOf(require(name).get("name"));
    }

    public boolean supportsSource(String name) {
        return Boolean.TRUE.equals(require(name).get("source"));
    }

    public boolean supportsSink(String name) {
        return Boolean.TRUE.equals(require(name).get("sink"));
    }

    public String status(String name) {
        return String.valueOf(require(name).getOrDefault("status", "NEEDS_JAR"));
    }

    public void requireRunnable(String name, boolean source) {
        Map<String, Object> item = require(name);
        if (source && !Boolean.TRUE.equals(item.get("source"))) {
            throw ApiException.badRequest(name + " 不支持作为 Source");
        }
        if (!source && !Boolean.TRUE.equals(item.get("sink"))) {
            throw ApiException.badRequest(name + " 不支持作为 Sink");
        }
        String status = String.valueOf(item.getOrDefault("status", "NEEDS_JAR"));
        if (!"READY".equals(status)) {
            throw ApiException.badRequest("连接器 " + name + " 当前不可运行（" + status + "）");
        }
    }

    private String key(String value) {
        return value == null ? "" : value.replace("-", "").replace("_", "")
                .toLowerCase(Locale.ROOT);
    }
}
