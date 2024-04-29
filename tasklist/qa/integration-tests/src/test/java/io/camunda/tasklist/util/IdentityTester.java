/*
 * Copyright Camunda Services GmbH
 *
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING, OR DISTRIBUTING THE SOFTWARE (“USE”), YOU INDICATE YOUR ACCEPTANCE TO AND ARE ENTERING INTO A CONTRACT WITH, THE LICENSOR ON THE TERMS SET OUT IN THIS AGREEMENT. IF YOU DO NOT AGREE TO THESE TERMS, YOU MUST NOT USE THE SOFTWARE. IF YOU ARE RECEIVING THE SOFTWARE ON BEHALF OF A LEGAL ENTITY, YOU REPRESENT AND WARRANT THAT YOU HAVE THE ACTUAL AUTHORITY TO AGREE TO THE TERMS AND CONDITIONS OF THIS AGREEMENT ON BEHALF OF SUCH ENTITY.
 * “Licensee” means you, an individual, or the entity on whose behalf you receive the Software.
 *
 * Permission is hereby granted, free of charge, to the Licensee obtaining a copy of this Software and associated documentation files to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject in each case to the following conditions:
 * Condition 1: If the Licensee distributes the Software or any derivative works of the Software, the Licensee must attach this Agreement.
 * Condition 2: Without limiting other conditions in this Agreement, the grant of rights is solely for non-production use as defined below.
 * "Non-production use" means any use of the Software that is not directly related to creating products, services, or systems that generate revenue or other direct or indirect economic benefits.  Examples of permitted non-production use include personal use, educational use, research, and development. Examples of prohibited production use include, without limitation, use for commercial, for-profit, or publicly accessible systems or use for commercial or revenue-generating purposes.
 *
 * If the Licensee is in breach of the Conditions, this Agreement, including the rights granted under it, will automatically terminate with immediate effect.
 *
 * SUBJECT AS SET OUT BELOW, THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * NOTHING IN THIS AGREEMENT EXCLUDES OR RESTRICTS A PARTY’S LIABILITY FOR (A) DEATH OR PERSONAL INJURY CAUSED BY THAT PARTY’S NEGLIGENCE, (B) FRAUD, OR (C) ANY OTHER LIABILITY TO THE EXTENT THAT IT CANNOT BE LAWFULLY EXCLUDED OR RESTRICTED.
 */
package io.camunda.tasklist.util;

import static io.camunda.tasklist.Application.SPRING_THYMELEAF_PREFIX_KEY;
import static io.camunda.tasklist.Application.SPRING_THYMELEAF_PREFIX_VALUE;
import static io.camunda.tasklist.qa.util.TestContainerUtil.KEYCLOAK_PASSWORD;
import static io.camunda.tasklist.qa.util.TestContainerUtil.KEYCLOAK_PASSWORD_2;
import static io.camunda.tasklist.qa.util.TestContainerUtil.KEYCLOAK_USERNAME;
import static io.camunda.tasklist.qa.util.TestContainerUtil.KEYCLOAK_USERNAME_2;
import static io.camunda.tasklist.webapp.security.TasklistURIs.COOKIE_JSESSIONID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.tasklist.property.TasklistProperties;
import io.camunda.tasklist.qa.util.TestContainerUtil;
import io.camunda.tasklist.qa.util.TestContext;
import io.camunda.tasklist.webapp.security.TasklistURIs;
import io.camunda.tasklist.webapp.security.oauth.IdentityJwt2AuthenticationTokenConverter;
import io.camunda.zeebe.client.impl.util.Environment;
import java.util.Collections;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

public abstract class IdentityTester extends SessionlessTasklistZeebeIntegrationTest {
  public static TestContext testContext;
  protected static final String USER = KEYCLOAK_USERNAME;
  protected static final String USER_2 = KEYCLOAK_USERNAME_2;
  private static final String REALM = "camunda-platform";
  private static final String CONTEXT_PATH = "/auth";
  private static final Map<String, String> USERS_STORE =
      Map.of(USER, KEYCLOAK_PASSWORD, USER_2, KEYCLOAK_PASSWORD_2);
  private static JwtDecoder jwtDecoder;
  @Autowired private static TestContainerUtil testContainerUtil;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private IdentityJwt2AuthenticationTokenConverter jwtAuthenticationConverter;

  protected static void beforeClass(boolean multiTenancyEnabled) {

    testContainerUtil = new TestContainerUtil();
    testContext = new TestContext();
    testContainerUtil.startIdentity(
        testContext,
        ContainerVersionsUtil.readProperty(
            ContainerVersionsUtil.IDENTITY_CURRENTVERSION_DOCKER_PROPERTY_NAME),
        multiTenancyEnabled);
    jwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(
                testContext.getExternalKeycloakBaseUrl()
                    + "/auth/realms/camunda-platform/protocol/openid-connect/certs")
            .build();
    Environment.system().put("ZEEBE_CLIENT_ID", "zeebe");
    Environment.system().put("ZEEBE_CLIENT_SECRET", "zecret");
    Environment.system().put("ZEEBE_TOKEN_AUDIENCE", "zeebe-api");
    Environment.system()
        .put(
            "ZEEBE_AUTHORIZATION_SERVER_URL",
            testContext.getExternalKeycloakBaseUrl()
                + "/auth/realms/camunda-platform/protocol/openid-connect/token");
  }

  @BeforeEach
  public void before() {
    super.before();
    tester =
        beanFactory
            .getBean(TasklistTester.class, zeebeClient, databaseTestExtension, jwtDecoder)
            .withAuthenticationToken(generateCamundaIdentityToken());
  }

  protected static void registerProperties(
      DynamicPropertyRegistry registry, boolean multiTenancyEnabled) {
    registry.add(
        "camunda.tasklist.identity.baseUrl", () -> testContext.getExternalIdentityBaseUrl());
    registry.add("camunda.tasklist.identity.resourcePermissionsEnabled", () -> true);
    registry.add(
        "camunda.tasklist.identity.issuerBackendUrl",
        () -> testContext.getExternalKeycloakBaseUrl() + "/auth/realms/camunda-platform");
    registry.add(
        "camunda.tasklist.identity.issuerUrl",
        () -> testContext.getExternalKeycloakBaseUrl() + "/auth/realms/camunda-platform");
    registry.add("camunda.tasklist.identity.clientId", () -> "tasklist");
    registry.add("camunda.tasklist.identity.clientSecret", () -> "the-cake-is-alive");
    registry.add("camunda.tasklist.identity.audience", () -> "tasklist-api");
    registry.add("server.servlet.session.cookie.name", () -> COOKIE_JSESSIONID);
    registry.add(TasklistProperties.PREFIX + ".importer.startLoadingDataOnStartup", () -> false);
    registry.add(TasklistProperties.PREFIX + ".archiver.rolloverEnabled", () -> false);
    registry.add(TasklistProperties.PREFIX + "importer.jobType", () -> "testJobType");
    registry.add("graphql.servlet.exception-handlers-enabled", () -> true);
    registry.add(
        "management.endpoints.web.exposure.include", () -> "info,prometheus,loggers,usage-metrics");
    registry.add(SPRING_THYMELEAF_PREFIX_KEY, () -> SPRING_THYMELEAF_PREFIX_VALUE);
    registry.add("server.servlet.session.cookie.name", () -> TasklistURIs.COOKIE_JSESSIONID);
    registry.add(
        "camunda.tasklist.multiTenancy.enabled", () -> String.valueOf(multiTenancyEnabled));
  }

  protected String generateCamundaIdentityToken() {
    return generateToken(
        USER,
        KEYCLOAK_PASSWORD,
        "camunda-identity",
        testContainerUtil.getIdentityClientSecret(),
        "password",
        null);
  }

  protected String generateTasklistToken() {
    return generateToken(
        USER,
        KEYCLOAK_PASSWORD,
        "camunda-identity",
        testContainerUtil.getIdentityClientSecret(),
        "password",
        "tasklist-api");
  }

  protected String generateTokenForUser(String username) {
    return generateToken(
        username,
        USERS_STORE.get(username),
        "camunda-identity",
        testContainerUtil.getIdentityClientSecret(),
        "password",
        null);
  }

  private String generateToken(String clientId, String clientSecret) {
    return generateToken(null, null, clientId, clientSecret, "client_credentials", null);
  }

  private String generateToken(
      final String defaultUserUsername,
      final String defaultUserPassword,
      final String clientId,
      final String clientSecret,
      final String grantType,
      final String audience) {
    final MultiValueMap<String, String> formValues = new LinkedMultiValueMap<>();
    formValues.put("grant_type", Collections.singletonList(grantType));
    formValues.put("client_id", Collections.singletonList(clientId));
    formValues.put("client_secret", Collections.singletonList(clientSecret));
    if (defaultUserUsername != null) {
      formValues.put("username", Collections.singletonList(defaultUserUsername));
    }
    if (defaultUserPassword != null) {
      formValues.put("password", Collections.singletonList(defaultUserPassword));
    }
    if (audience != null) {
      formValues.put("audience", Collections.singletonList(audience));
    }

    final HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    final RestTemplate restTemplate = new RestTemplate();
    final String tokenJson =
        restTemplate.postForObject(
            getAuthTokenUrl(), new HttpEntity<>(formValues, httpHeaders), String.class);
    try {
      return objectMapper.readTree(tokenJson).get("access_token").asText();
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  protected String getDemoUserId() {
    return getUserId(0);
  }

  protected String getUserId(int index) {
    final String response = getUsers();
    try {
      return objectMapper.readTree(response).get(index).get("id").asText();
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  protected String getUsers() {
    final HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    httpHeaders.setBearerAuth(generateCamundaIdentityToken());
    final HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
    final RestTemplate restTemplate = new RestTemplate();
    final ResponseEntity<String> response =
        restTemplate.exchange(getUsersUrl(), HttpMethod.GET, entity, String.class);

    return response.getBody();
  }

  protected void createAuthorization(
      String entityId,
      String entityType,
      String resourceKey,
      String resourceType,
      String permission)
      throws JSONException {
    final JSONObject obj = new JSONObject();

    obj.put("entityId", entityId);
    obj.put("entityType", entityType);
    obj.put("resourceKey", resourceKey);
    obj.put("resourceType", resourceType);
    obj.put("permission", permission);

    final HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    httpHeaders.setBearerAuth(generateCamundaIdentityToken());

    final RestTemplate restTemplate = new RestTemplate();

    final ResponseEntity<String> response =
        restTemplate.exchange(
            getAuthorizationsUrl(),
            HttpMethod.POST,
            new HttpEntity<>(obj.toString(), httpHeaders),
            String.class);
  }

  protected String getAuthTokenUrl() {
    return getAuthServerUrl()
        .concat("/realms/")
        .concat(REALM)
        .concat("/protocol/openid-connect/token");
  }

  protected String getUsersUrl() {
    return "http://"
        + testContext.getExternalIdentityHost()
        + ":"
        + testContext.getExternalIdentityPort()
        + "/api/users";
  }

  protected String getAuthorizationsUrl() {
    return "http://"
        + testContext.getExternalIdentityHost()
        + ":"
        + testContext.getExternalIdentityPort()
        + "/api/authorizations";
  }

  protected static String getAuthServerUrl() {
    return "http://"
        + testContext.getExternalKeycloakHost()
        + ":"
        + testContext.getExternalKeycloakPort()
        + CONTEXT_PATH;
  }

  @AfterAll
  public static void stopContainers() {
    Environment.system().remove("ZEEBE_CLIENT_ID");
    Environment.system().remove("ZEEBE_CLIENT_SECRET");
    Environment.system().remove("ZEEBE_TOKEN_AUDIENCE");
    Environment.system().remove("ZEEBE_AUTHORIZATION_SERVER_URL");
    testContainerUtil.stopIdentity(testContext);
  }
}