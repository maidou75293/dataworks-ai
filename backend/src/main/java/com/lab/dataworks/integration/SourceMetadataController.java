package com.lab.dataworks.integration;

import com.lab.dataworks.auth.RequireAtom;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REQ-DW-15：选完源数据源后的「结构预览」只读接口（按已保存的 id）。
 *
 * <p>MySQL / PostgreSQL / Doris：库表清单 + 字段定义（名称/类型/可空/注释），
 * 全部走 JDBC DatabaseMetaData；Kafka：topic 清单 + 抽样消息推断字段结构
 * （响应带 inferred=true，前端标注「抽样推断」）。整个接口只读——不写源端、不建任务。</p>
 *
 * <p>REQ-DW-16 后连接与元数据逻辑收口在 {@link SourceMetadataProbe}；
 * 未保存表单的临时预览走 {@link AdhocSourceMetadataController}，两条路径共用同一实现。</p>
 */
@RestController
@RequestMapping("/api/integration/datasource/{id}")
public class SourceMetadataController {

    private final SourceMetadataProbe probe;

    public SourceMetadataController(SourceMetadataProbe probe) {
        this.probe = probe;
    }

    /** 表（或 Kafka topic）清单。 */
    @GetMapping("/tables")
    @RequireAtom("datasource:read")
    public Map<String, Object> tables(@PathVariable long id) {
        return probe.tablesOf(probe.specOfSaved(id));
    }

    /**
     * 单表字段定义；Kafka 数据源下同名为 topic，返回抽样推断结构。
     *
     * @param catalog/schema 由 /tables 结果原样回传，用于精确锁定（可空）
     */
    @GetMapping("/tables/{table}/columns")
    @RequireAtom("datasource:read")
    public Map<String, Object> columns(@PathVariable long id,
                                       @PathVariable String table,
                                       @RequestParam(required = false) String catalog,
                                       @RequestParam(required = false) String schema,
                                       @RequestParam(defaultValue = "10") int sampleLimit) {
        return probe.columnsOf(probe.specOfSaved(id), table, catalog, schema, sampleLimit);
    }
}
