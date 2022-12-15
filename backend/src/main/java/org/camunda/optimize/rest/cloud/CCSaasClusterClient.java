/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.rest.cloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.camunda.optimize.dto.optimize.query.ui_configuration.AppName;
import org.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.service.util.configuration.condition.CCSaaSCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.camunda.optimize.dto.optimize.query.ui_configuration.AppName.CONSOLE;
import static org.camunda.optimize.dto.optimize.query.ui_configuration.AppName.MODELER;
import static org.camunda.optimize.dto.optimize.query.ui_configuration.AppName.OPTIMIZE;

@Component
@Slf4j
@Conditional(CCSaaSCondition.class)
public class CCSaasClusterClient extends AbstractCCSaaSClient {
  private Map<AppName, String> webappsLinks;
  private static final String HTTP_PREFIX = "http://";
  private static final String HTTPS_PREFIX = "https://";

  public CCSaasClusterClient(final ConfigurationService configurationService,
                             final ObjectMapper objectMapper) {
    super(objectMapper, configurationService);
  }

  public Map<AppName, String> getWebappLinks(final String accessToken) {
    if (MapUtils.isEmpty(webappsLinks)) {
      webappsLinks = retrieveWebappsLinks(accessToken);
    }
    return webappsLinks;
  }

  private Map<AppName, String> retrieveWebappsLinks(String accessToken) {
     try {
      log.info("Fetching cluster metadata.");
      final HttpGet request = new HttpGet(String.format(
        GET_CLUSTERS_TEMPLATE,
        String.format(CONSOLE_ROOTURL_TEMPLATE, retrieveDomainOfRunningInstance()),
        getCloudAuthConfiguration().getOrganizationId()
      ));
      final CloseableHttpResponse response = performRequest(request, accessToken);
      if (response.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
        throw new OptimizeRuntimeException(String.format(
          "Unexpected response when fetching cluster metadata: %s", response.getStatusLine().getStatusCode()));
      }
      log.info("Processing response from Cluster metadata");
      // To make sure we don't crash when an unknown app is sent, ignore the unknowns
      objectMapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
      final ClusterMetadata[] metadataForAllClusters = objectMapper.readValue(response.getEntity().getContent(),
                                                                         ClusterMetadata[].class);
      if (metadataForAllClusters != null) {
        String currentClusterId = getCloudAuthConfiguration().getClusterId();
        return Arrays.stream(metadataForAllClusters)
          .filter(cm -> cm.getUuid().equals(currentClusterId))
          .findFirst()
          .map(cluster -> addModelerAndConsoleLinksIfNotExists(cluster.getUrls()))
          // If we can't find cluster metadata for the current cluster, we can't return URLs
          .orElseThrow(() -> new OptimizeRuntimeException(
            "Fetched Cluster metadata successfully, but there was no data for the cluster " + currentClusterId));
      } else {
        throw new OptimizeRuntimeException("Could not fetch Cluster metadata");
      }
    } catch (IOException e) {
      throw new OptimizeRuntimeException("There was a problem fetching cluster metadata.", e);
    }
  }

  private Map<AppName, String> addModelerAndConsoleLinksIfNotExists(final Map<AppName, String> urls) {
    final String organizationId = getCloudAuthConfiguration().getOrganizationId();
    final String domain = retrieveDomainOfRunningInstance();
    final String clusterId = getCloudAuthConfiguration().getClusterId();
    urls.computeIfAbsent(MODELER, key ->  String.format(MODELER_URL_TEMPLATE, domain, organizationId));
    urls.computeIfAbsent(CONSOLE, key ->  String.format(CONSOLE_URL_TEMPLATE, domain, organizationId, clusterId));
    return urls;
  }

  private String retrieveDomainOfRunningInstance() {
    final Optional<String> containerAccessUrl = configurationService.getContainerAccessUrl();
    String rootUrl;
    if (containerAccessUrl.isPresent()) {
      rootUrl = containerAccessUrl.get();
    } else {
      Optional<Integer> containerHttpPort = configurationService.getContainerHttpPort();
      String httpPrefix = containerHttpPort.map(p -> HTTP_PREFIX).orElse(HTTPS_PREFIX);
      Integer port = containerHttpPort.orElse(configurationService.getContainerHttpsPort());
      rootUrl = httpPrefix + configurationService.getContainerHost()
        + ":" + port + configurationService.getContextPath().orElse("");
    }
    // Now strip the URL and get only the main part
    // URL looks like this https://bru-2.optimize.dev.ultrawombat.com/ff488019-8082-411e-8abc-46f8597cd7d3/
    Pattern urlPattern = Pattern.compile("^(?:https?://)?(?:[^@/\\n]+@)?(?:www\\.)?([^:/?\\n]+)");
    Matcher matcher = urlPattern.matcher(rootUrl);
    if(matcher.find()) {
      String pureUrl = matcher.group();
      // Now I have sth like bru-2.optimize.dev.ultrawombat.com in my hand, let's get the juicy part
      Pattern domainPattern = Pattern.compile("(?<=" + OPTIMIZE + ").*");
      Matcher domainMatcher = domainPattern.matcher(pureUrl);
      if(domainMatcher.find()) {
        // Now I only have what I'm actually interested in: .dev.ultrawombat.com
        return domainMatcher.group();
      }
      else {
        log.warn("The processed URL that I received looks odd and I cannot parse it: " + pureUrl + " . Therefore I'm " +
                   "returning the fallback domain " + DEFAULT_DOMAIN_WHEN_ERROR_OCCURS);
        return DEFAULT_DOMAIN_WHEN_ERROR_OCCURS;
      }
    } else {
      log.warn("The domain URL that I received looks odd and I cannot parse it: " + rootUrl + " . Therefore I'm " +
                 "returning the fallback domain " + DEFAULT_DOMAIN_WHEN_ERROR_OCCURS);
      return DEFAULT_DOMAIN_WHEN_ERROR_OCCURS;
    }
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class ClusterMetadata implements Serializable {
    private String uuid;
    private Map<AppName, String> urls = new EnumMap<>(AppName.class);
  }
}
