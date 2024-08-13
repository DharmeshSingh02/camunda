/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.gateway.rest.controller;

import io.camunda.service.DocumentServices;
import io.camunda.service.DocumentServices.DocumentReferenceResponse;
import io.camunda.zeebe.gateway.protocol.rest.DocumentLinkRequest;
import io.camunda.zeebe.gateway.protocol.rest.DocumentMetadata;
import io.camunda.zeebe.gateway.protocol.rest.DocumentReference;
import io.camunda.zeebe.gateway.rest.RequestMapper;
import io.camunda.zeebe.gateway.rest.ResponseMapper;
import io.camunda.zeebe.gateway.rest.RestErrorMapper;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@CamundaRestController
@RequestMapping("/v2/documents")
public class DocumentController {

  private final DocumentServices documentServices;

  @Autowired
  public DocumentController(final DocumentServices documentServices) {
    this.documentServices = documentServices;
  }

  @PostMapping
  public CompletableFuture<ResponseEntity<Object>> createDocument(
      @RequestParam(required = false) String documentId,
      @RequestParam(required = false) String storeId,
      @RequestPart("file") MultipartFile file,
      @RequestPart(value = "metadata", required = false) DocumentMetadata metadata) {

    return RequestMapper.toDocumentCreateRequest(documentId, storeId, file, metadata)
        .fold(RestErrorMapper::mapProblemToCompletedResponse, this::createDocument);
  }

  private CompletableFuture<ResponseEntity<Object>> createDocument(
      final DocumentServices.DocumentCreateRequest request) {

    return RequestMapper.executeServiceMethod(
        () -> documentServices
            .withAuthentication(RequestMapper.getAuthentication())
            .createDocument(request),
        ResponseMapper::toDocumentReference);
  }

  @GetMapping("/{documentId}")
  public CompletableFuture<ResponseEntity<Object>> getDocumentContent(
      @PathVariable String documentId, @RequestParam(required = false) String storeId) {

    return RequestMapper.executeServiceMethod(
        () -> documentServices
            .withAuthentication(RequestMapper.getAuthentication())
            .getDocumentContent(documentId, storeId),
        documentContentInputStream -> {
          final StreamingResponseBody body = documentContentInputStream::transferTo;
          return new ResponseEntity<>(body, HttpStatus.OK);
        });
  }

  @DeleteMapping("/{documentId}")
  public CompletableFuture<ResponseEntity<Object>> deleteDocument(
      @PathVariable String documentId, @RequestParam(required = false) String storeId) {

    return RequestMapper.executeServiceMethodWithNoContentResult(
        () -> documentServices
            .withAuthentication(RequestMapper.getAuthentication())
            .deleteDocument(documentId, storeId));
  }

  @PostMapping("/{documentId}/links")
  public ResponseEntity<Void> createDocumentLink(
      @PathVariable String documentId, @RequestBody DocumentLinkRequest linkRequest) {

    // TODO: implement
    throw new UnsupportedOperationException("Not yet implemented");
  }


}
