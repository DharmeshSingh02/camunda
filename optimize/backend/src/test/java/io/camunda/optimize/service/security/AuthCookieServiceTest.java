/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.security;

import static io.camunda.optimize.rest.constants.RestConstants.OPTIMIZE_AUTHORIZATION;
import static io.camunda.optimize.rest.constants.RestConstants.OPTIMIZE_SERVICE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.camunda.optimize.service.util.configuration.ConfigurationService;
import io.camunda.optimize.service.util.configuration.security.AuthConfiguration;
import io.camunda.optimize.service.util.configuration.security.CloudAuthConfiguration;
import io.camunda.optimize.service.util.configuration.security.CookieConfiguration;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

@ExtendWith(MockitoExtension.class)
public class AuthCookieServiceTest {

  @Mock ConfigurationService configurationService;

  @Mock OAuth2AccessToken oAuth2AccessToken;

  @Test
  public void getTokenFromContainerRequestContext() {
    // given
    String authorizationHeader = String.format("Bearer %s", "test");
    jakarta.ws.rs.core.Cookie cookie =
        new jakarta.ws.rs.core.Cookie.Builder(OPTIMIZE_AUTHORIZATION)
            .value(authorizationHeader)
            .build();
    Map<String, jakarta.ws.rs.core.Cookie> cookies =
        Collections.singletonMap(OPTIMIZE_AUTHORIZATION, cookie);
    ContainerRequestContext requestMock = Mockito.mock(ContainerRequestContext.class);
    when(requestMock.getCookies()).thenReturn(cookies);

    // when
    Optional<String> token = AuthCookieService.getAuthCookieToken(requestMock);

    // then
    assertThat(token).isPresent().get().isEqualTo("test");
  }

  @Test
  public void getTokenExceptionFromContainerRequestContext() {
    ContainerRequestContext requestMock = Mockito.mock(ContainerRequestContext.class);
    assertThat(AuthCookieService.getAuthCookieToken(requestMock)).isEmpty();
  }

  @Test
  public void getTokenFromHttpServletRequest() {
    // given
    String authorizationHeader = String.format("Bearer %s", "test");
    Cookie[] cookies = new Cookie[] {new Cookie(OPTIMIZE_AUTHORIZATION, authorizationHeader)};
    HttpServletRequest servletRequestMock = Mockito.mock(HttpServletRequest.class);
    when(servletRequestMock.getCookies()).thenReturn(cookies);

    // when
    Optional<String> token = AuthCookieService.getAuthCookieToken(servletRequestMock);

    // then
    assertThat(token).isPresent().get().isEqualTo("test");
  }

  @Test
  public void getTokenExceptionFromHttpServletRequest() {
    HttpServletRequest servletRequestMock = Mockito.mock(HttpServletRequest.class);
    assertThat(AuthCookieService.getAuthCookieToken(servletRequestMock)).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("tokenAndExpectedCookieValues")
  public void createServiceTokenCookies(
      final String serviceTokenValue, List<String> expectedCookieValues) {
    // given
    CookieConfiguration cookieConfig = new CookieConfiguration();
    cookieConfig.setMaxSize(2);
    CloudAuthConfiguration cloudAuthConfig = new CloudAuthConfiguration();
    cloudAuthConfig.setClientId("clusterId");
    final AuthConfiguration authConfig = new AuthConfiguration();
    authConfig.setCookieConfiguration(cookieConfig);
    authConfig.setCloudAuthConfiguration(cloudAuthConfig);
    when(configurationService.getAuthConfiguration()).thenReturn(authConfig);
    when(oAuth2AccessToken.getTokenValue()).thenReturn(serviceTokenValue);
    final AuthCookieService authCookieService = new AuthCookieService(configurationService);

    // when
    final List<jakarta.servlet.http.Cookie> cookies =
        authCookieService.createOptimizeServiceTokenCookies(
            oAuth2AccessToken, Instant.now(), "http");

    // then the correct cookies are created
    assertThat(cookies)
        .extracting(jakarta.servlet.http.Cookie::getValue)
        .containsExactlyElementsOf(expectedCookieValues);
  }

  @ParameterizedTest
  @MethodSource("tokenAndExpectedCookieValues")
  public void extractServiceTokenFromCookies(
      final String expectedServiceTokenValue, List<String> cookieValues) {
    // given
    List<jakarta.servlet.http.Cookie> cookies = new ArrayList<>();
    for (int i = 0; i < cookieValues.size(); i++) {
      cookies.add(
          new jakarta.servlet.http.Cookie(OPTIMIZE_SERVICE_TOKEN + "_" + i, cookieValues.get(i)));
    }
    HttpServletRequest servletRequestMock = Mockito.mock(HttpServletRequest.class);
    when(servletRequestMock.getCookies())
        .thenReturn(cookies.toArray(jakarta.servlet.http.Cookie[]::new));

    // when
    final Optional<String> serviceAccessToken =
        AuthCookieService.getServiceAccessToken(servletRequestMock);

    // then the correct service token value can be extracted
    assertThat(serviceAccessToken).isPresent().get().isEqualTo(expectedServiceTokenValue);
  }

  private static Stream<Arguments> tokenAndExpectedCookieValues() {
    return Stream.of(
        Arguments.of("a", List.of("a")),
        Arguments.of("bc", List.of("bc")),
        Arguments.of("def", List.of("de", "f")),
        Arguments.of("ghij", List.of("gh", "ij")));
  }
}