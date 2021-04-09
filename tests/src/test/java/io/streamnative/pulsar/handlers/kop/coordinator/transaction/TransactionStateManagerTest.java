/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop.coordinator.transaction;

import com.google.common.collect.Sets;
import io.streamnative.pulsar.handlers.kop.KafkaProtocolHandler;
import io.streamnative.pulsar.handlers.kop.KopProtocolHandlerTestBase;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.collections.Maps;

/**
 * Transaction state manager test.
 */
@Slf4j
public class TransactionStateManagerTest extends KopProtocolHandlerTestBase {

    Short producerEpoch = 0;
    Integer transactionTimeoutMs = 1000;

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        this.conf.setEnableTransactionCoordinator(true);
        internalSetup();
        if (!admin.tenants().getTenants().contains("public")) {
            admin.tenants().createTenant("public",
                    new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        } else {
            admin.tenants().updateTenant("public",
                    new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        }
        if (!admin.namespaces().getNamespaces("public").contains("public/default")) {
            admin.namespaces().createNamespace("public/default");
            admin.namespaces().setNamespaceReplicationClusters("public/default", Sets.newHashSet("test"));
            admin.namespaces().setRetention("public/default",
                    new RetentionPolicies(60, 1000));
        }
        if (!admin.namespaces().getNamespaces("public").contains("public/__kafka")) {
            admin.namespaces().createNamespace("public/__kafka");
            admin.namespaces().setNamespaceReplicationClusters("public/__kafka", Sets.newHashSet("test"));
            admin.namespaces().setRetention("public/__kafka",
                    new RetentionPolicies(20, 100));
        }
        log.info("txn topic partition {}", admin.topics().getPartitionedTopicMetadata(
                TransactionConfig.DefaultTransactionMetadataTopicName).partitions);
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        internalCleanup();
    }

    @Test()
    public void loadTest() throws Exception {
        Map<String, Long> pidMappings = Maps.newHashMap();
        pidMappings.put("zero", 0L);
        pidMappings.put("one", 1L);
        pidMappings.put("two", 2L);
        pidMappings.put("three", 3L);
        pidMappings.put("four", 4L);
        pidMappings.put("five", 5L);

        Map<Long, TransactionState> transactionStates = Maps.newHashMap();
        transactionStates.put(0L, TransactionState.EMPTY);
        transactionStates.put(1L, TransactionState.ONGOING);
        transactionStates.put(2L, TransactionState.PREPARE_COMMIT);
        transactionStates.put(3L, TransactionState.COMPLETE_COMMIT);
        transactionStates.put(4L, TransactionState.PREPARE_ABORT);
        transactionStates.put(5L, TransactionState.COMPLETE_ABORT);

        Class<TransactionStateManager> stateManagerClass = TransactionStateManager.class;
        Field txnMetadataCacheField = stateManagerClass.getDeclaredField("transactionMetadataCache");
        txnMetadataCacheField.setAccessible(true);

        TransactionStateManager transactionStateManager = getTxnManager();

        CountDownLatch countDownLatch = new CountDownLatch(pidMappings.size());
        pidMappings.forEach((transactionalId, producerId) -> {
            TransactionMetadata.TransactionMetadataBuilder txnMetadataBuilder = TransactionMetadata.builder()
                    .transactionalId(transactionalId)
                    .producerId(producerId)
                    .lastProducerId(RecordBatch.NO_PRODUCER_ID)
                    .producerEpoch(producerEpoch)
                    .lastProducerEpoch(RecordBatch.NO_PRODUCER_EPOCH)
                    .txnTimeoutMs(transactionTimeoutMs)
                    .state(transactionStates.get(producerId))
                    .pendingState(Optional.of(transactionStates.get(producerId)))
                    .topicPartitions(Sets.newHashSet())
                    .txnStartTimestamp(transactionStates.get(producerId) == TransactionState.EMPTY
                            ? -1 : System.currentTimeMillis());

            if (transactionStates.get(producerId).equals(TransactionState.COMPLETE_ABORT)
                    || transactionStates.get(producerId).equals(TransactionState.COMPLETE_COMMIT)) {
                txnMetadataBuilder.txnStartTimestamp(0);
            }
            TransactionMetadata txnMetadata = txnMetadataBuilder.build();

            transactionStateManager.putTransactionStateIfNotExists(txnMetadata);
            transactionStateManager.appendTransactionToLog(transactionalId, -1, txnMetadata.prepareNoTransit(),
                    new TransactionStateManager.ResponseCallback() {
                        @Override
                        public void complete() {
                            log.info("Success append transaction log.");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void fail(Errors errors) {
                            log.error("Failed append transaction log.", errors.exception());
                            countDownLatch.countDown();
                            Assert.fail("Failed append transaction log.");
                        }
                    }, errors -> false);
        });
        countDownLatch.await();

        Map<Integer, Map<String, TransactionMetadata>> txnMetadataCache =
                (Map<Integer, Map<String, TransactionMetadata>>) txnMetadataCacheField.get(transactionStateManager);
        // retain the transaction metadata cache
        Map<Integer, Map<String, TransactionMetadata>> beforeTxnMetadataCache = new HashMap<>(txnMetadataCache);

        stopBroker();
        // when stop broker, clear the cache
        Assert.assertEquals(0, txnMetadataCache.size());
        startBroker();

        // verify the loaded transaction metadata
        Map<Integer, Map<String, TransactionMetadata>> loadedTxnMetadataCache =
                (Map<Integer, Map<String, TransactionMetadata>>) txnMetadataCacheField.get(getTxnManager());
        for (int i = 0; i < conf.getTxnLogTopicNumPartitions(); i++) {
            Map<String, TransactionMetadata> txnMetadataMap = beforeTxnMetadataCache.get(i);
            Map<String, TransactionMetadata> loadedTxnMetadataMap = loadedTxnMetadataCache.get(i);
            if (txnMetadataMap == null) {
                Assert.assertNull(loadedTxnMetadataMap);
                continue;
            }
            Assert.assertEquals(txnMetadataMap.size(), loadedTxnMetadataMap.size());
            txnMetadataMap.forEach((txnId, txnMetadata) -> {
                TransactionMetadata loadedTxnMetadata = loadedTxnMetadataMap.get(txnId);
                Assert.assertEquals(txnMetadata.getTransactionalId(), loadedTxnMetadata.getTransactionalId());
                Assert.assertEquals(txnMetadata.getProducerId(), loadedTxnMetadata.getProducerId());
                Assert.assertEquals(txnMetadata.getLastProducerId(), loadedTxnMetadata.getLastProducerId());
                Assert.assertEquals(txnMetadata.getProducerEpoch(), loadedTxnMetadata.getProducerEpoch());
                Assert.assertEquals(txnMetadata.getLastProducerEpoch(), loadedTxnMetadata.getLastProducerEpoch());
                Assert.assertEquals(txnMetadata.getTxnTimeoutMs(), loadedTxnMetadata.getTxnTimeoutMs());
                Assert.assertEquals(txnMetadata.getTopicPartitions(), loadedTxnMetadata.getTopicPartitions());
                Assert.assertEquals(txnMetadata.getTxnStartTimestamp(), loadedTxnMetadata.getTxnStartTimestamp());
                if (txnMetadata.getState().equals(TransactionState.PREPARE_ABORT)) {
                    // the prepare state will complete
                    Assert.assertEquals(TransactionState.COMPLETE_ABORT, loadedTxnMetadata.getState());
                    Assert.assertTrue(loadedTxnMetadata.getTxnLastUpdateTimestamp() > 0);
                } else if (txnMetadata.getState().equals(TransactionState.PREPARE_COMMIT)) {
                    // the prepare state will complete
                    Assert.assertEquals(TransactionState.COMPLETE_COMMIT, loadedTxnMetadata.getState());
                    Assert.assertTrue(loadedTxnMetadata.getTxnLastUpdateTimestamp() > 0);
                } else {
                    Assert.assertEquals(txnMetadata.getState(), loadedTxnMetadata.getState());
                    Assert.assertEquals(txnMetadata.getTxnLastUpdateTimestamp(),
                            loadedTxnMetadata.getTxnLastUpdateTimestamp());
                }
            });
        }
    }

    private TransactionStateManager getTxnManager() {
        return ((KafkaProtocolHandler) this.pulsar.getProtocolHandlers().protocol("kafka"))
                        .getTransactionCoordinator().getTxnManager();
    }

}