package com.jingzhou.webterminal.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/console")
    public String console() {
        return "redirect:/console.html";
    }
}
