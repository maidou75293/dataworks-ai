package com.lab.dataworks.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** V101：Gravitino REST 契约、错误映射、重试策略。 */
class GravitinoClientTest {

    private MockRestServiceServer server;
    private GravitinoClient client;

    @BeforeEach
    void setUp() {
        RestTemplate http = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(1))
                .setReadTimeout(Duration.ofSeconds(1))
                .build();
        server = MockRestServiceServer.bindTo(http).build();
        client = new GravitinoClient(http, new ObjectMapper(),
                "http://gravitino:8090", "dataworks", 1);
    }

    @Test
    void readsUseGravitinoMediaType() {
        // Gravitino 发 application/json 会回 406；Accept 必须是 vnd.gravitino.v1+json
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("Accept", GravitinoClient.ACCEPT))
                .andRespond(withSuccess("{\"code\":0,\"identifiers\":[]}",
                        MediaType.APPLICATION_JSON));
        assertTrue(client.listCatalogs().isEmpty());
        server.verify();
    }

    @Test
    void createSendsJdbcDorisContract() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("default_doris_kb"))
                .andExpect(jsonPath("$.type").value("relational"))
                .andExpect(jsonPath("$.provider").value("jdbc-doris"))
                .andExpect(jsonPath("$.properties['jdbc-url']").value("jdbc:mysql://doris:9030/"))
                .andExpect(jsonPath("$.properties['jdbc-user']").value("dataworks"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        client.createCatalog("default_doris_kb", "jdbc-doris",
                Map.of("jdbc-url", "jdbc:mysql://doris:9030/",
                        "jdbc-user", "dataworks",
                        "jdbc-password", "secret"), "test");

        server.verify();
    }

    @Test
    void alterSendsSetPropertyUpdates() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/default_doris_kb"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.updates[0]['@type']").value("setProperty"))
                .andExpect(jsonPath("$.updates[0].property").value("jdbc-url"))
                .andExpect(jsonPath("$.updates[0].value").value("jdbc:mysql://new:9030/"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        client.alterCatalog("default_doris_kb",
                Map.of("jdbc-url", "jdbc:mysql://new:9030/"));

        server.verify();
    }

    @Test
    void renameUsesAlterApi() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/old_name"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.updates[0]['@type']").value("rename"))
                .andExpect(jsonPath("$.updates[0].newName").value("default_doris_kb"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        client.renameCatalog("old_name", "default_doris_kb");

        server.verify();
    }

    @Test
    void missingCatalogIsNotAnErrorForExistenceCheck() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/nope"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":1003,\"type\":\"NotFoundException\",\"message\":\"Catalog nope not found\"}"));

        assertFalse(client.catalogExists("nope"));
        server.verify();
    }

    @Test
    void deleteIsForceSoftDeleteAndIdempotent() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/default_doris_kb?force=true"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/gone?force=true"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":1003,\"message\":\"not found\"}"));

        client.deleteCatalog("default_doris_kb");
        client.deleteCatalog("gone");

        server.verify();
    }

    @Test
    void serverErrorIsRetriedOnce() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andRespond(withSuccess("{\"code\":0,\"identifiers\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), client.listCatalogs());
        server.verify();
    }

    @Test
    void badRequestIsNotRetriedAndMapsToDiagnosableCode() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":1001,\"type\":\"IllegalArgumentException\",\"message\":\"bad provider\"}"));

        GravitinoException e = assertThrows(GravitinoException.class,
                () -> client.createCatalog("x", "bad", Map.of(), ""));
        assertEquals(400, e.httpStatus(), e.getMessage());
        assertEquals(GravitinoException.BAD_REQUEST, e.code());
        assertTrue(e.getMessage().contains("bad provider"), e.getMessage());
        server.verify();
    }

    @Test
    void listsSchemasAndTablesFromIdentifiers() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/cat1/schemas"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"identifiers\":["
                        + "{\"name\":\"kb_dev\"},{\"name\":\"mysql\"},"
                        + "{\"name\":\"kb_dev\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/cat1/schemas/kb_dev/tables"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"identifiers\":["
                        + "{\"namespace\":[\"kb_dev\"],\"name\":\"kb_chunk\"},"
                        + "\"t_plain\"]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of("kb_dev", "mysql"), client.listSchemas("cat1"));
        assertEquals(List.of("kb_chunk", "t_plain"), client.listTables("cat1", "kb_dev"));
        server.verify();
    }

    @Test
    void readsTableColumnsFromWrappedResponse() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/cat1"
                        + "/schemas/kb_dev/tables/kb_chunk"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"table\":{\"name\":\"kb_chunk\","
                        + "\"columns\":[{\"name\":\"chunk_id\",\"type\":\"long\","
                        + "\"nullable\":false,\"comment\":\"主键\"}]}}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> table = client.getTable("cat1", "kb_dev", "kb_chunk");

        assertEquals("kb_chunk", table.get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
        assertEquals(1, columns.size());
        assertEquals("chunk_id", columns.get(0).get("name"));
        assertEquals("long", columns.get(0).get("type"));
        assertEquals(false, columns.get(0).get("nullable"));
        server.verify();
    }

    @Test
    void missingTableReturnsNull() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs/cat1"
                        + "/schemas/kb_dev/tables/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":1003,\"message\":\"table not found\"}"));

        assertNull(client.getTable("cat1", "kb_dev", "gone"));
        server.verify();
    }

    @Test
    void forbiddenMapsToForbiddenCode() {
        server.expect(requestTo("http://gravitino:8090/api/metalakes/dataworks/catalogs"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":1002,\"message\":\"denied\"}"));

        GravitinoException e = assertThrows(GravitinoException.class, client::listCatalogs);
        assertEquals(403, e.httpStatus(), e.getMessage());
        assertEquals(GravitinoException.FORBIDDEN, e.code());
    }
}
