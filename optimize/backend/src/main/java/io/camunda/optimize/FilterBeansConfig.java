/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize;

import static io.camunda.optimize.jetty.OptimizeResourceConstants.REST_API_PATH;
import static io.camunda.optimize.jetty.OptimizeResourceConstants.STATIC_RESOURCE_PATH;
import static io.camunda.optimize.rest.IngestionRestService.EVENT_BATCH_SUB_PATH;
import static io.camunda.optimize.rest.IngestionRestService.INGESTION_PATH;
import static io.camunda.optimize.rest.IngestionRestService.VARIABLE_SUB_PATH;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.optimize.jetty.IngestionQoSFilter;
import io.camunda.optimize.jetty.JavaScriptMainLicenseEnricherFilter;
import io.camunda.optimize.jetty.MaxRequestSizeFilter;
import io.camunda.optimize.jetty.NoCachingFilter;
import io.camunda.optimize.service.util.configuration.ConfigurationService;
import jakarta.servlet.DispatcherType;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The name for each {@link FilterRegistrationBean} has to be set in order to avoid conflicts in
 * case multiple Filters are of the same class, e.g. {@code
 * registrationBean.setName("variableIngestionMaxRequestSizeFilter");}
 */
@Configuration
public class FilterBeansConfig {
  @Bean
  public JavaScriptMainLicenseEnricherFilter javaScriptMainLicenseEnricherFilter() {
    return new JavaScriptMainLicenseEnricherFilter();
  }

  @Bean
  public NoCachingFilter noCachingFilter() {
    return new NoCachingFilter();
  }

  @Bean
  public FilterRegistrationBean<NoCachingFilter> noCachingFilterRegistrationBean(
      final NoCachingFilter noCachingFilter) {
    final FilterRegistrationBean<NoCachingFilter> registrationBean = new FilterRegistrationBean<>();

    registrationBean.setFilter(noCachingFilter);
    registrationBean.addUrlPatterns("/*");
    registrationBean.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC);

    registrationBean.setName("noCachingFilter");

    return registrationBean;
  }

  @Bean
  public MaxRequestSizeFilter maxRequestSizeFilter(
      @Qualifier("optimizeMapper") final ObjectMapper objectMapper,
      final ConfigurationService configurationService) {
    return new MaxRequestSizeFilter(
        () -> objectMapper,
        () -> configurationService.getEventIngestionConfiguration().getMaxBatchRequestBytes());
  }

  @Bean
  public FilterRegistrationBean<MaxRequestSizeFilter> maxRequestSizeFilterRegistrationBean(
      final MaxRequestSizeFilter maxRequestSizeFilter) {
    final FilterRegistrationBean<MaxRequestSizeFilter> registrationBean =
        new FilterRegistrationBean<>();

    registrationBean.setFilter(maxRequestSizeFilter);
    registrationBean.addUrlPatterns(REST_API_PATH + INGESTION_PATH + EVENT_BATCH_SUB_PATH);
    registrationBean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);

    registrationBean.setName("eventIngestionMaxRequestSizeFilter");

    return registrationBean;
  }

  @Bean
  public FilterRegistrationBean<IngestionQoSFilter> variableIngestionQoSFilterRegistrationBean(
      final ConfigurationService configurationService) {
    return getIngestionQoSFilterRegistrationBean(
        () -> configurationService.getVariableIngestionConfiguration().getMaxRequests(),
        VARIABLE_SUB_PATH,
        "variableIngestionQoSFilter");
  }

  @Bean
  public FilterRegistrationBean<IngestionQoSFilter> eventIngestionQoSFilterRegistrationBean(
      final ConfigurationService configurationService) {
    return getIngestionQoSFilterRegistrationBean(
        () -> configurationService.getEventIngestionConfiguration().getMaxRequests(),
        EVENT_BATCH_SUB_PATH,
        "eventIngestionQoSFilter");
  }

  private FilterRegistrationBean<IngestionQoSFilter> getIngestionQoSFilterRegistrationBean(
      final Callable<Integer> provider, final String subPath, final String name) {
    final IngestionQoSFilter ingestionQoSFilter = new IngestionQoSFilter(provider);

    final FilterRegistrationBean<IngestionQoSFilter> registrationBean =
        new FilterRegistrationBean<>();

    registrationBean.setFilter(ingestionQoSFilter);
    registrationBean.addUrlPatterns(REST_API_PATH + INGESTION_PATH + subPath);
    registrationBean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    registrationBean.setName(name);
    return registrationBean;
  }

  @Bean
  public FilterRegistrationBean<JavaScriptMainLicenseEnricherFilter>
      javaScriptMainLicenseEnricherFilterRegistrationBean(
          final JavaScriptMainLicenseEnricherFilter javaScriptMainLicenseEnricherFilter) {
    final FilterRegistrationBean<JavaScriptMainLicenseEnricherFilter> registrationBean =
        new FilterRegistrationBean<>();

    registrationBean.setFilter(javaScriptMainLicenseEnricherFilter);
    registrationBean.addUrlPatterns(STATIC_RESOURCE_PATH + "/*");
    registrationBean.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC);
    registrationBean.setName("javaScriptMainLicenseEnricherFilter");

    return registrationBean;
  }

  @Bean
  public FilterRegistrationBean<MaxRequestSizeFilter>
      variableIngestionRequestLimitFilterRegistrationBean(
          final ConfigurationService configurationService, final ObjectMapper objectMapper) {

    final MaxRequestSizeFilter variableIngestionRequestLimitFilter =
        new MaxRequestSizeFilter(
            () -> objectMapper,
            () ->
                configurationService.getVariableIngestionConfiguration().getMaxBatchRequestBytes());

    final FilterRegistrationBean<MaxRequestSizeFilter> registrationBean =
        new FilterRegistrationBean<>();

    registrationBean.setFilter(variableIngestionRequestLimitFilter);
    registrationBean.addUrlPatterns(REST_API_PATH + INGESTION_PATH + VARIABLE_SUB_PATH);
    registrationBean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);

    registrationBean.setName("variableIngestionMaxRequestSizeFilter");

    return registrationBean;
  }
}
