package org.camunda.optimize.service.status;

import org.camunda.optimize.dto.optimize.query.ConnectionStatusDto;
import org.camunda.optimize.rest.engine.EngineClientFactory;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.service.util.configuration.EngineConfiguration;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Component
public class StatusCheckingService {


  @Autowired
  private org.elasticsearch.client.Client elasticsearchClient;

  @Autowired
  private ConfigurationService configurationService;

  @Autowired
  private EngineClientFactory engineClientFactory;


  public ConnectionStatusDto getConnectionStatus() {
    ConnectionStatusDto status = new ConnectionStatusDto();
    status.setConnectedToElasticsearch(isConnectedToElasticSearch());
    Map<String, Boolean> engineConnections = new HashMap<>();
    for (Map.Entry<String,EngineConfiguration> e : configurationService.getConfiguredEngines().entrySet()) {
      engineConnections.put(e.getKey(), isConnectedToEngine(e.getKey()));
    }
    status.setEngineConnections(engineConnections);
    return status;
  }

  private boolean isConnectedToEngine(String engineAlias) {
    boolean isConnected = false;
    try {
      String endPoint = configurationService.getEngineRestApiEndpoint(engineAlias);
      String engineEndpoint = endPoint + "/engine";
      Response response = getEngineClient(engineAlias)
        .target(engineEndpoint)
        .request(MediaType.APPLICATION_JSON)
        .get();
      isConnected = response.getStatus() == 200;
    } catch (Exception ignored) {
      // do nothing
    }
    return isConnected;
  }

  private Client getEngineClient(String engineAlias) {
    return engineClientFactory.getInstance(engineAlias);
  }

  private boolean isConnectedToElasticSearch() {
    boolean isConnected = false;
    try {
      ClusterHealthResponse getResponse = elasticsearchClient
        .admin()
        .cluster()
        .prepareHealth(configurationService.getOptimizeIndex())
        .get();
      isConnected = getResponse.status().getStatus() == 200 && getResponse.getStatus() != ClusterHealthStatus.RED;
    } catch (Exception ignored) {
      // do nothing
    }
    return isConnected;
  }
}
