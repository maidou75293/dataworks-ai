package com.lab.dataworks.lineage;

import com.lab.dataworks.query.DorisQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V103：Doris EXPLAIN 确认后表级边升 measured；列级边保持 inferred。 */
class LineageConfidenceTest {

    @Test
    void markMeasuredOnlyPromotesConfirmedTableEdges() {
        SqlLineageParser parser = new SqlLineageParser();
        List<SqlLineageParser.Edge> edges = parser.parse(
                "insert into dwd.users (id) select u.id from ods.users u");
        List<SqlLineageParser.Edge> marked = LineageService.markMeasured(
                edges, Set.of("ods.users", "users"));

        for (SqlLineageParser.Edge edge : marked) {
            if ("TABLE".equals(edge.edgeType())) {
                assertEquals("measured", edge.confidence());
            } else {
                assertEquals("inferred", edge.confidence(), "列级边不因 EXPLAIN 被提升");
            }
        }
    }

    @Test
    void unconfirmedTableEdgeStaysInferred() {
        List<SqlLineageParser.Edge> edges = new SqlLineageParser().parse(
                "insert into dwd.users select * from other.orders");
        List<SqlLineageParser.Edge> marked = LineageService.markMeasured(edges, Set.of("ods.users"));
        assertTrue(marked.stream()
                .filter(e -> "TABLE".equals(e.edgeType()))
                .allMatch(e -> "inferred".equals(e.confidence())));
    }

    @Test
    void dorisPlanExtractsTableNames() {
        DorisQueryService doris = mock(DorisQueryService.class);
        when(doris.rawQuery(anyLong(), anyString(), anyInt())).thenReturn(
                new DorisQueryService.Result(List.of("Explain String"),
                        List.of(List.of("   TABLE: demo_retail.ods_order")), 1, 500, 1L, "doris"));
        DorisPlanLineage plan = new DorisPlanLineage(doris);
        Set<String> tables = plan.tables(1L, "insert into dws select * from ods_order");
        assertTrue(tables.contains("demo_retail.ods_order"));
        assertTrue(tables.contains("ods_order"));
    }

    @Test
    void dorisPlanFailureDegradesToEmptySet() {
        DorisQueryService doris = mock(DorisQueryService.class);
        when(doris.rawQuery(anyLong(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("doris down"));
        assertTrue(new DorisPlanLineage(doris).tables(1L, "insert into t select * from s").isEmpty());
    }
}
