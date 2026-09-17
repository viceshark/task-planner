package ru.planner.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Set;

/**
 * Тема оформления хранится в cookie, чтобы страница сразу рендерилась в нужной теме
 * (без «мигания» при переключении на клиенте) и работала ещё до входа в систему.
 */
@ControllerAdvice(basePackages = "ru.planner.web")
public class ThemeAdvice {

    public static final String COOKIE = "theme";
    public static final String DEFAULT_THEME = "retrowave";
    public static final Set<String> THEMES = Set.of("retrowave", "90s");

    @ModelAttribute("theme")
    public String theme(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return DEFAULT_THEME;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && THEMES.contains(c.getValue())) {
                return c.getValue();
            }
        }
        return DEFAULT_THEME;
    }
}
