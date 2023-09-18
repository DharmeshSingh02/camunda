/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.qa.util.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Registers the {@link ZeebeIntegrationExtension} extension, which will manage the lifecycle of {@link io.camunda.zeebe.qa.util.cluster.TestCluster} or {@link io.camunda.zeebe.qa.util.cluster.TestApplication} instances.
 *
 * <pre>{@code
 * @ManageTestZeebe
 * final class MyClusteredTest {
 *   @TestZeebe(autoStart = true, awaitCompleteTopology = true)
 *   private TestCluster cluster = TestCluster.builder()
 *          .withBrokersCount(3)
 *          .withReplicationFactor(3)
 *          .withPartitionsCount(1)
 *          .useEmbeddedGateway(true)
 *          .build();
 *
 *   @Test
 *   void shouldConnectToCluster() {
 *     // given
 *     final Topology topology;
 *
 *     // when
 *     try (final ZeebeClient client = cluster.newClientBuilder().build()) {
 *       topology = c.newTopologyRequest().send().join();
 *     }
 *
 *     // then
 *     assertThat(topology.getClusterSize()).isEqualTo(3);
 *   }
 * }</pre>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(ZeebeIntegrationExtension.class)
public @interface ZeebeIntegration {

  @Target({ElementType.FIELD})
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @Inherited
  @interface TestZeebe {
    /** If true (the default), will automatically start the annotated instance before tests. */
    boolean autoStart() default true;

    /**
     * If true (the default), will block and wait until all managed applications are ready.
     *
     * <p>Does nothing if {@link #autoStart()} is false.
     */
    boolean awaitReady() default true;

    /**
     * If true (the default), will block and wait until all managed applications are started.
     *
     * <p>Does nothing if {@link #autoStart()} is false.
     */
    boolean awaitStarted() default true;

    /**
     * If true (the default), will block and wait until the topology is complete, using {@link
     * #clusterSize()}, {@link #partitionCount()}, and {@link #replicationFactor()} as parameters.
     *
     * <p>If a {@link io.camunda.zeebe.qa.util.cluster.TestCluster} instance is annotated with this,
     * verifies this on all gateways. If the cluster size, partition count, and replication factor
     * attributes are left to defaults (-1), uses the cluster's information to replace them.
     * However, if they're set to something, this will override the cluster's settings.
     *
     * <p>If a {@link io.camunda.zeebe.qa.util.cluster.TestGateway} instance is annotated with this,
     * and replaces the default cluster size, partition count, and replication factor by 1.
     *
     * <p>Does nothing if a {@link io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker} is
     * annotated with this that does not have an embedded broker.
     *
     * <p>Does nothing if {@link #autoStart()} is false.
     */
    boolean awaitCompleteTopology() default true;

    /** The expected number of brokers in the cluster, used for {@link #awaitCompleteTopology()}. */
    int clusterSize() default 0;

    /** The expected partition count, used for {@link #awaitCompleteTopology()}. */
    int partitionCount() default 0;

    /** The expected replication factor, used for {@link #awaitCompleteTopology()}. */
    int replicationFactor() default 0;
  }
}
