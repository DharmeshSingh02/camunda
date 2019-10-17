/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.operate.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.camunda.operate.util.ConversionUtils.*;

import org.camunda.operate.exceptions.OperateRuntimeException;

public abstract class CollectionUtil {

  @SafeVarargs
  public static <T> List<T> throwAwayNullElements(T... array) {
    List<T> listOfNotNulls = new ArrayList<>();
    for (T o: array) {
      if (o != null) {
        listOfNotNulls.add(o);
      }
    }
    return listOfNotNulls;
  }
  
  @SuppressWarnings("unchecked")
  public static <T> List<T> withoutNulls(Collection<T> aCollection){
    if(aCollection!=null) {
      return filter(aCollection, obj -> obj != null);
    }
    return Collections.EMPTY_LIST;
  }

  public static <T, S> Map<T,List<S>> addToMap(Map<T, List<S>> map, T key, S value) {
    map.computeIfAbsent(key, k -> new ArrayList<S>()).add(value);
    return map;
  }
 
  public static Map<String, Object> asMap(Object ...keyValuePairs){
    if(keyValuePairs == null || keyValuePairs.length % 2 != 0) {
      throw new OperateRuntimeException("keyValuePairs should not be null and has a even length.");
    }
    Map<String,Object> result = new HashMap<String, Object>();
    for(int i=0;i<keyValuePairs.length-1;i+=2) {
      result.put(keyValuePairs[i].toString(), keyValuePairs[i+1]);
    }
    return result;
  }
  
  public static <S,T> List<T> map(Collection<S> sourceList,Function<S,T> mapper){
    return map(sourceList.stream(),mapper);
  }
  
  public static <S,T> List<T> map(S[] sourceArray, Function<S, T> mapper) {
    return map(Arrays.stream(sourceArray).parallel(),mapper);
  }
  
  public static <S,T> List<T> map(Stream<S> sequenceStream, Function<S, T> mapper) {
    return sequenceStream.map(mapper).collect(Collectors.toList());
  }
 
  public static <T> List<T> filter(Collection<T> collection, Predicate<T> predicate){
    return filter(collection.stream(),predicate);
  }
  
  public static <T> List<T> filter(Stream<T> filterStream, Predicate<T> predicate){
    return filterStream.filter(predicate).collect(Collectors.toList());
  }
  
  public static List<String> toSafeListOfStrings(Collection<?> aCollection){
      return map(withoutNulls(aCollection),obj -> obj.toString());
  }
  
  public static String[] toSafeArrayOfStrings(Collection<?> aCollection){
    return toSafeListOfStrings(aCollection).toArray(new String[]{});
  }
   
  public static List<String> toSafeListOfStrings(Object... objects){
    return toSafeListOfStrings(Arrays.asList(objects));
  }
  
  public static List<Long> toSafeListOfLongs(Collection<String> aCollection){
    return map(withoutNulls(aCollection),stringToLong);
  }
  
  public static <T> void addNotNull(Collection<T> collection, T object) {
    if (collection!= null && object != null) {
      collection.add(object);
    }
  }

  public static List<Integer> fromTo(int from, int to) {
    List<Integer> result = new ArrayList<>();
    for (int i = from; i <= to; i++) {
      result.add(i);
    }
    return result;
  }

  public static boolean isNotEmpty(Collection aCollection) {
    return aCollection!=null && !aCollection.isEmpty();
  }

  /**
   *
   * @param list
   * @param subsetCount
   * @param subsetId starts from 0
   * @param <E>
   * @return
   */
  public static <E> List<E> splitAndGetSublist(List<E> list, int subsetCount, int subsetId) {
    if (subsetId >= subsetCount) {
      return new ArrayList<>();
    }
    Integer size = list.size();
    int bucketSize = (int) Math.round((double) size / (double) subsetCount);
    int start = bucketSize * subsetId;
    int end;
    if (subsetId == subsetCount - 1) {
      end = size;
    } else {
      end = start + bucketSize;
    }
    return new ArrayList<>(list.subList(start, end));
  }

}
