/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.executor;

import clojure.lang.IFn;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.ProducerType;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.storm.Config;
import org.apache.storm.Constants;
import org.apache.storm.StormTimer;
import org.apache.storm.cluster.ClusterStateContext;
import org.apache.storm.cluster.ClusterUtils;
import org.apache.storm.cluster.DaemonType;
import org.apache.storm.cluster.IStormClusterState;
import org.apache.storm.daemon.GrouperFactory;
import org.apache.storm.daemon.StormCommon;
import org.apache.storm.daemon.Task;
import org.apache.storm.executor.bolt.BoltExecutor;
import org.apache.storm.executor.error.IReportError;
import org.apache.storm.executor.error.ReportError;
import org.apache.storm.executor.error.ReportErrorAndDie;
import org.apache.storm.executor.spout.SpoutExecutor;
import org.apache.storm.generated.Bolt;
import org.apache.storm.generated.DebugOptions;
import org.apache.storm.generated.Grouping;
import org.apache.storm.generated.SpoutSpec;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.grouping.LoadAwareCustomStreamGrouping;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.metric.api.IMetricsConsumer;
import org.apache.storm.stats.BoltExecutorStats;
import org.apache.storm.stats.CommonStats;
import org.apache.storm.stats.SpoutExecutorStats;
import org.apache.storm.stats.StatsUtil;
import org.apache.storm.task.WorkerTopologyContext;
import org.apache.storm.tuple.AddressedTuple;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.DisruptorBackpressureCallback;
import org.apache.storm.utils.DisruptorQueue;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.Utils;
import org.apache.storm.utils.WorkerBackpressureThread;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public abstract class Executor implements Callable, EventHandler<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(Executor.class);

    protected final Map workerData;
    protected final WorkerTopologyContext workerTopologyContext;
    protected final List<Long> executorId;
    protected final List<Integer> taskIds;
    protected final String componentId;
    protected final AtomicBoolean openOrPrepareWasCalled;
    protected final Map stormConf;
    protected final Map conf;
    protected final String stormId;
    protected final HashMap sharedExecutorData;
    protected final AtomicBoolean stormActive;
    protected final AtomicReference<Map<String, DebugOptions>> stormComponentDebug;
    protected final Runnable suicideFn;
    protected final IStormClusterState stormClusterState;
    protected final Map<Integer, String> taskToComponent;
    protected CommonStats stats;
    protected final Map<Integer, Map<Integer, Map<String, IMetric>>> intervalToTaskToMetricToRegistry;
    protected final Map<String, Map<String, LoadAwareCustomStreamGrouping>> streamToComponentToGrouper;
    protected final ReportErrorAndDie reportErrorDie;
    protected final Callable<Boolean> sampler;
    protected final AtomicBoolean backpressure;
    protected ExecutorTransfer executorTransfer;
    protected final String type;
    protected final AtomicBoolean throttleOn;
    protected IFn transferFn;

    protected final IReportError reportError;
    protected final Random rand;
    protected final DisruptorQueue transferQueue;
    protected final DisruptorQueue receiveQueue;
    protected Map<Integer, Task> idToTask;
    protected final Map<String, String> credentials;
    protected final Boolean isDebug;
    protected final Boolean hasEventLoggers;
    protected String hostname;

    protected Executor(Map workerData, List<Long> executorId, Map<String, String> credentials) {
        this.workerData = workerData;
        this.executorId = executorId;
        this.workerTopologyContext = StormCommon.makeWorkerContext(workerData);
        this.taskIds = StormCommon.executorIdToTasks(executorId);
        this.componentId = workerTopologyContext.getComponentId(taskIds.get(0));
        this.openOrPrepareWasCalled = new AtomicBoolean(false);
        this.stormConf = normalizedComponentConf((Map) workerData.get(Constants.STORM_CONF), workerTopologyContext, componentId);
        this.receiveQueue = (DisruptorQueue) (((Map) workerData.get(Constants.EXECUTOR_RECEIVE_QUEUE_MAP)).get(executorId));
        this.stormId = (String) workerData.get(Constants.STORM_ID);
        this.conf = (Map) workerData.get(Constants.CONF);
        this.sharedExecutorData = new HashMap();
        this.stormActive = (AtomicBoolean) workerData.get(Constants.STORM_ACTIVE_ATOM);
        this.stormComponentDebug = (AtomicReference<Map<String, DebugOptions>>) workerData.get(Constants.COMPONENT_TO_DEBUG_ATOM);

        this.transferQueue = mkExecutorBatchQueue(stormConf, executorId);
        this.transferFn = (IFn) workerData.get(Constants.TRANSFER_FN);
        this.executorTransfer = new ExecutorTransfer(workerTopologyContext, transferQueue, stormConf, transferFn);

        this.suicideFn = (Runnable) workerData.get(Constants.SUICIDE_FN);
        try {
            this.stormClusterState = ClusterUtils.mkStormClusterState(workerData.get("state-store"), Utils.getWorkerACL(stormConf),
                    new ClusterStateContext(DaemonType.WORKER));
        } catch (Exception e) {
            throw Utils.wrapInRuntime(e);
        }

        StormTopology topology = workerTopologyContext.getRawTopology();
        Map<String, SpoutSpec> spouts = topology.get_spouts();
        Map<String, Bolt> bolts = topology.get_bolts();
        if (spouts.containsKey(componentId)) {
            this.type = StatsUtil.SPOUT;
            this.stats = new SpoutExecutorStats(ConfigUtils.samplingRate(stormConf));
        } else if (bolts.containsKey(componentId)) {
            this.type = StatsUtil.BOLT;
            this.stats = new BoltExecutorStats(ConfigUtils.samplingRate(stormConf));
        } else {
            throw new RuntimeException("Could not find " + componentId + " in " + topology);
        }

        this.intervalToTaskToMetricToRegistry = new HashMap<>();
        this.taskToComponent = (Map<Integer, String>) workerData.get(Constants.TASK_TO_COMPONENT);
        this.streamToComponentToGrouper = outboundComponents(workerTopologyContext, componentId, stormConf);
        this.reportError = new ReportError(stormConf, stormClusterState, stormId, componentId, workerTopologyContext);
        this.reportErrorDie = new ReportErrorAndDie(reportError, suicideFn);
        this.sampler = ConfigUtils.mkStatsSampler(stormConf);
        this.backpressure = new AtomicBoolean(false);
        this.throttleOn = (AtomicBoolean) workerData.get(Constants.THROTTLE_ON);
        this.isDebug = Utils.getBoolean(stormConf.get(Config.TOPOLOGY_DEBUG), false);
        this.rand = new Random(Utils.secureRandomLong());
        this.credentials = credentials;
        this.hasEventLoggers = StormCommon.hasEventLoggers(stormConf);

        try {
            this.hostname = Utils.hostname(stormConf);
        } catch (UnknownHostException ignored) {
            this.hostname = "";
        }
    }

    public static Executor mkExecutor(Map workerData, List<Long> executorId, Map<String, String> credentials) {
        Executor executor;

        Map<String, Object> convertedWorkerData = Utils.convertClojureMapToJavaMap(workerData);
        WorkerTopologyContext workerTopologyContext = StormCommon.makeWorkerContext(convertedWorkerData);
        List<Integer> taskIds = StormCommon.executorIdToTasks(executorId);
        String componentId = workerTopologyContext.getComponentId(taskIds.get(0));

        String type = getExecutorType(workerTopologyContext, componentId);
        if (StatsUtil.SPOUT.equals(type)) {
            executor = new SpoutExecutor(convertedWorkerData, executorId, credentials);
            executor.stats = new SpoutExecutorStats(ConfigUtils.samplingRate(executor.getStormConf()));
        } else {
            executor = new BoltExecutor(convertedWorkerData, executorId, credentials);
            executor.stats = new BoltExecutorStats(ConfigUtils.samplingRate(executor.getStormConf()));
        }

        Map<Integer, Task> idToTask = new HashMap<>();
        for (Integer taskId : taskIds) {
            try {
                Task task = new Task(executor, taskId);
                executor.sendUnanchored(
                        task, StormCommon.SYSTEM_STREAM_ID, new Values("startup"), executor.getExecutorTransfer());
                idToTask.put(taskId, task);
            } catch (IOException ex) {
                throw Utils.wrapInRuntime(ex);
            }
        }
        executor.init(idToTask);

        return executor;
    }

    private static String getExecutorType(WorkerTopologyContext workerTopologyContext, String componentId) {
        StormTopology topology = workerTopologyContext.getRawTopology();
        Map<String, SpoutSpec> spouts = topology.get_spouts();
        Map<String, Bolt> bolts = topology.get_bolts();
        if (spouts.containsKey(componentId)) {
            return StatsUtil.SPOUT;
        } else if (bolts.containsKey(componentId)) {
            return StatsUtil.BOLT;
        } else {
            throw new RuntimeException("Could not find " + componentId + " in " + topology);
        }
    }

    /**
     * separated from mkExecutor in order to replace executor transfer in executor data for testing
     */
    public ExecutorShutdown execute() throws Exception {
        LOG.info("Loading executor tasks " + componentId + ":" + executorId);

        registerBackpressure();
        Utils.SmartThread systemThreads =
                Utils.asyncLoop(executorTransfer, executorTransfer.getName(), reportErrorDie);

        String handlerName = componentId + "-executor" + executorId;
        Utils.SmartThread handlers =
                Utils.asyncLoop(this, false, reportErrorDie, Thread.NORM_PRIORITY, true, true, handlerName);
        setupTicks(StatsUtil.SPOUT.equals(type));

        LOG.info("Finished loading executor " + componentId + ":" + executorId);
        return new ExecutorShutdown(this, Lists.newArrayList(systemThreads, handlers), idToTask);
    }

    public abstract void tupleActionFn(int taskId, TupleImpl tuple) throws Exception;

    public abstract void init(Map<Integer, Task> idToTask);

    @SuppressWarnings("unchecked")
    @Override
    public void onEvent(Object event, long seq, boolean endOfBatch) throws Exception {
        ArrayList<AddressedTuple> addressedTuples = (ArrayList<AddressedTuple>) event;
        for (AddressedTuple addressedTuple : addressedTuples) {
            TupleImpl tuple = (TupleImpl) addressedTuple.getTuple();
            int taskId = addressedTuple.getDest();
            if (isDebug) {
                LOG.info("Processing received message FOR {} TUPLE: {}", taskId, tuple);
            }
            if (taskId != AddressedTuple.BROADCAST_DEST) {
                tupleActionFn(taskId, tuple);
            } else {
                for (Integer t : taskIds) {
                    tupleActionFn(t, tuple);
                }
            }
        }
    }

    public void metricsTick(Task taskData, TupleImpl tuple) {
        try {
            Integer interval = tuple.getInteger(0);
            int taskId = taskData.getTaskId();
            Map<Integer, Map<String, IMetric>> taskToMetricToRegistry = intervalToTaskToMetricToRegistry.get(interval);
            Map<String, IMetric> nameToRegistry = null;
            if (taskToMetricToRegistry != null) {
                nameToRegistry = taskToMetricToRegistry.get(taskId);
            }
            if (nameToRegistry != null) {
                IMetricsConsumer.TaskInfo taskInfo = new IMetricsConsumer.TaskInfo(
                        hostname, workerTopologyContext.getThisWorkerPort(),
                        componentId, taskId, Time.currentTimeSecs(), interval);
                List<IMetricsConsumer.DataPoint> dataPoints = new ArrayList<>();
                for (Map.Entry<String, IMetric> entry : nameToRegistry.entrySet()) {
                    IMetric metric = entry.getValue();
                    Object value = metric.getValueAndReset();
                    if (value != null) {
                        IMetricsConsumer.DataPoint dataPoint = new IMetricsConsumer.DataPoint(entry.getKey(), value);
                        dataPoints.add(dataPoint);
                    }
                }
                if (!dataPoints.isEmpty()) {
                    sendUnanchored(taskData, Constants.METRICS_STREAM_ID,
                            new Values(taskInfo, dataPoints), executorTransfer);
                }
            }
        } catch (Exception e) {
            throw Utils.wrapInRuntime(e);
        }
    }

    protected void setupMetrics() {
        for (final Integer interval : intervalToTaskToMetricToRegistry.keySet()) {
            StormTimer timerTask = (StormTimer) workerData.get(Constants.USER_TIMER);
            timerTask.scheduleRecurring(interval, interval, new Runnable() {
                @Override
                public void run() {
                    TupleImpl tuple = new TupleImpl(workerTopologyContext, new Values(interval),
                            (int) Constants.SYSTEM_TASK_ID, Constants.METRICS_TICK_STREAM_ID);
                    List<AddressedTuple> metricsTickTuple =
                            Lists.newArrayList(new AddressedTuple(AddressedTuple.BROADCAST_DEST, tuple));
                    receiveQueue.publish(metricsTickTuple);
                }
            });
        }
    }

    public void sendUnanchored(Task task, String stream, List<Object> values, ExecutorTransfer transfer) {
        Tuple tuple = task.getTuple(stream, values);
        List<Integer> tasks = task.getOutgoingTasks(stream, values);
        for (Integer t : tasks) {
            transfer.transfer(t, tuple);
        }
    }

    /**
     * Send sampled data to the eventlogger if the global or component level debug flag is set (via nimbus api).
     */
    public void sendToEventLogger(Executor executor, Task taskData, List values,
                                  String componentId, Object messageId, Random random) {
        Map<String, DebugOptions> componentDebug = executor.getStormComponentDebug().get();
        DebugOptions debugOptions = componentDebug.get(componentId);
        if (debugOptions == null) {
            debugOptions = componentDebug.get(executor.getStormId());
        }
        double spct = ((debugOptions != null) && (debugOptions.is_enable())) ? debugOptions.get_samplingpct() : 0;
        if (spct > 0 && (random.nextDouble() * 100) < spct) {
            sendUnanchored(taskData, StormCommon.EVENTLOGGER_STREAM_ID,
                    new Values(componentId, messageId, System.currentTimeMillis(), values),
                    executor.getExecutorTransfer());
        }
    }

    private void registerBackpressure() {
        receiveQueue.registerBackpressureCallback(new DisruptorBackpressureCallback() {
            @Override
            public void highWaterMark() throws Exception {
                if (!backpressure.get()) {
                    backpressure.set(true);
                    LOG.debug("executor " + executorId + " is congested, set backpressure flag true");
                    WorkerBackpressureThread.notifyBackpressureChecker(workerData.get("backpressure-trigger"));
                }
            }

            @Override
            public void lowWaterMark() throws Exception {
                if (backpressure.get()) {
                    backpressure.set(false);
                    LOG.debug("executor " + executorId + " is not-congested, set backpressure flag false");
                    WorkerBackpressureThread.notifyBackpressureChecker(workerData.get("backpressure-trigger"));
                }
            }
        });
        receiveQueue.setHighWaterMark(Utils.getDouble(stormConf.get(Config.BACKPRESSURE_DISRUPTOR_HIGH_WATERMARK)));
        receiveQueue.setLowWaterMark(Utils.getDouble(stormConf.get(Config.BACKPRESSURE_DISRUPTOR_LOW_WATERMARK)));
        receiveQueue.setEnableBackpressure(Utils.getBoolean(stormConf.get(Config.TOPOLOGY_BACKPRESSURE_ENABLE), false));
    }

    protected void setupTicks(boolean isSpout) {
        final Integer tickTimeSecs = Utils.getInt(stormConf.get(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS), null);
        boolean enableMessageTimeout = (Boolean) stormConf.get(Config.TOPOLOGY_ENABLE_MESSAGE_TIMEOUTS);
        if (tickTimeSecs != null) {
            if (Utils.isSystemId(componentId) || (!enableMessageTimeout && isSpout)) {
                LOG.info("Timeouts disabled for executor " + componentId + ":" + executorId);
            } else {
                StormTimer timerTask = (StormTimer) workerData.get(Constants.USER_TIMER);
                timerTask.scheduleRecurring(tickTimeSecs, tickTimeSecs, new Runnable() {
                    @Override
                    public void run() {
                        TupleImpl tuple = new TupleImpl(workerTopologyContext, new Values(tickTimeSecs),
                                (int) Constants.SYSTEM_TASK_ID, Constants.SYSTEM_TICK_STREAM_ID);
                        List<AddressedTuple> tickTuple =
                                Lists.newArrayList(new AddressedTuple(AddressedTuple.BROADCAST_DEST, tuple));
                        receiveQueue.publish(tickTuple);
                    }
                });
            }
        }
    }


    private DisruptorQueue mkExecutorBatchQueue(Map stormConf, List<Long> executorId) {
        int sendSize = Utils.getInt(stormConf.get(Config.TOPOLOGY_EXECUTOR_SEND_BUFFER_SIZE));
        int waitTimeOutMs = Utils.getInt(stormConf.get(Config.TOPOLOGY_DISRUPTOR_WAIT_TIMEOUT_MILLIS));
        int batchSize = Utils.getInt(stormConf.get(Config.TOPOLOGY_DISRUPTOR_BATCH_SIZE));
        int batchTimeOutMs = Utils.getInt(stormConf.get(Config.TOPOLOGY_DISRUPTOR_BATCH_TIMEOUT_MILLIS));
        return new DisruptorQueue("executor" + executorId + "-send-queue", ProducerType.SINGLE,
                sendSize, waitTimeOutMs, batchSize, batchTimeOutMs);
    }

    /**
     * Returns map of stream id to component id to grouper
     */
    private Map<String, Map<String, LoadAwareCustomStreamGrouping>> outboundComponents(
            WorkerTopologyContext workerTopologyContext, String componentId, Map stormConf) {
        Map<String, Map<String, LoadAwareCustomStreamGrouping>> ret = new HashMap<>();

        Map<String, Map<String, Grouping>> outputGroupings = workerTopologyContext.getTargets(componentId);
        for (Map.Entry<String, Map<String, Grouping>> entry : outputGroupings.entrySet()) {
            String streamId = entry.getKey();
            Map<String, Grouping> componentGrouping = entry.getValue();
            Fields outFields = workerTopologyContext.getComponentOutputFields(componentId, streamId);
            Map<String, LoadAwareCustomStreamGrouping> componentGrouper = new HashMap<String, LoadAwareCustomStreamGrouping>();
            for (Map.Entry<String, Grouping> cg : componentGrouping.entrySet()) {
                String component = cg.getKey();
                Grouping grouping = cg.getValue();
                List<Integer> outTasks = workerTopologyContext.getComponentTasks(component);
                LoadAwareCustomStreamGrouping grouper = GrouperFactory.mkGrouper(
                        workerTopologyContext, componentId, streamId, outFields, grouping, outTasks, stormConf);
                componentGrouper.put(component, grouper);
            }
            if (componentGrouper.size() > 0) {
                ret.put(streamId, componentGrouper);
            }
        }

        for (String stream : workerTopologyContext.getComponentCommon(componentId).get_streams().keySet()) {
            if (!ret.containsKey(stream)) {
                ret.put(stream, null);
            }
        }

        return ret;
    }

    private Map normalizedComponentConf(Map stormConf, WorkerTopologyContext topologyContext, String componentId) {
        List<Object> keysToRemove = ConfigUtils.All_CONFIGS();
        keysToRemove.remove(Config.TOPOLOGY_DEBUG);
        keysToRemove.remove(Config.TOPOLOGY_MAX_SPOUT_PENDING);
        keysToRemove.remove(Config.TOPOLOGY_MAX_TASK_PARALLELISM);
        keysToRemove.remove(Config.TOPOLOGY_TRANSACTIONAL_ID);
        keysToRemove.remove(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS);
        keysToRemove.remove(Config.TOPOLOGY_SLEEP_SPOUT_WAIT_STRATEGY_TIME_MS);
        keysToRemove.remove(Config.TOPOLOGY_SPOUT_WAIT_STRATEGY);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_COUNT);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_COUNT);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_DURATION_MS);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_TUPLE_TIMESTAMP_FIELD_NAME);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_TUPLE_TIMESTAMP_MAX_LAG_MS);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_MESSAGE_ID_FIELD_NAME);
        keysToRemove.remove(Config.TOPOLOGY_STATE_PROVIDER);
        keysToRemove.remove(Config.TOPOLOGY_STATE_PROVIDER_CONFIG);
        keysToRemove.remove(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM);

        Map<Object, Object> componentConf;
        String specJsonConf = topologyContext.getComponentCommon(componentId).get_json_conf();
        if (specJsonConf != null) {
            componentConf = (Map<Object, Object>) JSONValue.parse(specJsonConf);
            for (Object p : keysToRemove) {
                componentConf.remove(p);
            }
        } else {
            componentConf = new HashMap<>();
        }

        Map<Object, Object> ret = new HashMap<>();
        ret.putAll(stormConf);
        ret.putAll(componentConf);

        return ret;
    }

    // =============================================================================
    // ============================ getter methods =================================
    // =============================================================================

    public List<Long> getExecutorId() {
        return executorId;
    }

    public List<Integer> getTaskIds() {
        return taskIds;
    }

    public String getComponentId() {
        return componentId;
    }

    public AtomicBoolean getOpenOrPrepareWasCalled() {
        return openOrPrepareWasCalled;
    }

    public Map getStormConf() {
        return stormConf;
    }

    public String getStormId() {
        return stormId;
    }

    public CommonStats getStats() {
        return stats;
    }

    public AtomicBoolean getThrottleOn() {
        return throttleOn;
    }

    public String getType() {
        return type;
    }

    public Boolean getIsDebug() {
        return isDebug;
    }

    public ExecutorTransfer getExecutorTransfer() {
        return executorTransfer;
    }

    public IReportError getReportError() {
        return reportError;
    }

    public WorkerTopologyContext getWorkerTopologyContext() {
        return workerTopologyContext;
    }

    public Callable<Boolean> getSampler() {
        return sampler;
    }

    public AtomicReference<Map<String, DebugOptions>> getStormComponentDebug() {
        return stormComponentDebug;
    }

    public DisruptorQueue getReceiveQueue() {
        return receiveQueue;
    }

    public AtomicBoolean getBackpressure() {
        return backpressure;
    }

    public DisruptorQueue getTransferWorkerQueue() {
        return transferQueue;
    }

    public IStormClusterState getStormClusterState() {
        return stormClusterState;
    }

    public Map getWorkerData() {
        return workerData;
    }

    public Map<String, Map<String, LoadAwareCustomStreamGrouping>> getStreamToComponentToGrouper() {
        return streamToComponentToGrouper;
    }

    public HashMap getSharedExecutorData() {
        return sharedExecutorData;
    }

    public Map<Integer, Map<Integer, Map<String, IMetric>>> getIntervalToTaskToMetricToRegistry() {
        return intervalToTaskToMetricToRegistry;
    }

    public IFn getTransferFn() {
        return transferFn;
    }

    @VisibleForTesting
    public void setLocalExecutorTransfer(ExecutorTransfer executorTransfer) {
        this.executorTransfer = executorTransfer;
    }
}
