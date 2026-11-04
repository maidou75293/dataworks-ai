package com.lab.dataworks.usage;

import com.lab.dataworks.auth.RequireAtom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * TC7-G4：用量计量出口（docs/测试-TC7-G4.md §五 验收契约；只计量，不做计费与账单）。
 *
 * <p>权限面按契约 §五.6「并入项目读面」：{@code project:read}——DEVELOPER 默认持有
 * （V55 角色种子），守卫主体不做任何 RBAC 装配即可见本租户归集；未登录由
 * {@code AuthInterceptor} 统一 401（契约 §五.1 / 守卫 R06）。</p>
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageMeteringService metering;

    public UsageController(UsageMeteringService metering) {
        this.metering = metering;
    }

    /**
     * {@code GET /api/usage/summary?groupBy=project|tenant&from=YYYY-MM-DD&to=YYYY-MM-DD}
     *
     * <p>窗口为闭开区间 {@code [from, to)}；{@code from == to} 是合法空窗（各计数 0，
     * 守卫 R04 用它验参数真生效）；{@code from > to} 或未知 groupBy → 400，message 可读。</p>
     */
    @GetMapping("/summary")
    @RequireAtom("project:read")
    public List<Map<String, Object>> summary(@RequestParam(required = false) String groupBy,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        String gb = UsageMeteringService.checkGroupBy(groupBy);
        LocalDate f = UsageMeteringService.checkDay(from, "from");
        LocalDate t = UsageMeteringService.checkDay(to, "to");
        UsageMeteringService.checkWindow(f, t);
        return metering.summary(gb, f, t);
    }
}
