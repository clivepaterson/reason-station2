package org.reasonstation.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "app.scalar-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ScalarUiController {

    @GetMapping("/scalar-ui")
    public String scalarUI() {
        return "redirect:/scalar/index.html";
    }
}
