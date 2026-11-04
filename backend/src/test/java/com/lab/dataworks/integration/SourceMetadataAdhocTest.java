package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.integration.SourceMetadataProbe.AdhocRequest;
import com.lab.dataworks.integration.SourceMetadataProbe.Spec;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-DW-16 回归：未保存表单的临时预览参数装配（纯逻辑，不连库）。
 *
 * <p>验收点：类型缺失/非法显式 400；表单密码原样透传（不落库）；
 * 编辑复用已存密码必须地址一致（防把库密码发给恶意 server）；
 * 请求体容忍多余表单字段；不支持类型/通配表名/缺 bootstrap 都显式报错不静默。</p>
 */
class SourceMetadataAdhocTest {

    private static SourceMetadataProbe probe(JdbcTemplate jdbc, SecretCipher cipher) {
        return new SourceMetadataProbe(jdbc, new ObjectMapper(), cipher, null, null);
    }

    /** decrypt 对无前缀历史明文原样返回，测试里不需要真实密钥材料。 */
    private static SecretCipher cipher() {
        return new SecretCipher("REQ-DW-16-test-key");
    }

    private static AdhocRequest req(String type, String url, String user, String pwd,
                                    Map<String, Object> config, Long id) {
        return new AdhocRequest(type, url, user, pwd, config, id, null, null, null, null);
    }

    @Test
    void 缺类型显式400() {
        ApiException e = assertThrows(ApiException.class,
                () -> probe(null, null).specOfAdhoc(req(null, "jdbc:mysql://h/db", "u", "p", Map.of(), null)));
        assertEquals(400, e.getStatus());
        assertEquals("缺少数据源类型，请先在表单选择类型", e.getMessage());
    }

    @Test
    void 表单密码原样透传不落库() {
        Spec spec = probe(null, null)
                .specOfAdhoc(req("mysql", "jdbc:mysql://h/db", "u", "p@ss", Map.of(), null));
        assertEquals("MYSQL", spec.type());
        assertEquals("p@ss", spec.password(), "明文密码只在请求内存活，原样使用");
    }

    @Test
    void 复用已存密码时地址必须一致() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(Map.of(
                "ds_type", "MYSQL", "jdbc_url", "jdbc:mysql://real:3306/db",
                "username", "u", "password", "enc", "config", "{}")));
        ApiException e = assertThrows(ApiException.class, () -> probe(jdbc, cipher()).specOfAdhoc(
                req("MYSQL", "jdbc:mysql://evil:3306/db", "u", "", Map.of(), 7L)));
        assertEquals(400, e.getStatus());
        assertEquals("复用已保存的密码时不能修改 JDBC 地址，请重新输入密码", e.getMessage());
    }

    @Test
    void 地址未变才复用已存密码() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(Map.of(
                "ds_type", "MYSQL", "jdbc_url", " jdbc:mysql://real:3306/db ",
                "username", "u", "password", "", "config", "{}")));
        // 地址只差首尾空白与大小写：允许复用
        Spec spec = probe(jdbc, cipher()).specOfAdhoc(
                req("MYSQL", "jdbc:mysql://REAL:3306/db", "u", "", Map.of(), 7L));
        assertEquals("", spec.password(), "空密文解密为空串");
    }

    @Test
    void 请求体容忍多余表单字段() throws Exception {
        AdhocRequest r = new ObjectMapper().readValue(
                "{\"type\":\"KAFKA\",\"name\":\"n\",\"env\":\"DEV\",\"config\":{}}",
                AdhocRequest.class);
        assertEquals("KAFKA", r.type(), "name/env 等提交表单带来的字段忽略，不 400");
    }

    @Test
    void 不支持类型显式拒绝预览() {
        ApiException e = assertThrows(ApiException.class, () -> probe(null, null)
                .tablesOf(new Spec("S3FILE", null, null, "", Map.of())));
        assertEquals(400, e.getStatus());
        assertTrue400(e);
    }

    @Test
    void 表名含通配符或为空都400() {
        ApiException wildcard = assertThrows(ApiException.class, () -> probe(null, null)
                .columnsOf(new Spec("MYSQL", "jdbc:mysql://h/db", "u", "p", Map.of()), "a%b", null, null, null));
        assertEquals(400, wildcard.getStatus());
        ApiException empty = assertThrows(ApiException.class, () -> probe(null, null)
                .columnsOf(new Spec("MYSQL", "jdbc:mysql://h/db", "u", "p", Map.of()), " ", null, null, null));
        assertEquals("表名不能为空", empty.getMessage());
    }

    @Test
    void Kafka缺bootstrap显式400() {
        ApiException e = assertThrows(ApiException.class, () -> probe(null, null)
                .tablesOf(new Spec("KAFKA", null, null, "", Map.of())));
        assertEquals("数据源缺少 bootstrap.servers", e.getMessage());
    }

    private static void assertTrue400(ApiException e) {
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("暂不支持结构预览"), e.getMessage());
    }
}
