package org.reasonstation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ScalarUiController {

    @GetMapping("/scalar-ui")
    public String scalarUI() {
        return "redirect:/scalar/index.html";
    }
}
