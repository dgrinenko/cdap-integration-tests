/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.longrunning.txprune;

import co.cask.cdap.client.StreamClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.StreamNotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.proto.ConfigEntry;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.LongRunningTestBase;
import co.cask.cdap.test.MapReduceManager;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpResponse;
import co.cask.common.http.ObjectResponse;
import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.reflect.TypeToken;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class InvalidListPruneTest extends LongRunningTestBase<InvalidListPruneTestState> {
  private static final Logger LOG = LoggerFactory.getLogger(InvalidListPruneTest.class);
  private static final long MAX_EVENTS = 10000000;
  private static final int BATCH_SIZE = 5000;

  @Override
  public void deploy() throws Exception {
    deployApplication(getLongRunningNamespace(), InvalidTxGeneratorApp.class);
  }

  @Override
  public void start() throws Exception {
    // Nothing to start
  }

  @Override
  public void stop() throws Exception {
    // Nothing to stop
  }

  @Override
  public InvalidListPruneTestState getInitialState() {
    return new InvalidListPruneTestState(0, new HashMap<Integer, List<Long>>());
  }

  @Override
  public void awaitOperations(InvalidListPruneTestState state) throws Exception {
    int iteration = state.getIteration();

    // Wait for the previous mapreduce job to finish running
    ApplicationManager applicationManager = getApplicationManager();
    MapReduceManager mrManager = applicationManager.getMapReduceManager(InvalidTxGeneratorApp.InvalidMapReduce.MR_NAME);
    mrManager.waitForRuns(ProgramRunStatus.FAILED, iteration, 5, TimeUnit.MINUTES);

  }

  @Override
  public InvalidListPruneTestState verifyRuns(InvalidListPruneTestState state) throws Exception {
    // Verify that the invalid ids from the 5th iteration earlier have been pruned
    // TODO: This check has to be enhanced to take into account test iteration interval, prune interval, tx max lifetime
    int checkIteration = state.getIteration() - 5;
    Set<Long> removeIds = new HashSet<>();
    Map<Integer, List<Long>> newIterationState = new HashMap<>();
    for (Map.Entry<Integer, List<Long>> entry : state.getInvalidTxIds().entrySet()) {
      int iteration = entry.getKey();
      if (iteration < checkIteration) {
        removeIds.addAll(entry.getValue());
      } else {
        newIterationState.put(entry.getKey(), entry.getValue());
      }
    }

    Set<Long> currentInvalids = Sets.newHashSet(getInvalidList());
    Sets.SetView<Long> notRemovedIds = Sets.intersection(currentInvalids, removeIds);
    Assert.assertTrue("Expected the following invalid ids to be pruned: " + notRemovedIds, notRemovedIds.isEmpty());

    return new InvalidListPruneTestState(state.getIteration(), newIterationState);
  }

  @Override
  public InvalidListPruneTestState runOperations(InvalidListPruneTestState state) throws Exception {
    int iteration = state.getIteration() + 1;
    List<Long> invalidList = getInvalidList();

    flushAndCompactTables();

    truncateAndSendEvents(InvalidTxGeneratorApp.STREAM, iteration);

    // Now run the mapreduce job
    ApplicationManager applicationManager = getApplicationManager();
    applicationManager.getMapReduceManager(InvalidTxGeneratorApp.InvalidMapReduce.MR_NAME).start();

    // TODO: get the invalid transaction for the MR
    HashMap<Integer, List<Long>> invalidTxIds = Maps.newHashMap(state.getInvalidTxIds());
    invalidTxIds.put(iteration, invalidList);
    return new InvalidListPruneTestState(iteration, invalidTxIds);
  }

  private void truncateAndSendEvents(String stream, int iteration)
    throws IOException, StreamNotFoundException, UnauthenticatedException, UnauthorizedException {
    StreamClient streamClient = getStreamClient();
    streamClient.truncate(getLongRunningNamespace().stream(stream));

    // Create unique events for this iteration using the iteration id as part of the event
    StringWriter writer = new StringWriter();
    for (int i = 0; i < BATCH_SIZE; i++) {
      writer.write(String.format("%s", (iteration * MAX_EVENTS) + i));
      writer.write("\n");
    }

    // Throw an exception to create an invalid transaction
    writer.write(InvalidTxGeneratorApp.EXCEPTION_STRING);
    writer.write("\n");
    LOG.info("Writing {} events in one batch to stream {}", BATCH_SIZE + 1, stream);
    streamClient.sendBatch(getLongRunningNamespace().stream(stream), "text/plain",
                           ByteStreams.newInputStreamSupplier(writer.toString().getBytes(Charsets.UTF_8)));
  }

  private ApplicationManager getApplicationManager() throws Exception {
    return getApplicationManager(getLongRunningNamespace().app(InvalidTxGeneratorApp.APP_NAME));
  }

  private void flushAndCompactTables() throws IOException, UnauthorizedException, UnauthenticatedException {
    Connection connection = ConnectionFactory.createConnection(getHBaseConf());
    LOG.info("Using connection: {}", connection.getConfiguration().get("hbase.zookeeper.quorum"));
    HBaseAdmin admin = new HBaseAdmin(connection);
    HTableDescriptor[] descriptors = admin.listTables();
    for (HTableDescriptor descriptor : descriptors) {
      LOG.info("Flushing table {}", descriptor.getTableName());
      admin.flush(descriptor.getTableName());
//        admin.compact(descriptor.getTableName());
      LOG.info("Major compacting table {}", descriptor.getTableName());
      admin.majorCompact(descriptor.getTableName());
//        admin.flush(descriptor.getTableName());
    }
  }

  private Configuration getHBaseConf() throws UnauthorizedException, IOException, UnauthenticatedException {
    Configuration conf = new Configuration();
    conf.clear();
    for (Map.Entry<String, ConfigEntry> entry : getMetaClient().getHadoopConfig().entrySet()) {
      conf.set(entry.getKey(), entry.getValue().getValue());
    }
    return conf;
  }


  private List<Long> getInvalidList() throws IOException, UnauthorizedException, UnauthenticatedException {
    RESTClient restClient = getRestClient();
    ClientConfig config = getClientConfig();
    HttpResponse response = restClient.execute(HttpMethod.GET, config.resolveURL("transactions/invalid"),
                                               config.getAccessToken());
    return ObjectResponse.fromJsonBody(response, new TypeToken<List<Long>>() { }).getResponseObject();
  }
}