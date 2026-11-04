package com.lab.dataworks.help;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 帮助中心只读接口（REQ-HELP-NAV-1）：目录树 + 正文。
 * GET 无权限注解 = 「登录 + 租户收口」的正常面；内容是全平台手册，不含租户数据。
 */
@RestController
@RequestMapping("/api/help")
public class HelpController {

    private final HelpService helpService;

    public HelpController(HelpService helpService) {
        this.helpService = helpService;
    }

    @GetMapping("/tree")
    public Map<String, Object> tree() {
        return helpService.tree();
    }

    @GetMapping("/doc")
    public Map<String, Object> doc(@RequestParam("path") String path) {
        HelpService.Doc d = helpService.doc(path);
        return Map.of(
                "path", d.path(),
                "title", d.title(),
                "manualDate", d.manualDate(),
                "sources", d.sources(),
                "markdown", d.markdown(),
                "sections", d.sections().stream()
                        .map(s -> Map.of("id", s.id(), "title", s.title())).toList());
    }
}
