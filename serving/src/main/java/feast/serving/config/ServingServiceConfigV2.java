/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.serving.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import feast.serving.service.OnlineServingServiceV2;
import feast.serving.service.ServingServiceV2;
import feast.serving.specs.CachedSpecService;
import feast.storage.api.retriever.OnlineRetrieverV2;
import feast.storage.connectors.bigtable.retriever.BigTableOnlineRetriever;
import feast.storage.connectors.bigtable.retriever.BigTableStoreConfig;
import feast.storage.connectors.cassandra.retriever.CassandraOnlineRetriever;
import feast.storage.connectors.cassandra.retriever.CassandraStoreConfig;
import feast.storage.connectors.redis.retriever.*;
import io.opentracing.Tracer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class ServingServiceConfigV2 {
  private static final Logger log = org.slf4j.LoggerFactory.getLogger(ServingServiceConfigV2.class);

  @Autowired private ApplicationContext context;

  @Bean
  @Lazy(true)
  public BigtableDataClient bigtableClient(FeastProperties feastProperties) throws IOException {
    BigTableStoreConfig config = feastProperties.getActiveStore().getBigtableConfig();
    String projectId = config.getProjectId();
    String instanceId = config.getInstanceId();

    return BigtableDataClient.create(
        BigtableDataSettings.newBuilder()
            .setProjectId(projectId)
            .setInstanceId(instanceId)
            .build());
  }

  @Bean
  public ServingServiceV2 servingServiceV2(
      FeastProperties feastProperties, CachedSpecService specService, Tracer tracer) {
    ServingServiceV2 servingService = null;
    FeastProperties.Store store = feastProperties.getActiveStore();

    switch (store.getType()) {
      case REDIS_CLUSTER:
        RedisClientAdapter redisClusterClient =
            RedisClusterClient.create(store.getRedisClusterConfig());
        OnlineRetrieverV2 redisClusterRetriever = new OnlineRetriever(redisClusterClient);
        servingService = new OnlineServingServiceV2(redisClusterRetriever, specService, tracer);
        break;
      case REDIS:
        RedisClientAdapter redisClient = RedisClient.create(store.getRedisConfig());
        OnlineRetrieverV2 redisRetriever = new OnlineRetriever(redisClient);
        servingService = new OnlineServingServiceV2(redisRetriever, specService, tracer);
        break;
      case BIGTABLE:
        BigtableDataClient bigtableClient = context.getBean(BigtableDataClient.class);
        OnlineRetrieverV2 bigtableRetriever = new BigTableOnlineRetriever(bigtableClient);
        servingService = new OnlineServingServiceV2(bigtableRetriever, specService, tracer);
        break;
      case CASSANDRA:
        CassandraStoreConfig config = feastProperties.getActiveStore().getCassandraConfig();
        String connectionString = config.getConnectionString();
        String dataCenter = config.getDataCenter();
        String keySpace = config.getKeySpace();

        List<InetSocketAddress> contactPoints =
            Arrays.stream(connectionString.split(","))
                .map(String::trim)
                .map(cs -> cs.split(":"))
                .map(
                    hostPort -> {
                      int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9042;
                      return new InetSocketAddress(hostPort[0], port);
                    })
                .collect(Collectors.toList());

        CqlSession session =
            new CqlSessionBuilder()
                .addContactPoints(contactPoints)
                .withLocalDatacenter(dataCenter)
                .withKeyspace(keySpace)
                .build();
        OnlineRetrieverV2 cassandraRetriever = new CassandraOnlineRetriever(session);
        servingService = new OnlineServingServiceV2(cassandraRetriever, specService, tracer);
        break;
    }

    return servingService;
  }
}
