/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.events.autogeneration;

import static io.camunda.optimize.service.db.DatabaseConstants.EXTERNAL_EVENTS_INDEX_SUFFIX;
import static java.util.stream.Collectors.toMap;

import io.camunda.optimize.dto.optimize.query.event.autogeneration.AutogenerationEventGraphDto;
import io.camunda.optimize.dto.optimize.query.event.autogeneration.AutogenerationProcessModelDto;
import io.camunda.optimize.dto.optimize.query.event.autogeneration.CorrelatedInstanceDto;
import io.camunda.optimize.dto.optimize.query.event.autogeneration.CorrelatedTraceDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventMappingDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventTypeDto;
import io.camunda.optimize.dto.optimize.query.event.process.source.EventSourceEntryDto;
import io.camunda.optimize.dto.optimize.query.event.process.source.EventSourceType;
import io.camunda.optimize.dto.optimize.query.event.sequence.EventSequenceCountDto;
import io.camunda.optimize.dto.optimize.query.event.sequence.EventTraceStateDto;
import io.camunda.optimize.service.db.events.EventTraceStateServiceFactory;
import io.camunda.optimize.service.events.EventTraceStateService;
import io.camunda.optimize.service.util.EventModelBuilderUtil;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.AbstractBpmnModelElementBuilder;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.builder.ProcessBuilder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AutogenerationProcessModelService {

  private static final String AUTOGENERATED_PROCESS_ID = "AutogeneratedProcessId";
  private static final int MAX_EXTERNAL_SAMPLE_RESULTS = 200;

  private final ExternalEventModelBuilderService externalEventModelBuilderService;
  private final CorrelatedInstanceService correlatedInstanceService;
  private final EventTraceStateService eventTraceStateService;

  public AutogenerationProcessModelService(
      final EventTraceStateServiceFactory eventTraceStateServiceFactory,
      final ExternalEventModelBuilderService externalEventModelBuilderService,
      final CorrelatedInstanceService correlatedInstanceService) {
    this.externalEventModelBuilderService = externalEventModelBuilderService;
    this.correlatedInstanceService = correlatedInstanceService;
    eventTraceStateService =
        eventTraceStateServiceFactory.createEventTraceStateService(EXTERNAL_EVENTS_INDEX_SUFFIX);
  }

  public AutogenerationProcessModelDto generateModelFromEventSources(
      final List<EventSourceEntryDto<?>> eventSources) {
    AutogenerationEventGraphDto autogeneratedEventGraphDto = null;
    Map<String, List<EventTypeDto>> sampleTraceListsByTracingId = new HashMap<>();
    if (eventSources.stream()
        .anyMatch(source -> EventSourceType.EXTERNAL.equals(source.getSourceType()))) {
      final List<EventSequenceCountDto> externalSequenceCounts =
          eventTraceStateService.getAllSequenceCounts();
      autogeneratedEventGraphDto =
          EventModelBuilderUtil.generateExternalEventGraph(externalSequenceCounts);
      sampleTraceListsByTracingId =
          getSampleTraceListsByTracingId(
              autogeneratedEventGraphDto.getStartEvents(),
              autogeneratedEventGraphDto.getEndEvents());
    }
    final List<String> externalSampleTraceIds =
        new ArrayList<>(sampleTraceListsByTracingId.keySet());
    final List<EventSourceEntryDto<?>> orderedEventSources =
        sortEventSourcesForModelOrder(eventSources, externalSampleTraceIds);

    final ProcessBuilder diagramBuilder = Bpmn.createProcess(AUTOGENERATED_PROCESS_ID);
    final Map<String, EventMappingDto> mappings = new HashMap<>();
    AbstractFlowNodeBuilder<?, ?> generatedModelBuilder = null;
    for (final EventSourceEntryDto<?> source : orderedEventSources) {
      if (EventSourceType.EXTERNAL.equals(source.getSourceType())) {
        generatedModelBuilder =
            externalEventModelBuilderService.createOrExtendModelWithExternalEventSource(
                autogeneratedEventGraphDto,
                new ArrayList<>(sampleTraceListsByTracingId.values()),
                diagramBuilder,
                generatedModelBuilder,
                mappings,
                isFinalSourceInSeries(source, orderedEventSources));
      }
    }

    final BpmnModelInstance modelInstance =
        Optional.ofNullable(generatedModelBuilder)
            .map(AbstractBpmnModelElementBuilder::done)
            .orElse(diagramBuilder.done());
    return AutogenerationProcessModelDto.builder()
        .xml(Bpmn.convertToString(modelInstance))
        .mappings(mappings)
        .build();
  }

  private List<EventSourceEntryDto<?>> sortEventSourcesForModelOrder(
      final List<EventSourceEntryDto<?>> eventSources, final List<String> externalEventTracingIds) {
    if (eventSources.size() <= 1) {
      return eventSources;
    }
    final Map<String, EventSourceEntryDto<?>> eventSourceByIdentifier =
        eventSources.stream()
            .collect(toMap(EventSourceEntryDto::getSourceIdentifier, Function.identity()));
    final List<String> sampleCorrelationValuesForSources = new ArrayList<>();
    sampleCorrelationValuesForSources.addAll(externalEventTracingIds);

    final List<CorrelatedTraceDto> correlatedTraces =
        correlatedInstanceService.getCorrelatedTracesForEventSources(
            eventSources, sampleCorrelationValuesForSources);

    // We first set the ordered sources to be the same as the trace we have found that contains most
    // sources
    final List<EventSourceEntryDto<?>> orderedEventSources = new ArrayList<>();
    orderedEventSources.addAll(
        correlatedTraces.stream()
            .max(Comparator.comparing(trace -> trace.getInstances().size()))
            .map(
                trace ->
                    trace.getInstances().stream()
                        .map(
                            instance -> eventSourceByIdentifier.get(instance.getSourceIdentifier()))
                        .collect(Collectors.toList()))
            .orElse(Collections.emptyList()));
    log.debug(
        "Trace found containing all following source identifiers in order: {}",
        orderedEventSources.stream()
            .map(EventSourceEntryDto::getSourceIdentifier)
            .collect(Collectors.toList()));

    // Then we check which sources are not yet included in our ordered trace and try to place them
    // individually based on
    // our found traces by placing them before/after a source that they appear in a trace with
    final List<String> identifiersForPlacedSources =
        orderedEventSources.stream()
            .map(EventSourceEntryDto::getSourceIdentifier)
            .collect(Collectors.toList());
    for (final EventSourceEntryDto<?> unplacedSource :
        findUnplacedSources(eventSources, orderedEventSources)) {
      tryToAddUnplacedSourceInOrderedSources(
          eventSourceByIdentifier,
          correlatedTraces,
          orderedEventSources,
          identifiersForPlacedSources,
          unplacedSource);
    }
    // Anything that is still unplaced gets placed at the end of the ordered traces
    orderedEventSources.addAll(findUnplacedSources(eventSources, orderedEventSources));
    return orderedEventSources;
  }

  private void tryToAddUnplacedSourceInOrderedSources(
      final Map<String, EventSourceEntryDto<?>> sourceByIdentifier,
      final List<CorrelatedTraceDto> correlatedTrace,
      final List<EventSourceEntryDto<?>> orderedEventSources,
      final List<String> identifiersForPlacedSources,
      final EventSourceEntryDto<?> unplacedSource) {
    log.debug(
        "Attempting to add source of type {} with identifier {} to ordered trace",
        unplacedSource.getSourceType(),
        unplacedSource.getSourceIdentifier());
    final List<CorrelatedInstanceDto> correlatedInstancesForCorrelatedTrace =
        findCorrelatedInstancesContainingUnplacedSourceAndAtLeastOnePlaced(
            correlatedTrace, identifiersForPlacedSources, unplacedSource);
    // If there is a trace containing the unplaced source that also contains sources that have been
    // placed
    if (correlatedInstancesForCorrelatedTrace.size() > 1) {
      final Optional<CorrelatedInstanceDto> instanceForUnplacedSource =
          correlatedInstancesForCorrelatedTrace.stream()
              .filter(
                  instance ->
                      instance.getSourceIdentifier().equals(unplacedSource.getSourceIdentifier()))
              .findFirst();
      final CorrelatedInstanceDto unplacedSourceInstance =
          instanceForUnplacedSource.orElseThrow(
              () -> new IllegalStateException("Can't place source as instance not found in trace"));
      final int unplacedSourceIndex =
          correlatedInstancesForCorrelatedTrace.indexOf(unplacedSourceInstance);
      // If the unplaced source is at the start of the trace
      if (unplacedSourceIndex == 0) {
        // Then we get the following source that we know to already be placed
        final EventSourceEntryDto<?> placedSource =
            sourceByIdentifier.get(
                correlatedInstancesForCorrelatedTrace
                    .get(unplacedSourceIndex + 1)
                    .getSourceIdentifier());
        // And place the unplaced source at its index, before the existing placed source
        log.debug(
            "Adding source with identifier {} to ordered trace before source with identifier {}",
            unplacedSource.getSourceIdentifier(),
            placedSource.getSourceIdentifier());
        orderedEventSources.add(orderedEventSources.indexOf(placedSource), unplacedSource);
        // If the unplaced source is at the end of the trace
      } else if (unplacedSourceIndex == correlatedInstancesForCorrelatedTrace.size() - 1) {
        // Then we get the previous source that we know to already be placed
        final EventSourceEntryDto<?> placedSource =
            sourceByIdentifier.get(
                correlatedInstancesForCorrelatedTrace
                    .get(unplacedSourceIndex - 1)
                    .getSourceIdentifier());
        // And place the unplaced source after the existing placed source
        log.debug(
            "Adding source with identifier {} to ordered trace after source with identifier {}",
            unplacedSource.getSourceIdentifier(),
            placedSource.getSourceIdentifier());
        orderedEventSources.add(orderedEventSources.indexOf(placedSource) + 1, unplacedSource);
        // Otherwise, we place it adjacent to the nearest already placed source based on process
        // start time
      } else {
        final CorrelatedInstanceDto instanceForPreviousSource =
            correlatedInstancesForCorrelatedTrace.get(unplacedSourceIndex - 1);
        final long timeBetweenPreviousAndUnplaced =
            instanceForPreviousSource
                .getStartDate()
                .until(unplacedSourceInstance.getStartDate(), ChronoUnit.MILLIS);
        final CorrelatedInstanceDto instanceForNextSource =
            correlatedInstancesForCorrelatedTrace.get(unplacedSourceIndex + 1);
        final long timeBetweenUnplacedAndNext =
            unplacedSourceInstance
                .getStartDate()
                .until(instanceForNextSource.getStartDate(), ChronoUnit.MILLIS);
        // If the unplaced source is nearer in this instance to its previous source, we place it
        // directly after
        if (timeBetweenPreviousAndUnplaced < timeBetweenUnplacedAndNext) {
          final EventSourceEntryDto<?> previousSource =
              sourceByIdentifier.get(instanceForPreviousSource.getSourceIdentifier());
          log.debug(
              "Adding source with identifier {} to ordered trace after source with identifier {}",
              unplacedSource.getSourceIdentifier(),
              previousSource.getSourceIdentifier());
          orderedEventSources.add(orderedEventSources.indexOf(previousSource) + 1, unplacedSource);
          // Otherwise, we place it before the next source
        } else {
          final EventSourceEntryDto<?> nextSource =
              sourceByIdentifier.get(instanceForNextSource.getSourceIdentifier());
          log.debug(
              "Adding source with identifier {} to ordered trace after source with identifier {}",
              unplacedSource.getSourceIdentifier(),
              nextSource.getSourceIdentifier());
          orderedEventSources.add(orderedEventSources.indexOf(nextSource), unplacedSource);
        }
      }
      // We have now placed this source so can add it to our placed identifiers list to help future
      // placements
      identifiersForPlacedSources.add(unplacedSource.getSourceIdentifier());
    } else {
      log.debug(
          "Not able to find a suitable place for source of type {} and with identifier {} based on sample traces",
          unplacedSource.getSourceType(),
          unplacedSource.getSourceIdentifier());
    }
  }

  private List<CorrelatedInstanceDto>
      findCorrelatedInstancesContainingUnplacedSourceAndAtLeastOnePlaced(
          final List<CorrelatedTraceDto> correlatedTrace,
          final List<String> identifiersForPlacedSources,
          final EventSourceEntryDto<?> unplacedSource) {
    return correlatedTrace.stream()
        .filter(
            trace ->
                trace.getInstances().stream()
                    .map(CorrelatedInstanceDto::getSourceIdentifier)
                    .anyMatch(
                        identifier -> identifier.equals(unplacedSource.getSourceIdentifier())))
        .map(
            traceContainingUnplaced ->
                traceContainingUnplaced.getInstances().stream()
                    .filter(
                        instance ->
                            identifiersForPlacedSources.contains(instance.getSourceIdentifier())
                                || instance
                                    .getSourceIdentifier()
                                    .equals(unplacedSource.getSourceIdentifier()))
                    .collect(Collectors.toList()))
        .max(Comparator.comparing(List::size))
        .orElse(Collections.emptyList());
  }

  private List<EventSourceEntryDto<?>> findUnplacedSources(
      final List<EventSourceEntryDto<?>> eventSources,
      final List<EventSourceEntryDto<?>> orderedEventSources) {
    return eventSources.stream()
        .filter(source -> !orderedEventSources.contains(source))
        .collect(Collectors.toList());
  }

  private boolean isFinalSourceInSeries(
      final EventSourceEntryDto<?> source, final List<EventSourceEntryDto<?>> allSources) {
    return allSources.indexOf(source) == allSources.size() - 1;
  }

  private Map<String, List<EventTypeDto>> getSampleTraceListsByTracingId(
      final List<EventTypeDto> startEvents, final List<EventTypeDto> endEvents) {
    final List<EventTraceStateDto> sampleTraces =
        eventTraceStateService.getTracesContainingAtLeastOneEventFromEach(
            startEvents, endEvents, MAX_EXTERNAL_SAMPLE_RESULTS);
    return sampleTraces.stream()
        .collect(
            Collectors.toMap(
                EventTraceStateDto::getTraceId,
                trace ->
                    trace.getEventTrace().stream()
                        .map(
                            event ->
                                EventTypeDto.builder()
                                    .group(event.getGroup())
                                    .source(event.getSource())
                                    .eventName(event.getEventName())
                                    .build())
                        .collect(Collectors.toList())));
  }
}
