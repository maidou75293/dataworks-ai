package com.lab.dataworks.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 监控要看的命名空间（可配置多值）。
 *
 * <p>以前 ClusterOverviewService / K8sSnapshotService 把 {@code dw-dev} 写死，
 * Spark Thrift Server 挪到 {@code dw-spark} 之后，监控页就再也看不到它——
 * 集群里 Running、页面上不存在。这里收口成一处配置，默认覆盖 dw-dev / dw-spark / kube-system。</p>
 *
 * <p>配置：{@code platform.monitor.namespaces}（环境变量 {@code PLATFORM_MONITOR_NAMESPACES}），
 * 逗号分隔；只影响监控读取范围，不影响任何部署行为。</p>
 */
@Component
public class MonitorNamespaces {

    /**
     * 默认覆盖的命名空间；配置为空时也用它，避免监控整体看不到 Spark。
     * 2026-11-03 补 kube-system：入口组件（traefik/coredns）不在监控范围时，
     * 集群里 Pod 卡死 34 分钟、页面「异常 Pod=0」——平台自己的入口坏了却看不见。
     */
    private static final List<String> DEFAULT = List.of("dw-dev", "dw-spark", "kube-system");

    private final List<String> all;

    public MonitorNamespaces(
            @Value("${platform.monitor.namespaces:dw-dev,dw-spark,kube-system}") String raw) {
        this.all = Arrays.stream(raw == null ? new String[0] : raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                // 命名空间是 DNS 标签：先归一化小写再去重，否则 DW-SPARK 会被当成第二个
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }

    /** 顺序稳定、去重后的命名空间列表；配空/null 时用默认值（不让监控整体空掉）。 */
    public List<String> all() {
        return all.isEmpty() ? DEFAULT : all;
    }

    /** PromQL 用的 {@code namespace=~"dw-dev|dw-spark"} 里的那段正则。 */
    public String regex() {
        return all().stream()
                .map(MonitorNamespaces::escapeRegex)
                .collect(Collectors.joining("|"));
    }

    private static String escapeRegex(String value) {
        // 命名空间理论上只有字母数字和 -，这里仍然转义一遍，避免配置写进正则元字符
        return value.replaceAll("([.\\\\^$|()\\[\\]{}*+?])", "\\\\$1");
    }
}
