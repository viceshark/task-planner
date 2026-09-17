package ru.planner;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pagesRedirectAnonymousToLogin() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "http://localhost/login"));
        mvc.perform(get("/analytics"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void apiRespondsUnauthorizedInsteadOfRedirect() throws Exception {
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks?from=2026-01-01&to=2026-01-31")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageAndStaticResourcesArePublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/js/calendar.js")).andExpect(status().isOk());
        mvc.perform(get("/webjars/chart.js/4.4.1/dist/chart.umd.js")).andExpect(status().isOk());
    }

    @Test
    void themeComesFromCookieWithSafeDefault() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(content().string(containsString("data-theme=\"retrowave\"")));
        mvc.perform(get("/login").cookie(new Cookie("theme", "90s")))
            .andExpect(content().string(containsString("data-theme=\"90s\"")));
        mvc.perform(get("/login").cookie(new Cookie("theme", "evil\"><script>")))
            .andExpect(content().string(containsString("data-theme=\"retrowave\"")));
    }

    @Test
    void formLoginWithConfiguredCredentials() throws Exception {
        mvc.perform(formLogin().user("tester").password("secret")).andExpect(authenticated());
        mvc.perform(formLogin().user("tester").password("wrong")).andExpect(unauthenticated());
    }
}
