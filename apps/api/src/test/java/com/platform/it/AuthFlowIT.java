package com.platform.it;

import com.platform.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registerVerifyLoginRefreshRotationAndReuseDetection() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.com";
        String body = "{\"fullName\":\"Flow\",\"email\":\"" + email + "\",\"password\":\"Sup3r-Secret!\"}";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        // Same response for an existing email (no enumeration)
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        String login = "{\"email\":\"" + email + "\",\"password\":\"Sup3r-Secret!\"}";
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE email = ?", email);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        MvcResult ok = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists()).andReturn();
        Cookie first = ok.getResponse().getCookie("vd_rt");
        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isForbidden()); // missing X-Requested-With
        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andReturn();
        Cookie second = rotated.getResponse().getCookie("vd_rt");
        // Reusing the old token revokes the whole family
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isUnauthorized());
        String access = com.platform.shared.Json.read(ok.getResponse().getContentAsString()).path("accessToken").asString();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access + "x")).andExpect(status().isUnauthorized());
    }
}
