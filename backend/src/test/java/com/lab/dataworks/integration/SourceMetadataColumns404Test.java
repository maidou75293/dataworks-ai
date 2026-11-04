package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.integration.SourceMetadataProbe.Qualified;
import com.lab.dataworks.integration.SourceMetadataProbe.Spec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DL-F3（DL-1 发现 3）回归：columns 预览对「不存在的表 / schema.table 限定名」的行为。
 *
 * <p>验收点：限定名按类型拆成精确锁定参数（PG 模式、MySQL/Doris 库=catalog，与 /tables
 * 返回的字段语义一致）；与显式 catalog/schema 参数冲突显式 400；查空后补存在性探测，
 * 仍缺失 → 404 明确文案（对齐同方法 Kafka 分支「topic 不存在→404」）；零列表不误伤（列空
 * 但表存在 → 200 空列）；正常有列路径不多一次 getTables 往返。全程 mock，不连真库。</p>
 */
class SourceMetadataColumns404Test {

    private static final Spec PG = new Spec("POSTGRESQL", "jdbc:postgresql://h/db", "u", "p", Map.of());
    private static final Spec MYSQL = new Spec("MYSQL", "jdbc:mysql://h/db", "u", "p", Map.of());

    // ------------------------------------------------------------------ ② 限定名拆解（纯函数）

    @Test
    void 无点表名原样保留() {
        Qualified q = SourceMetadataProbe.resolveQualifiedName("POSTGRESQL", "orders");
        assertEquals("orders", q.table());
        assertEquals(null, q.catalog());
        assertEquals(null, q.schema());
    }

    @Test
    void PG两段按schema表名拆() {
        Qualified q = SourceMetadataProbe.resolveQualifiedName("POSTGRESQL", "public.e2e_src");
        assertEquals("public", q.schema());
        assertEquals("e2e_src", q.table());
        assertEquals(null, q.catalog());
    }

    @Test
    void PG三段按catalog_schema_表拆() {
        Qualified q = SourceMetadataProbe.resolveQualifiedName("POSTGRESQL", "db.public.t");
        assertEquals("db", q.catalog());
        assertEquals("public", q.schema());
        assertEquals("t", q.table());
    }

    @Test
    void MySQL和Doris两段前段是catalog() {
        // MySQL 协议驱动里库落在 TABLE_CAT、TABLE_SCHEM 恒空；前段当 schema 会被驱动忽略
        Qualified my = SourceMetadataProbe.resolveQualifiedName("MYSQL", "shop.orders");
        assertEquals("shop", my.catalog());
        assertEquals(null, my.schema());
        assertEquals("orders", my.table());
        Qualified doris = SourceMetadataProbe.resolveQualifiedName("DORIS", "dw.t");
        assertEquals("dw", doris.catalog());
        assertEquals("t", doris.table());
    }

    @Test
    void MySQL三段与空段和超长层级都400() {
        ApiException three = assertThrows(ApiException.class,
                () -> SourceMetadataProbe.resolveQualifiedName("MYSQL", "a.b.c"));
        assertEquals(400, three.getStatus());
        assertTrue(three.getMessage().contains("表名不合法"));
        for (String bad : new String[]{"a.", ".b", "a..b"}) {
            assertThrows(ApiException.class,
                    () -> SourceMetadataProbe.resolveQualifiedName("POSTGRESQL", bad), bad);
        }
        assertThrows(ApiException.class,
                () -> SourceMetadataProbe.resolveQualifiedName("POSTGRESQL", "a.b.c.d"));
    }

    @Test
    void 限定前缀与schema参数冲突显式400不静默偏向() {
        // 拆解与冲突判定都发生在建连之前：连接都没开就该报错
        SourceMetadataProbe probe = new SourceMetadataProbe(null, new ObjectMapper(), null, null, null);
        ApiException e = assertThrows(ApiException.class,
                () -> probe.columnsOf(PG, "public.t", null, "other", null));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("冲突"), e.getMessage());
    }

    // ------------------------------------------------------------------ ① 存在性探测 → 404

    /** 注入 mock 连接 + mock 元数据服务的探针。 */
    private static SourceMetadataProbe probeWith(Connection conn, SourceMetadataService metadata) {
        return new SourceMetadataProbe(null, new ObjectMapper(), null, null, metadata) {
            @Override
            Connection open(Spec spec) {
                return conn;
            }
        };
    }

    private static Connection mockConn() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbmd = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(dbmd);
        when(dbmd.getDatabaseProductName()).thenReturn("PostgreSQL");
        return conn;
    }

    @Test
    void 查空且表不存在_404_文案与参数透传() throws Exception {
        Connection conn = mockConn();
        SourceMetadataService metadata = mock(SourceMetadataService.class);
        when(metadata.columns(same(conn), isNull(), eq("public"), eq("no_such_tbl")))
                .thenReturn(List.of());
        when(metadata.tableExists(same(conn), isNull(), eq("public"), eq("no_such_tbl")))
                .thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> probeWith(conn, metadata)
                .columnsOf(PG, "no_such_tbl", null, "public", null));
        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
        assertTrue(e.getMessage().contains("no_such_tbl"), e.getMessage());
    }

    @Test
    void 限定名拆解后按段查列并透传存在性探测() throws Exception {
        Connection conn = mockConn();
        SourceMetadataService metadata = mock(SourceMetadataService.class);
        when(metadata.columns(same(conn), isNull(), eq("public"), eq("e2e_src")))
                .thenReturn(List.of());
        when(metadata.tableExists(same(conn), isNull(), eq("public"), eq("e2e_src")))
                .thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> probeWith(conn, metadata)
                .columnsOf(PG, "public.e2e_src", null, null, null));
        assertEquals(404, e.getStatus());
        // 404 文案回显用户原始输入，便于对照排查
        assertTrue(e.getMessage().contains("public.e2e_src"), e.getMessage());
        verify(metadata).columns(same(conn), isNull(), eq("public"), eq("e2e_src"));
    }

    @Test
    void 零列表不误伤_表存在则仍200空列() throws Exception {
        Connection conn = mockConn();
        SourceMetadataService metadata = mock(SourceMetadataService.class);
        when(metadata.columns(same(conn), isNull(), isNull(), eq("empty_tbl"))).thenReturn(List.of());
        when(metadata.tableExists(same(conn), isNull(), isNull(), eq("empty_tbl"))).thenReturn(true);
        Map<String, Object> out = probeWith(conn, metadata)
                .columnsOf(PG, "empty_tbl", null, null, null);
        assertEquals(List.of(), out.get("columns"));
        assertEquals("empty_tbl", out.get("table"), "响应回显表名（无点时与入参一致）");
    }

    @Test
    void 正常有列路径不多一次存在性探测() throws Exception {
        Connection conn = mockConn();
        SourceMetadataService metadata = mock(SourceMetadataService.class);
        when(metadata.columns(same(conn), isNull(), isNull(), eq("orders")))
                .thenReturn(List.of(Map.of("name", "id")));
        Map<String, Object> out = probeWith(conn, metadata)
                .columnsOf(PG, "orders", null, null, null);
        assertEquals(1, ((List<?>) out.get("columns")).size());
        verify(metadata, never()).tableExists(any(), any(), any(), any());
    }

    @Test
    void MySQL限定名按catalog透传_前缀与参数一致则合并() throws Exception {
        Connection conn = mockConn();
        SourceMetadataService metadata = mock(SourceMetadataService.class);
        when(metadata.columns(same(conn), eq("shop"), isNull(), eq("orders")))
                .thenReturn(List.of(Map.of("name", "id")));
        probeWith(conn, metadata).columnsOf(MYSQL, "shop.orders", null, null, null);
        verify(metadata).columns(same(conn), eq("shop"), isNull(), eq("orders"));
        // 显式 catalog 与前缀一致：不冲突，照常合并
        probeWith(conn, metadata).columnsOf(MYSQL, "shop.orders", "shop", null, null);
    }

    // ------------------------------------------------------------------ tableExists 过滤口径

    @Test
    void tableExists按原名精确过滤_通配近邻不算存在() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbmd = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(dbmd);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        // pattern 里 `_` 是单字通配：e2e_srcx 是近邻假命中，必须丢弃；大小写不同但同名的算存在
        when(rs.getString("TABLE_NAME")).thenReturn("e2e_srcx", "E2E_SRC");
        when(dbmd.getTables(isNull(), isNull(), eq("e2e_src"),
                Mockito.eq(new String[]{"TABLE", "VIEW"}))).thenReturn(rs);
        SourceMetadataService svc = new SourceMetadataService(new ObjectMapper());
        assertTrue(svc.tableExists(conn, null, null, "e2e_src"));
    }

    @Test
    void tableExists全近邻_返回false() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbmd = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(dbmd);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("TABLE_NAME")).thenReturn("e2e_srcx");
        when(dbmd.getTables(isNull(), isNull(), eq("e2e_src"),
                Mockito.<String[]>any())).thenReturn(rs);
        SourceMetadataService svc = new SourceMetadataService(new ObjectMapper());
        assertFalse(svc.tableExists(conn, null, null, "e2e_src"));
    }

    @Test
    void Kafka路径不做限定名拆解_topic含点原样传递() {
        // Kafka 的 topic 名合法含点（正则 [A-Za-z0-9._-]），拆解逻辑不得插手；
        // 这里用缺 bootstrap 的显式 400 证明名称未先被限定名规则拦下
        SourceMetadataProbe probe = new SourceMetadataProbe(null, new ObjectMapper(), null, null, null);
        ApiException e = assertThrows(ApiException.class, () -> probe.columnsOf(
                new Spec("KAFKA", null, null, "", Map.of()), "a.b", null, null, 10));
        assertEquals("数据源缺少 bootstrap.servers", e.getMessage());
    }
}
