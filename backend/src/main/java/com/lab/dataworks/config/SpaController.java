package com.lab.dataworks.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React single-page app from the backend jar.
 * API paths stay under /api and are handled by the REST controllers.
 */
@Controller
public class SpaController {

    @GetMapping({
            "/", "/login", "/dev", "/dev/**", "/query", "/ops", "/quality",
            "/admin", "/map", "/service", "/security"
    })
    public String index() {
        return "forward:/index.html";
    }
}
