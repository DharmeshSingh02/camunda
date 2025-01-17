/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service;

import static io.camunda.optimize.dto.optimize.DefinitionType.PROCESS;
import static io.camunda.optimize.dto.optimize.rest.ConflictedItemType.COMBINED_REPORT;
import static io.camunda.optimize.dto.optimize.rest.ConflictedItemType.REPORT;
import static io.camunda.optimize.service.util.BpmnModelUtil.extractFlowNodeData;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.camunda.optimize.dto.optimize.FlowNodeDataDto;
import io.camunda.optimize.dto.optimize.IdentityDto;
import io.camunda.optimize.dto.optimize.IdentityType;
import io.camunda.optimize.dto.optimize.query.IdResponseDto;
import io.camunda.optimize.dto.optimize.query.collection.CollectionScopeEntryDto;
import io.camunda.optimize.dto.optimize.query.event.autogeneration.AutogenerationProcessModelDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventImportSourceDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventMappingDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventProcessMappingDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventProcessPublishStateDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventProcessRoleRequestDto;
import io.camunda.optimize.dto.optimize.query.event.process.EventProcessState;
import io.camunda.optimize.dto.optimize.query.event.process.source.EventScopeType;
import io.camunda.optimize.dto.optimize.query.event.process.source.EventSourceEntryDto;
import io.camunda.optimize.dto.optimize.query.event.process.source.EventSourceType;
import io.camunda.optimize.dto.optimize.query.event.process.source.ExternalEventSourceConfigDto;
import io.camunda.optimize.dto.optimize.query.event.process.source.ExternalEventSourceEntryDto;
import io.camunda.optimize.dto.optimize.query.report.ReportDefinitionDto;
import io.camunda.optimize.dto.optimize.query.report.combined.CombinedReportDefinitionRequestDto;
import io.camunda.optimize.dto.optimize.rest.ConflictResponseDto;
import io.camunda.optimize.dto.optimize.rest.ConflictedItemDto;
import io.camunda.optimize.dto.optimize.rest.EventProcessMappingCreateRequestDto;
import io.camunda.optimize.dto.optimize.rest.EventProcessMappingRequestDto;
import io.camunda.optimize.service.db.reader.EventProcessMappingReader;
import io.camunda.optimize.service.db.reader.EventProcessPublishStateReader;
import io.camunda.optimize.service.db.writer.CollectionWriter;
import io.camunda.optimize.service.db.writer.EventProcessMappingWriter;
import io.camunda.optimize.service.db.writer.EventProcessPublishStateWriter;
import io.camunda.optimize.service.events.ExternalEventService;
import io.camunda.optimize.service.events.autogeneration.AutogenerationProcessModelService;
import io.camunda.optimize.service.exceptions.InvalidEventProcessStateException;
import io.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import io.camunda.optimize.service.exceptions.OptimizeValidationException;
import io.camunda.optimize.service.relations.ReportRelationService;
import io.camunda.optimize.service.report.ReportService;
import io.camunda.optimize.service.security.util.LocalDateUtil;
import io.camunda.optimize.service.security.util.definition.DataSourceDefinitionAuthorizationService;
import io.camunda.optimize.service.util.BpmnModelUtil;
import io.camunda.optimize.service.util.configuration.ConfigurationService;
import io.camunda.optimize.service.variable.ProcessVariableLabelService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.Event;
import org.camunda.bpm.model.xml.ModelParseException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class EventProcessService {

  public static final String DEFAULT_AUTOGENERATED_PROCESS_NAME = "Autogenerated Process";
  private static final Set<EventProcessState> PUBLISHABLE_STATES =
      java.util.Set.of(EventProcessState.MAPPED, EventProcessState.UNPUBLISHED_CHANGES);
  private static final Set<EventProcessState> PUBLISH_CANCELABLE_STATES =
      Set.of(
          EventProcessState.PUBLISH_PENDING,
          EventProcessState.PUBLISHED,
          EventProcessState.UNPUBLISHED_CHANGES);
  private static final String DEFAULT_PROCESS_NAME = "New Process";

  private final DataSourceDefinitionAuthorizationService definitionAuthorizationService;
  private final EventProcessDefinitionService eventProcessDefinitionService;
  private final ReportService reportService;
  private final ReportRelationService reportRelationService;
  private final CollectionWriter collectionWriter;
  private final ConfigurationService configurationService;

  private final ExternalEventService externalEventService;
  private final AutogenerationProcessModelService autogenerationProcessModelService;
  private final ProcessVariableLabelService processVariableLabelService;

  private final EventProcessMappingReader eventProcessMappingReader;
  private final EventProcessMappingWriter eventProcessMappingWriter;

  private final EventProcessPublishStateReader eventProcessPublishStateReader;
  private final EventProcessPublishStateWriter eventProcessPublishStateWriter;

  public IdResponseDto createEventProcessMapping(
      final String userId, final EventProcessMappingCreateRequestDto createRequestDto) {
    if (createRequestDto.isAutogenerate()) {
      return autogenerateEventProcessMapping(userId, createRequestDto);
    }
    final EventProcessMappingDto eventProcessMappingDto =
        EventProcessMappingCreateRequestDto.to(userId, createRequestDto);
    validateMappingsAndXmlCompatibility(eventProcessMappingDto);
    validateEventSources(userId, eventProcessMappingDto.getEventSources());
    return eventProcessMappingWriter.createEventProcessMapping(eventProcessMappingDto);
  }

  public void updateEventProcessMapping(
      final String userId,
      final String eventProcessId,
      final EventProcessMappingRequestDto updateRequest) {
    final EventProcessMappingDto eventProcessMappingDto =
        EventProcessMappingDto.builder()
            .id(eventProcessId)
            .name(Optional.ofNullable(updateRequest.getName()).orElse(DEFAULT_PROCESS_NAME))
            .xml(updateRequest.getXml())
            .mappings(updateRequest.getMappings())
            .lastModifier(userId)
            .eventSources(updateRequest.getEventSources())
            .build();
    validateMappingsAndXmlCompatibility(eventProcessMappingDto);
    validateEventSources(userId, eventProcessMappingDto.getEventSources());
    eventProcessMappingWriter.updateEventProcessMapping(eventProcessMappingDto);
  }

  private ConflictResponseDto getDeleteConflictingItems(final String eventProcessId) {
    final List<ReportDefinitionDto> reportsForProcessDefinitionKey =
        reportService.getAllReportsForProcessDefinitionKeyOmitXml(eventProcessId);
    final Set<ConflictedItemDto> conflictedItems = new HashSet<>();
    for (final ReportDefinitionDto reportForEventProcess : reportsForProcessDefinitionKey) {
      if (reportForEventProcess instanceof CombinedReportDefinitionRequestDto) {
        conflictedItems.add(
            new ConflictedItemDto(
                reportForEventProcess.getId(), COMBINED_REPORT, reportForEventProcess.getName()));
      } else {
        conflictedItems.add(
            new ConflictedItemDto(
                reportForEventProcess.getId(), REPORT, reportForEventProcess.getName()));
      }
      conflictedItems.addAll(
          reportRelationService.getConflictedItemsForDeleteReport(reportForEventProcess));
    }
    return new ConflictResponseDto(conflictedItems);
  }

  public boolean hasDeleteConflicts(final List<String> eventBasedProcessIds) {
    return eventBasedProcessIds.stream()
        .anyMatch(
            eventBasedProcessId ->
                !getDeleteConflictingItems(eventBasedProcessId).getConflictedItems().isEmpty());
  }

  public void bulkDeleteEventProcessMappings(final List<String> eventProcessMappingIds) {
    final List<String> eventProcessMappingsToDelete = new ArrayList<>();
    for (final String eventProcessMappingId : eventProcessMappingIds) {
      try {
        reportService.deleteAllReportsForProcessDefinitionKey(eventProcessMappingId);
        collectionWriter.deleteScopeEntryFromAllCollections(
            CollectionScopeEntryDto.convertTypeAndKeyToScopeEntryId(
                PROCESS, eventProcessMappingId));
        processVariableLabelService.deleteVariableLabelsForDefinition(eventProcessMappingId);
        eventProcessPublishStateWriter
            .markAsDeletedAllEventProcessPublishStatesForEventProcessMappingId(
                eventProcessMappingId);
        eventProcessMappingsToDelete.add(eventProcessMappingId);
      } catch (final OptimizeRuntimeException e) {
        log.debug(
            "There was an error while deleting resources associated to the event process mapping with id {}",
            eventProcessMappingId);
      }
    }
    eventProcessMappingWriter.deleteEventProcessMappings(eventProcessMappingsToDelete);
  }

  public EventProcessMappingDto getEventProcessMapping(final String eventProcessMappingId) {
    final Optional<EventProcessMappingDto> eventProcessMapping =
        eventProcessMappingReader.getEventProcessMapping(eventProcessMappingId);

    eventProcessMapping.ifPresent(
        eventProcessMappingDto ->
            assignState(
                eventProcessMappingDto,
                id ->
                    eventProcessPublishStateReader
                        .getEventProcessPublishStateByEventProcessId(id)
                        .orElse(null)));

    return eventProcessMapping.orElseThrow(
        () -> {
          final String message =
              String.format(
                  "Event-based process does not exist! Tried to retrieve event-based process with id: %s.",
                  eventProcessMappingId);
          return new NotFoundException(message);
        });
  }

  public List<EventProcessMappingDto> getAllEventProcessMappingsOmitXml(final String userId) {
    final Map<String, EventProcessPublishStateDto> allPublishedStates =
        eventProcessPublishStateReader
            .getAllEventProcessPublishStatesWithDeletedState(false)
            .stream()
            .collect(
                toMap(
                    EventProcessPublishStateDto::getProcessMappingId,
                    Function.identity(),
                    (mappingId1, mappingId2) -> mappingId2));

    List<EventProcessMappingDto> allEventProcessMappingsOmitXml =
        eventProcessMappingReader.getAllEventProcessMappingsOmitXml();

    allEventProcessMappingsOmitXml.forEach(
        eventProcessMappingDto -> assignState(eventProcessMappingDto, allPublishedStates::get));

    // filter by user authorization for event sources
    allEventProcessMappingsOmitXml =
        filterEventProcessMappingsByEventSourceAuthorizations(
            userId, allEventProcessMappingsOmitXml);

    return allEventProcessMappingsOmitXml;
  }

  public void publishEventProcessMapping(final String eventProcessMappingId) {
    final EventProcessMappingDto eventProcessMapping =
        getEventProcessMapping(eventProcessMappingId);

    if (!PUBLISHABLE_STATES.contains(eventProcessMapping.getState())) {
      throw new InvalidEventProcessStateException(
          "Cannot publish event-based process from state: " + eventProcessMapping.getState());
    }
    if (eventProcessMapping.getEventSources().isEmpty()) {
      throw new OptimizeValidationException(
          "Cannot publish event-based process with no data sources");
    }

    final EventProcessPublishStateDto processPublishState =
        EventProcessPublishStateDto.builder()
            .processMappingId(eventProcessMapping.getId())
            .name(eventProcessMapping.getName())
            .xml(eventProcessMapping.getXml())
            .publishDateTime(LocalDateUtil.getCurrentDateTime())
            .state(EventProcessState.PUBLISH_PENDING)
            .publishProgress(0.0D)
            .deleted(false)
            .mappings(eventProcessMapping.getMappings())
            .eventImportSources(
                createImportSourcesForEventSources(eventProcessMapping.getEventSources()))
            .build();

    final IdResponseDto processPublishStateId =
        eventProcessPublishStateWriter.createEventProcessPublishState(processPublishState);
    eventProcessPublishStateWriter
        .markAsDeletedPublishStatesForEventProcessMappingIdExcludingPublishStateId(
            eventProcessMappingId, processPublishStateId.getId());
  }

  public void cancelPublish(final String eventProcessMappingId) {
    final EventProcessMappingDto eventProcessMapping =
        getEventProcessMapping(eventProcessMappingId);

    if (!PUBLISH_CANCELABLE_STATES.contains(eventProcessMapping.getState())) {
      throw new InvalidEventProcessStateException(
          "Cannot cancel publishing of event-based process from state: "
              + eventProcessMapping.getState());
    }

    final boolean publishWasCanceledSuccessfully =
        eventProcessPublishStateWriter
            .markAsDeletedAllEventProcessPublishStatesForEventProcessMappingId(
                eventProcessMappingId);

    if (!publishWasCanceledSuccessfully) {
      final String message =
          String.format(
              "Cannot cancel publishing of an event-based process with key [%s] as it is not published yet.",
              eventProcessMappingId);
      throw new OptimizeValidationException(message);
    }
  }

  public void validateEventSources(
      final String userId, final List<EventSourceEntryDto<?>> eventSources) {
    if (eventSources == null || eventSources.contains(null)) {
      throw new OptimizeValidationException("Sources for an event-based process cannot be null");
    }
    final Map<EventSourceType, List<EventSourceEntryDto<?>>> sourceByType =
        eventSources.stream().collect(groupingBy(EventSourceEntryDto::getSourceType));
    final List<ExternalEventSourceEntryDto> externalSources =
        sourceByType.getOrDefault(EventSourceType.EXTERNAL, Collections.emptyList()).stream()
            .map(ExternalEventSourceEntryDto.class::cast)
            .toList();
    validateCompatibleExternalEventSources(externalSources);
  }

  public static void validateCompatibleExternalEventSources(
      final List<ExternalEventSourceEntryDto> externalSources) {
    final Map<Boolean, List<ExternalEventSourceEntryDto>> externalSourcesByIncludeAllGroups =
        externalSources.stream()
            .collect(groupingBy(source -> source.getConfiguration().isIncludeAllGroups()));
    final List<ExternalEventSourceEntryDto> sourcesForAllGroups =
        externalSourcesByIncludeAllGroups.getOrDefault(true, Collections.emptyList());
    if (sourcesForAllGroups.size() > 1) {
      throw new OptimizeValidationException(
          "Only one external event source with all groups selected can be used for event mappings");
    }
    if (sourcesForAllGroups.stream()
        .anyMatch(source -> source.getConfiguration().getGroup() != null)) {
      throw new OptimizeValidationException(
          "An external event source for all groups cannot specify a group name");
    }
    final List<ExternalEventSourceEntryDto> sourcesForSpecificGroups =
        externalSourcesByIncludeAllGroups.getOrDefault(false, Collections.emptyList());
    if (!sourcesForAllGroups.isEmpty() && !sourcesForSpecificGroups.isEmpty()) {
      throw new OptimizeValidationException(
          String.format(
              "External event sources for a specified group cannot be selected if all groups are also "
                  + "included as an event source. Individual groups selected: %s",
              sourcesForSpecificGroups));
    }
    final List<String> duplicateGroups =
        sourcesForSpecificGroups.stream()
            .map(EventSourceEntryDto::getSourceIdentifier)
            .collect(groupingBy(Function.identity(), counting()))
            .entrySet()
            .stream()
            .filter(countPerGroup -> countPerGroup.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();
    if (!duplicateGroups.isEmpty()) {
      throw new OptimizeValidationException(
          String.format(
              "The following group identifiers were supplied for more than one external event source: %s",
              sourcesForSpecificGroups));
    }
  }

  private IdResponseDto autogenerateEventProcessMapping(
      final String userId, final EventProcessMappingRequestDto autogenerationRequest) {
    final List<EventSourceEntryDto<?>> eventSources = autogenerationRequest.getEventSources();
    if (eventSources.isEmpty()) {
      throw new OptimizeValidationException("Autogeneration requires at least one event source");
    }
    final EventProcessMappingDto eventProcessMappingDto =
        EventProcessMappingDto.builder()
            .name(DEFAULT_AUTOGENERATED_PROCESS_NAME)
            .lastModifier(userId)
            .eventSources(eventSources)
            .roles(
                Collections.singletonList(
                    new EventProcessRoleRequestDto(new IdentityDto(userId, IdentityType.USER))))
            .build();
    validateEventSources(userId, eventProcessMappingDto.getEventSources());
    validateAutogenerationEventSources(eventProcessMappingDto.getEventSources());
    validateAutogenerationEventSourceScopes(eventProcessMappingDto);
    if (eventSources.stream()
            .anyMatch(source -> source.getSourceType().equals(EventSourceType.EXTERNAL))
        && !configurationService.getEventBasedProcessConfiguration().getEventImport().isEnabled()) {
      throw new OptimizeValidationException(
          "Cannot autogenerate process models using external sources unless event import is enabled");
    }

    final AutogenerationProcessModelDto autogeneratedProcessModelDto =
        autogenerationProcessModelService.generateModelFromEventSources(eventSources);
    eventProcessMappingDto.setXml(autogeneratedProcessModelDto.getXml());
    eventProcessMappingDto.setMappings(autogeneratedProcessModelDto.getMappings());
    validateMappingsAndXmlCompatibility(eventProcessMappingDto);
    return eventProcessMappingWriter.createEventProcessMapping(eventProcessMappingDto);
  }

  private List<EventImportSourceDto> createImportSourcesForEventSources(
      final List<EventSourceEntryDto<?>> eventSources) {
    final Map<EventSourceType, List<EventSourceEntryDto<?>>> sourcesByType =
        eventSources.stream().collect(groupingBy(EventSourceEntryDto::getSourceType));
    final List<EventImportSourceDto> importSources = new ArrayList<>();
    Optional.ofNullable(sourcesByType.get(EventSourceType.EXTERNAL))
        .ifPresent(
            externalSources -> {
              final List<ExternalEventSourceEntryDto> externalSourceConfigs =
                  externalSources.stream().map(ExternalEventSourceEntryDto.class::cast).toList();
              importSources.add(createEventImportSourceFromExternalSources(externalSourceConfigs));
            });
    return importSources;
  }

  private EventImportSourceDto createEventImportSourceFromExternalSources(
      final List<ExternalEventSourceEntryDto> externalSources) {
    final boolean includeAllGroups =
        externalSources.stream()
            .map(EventSourceEntryDto::getConfiguration)
            .anyMatch(ExternalEventSourceConfigDto::isIncludeAllGroups);
    final Pair<Optional<OffsetDateTime>, Optional<OffsetDateTime>> minAndMaxEventTimestamps;
    if (includeAllGroups) {
      minAndMaxEventTimestamps = externalEventService.getMinAndMaxIngestedTimestampsForAllEvents();
    } else {
      final List<String> groups =
          externalSources.stream()
              .map(EventSourceEntryDto::getConfiguration)
              .map(ExternalEventSourceConfigDto::getGroup)
              .toList();
      minAndMaxEventTimestamps =
          externalEventService.getMinAndMaxIngestedTimestampsForGroups(groups);
    }
    return EventImportSourceDto.builder()
        .firstEventForSourceAtTimeOfPublishTimestamp(
            minAndMaxEventTimestamps.getLeft().orElse(getEpochMilliTimestamp()))
        .lastEventForSourceAtTimeOfPublishTimestamp(
            minAndMaxEventTimestamps.getRight().orElse(getEpochMilliTimestamp()))
        .lastImportedEventTimestamp(
            minAndMaxEventTimestamps.getLeft().orElse(getEpochMilliTimestamp()))
        .eventImportSourceType(EventSourceType.EXTERNAL)
        .eventSourceConfigurations(
            externalSources.stream().map(EventSourceEntryDto::getConfiguration).collect(toList()))
        .build();
  }

  private OffsetDateTime getEpochMilliTimestamp() {
    return OffsetDateTime.ofInstant(Instant.ofEpochMilli(0), ZoneId.systemDefault());
  }

  private List<EventProcessMappingDto> filterEventProcessMappingsByEventSourceAuthorizations(
      final String userId, final List<EventProcessMappingDto> eventMappings) {
    // only return mappings where the user has access to all event sources
    return eventMappings.stream()
        .filter(
            eventMapping ->
                eventMapping.getEventSources().stream()
                    .allMatch(eventSource -> validateEventSourceAuthorisation(eventSource)))
        .toList();
  }

  private void validateAutogenerationEventSources(final List<EventSourceEntryDto<?>> eventSources) {
    // Autogeneration is only supported with a single external event source that includes all events
    final Map<Boolean, List<ExternalEventSourceEntryDto>> externalSourcesByIncludeAll =
        eventSources.stream()
            .filter(ExternalEventSourceEntryDto.class::isInstance)
            .map(ExternalEventSourceEntryDto.class::cast)
            .collect(groupingBy(source -> source.getConfiguration().isIncludeAllGroups()));
    if (externalSourcesByIncludeAll.containsKey(false)) {
      throw new OptimizeValidationException(
          "External event sources specific to a group cannot be used for generation");
    }
  }

  private void validateAutogenerationEventSourceScopes(
      final EventProcessMappingDto eventProcessMappingDto) {
    eventProcessMappingDto
        .getEventSources()
        .forEach(
            eventSource -> {
              final List<EventScopeType> eventScope =
                  eventSource.getConfiguration().getEventScope();
              if (eventScope.size() > 1) {
                throw new OptimizeValidationException(
                    "Event sources can only have a single event scope for autogeneration");
              }
              if (eventSource.getSourceType() == null) {
                throw new OptimizeValidationException("Event sources must have a specified type");
              }
              if (EventSourceType.EXTERNAL.equals(eventSource.getSourceType())
                  && !eventScope.contains(EventScopeType.ALL)) {
                throw new OptimizeValidationException(
                    String.format(
                        "An external event source must have an event scope of type %s for autogeneration",
                        EventScopeType.ALL));
              }
            });
  }

  private boolean validateEventSourceAuthorisation(final EventSourceEntryDto<?> eventSource) {
    return EventSourceType.EXTERNAL.equals(eventSource.getSourceType());
  }

  private void assignState(
      final EventProcessMappingDto eventProcessMappingDto,
      final Function<String, EventProcessPublishStateDto> publishStateReader) {
    if (MapUtils.isEmpty(eventProcessMappingDto.getMappings())) {
      eventProcessMappingDto.setState(EventProcessState.UNMAPPED);
    } else {
      eventProcessMappingDto.setState(EventProcessState.MAPPED);
    }

    Optional.ofNullable(publishStateReader.apply(eventProcessMappingDto.getId()))
        .ifPresent(
            processPublishStateDto -> {
              if (processPublishStateDto
                  .getPublishDateTime()
                  .isAfter(eventProcessMappingDto.getLastModified())) {
                eventProcessMappingDto.setState(processPublishStateDto.getState());
                eventProcessMappingDto.setPublishingProgress(
                    processPublishStateDto.getPublishProgress());
              } else {
                eventProcessMappingDto.setState(EventProcessState.UNPUBLISHED_CHANGES);
              }
            });
  }

  private void validateMappingsAndXmlCompatibility(
      final EventProcessMappingDto eventProcessMappingDto) {
    final Optional<BpmnModelInstance> modelInstance =
        Optional.ofNullable(eventProcessMappingDto.getXml()).map(this::parseXmlIntoBpmnModel);

    final Set<String> flowNodeIds =
        modelInstance
            .map(
                instance ->
                    extractFlowNodeData(instance).stream()
                        .map(FlowNodeDataDto::getId)
                        .collect(toSet()))
            .orElse(Collections.emptySet());

    final Map<String, EventMappingDto> eventMappings = eventProcessMappingDto.getMappings();
    if (eventMappings != null) {
      if (!flowNodeIds.containsAll(eventMappings.keySet())) {
        throw new BadRequestException(
            "All Flow Node IDs for event mappings must exist within the provided XML");
      }
      if (eventMappings.entrySet().stream()
          .anyMatch(
              mapping ->
                  mapping.getValue().getStart() == null && mapping.getValue().getEnd() == null)) {
        throw new BadRequestException(
            "All Flow Node mappings provided must have a start and/or end event mapped");
      }
      final List<String> singleMappableEventIdsInModel =
          modelInstance.map(this::getSingleMappableEventIds).orElse(Collections.emptyList());

      if (eventMappings.entrySet().stream()
          .anyMatch(
              mapping ->
                  singleMappableEventIdsInModel.contains(mapping.getKey())
                      && mapping.getValue().getStart() != null
                      && mapping.getValue().getEnd() != null)) {
        throw new BadRequestException(
            "BPMN events must have only one of either a start and end mapping");
      }
    }
  }

  private List<String> getSingleMappableEventIds(final BpmnModelInstance model) {
    return model.getModelElementsByType(Event.class).stream().map(BaseElement::getId).toList();
  }

  private BpmnModelInstance parseXmlIntoBpmnModel(final String xmlString) {
    try {
      return BpmnModelUtil.parseBpmnModel(xmlString);
    } catch (final ModelParseException ex) {
      throw new BadRequestException("The provided xml is not valid", ex);
    }
  }
}
