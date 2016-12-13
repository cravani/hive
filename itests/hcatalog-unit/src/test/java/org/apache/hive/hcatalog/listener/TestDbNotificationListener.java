/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/
package org.apache.hive.hcatalog.listener;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import org.apache.hadoop.hive.metastore.api.Function;
import org.apache.hadoop.hive.metastore.api.FunctionType;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.Order;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.ResourceType;
import org.apache.hadoop.hive.metastore.api.ResourceUri;
import org.apache.htrace.fasterxml.jackson.core.JsonFactory;
import org.apache.htrace.fasterxml.jackson.core.JsonParser;
import org.apache.htrace.fasterxml.jackson.databind.JsonNode;
import org.apache.htrace.fasterxml.jackson.databind.ObjectMapper;
import org.apache.htrace.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.FireEventRequest;
import org.apache.hadoop.hive.metastore.api.FireEventRequestData;
import org.apache.hadoop.hive.metastore.api.InsertEventRequestData;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.hcatalog.common.HCatConstants;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestDbNotificationListener {
  private static final Logger LOG = LoggerFactory.getLogger(TestDbNotificationListener.class.getName());
  private static final int EVENTS_TTL = 30;
  private static final int CLEANUP_SLEEP_TIME = 10;
  private static Map<String, String> emptyParameters = new HashMap<String, String>();
  private static IMetaStoreClient msClient;
  private static Driver driver;
  private int startTime;
  private long firstEventId;

  @SuppressWarnings("rawtypes")
  @BeforeClass
  public static void connectToMetastore() throws Exception {
    HiveConf conf = new HiveConf();
    conf.setVar(HiveConf.ConfVars.METASTORE_TRANSACTIONAL_EVENT_LISTENERS,
        DbNotificationListener.class.getName());
    conf.setVar(HiveConf.ConfVars.METASTORE_EVENT_DB_LISTENER_TTL, String.valueOf(EVENTS_TTL) + "s");
    conf.setBoolVar(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY, false);
    conf.setBoolVar(HiveConf.ConfVars.FIRE_EVENTS_FOR_DML, true);
    conf.setVar(HiveConf.ConfVars.DYNAMICPARTITIONINGMODE, "nonstrict");
    Class dbNotificationListener =
        Class.forName("org.apache.hive.hcatalog.listener.DbNotificationListener");
    Class[] classes = dbNotificationListener.getDeclaredClasses();
    for (Class c : classes) {
      if (c.getName().endsWith("CleanerThread")) {
        Field sleepTimeField = c.getDeclaredField("sleepTime");
        sleepTimeField.setAccessible(true);
        sleepTimeField.set(null, CLEANUP_SLEEP_TIME * 1000);
      }
    }
    conf.setVar(HiveConf.ConfVars.METASTORE_RAW_STORE_IMPL, DummyRawStoreFailEvent.class.getName());
    conf
    .setVar(HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER,
        "org.apache.hadoop.hive.ql.security.authorization.plugin.sqlstd.SQLStdHiveAuthorizerFactory");
    SessionState.start(new CliSessionState(conf));
    msClient = new HiveMetaStoreClient(conf);
    driver = new Driver(conf);
  }

  @Before
  public void setup() throws Exception {
    long now = System.currentTimeMillis() / 1000;
    startTime = 0;
    if (now > Integer.MAX_VALUE) fail("Bummer, time has fallen over the edge");
    else startTime = (int) now;
    firstEventId = msClient.getCurrentNotificationEventId().getEventId();
    DummyRawStoreFailEvent.setEventSucceed(true);
  }

  @Test
  public void createDatabase() throws Exception {
    Database db = new Database("mydb", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_CREATE_DATABASE_EVENT, event.getEventType());
    assertEquals("mydb", event.getDbName());
    assertNull(event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"CREATE_DATABASE\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"mydb\",\"timestamp\":[0-9]+}"));

    DummyRawStoreFailEvent.setEventSucceed(false);
    db = new Database("mydb2", "no description", "file:/tmp", emptyParameters);
    try {
      msClient.createDatabase(db);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());
  }

  @Test
  public void dropDatabase() throws Exception {
    Database db = new Database("dropdb", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    msClient.dropDatabase("dropdb");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_DROP_DATABASE_EVENT, event.getEventType());
    assertEquals("dropdb", event.getDbName());
    assertNull(event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"DROP_DATABASE\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"dropdb\",\"timestamp\":[0-9]+}"));

    db = new Database("dropdb", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.dropDatabase("dropdb");
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
  }

  @Test
  public void createTable() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("mytable", "default", "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_CREATE_TABLE_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("mytable", event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"CREATE_TABLE\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":\"mytable\",\"timestamp\":[0-9]+}"));

    table = new Table("mytable2", "default", "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.createTable(table);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());
  }

  @Test
  public void alterTable() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("alttable", "default", "me", startTime, startTime, 0, sd,
        new ArrayList<FieldSchema>(), emptyParameters, null, null, null);
    msClient.createTable(table);

    cols.add(new FieldSchema("col2", "int", ""));
    table = new Table("alttable", "default", "me", startTime, startTime, 0, sd,
        new ArrayList<FieldSchema>(), emptyParameters, null, null, null);
    msClient.alter_table("default", "alttable", table);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_ALTER_TABLE_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("alttable", event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"ALTER_TABLE\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":\"alttable\"," +
        "\"timestamp\":[0-9]+}"));

    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.alter_table("default", "alttable", table);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());
  }

  @Test
  public void dropTable() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("droptable", "default", "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);
    msClient.dropTable("default", "droptable");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_DROP_TABLE_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("droptable", event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"DROP_TABLE\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":" +
        "\"droptable\",\"timestamp\":[0-9]+}"));

    table = new Table("droptable2", "default", "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.dropTable("default", "droptable2");
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
  }

  @Test
  public void addPartition() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    List<FieldSchema> partCols = new ArrayList<FieldSchema>();
    partCols.add(new FieldSchema("ds", "string", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("addPartTable", "default", "me", startTime, startTime, 0, sd, partCols,
        emptyParameters, null, null, null);
    msClient.createTable(table);

    Partition partition = new Partition(Arrays.asList("today"), "default", "addPartTable",
        startTime, startTime, sd, emptyParameters);
    msClient.add_partition(partition);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_ADD_PARTITION_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("addparttable", event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"ADD_PARTITION\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":" +
        "\"addparttable\",\"timestamp\":[0-9]+,\"partitions\":\\[\\{\"ds\":\"today\"}]}"));

    partition = new Partition(Arrays.asList("tomorrow"), "default", "tableDoesNotExist",
        startTime, startTime, sd, emptyParameters);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.add_partition(partition);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());
  }

  @Test
  public void alterPartition() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    List<FieldSchema> partCols = new ArrayList<FieldSchema>();
    partCols.add(new FieldSchema("ds", "string", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("alterparttable", "default", "me", startTime, startTime, 0, sd,
        partCols, emptyParameters, null, null, null);
    msClient.createTable(table);

    Partition partition = new Partition(Arrays.asList("today"), "default", "alterparttable",
        startTime, startTime, sd, emptyParameters);
    msClient.add_partition(partition);

    Partition newPart = new Partition(Arrays.asList("today"), "default", "alterparttable",
        startTime, startTime + 1, sd, emptyParameters);
    msClient.alter_partition("default", "alterparttable", newPart, null);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_ALTER_PARTITION_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("alterparttable", event.getTableName());
    assertTrue(event.getMessage(),
        event.getMessage().matches("\\{\"eventType\":\"ALTER_PARTITION\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":\"alterparttable\"," +
        "\"timestamp\":[0-9]+,\"keyValues\":\\{\"ds\":\"today\"}}"));

    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.alter_partition("default", "alterparttable", newPart, null);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
  }

  @Test
  public void dropPartition() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    List<FieldSchema> partCols = new ArrayList<FieldSchema>();
    partCols.add(new FieldSchema("ds", "string", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("dropPartTable", "default", "me", startTime, startTime, 0, sd, partCols,
        emptyParameters, null, null, null);
    msClient.createTable(table);

    Partition partition = new Partition(Arrays.asList("today"), "default", "dropPartTable",
        startTime, startTime, sd, emptyParameters);
    msClient.add_partition(partition);

    msClient.dropPartition("default", "dropparttable", Arrays.asList("today"), false);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_DROP_PARTITION_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("dropparttable", event.getTableName());
    assertTrue(event.getMessage().matches("\\{\"eventType\":\"DROP_PARTITION\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":" +
        "\"dropparttable\",\"timestamp\":[0-9]+,\"partitions\":\\[\\{\"ds\":\"today\"}]}"));

    partition = new Partition(Arrays.asList("tomorrow"), "default", "dropPartTable",
        startTime, startTime, sd, emptyParameters);
      msClient.add_partition(partition);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.dropPartition("default", "dropparttable", Arrays.asList("tomorrow"), false);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(4, rsp.getEventsSize());
  }

  @Test
  public void createFunction() throws Exception {
    String funcName = "createFunction";
    String dbName = "default";
    String ownerName = "me";
    String funcClass = "o.a.h.h.myfunc";
    String funcResource = "file:/tmp/somewhere";
    Function func = new Function(funcName, dbName, funcClass, ownerName, PrincipalType.USER,
        startTime, FunctionType.JAVA, Arrays.asList(new ResourceUri(ResourceType.JAR,
        funcResource)));
    msClient.createFunction(func);
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_CREATE_FUNCTION_EVENT, event.getEventType());
    assertEquals(dbName, event.getDbName());
    Function funcObj = getFunctionObj(getJsonTree(event));
    assertEquals(dbName, funcObj.getDbName());
    assertEquals(funcName, funcObj.getFunctionName());
    assertEquals(funcClass, funcObj.getClassName());
    assertEquals(ownerName, funcObj.getOwnerName());
    assertEquals(FunctionType.JAVA, funcObj.getFunctionType());
    assertEquals(1, funcObj.getResourceUrisSize());
    assertEquals(ResourceType.JAR, funcObj.getResourceUris().get(0).getResourceType());
    assertEquals(funcResource, funcObj.getResourceUris().get(0).getUri());

    DummyRawStoreFailEvent.setEventSucceed(false);
    func = new Function("createFunction2", dbName, "o.a.h.h.myfunc2", "me", PrincipalType.USER,
        startTime, FunctionType.JAVA, Arrays.asList(new ResourceUri(ResourceType.JAR,
        "file:/tmp/somewhere2")));
    try {
      msClient.createFunction(func);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(1, rsp.getEventsSize());
  }

  @Test
  public void dropFunction() throws Exception {
    String funcName = "dropfunctiontest";
    String dbName = "default";
    String ownerName = "me";
    String funcClass = "o.a.h.h.dropFunctionTest";
    String funcResource = "file:/tmp/somewhere";
    Function func = new Function(funcName, dbName, funcClass, ownerName, PrincipalType.USER,
        startTime, FunctionType.JAVA, Arrays.asList(new ResourceUri(ResourceType.JAR,
        funcResource)));
    msClient.createFunction(func);
    msClient.dropFunction(dbName, funcName);
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_DROP_FUNCTION_EVENT, event.getEventType());
    assertEquals(dbName, event.getDbName());
    Function funcObj = getFunctionObj(getJsonTree(event));
    assertEquals(dbName, funcObj.getDbName());
    assertEquals(funcName, funcObj.getFunctionName());
    assertEquals(funcClass, funcObj.getClassName());
    assertEquals(ownerName, funcObj.getOwnerName());
    assertEquals(FunctionType.JAVA, funcObj.getFunctionType());
    assertEquals(1, funcObj.getResourceUrisSize());
    assertEquals(ResourceType.JAR, funcObj.getResourceUris().get(0).getResourceType());
    assertEquals(funcResource, funcObj.getResourceUris().get(0).getUri());

    func = new Function("dropfunctiontest2", dbName, "o.a.h.h.dropFunctionTest2", "me",
        PrincipalType.USER,  startTime, FunctionType.JAVA, Arrays.asList(
        new ResourceUri(ResourceType.JAR, "file:/tmp/somewhere2")));
    msClient.createFunction(func);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.dropFunction(dbName, "dropfunctiontest2");
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
  }

  @Test
  public void createIndex() throws Exception {
    String indexName = "createIndex";
    String dbName = "default";
    String tableName = "createIndexTable";
    String indexTableName = tableName + "__" + indexName + "__";
    int startTime = (int)(System.currentTimeMillis() / 1000);
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    Map<String, String> params = new HashMap<String, String>();
    params.put("key", "value");
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 17,
        serde, Arrays.asList("bucketcol"), Arrays.asList(new Order("sortcol", 1)), params);
    Table table = new Table(tableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);
    Index index = new Index(indexName, null, "default", tableName, startTime, startTime,
        indexTableName, sd, emptyParameters, false);
    Table indexTable = new Table(indexTableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createIndex(index, indexTable);
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_CREATE_INDEX_EVENT, event.getEventType());
    assertEquals(dbName, event.getDbName());
    Index indexObj = getIndexObj(getJsonTree(event));
    assertEquals(dbName, indexObj.getDbName());
    assertEquals(indexName, indexObj.getIndexName());
    assertEquals(tableName, indexObj.getOrigTableName());
    assertEquals(indexTableName, indexObj.getIndexTableName());

    DummyRawStoreFailEvent.setEventSucceed(false);
    index = new Index("createIndexTable2", null, "default", tableName, startTime, startTime,
        "createIndexTable2__createIndexTable2__", sd, emptyParameters, false);
    Table indexTable2 = new Table("createIndexTable2__createIndexTable2__", dbName, "me",
        startTime, startTime, 0, sd, null, emptyParameters, null, null, null);
    try {
      msClient.createIndex(index, indexTable2);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());
  }

  @Test
  public void dropIndex() throws Exception {
    String indexName = "dropIndex";
    String dbName = "default";
    String tableName = "dropIndexTable";
    String indexTableName = tableName + "__" + indexName + "__";
    int startTime = (int)(System.currentTimeMillis() / 1000);
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    Map<String, String> params = new HashMap<String, String>();
    params.put("key", "value");
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 17,
        serde, Arrays.asList("bucketcol"), Arrays.asList(new Order("sortcol", 1)), params);
    Table table = new Table(tableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);
    Index index = new Index(indexName, null, "default", tableName, startTime, startTime,
        indexTableName, sd, emptyParameters, false);
    Table indexTable = new Table(indexTableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createIndex(index, indexTable);
    msClient.dropIndex(dbName, tableName, indexName, true); // drops index and indexTable
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(4, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(3);
    assertEquals(firstEventId + 4, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_DROP_INDEX_EVENT, event.getEventType());
    assertEquals(dbName, event.getDbName());
    Index indexObj = getIndexObj(getJsonTree(event));
    assertEquals(dbName, indexObj.getDbName());
    assertEquals(indexName.toLowerCase(), indexObj.getIndexName());
    assertEquals(tableName.toLowerCase(), indexObj.getOrigTableName());
    assertEquals(indexTableName.toLowerCase(), indexObj.getIndexTableName());

    index = new Index("dropIndexTable2", null, "default", tableName, startTime, startTime,
        "dropIndexTable__dropIndexTable2__", sd, emptyParameters, false);
    Table indexTable2 = new Table("dropIndexTable__dropIndexTable2__", dbName, "me", startTime,
        startTime, 0, sd, null, emptyParameters, null, null, null);
    msClient.createIndex(index, indexTable2);
    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.dropIndex(dbName, tableName, "dropIndex2", true); // drops index and indexTable
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(6, rsp.getEventsSize());
  }

  @Test
  public void alterIndex() throws Exception {
    String indexName = "alterIndex";
    String dbName = "default";
    String tableName = "alterIndexTable";
    String indexTableName = tableName + "__" + indexName + "__";
    int startTime = (int)(System.currentTimeMillis() / 1000);
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    Map<String, String> params = new HashMap<String, String>();
    params.put("key", "value");
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 17,
        serde, Arrays.asList("bucketcol"), Arrays.asList(new Order("sortcol", 1)), params);
    Table table = new Table(tableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);
    Index oldIndex = new Index(indexName, null, "default", tableName, startTime, startTime,
        indexTableName, sd, emptyParameters, false);
    Table oldIndexTable = new Table(indexTableName, dbName, "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createIndex(oldIndex, oldIndexTable); // creates index and index table
    Index newIndex = new Index(indexName, null, "default", tableName, startTime, startTime + 1,
        indexTableName, sd, emptyParameters, false);
    msClient.alter_index(dbName, tableName, indexName, newIndex);
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(4, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(3);
    assertEquals(firstEventId + 4, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_ALTER_INDEX_EVENT, event.getEventType());
    assertEquals(dbName, event.getDbName());
    Index indexObj = getIndexObj(getJsonTree(event), "afterIndexObjJson");
    assertEquals(dbName, indexObj.getDbName());
    assertEquals(indexName, indexObj.getIndexName());
    assertEquals(tableName, indexObj.getOrigTableName());
    assertEquals(indexTableName, indexObj.getIndexTableName());
    assertTrue(indexObj.getCreateTime() < indexObj.getLastAccessTime());

    DummyRawStoreFailEvent.setEventSucceed(false);
    try {
      msClient.alter_index(dbName, tableName, indexName, newIndex);
    } catch (Exception ex) {
      // expected
    }

    rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(4, rsp.getEventsSize());
  }

  @Test
  public void insertTable() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("insertTable", "default", "me", startTime, startTime, 0, sd, null,
        emptyParameters, null, null, null);
    msClient.createTable(table);

    FireEventRequestData data = new FireEventRequestData();
    InsertEventRequestData insertData = new InsertEventRequestData();
    data.setInsertData(insertData);
    insertData.addToFilesAdded("/warehouse/mytable/b1");
    FireEventRequest rqst = new FireEventRequest(true, data);
    rqst.setDbName("default");
    rqst.setTableName("insertTable");
    msClient.fireListenerEvent(rqst);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("insertTable", event.getTableName());
    assertTrue(event.getMessage(),
        event.getMessage().matches("\\{\"eventType\":\"INSERT\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":" +
        "\"insertTable\",\"timestamp\":[0-9]+,\"files\":\\[\"/warehouse/mytable/b1\"]," +
        "\"partKeyVals\":\\{},\"partitionKeyValues\":\\{}}"));
  }

  @Test
  public void insertPartition() throws Exception {
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema("col1", "int", "nocomment"));
    List<FieldSchema> partCols = new ArrayList<FieldSchema>();
    partCols.add(new FieldSchema("ds", "string", ""));
    SerDeInfo serde = new SerDeInfo("serde", "seriallib", null);
    StorageDescriptor sd = new StorageDescriptor(cols, "file:/tmp", "input", "output", false, 0,
        serde, null, null, emptyParameters);
    Table table = new Table("insertPartition", "default", "me", startTime, startTime, 0, sd,
        partCols, emptyParameters, null, null, null);
    msClient.createTable(table);
    Partition partition = new Partition(Arrays.asList("today"), "default", "insertPartition",
        startTime, startTime, sd, emptyParameters);
    msClient.add_partition(partition);

    FireEventRequestData data = new FireEventRequestData();
    InsertEventRequestData insertData = new InsertEventRequestData();
    data.setInsertData(insertData);
    insertData.addToFilesAdded("/warehouse/mytable/today/b1");
    FireEventRequest rqst = new FireEventRequest(true, data);
    rqst.setDbName("default");
    rqst.setTableName("insertPartition");
    rqst.setPartitionVals(Arrays.asList("today"));
    msClient.fireListenerEvent(rqst);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(3, rsp.getEventsSize());

    NotificationEvent event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertTrue(event.getEventTime() >= startTime);
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertEquals("default", event.getDbName());
    assertEquals("insertPartition", event.getTableName());
    assertTrue(event.getMessage(),
        event.getMessage().matches("\\{\"eventType\":\"INSERT\",\"server\":\"\"," +
        "\"servicePrincipal\":\"\",\"db\":\"default\",\"table\":" +
        "\"insertPartition\",\"timestamp\":[0-9]+," +
        "\"files\":\\[\"/warehouse/mytable/today/b1\"],\"partKeyVals\":\\{\"ds\":\"today\"}," +
        "\"partitionKeyValues\":\\{\"ds\":\"today\"}}"));
  }

  @Test
  public void getOnlyMaxEvents() throws Exception {
    Database db = new Database("db1", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    db = new Database("db2", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    db = new Database("db3", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 2, null);
    assertEquals(2, rsp.getEventsSize());
    assertEquals(firstEventId + 1, rsp.getEvents().get(0).getEventId());
    assertEquals(firstEventId + 2, rsp.getEvents().get(1).getEventId());
  }

  @Test
  public void filter() throws Exception {
    Database db = new Database("f1", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    db = new Database("f2", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    msClient.dropDatabase("f2");

    IMetaStoreClient.NotificationFilter filter = new IMetaStoreClient.NotificationFilter() {
      @Override
      public boolean accept(NotificationEvent event) {
        return event.getEventType().equals(HCatConstants.HCAT_DROP_DATABASE_EVENT);
      }
    };

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, filter);
    assertEquals(1, rsp.getEventsSize());
    assertEquals(firstEventId + 3, rsp.getEvents().get(0).getEventId());
  }

  @Test
  public void filterWithMax() throws Exception {
    Database db = new Database("f10", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    db = new Database("f11", "no description", "file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    msClient.dropDatabase("f11");

    IMetaStoreClient.NotificationFilter filter = new IMetaStoreClient.NotificationFilter() {
      @Override
      public boolean accept(NotificationEvent event) {
        return event.getEventType().equals(HCatConstants.HCAT_CREATE_DATABASE_EVENT);
      }
    };

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 1, filter);
    assertEquals(1, rsp.getEventsSize());
    assertEquals(firstEventId + 1, rsp.getEvents().get(0).getEventId());
  }

  @Test
  public void sqlInsertTable() throws Exception {

    driver.run("create table sit (c int)");
    driver.run("insert into table sit values (1)");
    driver.run("alter table sit add columns (c2 int)");
    driver.run("drop table sit");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    // For reasons not clear to me there's an alter after the create table and one after the
    // insert.  I think the one after the insert is a stats calculation.
    assertEquals(6, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertEquals(HCatConstants.HCAT_CREATE_TABLE_EVENT, event.getEventType());
    event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    // Make sure the files are listed in the insert
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(4);
    assertEquals(firstEventId + 5, event.getEventId());
    assertEquals(HCatConstants.HCAT_ALTER_TABLE_EVENT, event.getEventType());
    event = rsp.getEvents().get(5);
    assertEquals(firstEventId + 6, event.getEventId());
    assertEquals(HCatConstants.HCAT_DROP_TABLE_EVENT, event.getEventType());
  }

  @Test
  public void sqlCTAS() throws Exception {

    driver.run("create table ctas_source (c int)");
    driver.run("insert into table ctas_source values (1)");
    driver.run("create table ctas_target as select c from ctas_source");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);

    assertEquals(6, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertEquals(HCatConstants.HCAT_CREATE_TABLE_EVENT, event.getEventType());
    event = rsp.getEvents().get(2);
    assertEquals(firstEventId + 3, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    // Make sure the files are listed in the insert
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(4);
    assertEquals(firstEventId + 5, event.getEventId());
    assertEquals(HCatConstants.HCAT_CREATE_TABLE_EVENT, event.getEventType());
  }

  @Test
  public void sqlTempTable() throws Exception {

    LOG.info("XXX Starting temp table");
    driver.run("create temporary table tmp1 (c int)");
    driver.run("insert into table tmp1 values (1)");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);

    assertEquals(0, rsp.getEventsSize());
  }

  @Test
  public void sqlDb() throws Exception {

    driver.run("create database sd");
    driver.run("drop database sd");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(0);
    assertEquals(firstEventId + 1, event.getEventId());
    assertEquals(HCatConstants.HCAT_CREATE_DATABASE_EVENT, event.getEventType());
    event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertEquals(HCatConstants.HCAT_DROP_DATABASE_EVENT, event.getEventType());
  }

  @Test
  public void sqlInsertPartition() throws Exception {

    driver.run("create table sip (c int) partitioned by (ds string)");
    driver.run("insert into table sip partition (ds = 'today') values (1)");
    driver.run("insert into table sip partition (ds = 'today') values (2)");
    driver.run("insert into table sip partition (ds) values (3, 'today')");
    driver.run("alter table sip add partition (ds = 'yesterday')");
    driver.run("insert into table sip partition (ds = 'yesterday') values (2)");

    driver.run("insert into table sip partition (ds) values (3, 'yesterday')");
    driver.run("insert into table sip partition (ds) values (3, 'tomorrow')");
    driver.run("alter table sip drop partition (ds = 'tomorrow')");

    driver.run("insert into table sip partition (ds) values (42, 'todaytwo')");
    driver.run("insert overwrite table sip partition(ds='todaytwo') select c from sip where 'ds'='today'");

    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);

    for (NotificationEvent ne : rsp.getEvents()) LOG.debug("EVENT: " + ne.getMessage());
    // For reasons not clear to me there's one or more alter partitions after add partition and
    // insert.
    assertEquals(24, rsp.getEventsSize());
    NotificationEvent event = rsp.getEvents().get(1);
    assertEquals(firstEventId + 2, event.getEventId());
    assertEquals(HCatConstants.HCAT_ADD_PARTITION_EVENT, event.getEventType());
    event = rsp.getEvents().get(3);
    assertEquals(firstEventId + 4, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    // Make sure the files are listed in the insert
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(6);
    assertEquals(firstEventId + 7, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(9);
    assertEquals(firstEventId + 10, event.getEventId());
    assertEquals(HCatConstants.HCAT_ADD_PARTITION_EVENT, event.getEventType());
    event = rsp.getEvents().get(10);
    assertEquals(firstEventId + 11, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(13);
    assertEquals(firstEventId + 14, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"files\":\\[\"pfile.*"));
    event = rsp.getEvents().get(16);
    assertEquals(firstEventId + 17, event.getEventId());
    assertEquals(HCatConstants.HCAT_ADD_PARTITION_EVENT, event.getEventType());
    event = rsp.getEvents().get(18);
    assertEquals(firstEventId + 19, event.getEventId());
    assertEquals(HCatConstants.HCAT_DROP_PARTITION_EVENT, event.getEventType());

    event = rsp.getEvents().get(19);
    assertEquals(firstEventId + 20, event.getEventId());
    assertEquals(HCatConstants.HCAT_ADD_PARTITION_EVENT, event.getEventType());
    event = rsp.getEvents().get(20);
    assertEquals(firstEventId + 21, event.getEventId());
    assertEquals(HCatConstants.HCAT_ALTER_PARTITION_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"ds\":\"todaytwo\".*"));

    event = rsp.getEvents().get(21);
    assertEquals(firstEventId + 22, event.getEventId());
    assertEquals(HCatConstants.HCAT_INSERT_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"files\":\\[\\].*")); // replace-overwrite introduces no new files
    event = rsp.getEvents().get(22);
    assertEquals(firstEventId + 23, event.getEventId());
    assertEquals(HCatConstants.HCAT_ALTER_PARTITION_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"ds\":\"todaytwo\".*"));
    event = rsp.getEvents().get(23);
    assertEquals(firstEventId + 24, event.getEventId());
    assertEquals(HCatConstants.HCAT_ALTER_PARTITION_EVENT, event.getEventType());
    assertTrue(event.getMessage().matches(".*\"ds\":\"todaytwo\".*"));
   }

  @Test
  public void cleanupNotifs() throws Exception {
    Database db = new Database("cleanup1","no description","file:/tmp", emptyParameters);
    msClient.createDatabase(db);
    msClient.dropDatabase("cleanup1");

    LOG.info("Pulling events immediately after createDatabase/dropDatabase");
    NotificationEventResponse rsp = msClient.getNextNotification(firstEventId, 0, null);
    assertEquals(2, rsp.getEventsSize());

    // sleep for expiry time, and then fetch again
    Thread.sleep(EVENTS_TTL * 2 * 1000); // sleep twice the TTL interval - things should have been cleaned by then.

    LOG.info("Pulling events again after cleanup");
    NotificationEventResponse rsp2 = msClient.getNextNotification(firstEventId, 0, null);
    LOG.info("second trigger done");
    assertEquals(0, rsp2.getEventsSize());
  }

  private ObjectNode getJsonTree(NotificationEvent event) throws Exception {
    JsonParser jsonParser = (new JsonFactory()).createJsonParser(event.getMessage());
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(jsonParser, ObjectNode.class);
  }

  private Function getFunctionObj(JsonNode jsonTree) throws Exception {
    TDeserializer deSerializer = new TDeserializer(new TJSONProtocol.Factory());
    Function funcObj = new Function();
    String tableJson = jsonTree.get("functionObjJson").asText();
    deSerializer.deserialize(funcObj, tableJson, "UTF-8");
    return funcObj;
  }

  private Index getIndexObj(JsonNode jsonTree) throws Exception {
    return getIndexObj(jsonTree, "indexObjJson");
  }

  private Index getIndexObj(JsonNode jsonTree, String indexObjKey) throws Exception {
    TDeserializer deSerializer = new TDeserializer(new TJSONProtocol.Factory());
    Index indexObj = new Index();
    String tableJson = jsonTree.get(indexObjKey).asText();
    deSerializer.deserialize(indexObj, tableJson, "UTF-8");
    return indexObj;
  }
}
