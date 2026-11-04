package com.lab.dataworks.lineage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLineageParserTest {

    private final SqlLineageParser parser = new SqlLineageParser();

    private Set<String> tablePairs(String sql) {
        return parser.parse(sql).stream()
                .filter(e -> "TABLE".equals(e.edgeType()))
                .map(e -> e.sourceTable() + "->" + e.targetTable())
                .collect(Collectors.toSet());
    }

    private List<SqlLineageParser.Edge> columns(String sql) {
        return parser.parse(sql).stream()
                .filter(e -> "COLUMN".equals(e.edgeType()))
                .toList();
    }

    @Test
    void insertSelectRecordsTableAndColumns() {
        Set<String> pairs = tablePairs("insert into dwd.users (id, name) select u.id, u.name from ods.users u");
        assertEquals(Set.of("ods.users->dwd.users"), pairs);
        assertEquals(Set.of("ods.users.id->dwd.users.id", "ods.users.name->dwd.users.name"),
                columns("insert into dwd.users (id, name) select u.id, u.name from ods.users u")
                        .stream().map(e -> e.sourceTable() + "." + e.sourceColumn() + "->"
                                + e.targetTable() + "." + e.targetColumn()).collect(Collectors.toSet()));
    }

    @Test
    void cteResolvesToRealTableAndDropsAlias() {
        String sql = """
                with base as (select id, name from ods.users)
                insert into dwd.users (id, name) select b.id, b.name from base b
                """;
        Set<String> pairs = tablePairs(sql);
        assertEquals(Set.of("ods.users->dwd.users"), pairs);
        assertFalse(pairs.stream().anyMatch(p -> p.contains("base->")));
        assertTrue(sql.contains("base"));
    }

    @Test
    void cteWithJoinKeepsTablesButSkipsColumnEdges() {
        String sql = """
                with base as (select u.id, r.name from ods.users u join ods.regions r on r.id = u.region_id)
                insert into dwd.users (id, region) select b.id, b.name from base b
                """;
        Set<String> pairs = tablePairs(sql);
        assertEquals(Set.of("ods.users->dwd.users", "ods.regions->dwd.users"), pairs);
        assertTrue(columns(sql).isEmpty(), "多表 CTE 的列归属不可靠，应只保留表级边");
    }

    @Test
    void createTableAsSelectMapsAliases() {
        String sql = "create table ads.daily as select a.id as uid, a.name as uname from dwd.users a";
        assertEquals(Set.of("dwd.users->ads.daily"), tablePairs(sql));
        assertEquals(Set.of("dwd.users.id->ads.daily.uid", "dwd.users.name->ads.daily.uname"),
                columns(sql).stream().map(e -> e.sourceTable() + "." + e.sourceColumn() + "->"
                        + e.targetTable() + "." + e.targetColumn()).collect(Collectors.toSet()));
    }

    @Test
    void unionRecordsAllSourcesWithoutColumns() {
        String sql = "insert into ads.all_users select id from ods.a union all select id from ods.b";
        assertEquals(Set.of("ods.a->ads.all_users", "ods.b->ads.all_users"), tablePairs(sql));
        assertTrue(columns(sql).isEmpty());
    }

    @Test
    void plainCreateProducesNoLineage() {
        assertTrue(parser.parse("create table if not exists ods.users (id int primary key, name text)").isEmpty());
    }

    @Test
    void dropAndTruncateProduceNoLineage() {
        assertTrue(parser.parse("drop table if exists ods.a, ods.b").isEmpty());
        assertTrue(parser.parse("truncate table ods.a").isEmpty());
        assertTrue(parser.parse("alter table ods.a add column c int").isEmpty());
    }

    @Test
    void insertValuesIsWriteOnly() {
        assertEquals(Set.of("->ods.users"), tablePairs("insert into ods.users (id) values (1)"));
    }

    @Test
    void selfInsertKeepsSelfEdge() {
        assertEquals(Set.of("ods.users->ods.users"),
                tablePairs("insert into ods.users select * from ods.users"));
    }

    @Test
    void commentsDoNotCreateEdges() {
        String sql = """
                -- insert into fake.t select * from fake.s
                /* create table fake.x as select * from fake.y */
                insert into t select id from s
                """;
        assertEquals(Set.of("s->t"), tablePairs(sql));
    }

    @Test
    void quotedIdentifiersAreNormalized() {
        assertEquals(Set.of("ods.users->dwd.users"),
                tablePairs("insert into \"DWD\".\"Users\" select id from \"ODS\".\"Users\""));
    }

    @Test
    void multiLevelCteResolvesToRealTable() {
        String sql = """
                with a as (select id, name from ods.src),
                     b as (select id, name from a)
                insert into dwd.t (id, name) select b.id, b.name from b
                """;
        assertEquals(Set.of("ods.src->dwd.t"), tablePairs(sql));
        assertEquals(Set.of("ods.src.id->dwd.t.id", "ods.src.name->dwd.t.name"),
                columns(sql).stream().map(e -> e.sourceTable() + "." + e.sourceColumn() + "->"
                        + e.targetTable() + "." + e.targetColumn()).collect(Collectors.toSet()));
    }

    @Test
    void recursiveCteDoesNotProduceBogusColumnEdges() {
        String sql = """
                with recursive tree as (
                    select id, parent_id from ods.nodes where parent_id is null
                    union all
                    select n.id, n.parent_id from ods.nodes n join tree t on n.parent_id = t.id
                )
                insert into dwd.tree (id, parent_id) select t.id, t.parent_id from tree t
                """;
        assertEquals(Set.of("ods.nodes->dwd.tree"), tablePairs(sql));
        assertTrue(columns(sql).isEmpty(), "递归 CTE 的列归属不可靠，应只保留表级边");
    }

    @Test
    void updateUsesTargetAndOtherTables() {
        Set<String> pairs = tablePairs("update dwd.users u set name = s.name from ods.staging s where s.id = u.id");
        assertEquals(Set.of("ods.staging->dwd.users"), pairs);
    }

    @Test
    void readOnlySelectHasEmptyTarget() {
        assertEquals(Set.of("ods.users->"), tablePairs("select * from ods.users"));
    }

    @Test
    void selectWithoutFromHasNoEdges() {
        assertTrue(parser.parse("select 1 as n").isEmpty());
    }

    @Test
    void multipleStatementsAreAllParsed() {
        assertEquals(Set.of("s1->t1", "s2->t2"),
                tablePairs("insert into t1 select a from s1; insert into t2 select b from s2"));
    }

    @Test
    void commentMarkersInsideStringsArePreserved() {
        assertEquals(Set.of("s->t"), tablePairs(
                "insert into t select 'http://x--y' as url from s"));
        assertEquals(Set.of("s->t"), tablePairs(
                "insert into t select 'a/*b*/c' as note from s"));
    }

    @Test
    void commaJoinRecordsBothSources() {
        assertEquals(Set.of("s1->t", "s2->t"),
                tablePairs("insert into t select a.id, b.name from s1 a, s2 b where a.id = b.id"));
    }

    @Test
    void updateWithSubqueryKeepsSubquerySource() {
        Set<String> pairs = tablePairs(
                "update dwd.users set name = (select name from ods.staging where id = 1)");
        assertEquals(Set.of("ods.staging->dwd.users"), pairs);
    }

    @Test
    void fallbackRegexStillFindsTablesOnDialectSyntax() {
        // JSqlParser 解析不了的方言写法，兜底正则至少保留表级边
        Set<String> pairs = tablePairs("insert into t select * from s where dt = ${biz_date}");
        assertTrue(pairs.contains("s->t"), "fallback should keep s->t, got " + pairs);
    }

    @Test
    void splitsCatalogDbAndDefaultsToDoris() {
        SqlLineageParser.Edge two = parser.parse(
                        "insert into dwd.users select * from ods.users")
                .stream().filter(e -> "TABLE".equals(e.edgeType())).findFirst().orElseThrow();
        assertEquals("doris", two.sourceCatalog());
        assertEquals("ods", two.sourceDb());
        assertEquals("doris.ods.users", two.sourceNode());
        assertEquals("doris.dwd.users", two.targetNode());
        assertEquals("inferred", two.confidence());

        SqlLineageParser.Edge three = parser.parse(
                        "insert into lake.dwd.users select * from lake.ods.users")
                .stream().filter(e -> "TABLE".equals(e.edgeType())).findFirst().orElseThrow();
        assertEquals("lake", three.sourceCatalog());
        assertEquals("ods", three.sourceDb());
        assertEquals("lake.ods.users", three.sourceNode());

        SqlLineageParser.Edge one = parser.parse("insert into t select * from s")
                .stream().filter(e -> "TABLE".equals(e.edgeType())).findFirst().orElseThrow();
        assertEquals("doris", one.sourceCatalog());
        assertEquals("", one.sourceDb());
        assertEquals("doris..s", one.sourceNode());
    }

    @Test
    void deeplyNestedSqlFallsBackInsteadOfThrowing() {
        // 几百层子查询会让 JSqlParser 递归 StackOverflowError（Error 而非 Exception）。
        // 这里必须降级成正则表级识别，不能把异常抛给接口。
        int depth = 700;
        String sql = "insert into t select * from s where a in ("
                + "select * from t where a in (".repeat(depth)
                + "select 1"
                + ")".repeat(depth) + ")";
        Set<String> pairs = tablePairs(sql);
        assertTrue(pairs.contains("s->t"), "deep SQL should fall back, got " + pairs);
    }
}
