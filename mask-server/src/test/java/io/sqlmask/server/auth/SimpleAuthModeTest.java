package io.sqlmask.server.auth;

import io.sqlmask.auth.Passwords;
import io.sqlmask.auth.SimpleUser;
import io.sqlmask.auth.SimpleUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simple auth mode end to end: seeded local users log in against PBKDF2
 * hashes and receive bearer tokens the gate accepts; wrong credentials get
 * the uniform 401; the mode endpoint tells the console what to render.
 */
@SpringBootTest(properties = {
    "mask.auth.mode=simple",
    "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
class SimpleAuthModeTest {

  @Autowired MockMvc mockMvc;

  @TestConfiguration
  static class SeededUsers {
    @Bean
    SimpleUserStore simpleUserStore() {
      io.sqlmask.auth.InMemorySimpleUserStore store = new io.sqlmask.auth.InMemorySimpleUserStore();
      store.create(new SimpleUser("amy", "Amy", io.sqlmask.auth.Role.ADMIN, List.of("admins"),
          Passwords.hash("amy-secret"), true));
      store.create(new SimpleUser("bob", "Bob", io.sqlmask.auth.Role.USER, List.of(),
          Passwords.hash("bob-secret"), true));
      return store;
    }
  }

  @Test
  void modeEndpointReportsSimple() throws Exception {
    mockMvc.perform(get("/api/auth/mode"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("simple"))
        .andExpect(jsonPath("$.login").value(true))
        .andExpect(jsonPath("$.ldap").value(false));
  }

  @Test
  void loginIssuesATokenThatPassesTheGate() throws Exception {
    String token = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"bob\",\"password\":\"bob-secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.username").value("bob"))
            .andExpect(jsonPath("$.user.role").value("USER"))
            .andReturn().getResponse().getContentAsString(), "$.token");

    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username", is("bob")));

    // a USER token may read instances but not write them (servletPath drives
    // the gate's prefix matching under MockMvc)
    mockMvc.perform(get("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());

    // the admin token may write
    String adminToken = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"amy\",\"password\":\"amy-secret\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.token");
    mockMvc.perform(get("/api/auth/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void wrongPasswordIsAUniform401() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"bob\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }
}
