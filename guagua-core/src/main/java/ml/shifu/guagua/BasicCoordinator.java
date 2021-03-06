/*
 * Copyright [2013-2014] PayPal Software Foundation
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.guagua;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ml.shifu.guagua.coordinator.zk.GuaguaZooKeeper;
import ml.shifu.guagua.io.Bytable;
import ml.shifu.guagua.io.Serializer;
import ml.shifu.guagua.util.NumberFormatUtils;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BasicCoordinator} is a basic implementation for both SyncWorkerCoordinator and SyncMasterCoordinator:
 * <ul>
 * <li>1. A {@link Watcher} to monitor zookeeper znodes.</li>
 * <li>2. Help functions to construct master znodes and worker znodes.</li>
 * <li>3. Basic functions to convert object to bytes or bytes to object.</li>
 * <li>4. Heart beat used to avoid ZooKeeperSessionExpiredException.</li>
 * </ul>
 * 
 * @param <MASTER_RESULT>
 *            master result for computation in each iteration.
 * @param <WORKER_RESULT>
 *            worker result for computation in each iteration.
 */
public class BasicCoordinator<MASTER_RESULT extends Bytable, WORKER_RESULT extends Bytable> implements Watcher {

    private static final Logger LOG = LoggerFactory.getLogger(BasicCoordinator.class);

    /**
     * Common zookeeper impmentation to operate znode.
     */
    private GuaguaZooKeeper zooKeeper;

    /**
     * Wait to connect zookeeper server successfully.
     */
    private CountDownLatch zkConnLatch = new CountDownLatch(1);

    /**
     * Default waiting time to check master or worker progress from zookeeper servers.
     */
    protected static final int WAIT_SLOT_MILLS = 300;

    /**
     * Waiting time to check master or worker progress from zookeeper servers.
     */
    private long sleepTime = WAIT_SLOT_MILLS;

    /**
     * Fixed-time waiting or each time increasing the waiting time.
     */
    private boolean isFixedTime = true;

    /**
     * {@link #masterSerializer} is used to serialize and de-serialize master results.
     */
    private Serializer<MASTER_RESULT> masterSerializer;

    /**
     * {@link #workerSerializer} is used to serialize and de-serialize worker results.
     */
    private Serializer<WORKER_RESULT> workerSerializer;

    /**
     * Heartbeat thread instance to send heart beat to zookeeper servers.
     */
    private HeartBeat heartBeat;

    /**
     * Heart beat checking time.
     */
    private static final long HEART_BEAT_SLEEP_TIME = 15 * 1000L;

    /**
     * Zookeeper has default heartbeat info, but sometimes failed, set a switch for that.
     */
    private boolean zkHeartBeatEnabled = false;

    /**
     * Create a thread pool to save master result or deserialize master result from zookeeper in parallel, only for case
     * if size of results over 1MB which is limitation per zk znde
     */
    private ExecutorService threadPool;

    public BasicCoordinator() {
    }

    protected StringBuilder getMasterBaseNode(final String appId) {
        return new StringBuilder(50).append(getAppNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_ZK_MASTER_NODE);
    }

    protected StringBuilder getMasterNode(final String appId, final int iteration) {
        return new StringBuilder(50).append(getMasterBaseNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(iteration);
    }

    protected StringBuilder getCurrentMasterNode(final String appId, final int iteration) {
        return getMasterNode(appId, iteration);
    }

    protected StringBuilder getCurrentMasterSplitNode(final String appId, final int iteration) {
        return new StringBuilder(50).append(getMasterBaseNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_ZK_SPLIT_NODE).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(iteration);
    }

    protected StringBuilder getLastMasterNode(final String appId, final int iteration) {
        return getMasterNode(appId, iteration - 1);
    }

    protected StringBuilder getRootNode() {
        return new StringBuilder(10).append(GuaguaConstants.ZOOKEEPER_SEPARATOR).append(
                GuaguaConstants.GUAGUA_ZK_ROOT_NODE);
    }

    protected StringBuilder getBaseMasterElectionNode(final String appId) {
        return new StringBuilder(20).append(getAppNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_MASTER_ELECTION);
    }

    protected StringBuilder getMasterElectionNode(final String appId, final long sessionId) {
        return new StringBuilder(40).append(getBaseMasterElectionNode(appId))
                .append(GuaguaConstants.ZOOKEEPER_SEPARATOR).append(sessionId);
    }

    protected StringBuilder getAppNode(final String appId) {
        return new StringBuilder(20).append(getRootNode()).append(GuaguaConstants.ZOOKEEPER_SEPARATOR).append(appId);
    }

    protected StringBuilder getWorkerBaseNode(final String appId) {
        return new StringBuilder(50).append(getAppNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_ZK_WORKERS_NODE);
    }

    protected StringBuilder getWorkerBaseNode(final String appId, final int iteration) {
        return new StringBuilder(50).append(getWorkerBaseNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(iteration);
    }

    protected StringBuilder getWorkerNode(final String appId, final String containerId, final int iteration) {
        return new StringBuilder(50).append(getWorkerBaseNode(appId, iteration))
                .append(GuaguaConstants.ZOOKEEPER_SEPARATOR).append(containerId);
    }

    protected StringBuilder getCurrentWorkerNode(final String appId, final String containerId, final int iteration) {
        return getWorkerNode(appId, containerId, iteration);
    }

    protected StringBuilder getCurrentWorkerSplitNode(final String appId, final String containerId, final int iteration) {
        return new StringBuilder(50).append(getAppNode(appId)).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_ZK_WORKERS_NODE).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(GuaguaConstants.GUAGUA_ZK_SPLIT_NODE).append(GuaguaConstants.ZOOKEEPER_SEPARATOR)
                .append(iteration).append(GuaguaConstants.ZOOKEEPER_SEPARATOR).append(containerId);
    }

    protected StringBuilder getLastWorkerNode(String appId, String containerId, int iteration) {
        return getWorkerNode(appId, containerId, iteration - 1);
    }

    /**
     * Coordinator initialization.
     */
    protected void initialize(Properties props) {
        this.zkHeartBeatEnabled = Boolean.TRUE.toString().equalsIgnoreCase(
                props.getProperty(GuaguaConstants.GUAGUA_ZK_HEARTBEAT_ENABLED, Boolean.FALSE.toString()));
        checkAndSetZooKeeper(props);
        setSleepTime(NumberFormatUtils.getLong(props.getProperty(GuaguaConstants.GUAGUA_COORDINATOR_SLEEP_UNIT),
                WAIT_SLOT_MILLS));
        setFixedTime(Boolean.TRUE.toString().equalsIgnoreCase(
                props.getProperty(GuaguaConstants.GUAGUA_COORDINATOR_FIXED_SLEEP_ENABLE,
                        GuaguaConstants.GUAGUA_COORDINATOR_FIXED_SLEEP)));
        this.threadPool = Executors.newFixedThreadPool(Integer.parseInt(props.getProperty(
                "guagua.master.result.thread.number", 8 + "")));
    }

    /**
     * Set up connection with given zookeeper settings.
     */
    protected void checkAndSetZooKeeper(Properties props) {
        if(getZooKeeper() == null) {
            try {
                String zkServers = props.getProperty(GuaguaConstants.GUAGUA_ZK_SERVERS);
                if(zkServers == null || zkServers.length() == 0) {
                    throw new GuaguaRuntimeException("Not set 'guagua.zk.servers'. Should be set for coordination.");
                }
                int sessionTimeout = NumberFormatUtils.getInt(
                        props.getProperty(GuaguaConstants.GUAGUA_ZK_SESSION_TIMEOUT),
                        GuaguaConstants.GUAGUA_ZK_SESSON_DEFAULT_TIMEOUT);
                int maxRetryAttempts = NumberFormatUtils.getInt(
                        props.getProperty(GuaguaConstants.GUAGUA_ZK_MAX_ATTEMPTS),
                        GuaguaConstants.GUAGUA_ZK_DEFAULT_MAX_ATTEMPTS);
                int retryWaitMsecs = NumberFormatUtils.getInt(
                        props.getProperty(GuaguaConstants.GUAGUA_ZK_RETRY_WAIT_MILLS),
                        GuaguaConstants.GUAGUA_ZK_DEFAULT_RETRY_WAIT_MILLS);
                setZooKeeper(new GuaguaZooKeeper(zkServers, sessionTimeout, maxRetryAttempts, retryWaitMsecs, this));
                // wait to connect successful to zookeeper.
                this.getZkConnLatch().await();
            } catch (IOException e) {
                throw new GuaguaRuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GuaguaRuntimeException(e);
            }
        }

        // heartbeat
        if(this.zkHeartBeatEnabled) {
            startHeartbeat();
        }
        // remember to stop it
    }

    /**
     * Close resources like zookeeper, thread pool
     */
    protected void close() throws InterruptedException {
        if(this.zkHeartBeatEnabled) {
            stopHeartBeat();
        }
        if(getZooKeeper() != null) {
            getZooKeeper().close();
        }

        // shut down thread pool
        this.threadPool.shutdownNow();
        this.threadPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    protected void startHeartbeat() {
        this.heartBeat = new HeartBeat();
        this.heartBeat.setDaemon(true);
        this.heartBeat.setName("ZooKeeper HeartBeat");
    }

    // should be invoked in postAllication.
    protected void stopHeartBeat() throws InterruptedException {
        this.heartBeat.setFollow(false);
        this.heartBeat.interrupt();
        this.heartBeat.join(HEART_BEAT_SLEEP_TIME + 1000);
    }

    @Override
    public void process(final WatchedEvent event) {
        LOG.debug("process: Got a new event, path = {}, type = {}, state = {}", event.getPath(), event.getType(),
                event.getState());

        if((event.getPath() == null) && (event.getType() == EventType.None)) {
            if(event.getState() == KeeperState.SyncConnected) {
                LOG.info("process: Asynchronous connection complete.");
                this.getZkConnLatch().countDown();
            } else {
                LOG.warn("process: Got unknown null path event {}.", event);
            }
            return;
        }
    }

    /**
     * Set bytes to znode, if bytes is over zookeeper data limit(1MB), use children znodes to store each part.
     * 
     * @return if result is split.
     */
    protected boolean setBytesToZNode(String znode, String splitZnode, byte[] bytes, CreateMode createNode)
            throws KeeperException, InterruptedException {
        LOG.debug("bytes length:{}", bytes.length);
        final int zkDataLimit = GuaguaConstants.GUAGUA_ZK_DATA_LIMIT;
        if(bytes.length > zkDataLimit) {
            // TODO don't recursively create split znode to avoid too many requests to zk servers.
            getZooKeeper().createExt(splitZnode, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, true);
            int childrenSize = (bytes.length % zkDataLimit == 0) ? (bytes.length / zkDataLimit)
                    : (bytes.length / zkDataLimit) + 1;
            int currentLen = bytes.length;

            CompletionService<Integer> completionService = new ExecutorCompletionService<Integer>(this.threadPool);
            for(int i = 0; i < childrenSize; i++) {
                int bytesLength = 0;
                if(currentLen >= zkDataLimit) {
                    currentLen -= zkDataLimit;
                    bytesLength = zkDataLimit;
                } else {
                    bytesLength = currentLen;
                }
                completionService.submit(new SaveResultToZookeeper(bytes, i, bytesLength, zkDataLimit, splitZnode,
                        createNode));
            }

            int rCnt = 0;
            while(rCnt < childrenSize) {
                try {
                    completionService.take().get();
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                rCnt += 1;
            }

            getZooKeeper().createExt(znode, null, Ids.OPEN_ACL_UNSAFE, createNode, false);
            return true;
        } else {
            getZooKeeper().createExt(znode, bytes, Ids.OPEN_ACL_UNSAFE, createNode, false);
            return false;
        }
    }

    public class SaveResultToZookeeper implements Callable<Integer> {

        private byte[] rawBytes;

        private int index;

        private int currentLen;

        private int zkDataLimit;

        private String splitZnode;

        private CreateMode createNode;

        public SaveResultToZookeeper(byte[] rawBytes, int index, int currentLen, int zkDataLimit, String splitZnode,
                CreateMode createNode) {
            this.rawBytes = rawBytes;
            this.index = index;
            this.currentLen = currentLen;
            this.zkDataLimit = zkDataLimit;
            this.splitZnode = splitZnode;
            this.createNode = createNode;
        }

        @Override
        public Integer call() throws Exception {
            byte[] currentBytes = new byte[currentLen];
            System.arraycopy(rawBytes, index * zkDataLimit, currentBytes, 0, currentBytes.length);
            getZooKeeper().createExt(splitZnode + GuaguaConstants.ZOOKEEPER_SEPARATOR + index, currentBytes,
                    Ids.OPEN_ACL_UNSAFE, createNode, false);
            return index;
        }

    }

    /**
     * This is reverse method to {@link #setBytesToZNode(String, String, byte[], CreateMode)}. Firstly get data from
     * {@link Code znode}. If data is empty, get data from its children.
     */
    protected byte[] getBytesFromZNode(String znode, String splitZnode) throws KeeperException, InterruptedException {
        byte[] data = getZooKeeper().getData(znode, null, null);
        if(data != null) {
            return data;
        }

        final List<String> children = getZooKeeper().getChildrenExt(splitZnode, false, true, new ChildrenComparator());
        if(children == null || children.size() == 0) {
            return null;
        }

        CompletionService<BytesPair> completionService = new ExecutorCompletionService<BytesPair>(this.threadPool);

        List<BytesPair> bytesPairList = new ArrayList<BytesPair>(children.size());
        int wholeLength = 0;
        for(int i = 0; i < children.size(); i++) {
            final int index = i;
            completionService.submit(new GetSplitBytes(getZooKeeper(), index, children.get(index)));
        }

        int rCnt = 0;
        while(rCnt < children.size()) {
            try {
                BytesPair bp = completionService.take().get();
                wholeLength += bp.bytes.length;
                bytesPairList.add(bp);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rCnt += 1;
        }

        Collections.sort(bytesPairList, new Comparator<BytesPair>() {
            @Override
            public int compare(BytesPair o1, BytesPair o2) {
                // return Integer.valueOf(s1).compareTo(Integer.valueOf(s2));
                return Integer.valueOf(o1.index).compareTo(Integer.valueOf(o2.index));
            }
        });

        byte[] results = new byte[wholeLength];
        for(int i = 0, currentLength = 0; i < bytesPairList.size(); i++) {
            byte[] currentBytes = bytesPairList.get(i).bytes;
            if(currentBytes != null) {
                System.arraycopy(currentBytes, 0, results, currentLength, currentBytes.length);
                currentLength += currentBytes.length;
            }
        }

        LOG.debug("znode results.length:{}", results.length);
        return results;
    }

    public static class GetSplitBytes implements Callable<BytesPair> {

        private GuaguaZooKeeper zookeeper;

        private int index;

        private String znode;

        public GetSplitBytes(GuaguaZooKeeper zookeeper, int index, String znode) {
            this.zookeeper = zookeeper;
            this.index = index;
            this.znode = znode;
        }

        @Override
        public BytesPair call() throws Exception {
            byte[] data = zookeeper.getData(znode, null, null);
            return new BytesPair(index, data);
        }

    }

    private static class BytesPair {

        public int index;

        public byte[] bytes;

        public BytesPair(int index, byte[] bytes) {
            super();
            this.index = index;
            this.bytes = bytes;
        }

    }

    /**
     * Compare int by string inputs.
     */
    private static class ChildrenComparator implements Comparator<String>, Serializable {

        private static final long serialVersionUID = 7871289234100249905L;

        @Override
        public int compare(String s1, String s2) {
            return Integer.valueOf(s1).compareTo(Integer.valueOf(s2));
        }
    }

    public GuaguaZooKeeper getZooKeeper() {
        return zooKeeper;
    }

    public void setZooKeeper(GuaguaZooKeeper zooKeeper) {
        this.zooKeeper = zooKeeper;
    }

    public long getSleepTime() {
        return sleepTime;
    }

    public void setSleepTime(long sleepTime) {
        this.sleepTime = sleepTime;
    }

    public boolean isFixedTime() {
        return isFixedTime;
    }

    public void setFixedTime(boolean isFixedTime) {
        this.isFixedTime = isFixedTime;
    }

    public Serializer<WORKER_RESULT> getWorkerSerializer() {
        return workerSerializer;
    }

    public void setWorkerSerializer(Serializer<WORKER_RESULT> workerSerializer) {
        this.workerSerializer = workerSerializer;
    }

    public Serializer<MASTER_RESULT> getMasterSerializer() {
        return masterSerializer;
    }

    public void setMasterSerializer(Serializer<MASTER_RESULT> masterSerializer) {
        this.masterSerializer = masterSerializer;
    }

    public CountDownLatch getZkConnLatch() {
        return zkConnLatch;
    }

    /**
     * A heartbeat thread to avoid zookeeper session time out.
     */
    private class HeartBeat extends Thread {

        private volatile boolean follow = true;

        @Override
        public void run() {
            while(isFollow()) {
                try {
                    Thread.sleep(HEART_BEAT_SLEEP_TIME);
                    LOG.debug("DEBUG: Heartbeat.");
                    Stat exists = getZooKeeper().exists(getRootNode().toString(), false);
                    LOG.debug("DEBUG: Heartbeat {}", exists);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (KeeperException.SessionExpiredException e) {
                    throw new GuaguaRuntimeException(e);
                } catch (KeeperException e) {
                    if(System.nanoTime() % 20 == 0) {
                        LOG.info("Heartbeat zookeeper exception, can be ignored.");
                    }
                }
            }
        }

        public boolean isFollow() {
            return follow;
        }

        public void setFollow(boolean follow) {
            this.follow = follow;
        }
    }

    /**
     * {@link CoordinatorCommand} is used for consistent process of zookeeper coordination.
     */
    public static interface CoordinatorCommand {

        /**
         * Command method.
         */
        void execute();
    }

    /**
     * {@link BasicCoordinatorCommand} is to process exceptions for zookeeper operations.
     */
    public static abstract class BasicCoordinatorCommand implements CoordinatorCommand {

        @Override
        public void execute() {
            try {
                doExecute();
            } catch (InterruptedException e) {
                // transfer interrupt state to caller thread.
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new GuaguaRuntimeException(e);
            }
        }

        /**
         * Real method to do coordinator operation.
         */
        public abstract void doExecute() throws Exception, InterruptedException;
    }

    /**
     * {@link RetryCoordinatorCommand} is used to wrap retry logic. {@link RetryCoordinatorCommand#retryExecution()}
     * will be retried by a fixed sleeping time or an increasing time.
     */
    public abstract static class RetryCoordinatorCommand extends BasicCoordinatorCommand {

        private long sleepUnitTime = WAIT_SLOT_MILLS;

        private boolean isFixedTime = true;

        private long startTime = 0;

        public RetryCoordinatorCommand(boolean isFixedTime, long sleepUnitTime) {
            this.isFixedTime = isFixedTime;
            this.sleepUnitTime = sleepUnitTime;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void doExecute() throws Exception, InterruptedException {
            int attempt = 0;
            do {
                ++attempt;
                if(this.isFixedTime) {
                    Thread.sleep(this.sleepUnitTime);
                } else {
                    Thread.sleep(attempt * this.sleepUnitTime);
                }
                if(retryExecution()) {
                    return;
                }
            } while(attempt < Integer.MAX_VALUE);
        }

        public abstract boolean retryExecution() throws Exception, InterruptedException;

        public long getElapsedTime() {
            return System.currentTimeMillis() - this.startTime;
        }

        /**
         * Return true for {@link #retryExecution()} if in minWorkersTimeout time get {@link Code (int) (workers *
         * minWorkersRatio))} workers completed.
         */
        protected boolean isTerminated(int workersCompleted, int workers, double minWorkersRatio, long minWorkersTimeout) {
            if(workers <= 10) {
                minWorkersRatio = 1d;
            }
            LOG.debug("DEBUG: workersCompleted={}, workers={}, minWorkersRatio={}, minWorkersTimeout={}",
                    workersCompleted, workers, minWorkersRatio, minWorkersTimeout);

            return workers == workersCompleted
                    || (getElapsedTime() >= minWorkersTimeout && workersCompleted >= (int) (workers * minWorkersRatio));
        }

    }

}
