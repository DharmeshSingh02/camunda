/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.tasklist.webapp.security.se;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.tasklist.es.RetryElasticsearchClient;
import io.camunda.tasklist.management.SearchEngineHealthIndicator;
import io.camunda.tasklist.property.TasklistProperties;
import io.camunda.tasklist.qa.util.TestElasticsearchSchemaManager;
import io.camunda.tasklist.schema.indices.UserIndex;
import io.camunda.tasklist.util.*;
import io.camunda.tasklist.webapp.security.WebSecurityConfig;
import io.camunda.tasklist.webapp.security.oauth.OAuth2WebConfigurer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * This test tests that: 1. If we configure custom username and password, this user is added to
 * Elasticsearch 2. If we adjust firstname and lastname in Elasticsearch, this values are returned
 * by UserDetailsService
 */
@SpringBootTest(
    classes = {
      TestElasticsearchSchemaManager.class,
      TestApplication.class,
      SearchEngineHealthIndicator.class,
      WebSecurityConfig.class,
      OAuth2WebConfigurer.class,
      RetryElasticsearchClient.class,
    },
    properties = {
      TasklistProperties.PREFIX + ".importer.startLoadingDataOnStartup = false",
      TasklistProperties.PREFIX + ".archiver.rolloverEnabled = false",
      TasklistProperties.PREFIX + ".userId = user1",
      TasklistProperties.PREFIX + ".password = psw1",
      "graphql.servlet.websocket.enabled=false"
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SearchEngineUserDetailsServiceIT extends TasklistIntegrationTest {

  private static final String TEST_USERNAME = "user1";
  private static final String TEST_PASSWORD = "psw1";
  private static final String TEST_FIRSTNAME = "Quentin";
  private static final String TEST_LASTNAME = "Tarantino ";

  @Rule
  public TasklistTestRule tasklistTestRule =
      TasklistZeebeIntegrationTest.IS_ELASTIC
          ? new ElasticsearchTestRule()
          : new OpenSearchTestRule();

  @Autowired private SearchEngineUserDetailsService userDetailsService;

  @Autowired private UserIndex userIndex;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private NoSqlHelper noSqlHelper;

  @Before
  public void setUp() {
    tasklistTestRule.refreshTasklistIndices();
  }

  @After
  public void deleteUser() throws IOException {
    deleteById(TEST_USERNAME);
  }

  @Test
  public void testCustomUserIsAdded() {
    // when
    userDetailsService.initializeUsers();
    tasklistTestRule.refreshTasklistIndices();

    // and
    updateUserRealName();

    // then
    final UserDetails userDetails = userDetailsService.loadUserByUsername(TEST_USERNAME);
    assertThat(userDetails).isInstanceOf(User.class);
    final User testUser = (User) userDetails;
    assertThat(testUser.getUsername()).isEqualTo(TEST_USERNAME);
    assertThat(passwordEncoder.matches(TEST_PASSWORD, testUser.getPassword())).isTrue();
    assertThat(testUser.getUserId()).isEqualTo("user1");
    assertThat(testUser.getDisplayName()).isEqualTo(TEST_FIRSTNAME + " " + TEST_LASTNAME);
  }

  private void updateUserRealName() {
    try {
      final Map<String, Object> jsonMap = new HashMap<>();
      jsonMap.put(UserIndex.DISPLAY_NAME, String.format("%s %s", TEST_FIRSTNAME, TEST_LASTNAME));
      noSqlHelper.update(userIndex.getFullQualifiedName(), TEST_USERNAME, jsonMap);
      tasklistTestRule.refreshTasklistIndices();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void deleteById(String id) throws IOException {
    noSqlHelper.delete(userIndex.getFullQualifiedName(), id);
  }
}
