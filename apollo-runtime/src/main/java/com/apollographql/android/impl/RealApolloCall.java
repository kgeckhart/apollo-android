package com.apollographql.android.impl;

import com.apollographql.android.ApolloCall;
import com.apollographql.android.CustomTypeAdapter;
import com.apollographql.android.api.graphql.Operation;
import com.apollographql.android.api.graphql.Response;
import com.apollographql.android.api.graphql.ResponseFieldMapper;
import com.apollographql.android.api.graphql.ScalarType;
import com.apollographql.android.cache.http.HttpCache;
import com.apollographql.android.cache.http.HttpCacheControl;
import com.apollographql.android.cache.normalized.Cache;
import com.apollographql.android.cache.normalized.CacheControl;
import com.apollographql.android.internal.ApolloLogger;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.Call;
import okhttp3.HttpUrl;

import static com.apollographql.android.api.graphql.util.Utils.checkNotNull;

@SuppressWarnings("WeakerAccess") final class RealApolloCall<T> implements ApolloCall<T> {
  final Operation operation;
  final HttpUrl serverUrl;
  final Call.Factory httpCallFactory;
  final HttpCache httpCache;
  final HttpCacheControl httpCacheControl;
  final Moshi moshi;
  final ResponseFieldMapper responseFieldMapper;
  final Map<ScalarType, CustomTypeAdapter> customTypeAdapters;
  final Cache cache;
  final CacheControl cacheControl;
  final ApolloInterceptorChain interceptorChain;
  final ExecutorService dispatcher;
  final ApolloLogger logger;
  volatile boolean executed;

  RealApolloCall(Operation operation, HttpUrl serverUrl, Call.Factory httpCallFactory, HttpCache httpCache,
      HttpCacheControl httpCacheControl, Moshi moshi, ResponseFieldMapper responseFieldMapper,
      Map<ScalarType, CustomTypeAdapter> customTypeAdapters, Cache cache, CacheControl cacheControl,
      ExecutorService dispatcher, ApolloLogger logger) {
    this.operation = operation;
    this.serverUrl = serverUrl;
    this.httpCallFactory = httpCallFactory;
    this.httpCache = httpCache;
    this.httpCacheControl = httpCacheControl;
    this.moshi = moshi;
    this.responseFieldMapper = responseFieldMapper;
    this.customTypeAdapters = customTypeAdapters;
    this.cache = cache;
    this.cacheControl = cacheControl;
    this.dispatcher = dispatcher;
    this.logger = logger;

    interceptorChain = new RealApolloInterceptorChain(operation, Arrays.asList(
        new ApolloCacheInterceptor(cache, cacheControl, responseFieldMapper, customTypeAdapters, dispatcher, logger),
        new ApolloParseInterceptor(httpCache, cache.networkResponseNormalizer(), responseFieldMapper,
            customTypeAdapters, logger),
        new ApolloServerInterceptor(serverUrl, httpCallFactory, httpCacheControl, false, moshi)
    ));
  }

  @SuppressWarnings("unchecked") @Nonnull @Override public Response<T> execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    return interceptorChain.proceed().parsedResponse.get();
  }

  @Override public void enqueue(@Nullable final Callback<T> callback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }

    interceptorChain.proceedAsync(dispatcher, new ApolloInterceptor.CallBack() {
      @Override public void onResponse(@Nonnull ApolloInterceptor.InterceptorResponse response) {
        if (callback != null) {
          //noinspection unchecked
          callback.onResponse(response.parsedResponse.get());
        }
      }

      @Override public void onFailure(@Nonnull Throwable t) {
        if (callback != null) {
          callback.onFailure(t);
        }
      }
    });
  }

  @Nonnull @Override public RealApolloWatcher<T> watcher() {
    return new RealApolloWatcher<>(clone(), cache);
  }

  @Nonnull @Override public RealApolloCall<T> httpCacheControl(@Nonnull HttpCacheControl httpCacheControl) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
    }
    return new RealApolloCall<>(operation, serverUrl, httpCallFactory, httpCache,
        checkNotNull(httpCacheControl, "httpCacheControl == null"), moshi, responseFieldMapper, customTypeAdapters,
        cache, cacheControl, dispatcher, logger);
  }

  @Nonnull @Override public RealApolloCall<T> cacheControl(@Nonnull CacheControl cacheControl) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
    }
    return new RealApolloCall<>(operation, serverUrl, httpCallFactory, httpCache, httpCacheControl, moshi,
        responseFieldMapper, customTypeAdapters, cache, checkNotNull(cacheControl, "cacheControl == null"),
        dispatcher, logger);
  }

  @Override public void cancel() {
    interceptorChain.dispose();
  }

  @Override @Nonnull public RealApolloCall<T> clone() {
    return new RealApolloCall<>(operation, serverUrl, httpCallFactory, httpCache, httpCacheControl, moshi,
        responseFieldMapper, customTypeAdapters, cache, cacheControl, dispatcher, logger);
  }
}