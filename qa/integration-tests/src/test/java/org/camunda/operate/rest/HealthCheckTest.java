/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.operate.rest;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.camunda.operate.Application;
import org.camunda.operate.rest.HealthCheckTest.AddManagementPropertiesInitializer;
import org.camunda.operate.util.apps.nobeans.TestApplicationWithNoBeans;
import org.camunda.operate.webapp.es.reader.Probes;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    classes = { TestApplicationWithNoBeans.class, Probes.class })
@ContextConfiguration(initializers = AddManagementPropertiesInitializer.class)
@RunWith(SpringRunner.class)
public class HealthCheckTest {

  @Autowired
  private WebApplicationContext context;

  @MockBean
  private Probes probes;

  private MockMvc mockMvc;

  @Before
  public void setupMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  public void testReady() throws Exception {
    given(probes.getHealth(anyBoolean())).willReturn(Health.up().build());
    mockMvc
        .perform(get("/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"));
    verify(probes, times(1)).getHealth(anyBoolean());
  }

  @Test
  public void testHealth() throws Exception {
    mockMvc
        .perform(get("/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"));
    verifyNoInteractions(probes);
  }

  @Test
  public void testReadyStateIsNotOK() throws Exception {
    given(probes.getHealth(anyBoolean())).willReturn(Health.down().build());
    mockMvc
        .perform(get("/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"));

    mockMvc
        .perform(get("/health/readiness"))
        .andExpect(status().isServiceUnavailable());
    verify(probes, times(1)).getHealth(anyBoolean());
  }

  @Test
  public void testMetrics() throws Exception {
    mockMvc
        .perform(get("/metrics"))
        .andExpect(status().isOk())
        .andExpect(content().string(StringContains.containsString(
            "# HELP jvm_memory_used_bytes The amount of used memory\n" +
            "# TYPE jvm_memory_used_bytes gauge")));
  }

  public static class AddManagementPropertiesInitializer implements
      ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
      final Map<String, Object> map = Application.getManagementProperties();
      final List<String> properties = new ArrayList<>();
      map.entrySet().forEach(e -> {
        //not clear how to connect mockMvc to management port
        if (!e.getKey().contains("port")) {
          properties.add(e.getKey() + "=" + String.valueOf(e.getValue()));
        }
      });
      TestPropertyValues.of(properties).applyTo(applicationContext.getEnvironment());
    }
  }

}