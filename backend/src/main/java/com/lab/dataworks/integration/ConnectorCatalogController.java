package com.lab.dataworks.integration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/integration")
public class ConnectorCatalogController {

    private final ConnectorCatalog catalog;

    public ConnectorCatalogController(ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/connectors")
    public Map<String, Object> connectors() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", catalog.version());
        out.put("connectors", catalog.list());
        return out;
    }
}
