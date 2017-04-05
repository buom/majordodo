/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package majordodo.replication;

import majordodo.clientfacade.TaskStatusView;
import majordodo.executors.TaskExecutor;
import majordodo.network.netty.NettyChannelAcceptor;
import majordodo.worker.WorkerCore;
import majordodo.worker.WorkerCoreConfiguration;
import majordodo.worker.WorkerStatusListener;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import majordodo.clientfacade.AddTaskRequest;
import majordodo.network.BrokerHostData;
import majordodo.task.Broker;
import majordodo.task.BrokerConfiguration;
import majordodo.task.SimpleTestUtils;
import static majordodo.task.SimpleTestUtils.checkResourcesUsage;
import majordodo.task.Task;
import majordodo.task.TaskProperties;
import majordodo.task.TaskPropertiesMapperFunction;
import majordodo.task.TasksHeap;
import majordodo.task.WorkerStatus;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Basic tests for recovery
 *
 * @author enrico.olivelli
 */
public class DeadWorkerResourceCountersAtFollowerPromotionTest {

    @Before
    public void setupLogger() throws Exception {
        SimpleTestUtils.setupLogger(Level.ALL);
    }

    protected TaskPropertiesMapperFunction createTaskPropertiesMapperFunction() {
        return (long taskid, String taskType, String userid) -> {
            int group1 = groupsMap.getOrDefault(userid, 0);
            return new TaskProperties(group1, RESOURCES);
        };
    }

    protected Map<String, Integer> groupsMap = new HashMap<>();

    private static final String TASKTYPE_MYTYPE = "mytype";
    private static final String[] RESOURCES = new String[]{"db1", "db2"};
    private static final String userId = "queue1";
    private static final int group = 12345;

    @Before
    public void before() throws Exception {
        groupsMap.clear();
        groupsMap.put(userId, group);
    }

    @Rule
    public TemporaryFolder folderSnapshots = new TemporaryFolder();
    @Rule
    public TemporaryFolder folderZk = new TemporaryFolder();

    @Test
    public void resendNotificationToPromotedLeaderTest() throws Exception {
        try (ZKTestEnv zkServer = new ZKTestEnv(folderZk.getRoot().toPath());) {
            zkServer.startBookie();
            long taskId;
            String workerId = "abc";
            String taskParams = "param";

            String host = "localhost";
            int port = 7000;
            String host2 = "localhost";
            int port2 = 7001;

            BrokerConfiguration brokerConfig = new BrokerConfiguration();
            brokerConfig.setMaxWorkerIdleTime(1000);
            // startAsWritable a broker and do some work
            try (Broker broker1 = new Broker(brokerConfig, new ReplicatedCommitLog(zkServer.getAddress(), zkServer.getTimeout(), zkServer.getPath(),
                folderSnapshots.newFolder().toPath(),
                BrokerHostData.formatHostdata(new BrokerHostData(host, port, "", false, null)), false),
                new TasksHeap(1000, createTaskPropertiesMapperFunction()));) {
                broker1.startAsWritable();
                try (NettyChannelAcceptor server1 = new NettyChannelAcceptor(broker1.getAcceptor(), host, port)) {
                    server1.start();

                    try (Broker broker2 = new Broker(brokerConfig,
                        new ReplicatedCommitLog(zkServer.getAddress(), zkServer.getTimeout(), zkServer.getPath(),
                            folderSnapshots.newFolder().toPath(),
                            BrokerHostData.formatHostdata(new BrokerHostData(host2, port2, "", false, null)), false),
                        new TasksHeap(1000, createTaskPropertiesMapperFunction()));) {
                        broker2.start();

                        try (NettyChannelAcceptor server2 = new NettyChannelAcceptor(broker2.getAcceptor(), host2, port2)) {
                            server2.start();

                            try (ZKBrokerLocator locator = new ZKBrokerLocator(zkServer.getAddress(),
                                zkServer.getTimeout(), zkServer.getPath())) {

                                CountDownLatch connectedLatch = new CountDownLatch(1);
                                CountDownLatch disconnectedLatch = new CountDownLatch(1);
                                CountDownLatch allTaskExecuted = new CountDownLatch(1);
                                CountDownLatch startExecutingTask = new CountDownLatch(1);
                                CountDownLatch secondBrokerIsLeader = new CountDownLatch(1);
                                WorkerStatusListener listener = new WorkerStatusListener() {

                                    @Override
                                    public void connectionEvent(String event, WorkerCore core) {
                                        System.out.println("connectionEvent " + event);
                                        if (event.equals(WorkerStatusListener.EVENT_CONNECTED)) {
                                            connectedLatch.countDown();
                                        }
                                        if (event.equals(WorkerStatusListener.EVENT_DISCONNECTED)) {
                                            disconnectedLatch.countDown();
                                        }
                                    }

                                };
                                Map<String, Integer> tags = new HashMap<>();
                                tags.put(TASKTYPE_MYTYPE, 1);

                                WorkerCoreConfiguration config = new WorkerCoreConfiguration();
                                config.setMaxPendingFinishedTaskNotifications(1);
                                config.setWorkerId(workerId);
                                config.setMaxThreadsByTaskType(tags);
                                config.setGroups(Arrays.asList(group));
                                try (WorkerCore core = new WorkerCore(config, workerId, locator, listener);) {
                                    core.start();
                                    assertTrue(connectedLatch.await(10, TimeUnit.SECONDS));
                                    core.setExecutorFactory(
                                        (String tasktype, Map<String, Object> parameters) -> new TaskExecutor() {

                                            @Override
                                            public String executeTask(Map<String, Object> parameters) throws Exception {
                                                startExecutingTask.countDown();
                                                core.die();
                                                allTaskExecuted.countDown();
                                                return "theresult";
                                            }

                                        }
                                    );

                                    taskId = broker1.getClient().submitTask(new AddTaskRequest(0, TASKTYPE_MYTYPE, userId, taskParams, 1, 0, null, 0, null, null)).getTaskId();

                                    assertTrue(startExecutingTask.await(30, TimeUnit.SECONDS));

                                    // worker must disconnect from broker1
                                    assertTrue(disconnectedLatch.await(1, TimeUnit.MINUTES));

                                    WorkerStatus workerStatus = broker1.getBrokerStatus().getWorkerStatus(workerId);
                                    for (int i = 0; i < 100; i++) {
                                        if (workerStatus.getStatus() == WorkerStatus.STATUS_DEAD) {
                                            break;
                                        }
                                        Thread.sleep(100);
                                    }
                                    assertEquals(WorkerStatus.STATUS_DEAD, workerStatus.getStatus());

                                    System.out.println("stopping broker1");
                                    broker1.close();
                                    server1.close();

                                    while (!broker2.isWritable()) {
                                        System.out.println("waiting for broker2 to become leader");
                                        Thread.sleep(1000);
                                    }

                                    // When broker2 gains leader status, it doesn't alter resources for tasks not in
                                    // status running (in this test the task has ERROR status when broker2 kicks in
                                    // becouse broker1 changes its status when it recognizes worker death)
                                    //checkResourcesUsage(broker2, workerId, RESOURCES, 1);

                                    secondBrokerIsLeader.countDown();
                                    System.out.println("broker2 is now writable");

                                    assertTrue(allTaskExecuted.await(30, TimeUnit.SECONDS));

                                    boolean okFinishedForBroker = false;
                                    for (int i = 0; i < 100; i++) {
                                        TaskStatusView task = broker2.getClient().getTask(taskId);
                                        System.out.println("" + task);
                                        if (task.getStatus() == Task.STATUS_ERROR) {
                                            okFinishedForBroker = true;
                                            break;
                                        }
                                        Thread.sleep(1000);
                                    }
                                    assertTrue(okFinishedForBroker);

                                    checkResourcesUsage(broker2, workerId, RESOURCES, 0);
                                }

                            }

                        }
                    }
                }

            }
        }
    }
    
    @Test
    public void resendNotificationToPromotedLeaderWhenFirstBrokerDiesTest() throws Exception {
        try (ZKTestEnv zkServer = new ZKTestEnv(folderZk.getRoot().toPath());) {
            zkServer.startBookie();
            long taskId;
            String workerId = "abc";
            String taskParams = "param";

            String host = "localhost";
            int port = 7000;
            String host2 = "localhost";
            int port2 = 7001;

            BrokerConfiguration brokerConfig = new BrokerConfiguration();
            brokerConfig.setMaxWorkerIdleTime(1000);
            // startAsWritable a broker and do some work
            try (Broker broker1 = new Broker(brokerConfig, new ReplicatedCommitLog(zkServer.getAddress(), zkServer.getTimeout(), zkServer.getPath(),
                folderSnapshots.newFolder().toPath(),
                BrokerHostData.formatHostdata(new BrokerHostData(host, port, "", false, null)), false),
                new TasksHeap(1000, createTaskPropertiesMapperFunction()));) {
                broker1.startAsWritable();
                try (NettyChannelAcceptor server1 = new NettyChannelAcceptor(broker1.getAcceptor(), host, port)) {
                    server1.start();

                    try (Broker broker2 = new Broker(brokerConfig,
                        new ReplicatedCommitLog(zkServer.getAddress(), zkServer.getTimeout(), zkServer.getPath(),
                            folderSnapshots.newFolder().toPath(),
                            BrokerHostData.formatHostdata(new BrokerHostData(host2, port2, "", false, null)), false),
                        new TasksHeap(1000, createTaskPropertiesMapperFunction()));) {
                        broker2.start();

                        try (NettyChannelAcceptor server2 = new NettyChannelAcceptor(broker2.getAcceptor(), host2, port2)) {
                            server2.start();

                            try (ZKBrokerLocator locator = new ZKBrokerLocator(zkServer.getAddress(),
                                zkServer.getTimeout(), zkServer.getPath())) {

                                CountDownLatch connectedLatch = new CountDownLatch(1);
                                CountDownLatch disconnectedLatch = new CountDownLatch(1);
                                CountDownLatch firstBrokerDied = new CountDownLatch(1);
                                CountDownLatch allTaskExecuted = new CountDownLatch(1);
                                CountDownLatch startExecutingTask = new CountDownLatch(1);
                                CountDownLatch secondBrokerIsLeader = new CountDownLatch(1);
                                WorkerStatusListener listener = new WorkerStatusListener() {

                                    @Override
                                    public void connectionEvent(String event, WorkerCore core) {
                                        System.out.println("connectionEvent " + event);
                                        if (event.equals(WorkerStatusListener.EVENT_CONNECTED)) {
                                            connectedLatch.countDown();
                                        }
                                        if (event.equals(WorkerStatusListener.EVENT_DISCONNECTED)) {
                                            disconnectedLatch.countDown();
                                        }
                                    }

                                };
                                Map<String, Integer> tags = new HashMap<>();
                                tags.put(TASKTYPE_MYTYPE, 1);

                                WorkerCoreConfiguration config = new WorkerCoreConfiguration();
                                config.setMaxPendingFinishedTaskNotifications(1);
                                config.setWorkerId(workerId);
                                config.setMaxThreadsByTaskType(tags);
                                config.setGroups(Arrays.asList(group));
                                try (WorkerCore core = new WorkerCore(config, workerId, locator, listener);) {
                                    core.start();
                                    assertTrue(connectedLatch.await(10, TimeUnit.SECONDS));
                                    core.setExecutorFactory(
                                        (String tasktype, Map<String, Object> parameters) -> new TaskExecutor() {

                                            @Override
                                            public String executeTask(Map<String, Object> parameters) throws Exception {
                                                startExecutingTask.countDown();
                                                firstBrokerDied.await(30, TimeUnit.SECONDS);
                                                core.die();
                                                allTaskExecuted.countDown();
                                                return "theresult";
                                            }

                                        }
                                    );

                                    taskId = broker1.getClient().submitTask(new AddTaskRequest(0, TASKTYPE_MYTYPE, userId, taskParams, 1, 0, null, 0, null, null)).getTaskId();

                                    assertTrue(startExecutingTask.await(30, TimeUnit.SECONDS));
                                    
                                    System.out.println("killing broker1");
                                    broker1.die();
                                    firstBrokerDied.countDown();

                                    // worker must disconnect from broker1
                                    assertTrue(disconnectedLatch.await(1, TimeUnit.MINUTES));
                                    
                                    while (!broker2.isWritable()) {
                                        System.out.println("waiting for broker2 to become leader");
                                        Thread.sleep(1000);
                                    }

                                    // When broker2 gains leader status, it doesn't alter resources for tasks not in
                                    // status running (in this test the task has ERROR status when broker2 kicks in
                                    // becouse broker1 changes its status when it recognizes worker death)
                                    checkResourcesUsage(broker2, workerId, RESOURCES, 1);

                                    secondBrokerIsLeader.countDown();
                                    System.out.println("broker2 is now writable");

                                    assertTrue(allTaskExecuted.await(30, TimeUnit.SECONDS));

                                    boolean okFinishedForBroker = false;
                                    for (int i = 0; i < 100; i++) {
                                        TaskStatusView task = broker2.getClient().getTask(taskId);
                                        System.out.println("" + task);
                                        if (task.getStatus() == Task.STATUS_ERROR) {
                                            okFinishedForBroker = true;
                                            break;
                                        }
                                        Thread.sleep(1000);
                                    }
                                    assertTrue(okFinishedForBroker);

                                    checkResourcesUsage(broker2, workerId, RESOURCES, 0);
                                }

                            }

                        }
                    }
                }

            }
        }
    }
}