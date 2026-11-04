package com.lab.dataworks.metadata;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据来源开关的管理接口（V101）。
 *
 * <p>GET / PUT 都只给平台管理员（{@code system:manage}，且该权限点按约定只在默认
 * 租户生效）：开关是平台级配置，读接口会暴露 Gravitino 地址与降级诊断信息，不应
 * 该对所有登录用户开放。页面已经用 {@code can('system:manage')} 控制展示。</p>
 */
@RestController
@RequestMapping("/api/metadata/source")
public class MetadataSourceController {

    public record Input(String source) {
    }

    private final MetadataSettings settings;
    private final GravitinoClient gravitino;
    private final MetadataReader reader;
    private final SessionService sessions;

    public MetadataSourceController(MetadataSettings settings, GravitinoClient gravitino,
                                    MetadataReader reader, SessionService sessions) {
        this.settings = settings;
        this.gravitino = gravitino;
        this.reader = reader;
        this.sessions = sessions;
    }

    @GetMapping
    @RequireAtom("system:manage")
    public Map<String, Object> get() {
        return describe();
    }

    @PutMapping
    @RequireAtom("system:manage")
    public Map<String, Object> put(@RequestBody Input in, HttpServletRequest request) {
        if (in == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String source = MetadataSettings.normalize(in.source());
        String actor = String.valueOf(request.getAttribute("username"));
        String previous = settings.source();
        settings.setSource(source, actor);
        // 切换立即生效：清掉上一来源留下的进程内缓存，避免切回 gravitino 时
        // 30–60 秒内仍看到切换前的库/表列表。
        if (!previous.equalsIgnoreCase(source)) {
            reader.invalidateAll();
        }
        sessions.audit(actor, "METADATA_SOURCE_UPDATE", source,
                "metadata.source=" + settings.source(), "SUCCESS",
                AuthInterceptor.clientIp(request));
        return describe();
    }

    private Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", settings.source());
        // 第二批：页面按 degraded 展示「元数据降级」，并给出本小时回退次数与最近原因
        out.putAll(reader.stats());
        out.put("options", List.of(
                Map.of("value", MetadataSettings.GRAVITINO, "label", "Gravitino",
                        "description", "DORIS 数据源保存时注册/更新 Gravitino Catalog"),
                Map.of("value", MetadataSettings.DIRECT, "label", "直连",
                        "description", "跳过 Gravitino 同步，数据源只保存在平台库")));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("baseUrl", gravitino.baseUrl());
        target.put("metalake", gravitino.metalake());
        out.put("gravitino", target);
        return out;
    }
}
