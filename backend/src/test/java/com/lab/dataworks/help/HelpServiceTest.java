package com.lab.dataworks.help;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帮助中心内容与接口逻辑（REQ-HELP-NAV-1）：树结构、章节锚点、正文、path 防穿越。 */
class HelpServiceTest {

    private final HelpService svc = new HelpService();

    @Test
    void treeHasBothRequiredDocsOrdered() {
        List<Map<String, Object>> docs = castDocs(svc.tree());
        assertTrue(docs.size() >= 2, "至少两篇必读：" + docs.size());
        assertEquals("k8s-结构介绍.md", docs.get(0).get("path"));
        assertEquals("运维手册.md", docs.get(1).get("path"));
        for (Map<String, Object> d : docs) {
            assertFalse(((String) d.get("manualDate")).isBlank(), "版本角标日期不能空: " + d.get("path"));
            assertTrue(((List<?>) d.get("sections")).size() >= 5, "章节导航太稀: " + d.get("path"));
        }
    }

    @Test
    void docReturnsMarkdownWithMatchingAnchors() {
        HelpService.Doc d = svc.doc("运维手册.md");
        for (HelpService.Section s : d.sections()) {
            assertTrue(d.markdown().contains("## " + s.title()), "锚点标题对不上正文: " + s.title());
        }
        // 围栏代码块里的注释行不能被算成章节（bash 块中没有任何 ## 标题）
        assertTrue(d.sections().stream().anyMatch(s -> s.title().contains("Pod 起不来")));
        assertTrue(d.sections().stream().anyMatch(s -> s.title().contains("配额")));
    }

    @Test
    void rejectsTraversalAndUnknownPath() {
        assertThrows(ApiException.class, () -> svc.doc("../application.yml"));
        assertThrows(ApiException.class, () -> svc.doc("不存在.md"));
        assertThrows(ApiException.class, () -> svc.doc(""));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castDocs(Map<String, Object> tree) {
        return (List<Map<String, Object>>) tree.get("docs");
    }
}
