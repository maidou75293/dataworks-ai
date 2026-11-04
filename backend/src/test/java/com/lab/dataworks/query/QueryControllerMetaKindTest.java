package com.lab.dataworks.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MT-P1-2：SHOW / DESC / EXPLAIN 必须识别成元数据语句。
 *
 * <p>{@code metaKind} 同时决定两件事：非默认租户按库收口（SHOW TABLES / DESC 必须带库名），
 * 以及不参与数据分级判定（否则默认 ENFORCE 下「SHOW TABLES FROM x」会被当成查表 x 而 403）。
 * 这里锁住分类口径，防止后续改动把放行条件又收回去。</p>
 */
class QueryControllerMetaKindTest {

    @Test
    void showDatabasesIsMetadata() {
        assertEquals("DATABASES", QueryController.metaKind("show databases"));
        assertEquals("DATABASES", QueryController.metaKind("SHOW DATABASES;"));
        assertEquals("DATABASES", QueryController.metaKind("  show schemas "));
    }

    @Test
    void showTablesIsMetadata() {
        assertEquals("TABLES", QueryController.metaKind("show tables from demo_retail"));
        assertEquals("TABLES", QueryController.metaKind("SHOW TABLES IN `demo_retail`"));
    }

    @Test
    void descIsMetadata() {
        assertEquals("DESC", QueryController.metaKind("desc demo_retail.ods_user"));
        assertEquals("DESC", QueryController.metaKind("DESCRIBE `demo_retail`.`ods_user`"));
    }

    @Test
    void explainIsMetadataButExplainAnalyzeIsNot() {
        assertEquals("EXPLAIN", QueryController.metaKind("explain select * from demo_retail.ods_user"));
        assertEquals("EXPLAIN", QueryController.metaKind("EXPLAIN FORMAT=json select 1"));
        // EXPLAIN ANALYZE 会真正执行查询，仍按普通 SQL 过只读校验与分级网关
        assertNull(QueryController.metaKind("explain analyze select * from demo_retail.ods_user"));
        // 多空格/换行不能绕过 EXPLAIN ANALYZE 的识别
        assertNull(QueryController.metaKind("explain  analyze select 1"));
        assertNull(QueryController.metaKind("explain\nanalyze select 1"));
    }

    @Test
    void normalQueriesAreNotMetadata() {
        assertNull(QueryController.metaKind("select * from demo_retail.ods_user"));
        assertNull(QueryController.metaKind("with t as (select 1) select * from t"));
        assertNull(QueryController.metaKind("update demo_retail.ods_user set name='x' where 1=0"));
        assertNull(QueryController.metaKind(""));
        assertNull(QueryController.metaKind(null));
    }
}
