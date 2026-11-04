package com.lab.dataworks.integration;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.integration.SourceMetadataProbe.AdhocRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REQ-DW-16：「新建/编辑数据源」表单在<b>未保存</b>状态下的临时结构预览。
 *
 * <p>请求体就是表单里的连接参数：后端用它们做一次只读连接取表清单/字段定义，
 * 参数（含明文密码）只在本请求内存活——<b>不落库、不保存凭据、不改 last_test_*</b>。
 * 权限点与 {@code POST /api/datasources/test}（未保存连接测试）一致用
 * {@code datasource:write}；能力矩阵与错误分类与 REQ-DW-15 完全同口径
 * （共用 {@link SourceMetadataProbe}，不存在第二份实现）。</p>
 *
 * <p>支持的类型：MySQL / PostgreSQL / Doris（表+字段）、Kafka（topic+抽样推断字段）；
 * 其余类型显式 400，不静默返回空清单。</p>
 */
@RestController
@RequestMapping("/api/integration/datasource/preview")
public class AdhocSourceMetadataController {

    private final SourceMetadataProbe probe;

    public AdhocSourceMetadataController(SourceMetadataProbe probe) {
        this.probe = probe;
    }

    /** 用表单参数试连并返回表（或 Kafka topic）清单。 */
    @PostMapping("/tables")
    @RequireAtom("datasource:write")
    public Map<String, Object> tables(@RequestBody AdhocRequest req) {
        return probe.tablesOf(probe.specOfAdhoc(req));
    }

    /** 用表单参数试连并返回单表字段定义；Kafka 返回抽样推断结构。 */
    @PostMapping("/columns")
    @RequireAtom("datasource:write")
    public Map<String, Object> columns(@RequestBody AdhocRequest req) {
        return probe.columnsOf(probe.specOfAdhoc(req), req.table(), req.catalog(),
                req.schema(), req.sampleLimit());
    }
}
