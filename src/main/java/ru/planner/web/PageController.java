package ru.planner.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String calendar(Model model) {
        model.addAttribute("page", "calendar");
        return "calendar";
    }

    @GetMapping("/analytics")
    public String analytics(Model model) {
        model.addAttribute("page", "analytics");
        return "analytics";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
