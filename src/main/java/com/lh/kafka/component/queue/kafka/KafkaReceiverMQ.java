package com.lh.kafka.component.queue.kafka;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import com.lh.kafka.component.queue.kafka.adapter.KafkaMessageAdapter;
import com.lh.kafka.component.queue.kafka.client.consumer.IKafkaMsReceiverClient;
import com.lh.kafka.component.queue.kafka.cons.KafkaConstants;
import com.lh.kafka.component.queue.kafka.support.Batch;
import com.lh.kafka.component.queue.kafka.support.Commit;
import com.lh.kafka.component.queue.kafka.support.KafkaTopic;
import com.lh.kafka.component.queue.kafka.thread.KafkaThreadFactory;
import com.lh.kafka.component.queue.kafka.thread.MsHandlerThread;
import com.lh.kafka.component.queue.kafka.thread.MsReceiverThread;

/**
 * @author 林浩<hao.lin@w-oasis.com>
 * @version 创建时间：2018年3月29日 上午11:03:23
 * 说明：此方式为默认自动消费方式
 */
public class KafkaReceiverMQ<K, V> extends KafakaMQ<K, V> implements IKafkaReceiverMQ<K, V> {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaReceiverMQ.class);
    
    /**
     * 提交方式：默认自动提交
     */
    private Commit commit = Commit.AUTO_COMMIT;
    
    /**
     * 是否批量消费：默认单个消费
     */
    private Batch batch = Batch.NO;
    
    /**
     * 接收本地线程
     */
    private BlockingQueue<ConsumerRecords<K, V>> blockingQueue;
    
    /**   
     * 消息接收器列表(size: 指定topic的partition大小)
     */
    private List<MsReceiverThread<K,V>> msReceiverThreads = new ArrayList<MsReceiverThread<K,V>>();
    
    /**
     * 消息接收处理线程
     */
    private List<MsHandlerThread<K,V>> msHandlerThreads = new ArrayList<MsHandlerThread<K,V>>();

    /**
     * 构造方法
     * @param config
     * @param messageAdapter
     */
    public KafkaReceiverMQ(Resource config, KafkaMessageAdapter<? extends Serializable, ? extends Serializable> messageAdapter) {
        super(config, messageAdapter);
    }

    /**
     * 构造方法
     * @param config
     * @param messageAdapter
     * @param commit
     */
    public KafkaReceiverMQ(Resource config, KafkaMessageAdapter<? extends Serializable, ? extends Serializable> messageAdapter, Commit commit) {
        super(config, messageAdapter);
        setCommit(commit);
    }

    public Commit getCommit() {
        return commit;
    }

    public void setCommit(Commit commit) {
        this.commit = commit;
        
        //判断是否不用自动提交
        if (!this.commit.equals(Commit.AUTO_COMMIT))
            props.setProperty(KafkaConstants.ENABLE_AUTO_COMMIT, "false");
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public BlockingQueue<ConsumerRecords<K, V>> getBlockingQueue() {
        return blockingQueue;
    }

    public void setBlockingQueue(BlockingQueue<ConsumerRecords<K, V>> blockingQueue) {
        this.blockingQueue = blockingQueue;
    }

    @Override
    public void start() {
        if(isRunning()){
            logger.info("kafka receiver mq start fail. because it has running...");
            return;
        }
        
        //获取一个新的接收器
        IKafkaMsReceiverClient<K, V> receiver = getNewReceiver();

        String topic = messageAdapter.getKafkaTopic().getTopic();
        int partitionCount = receiver.getPartitionCount(topic);
        if(this.poolSize == 0 || this.poolSize > partitionCount){
            setPoolSize(partitionCount);
        }
        
        switch (this.model) {
        case MODEL_1:
            //初始化接收线程池(线程池大小由partition决定)
            receiverExecutorService = Executors.newFixedThreadPool(this.getPoolSize(), new KafkaThreadFactory(topic));
            break;
        case MODEL_2:
            //初始化队列大小
            blockingQueue = new LinkedBlockingDeque<ConsumerRecords<K,V>>(this.asyncQueueSize);
            receiverExecutorService = Executors.newFixedThreadPool(this.getPoolSize(), new KafkaThreadFactory(topic));
            
            //初始化处理线程
            int handleSize = this.getPoolSize() * this.getAsyncHandleCoefficient() + 1;
            handlerExecutorService = Executors.newFixedThreadPool(handleSize, new KafkaThreadFactory(topic));
            for (int i = 0; i < handleSize; i++) {
                MsHandlerThread<K, V> msHandlerThread = new MsHandlerThread<K, V>(messageAdapter, blockingQueue);
                msHandlerThreads.add(msHandlerThread);
                handlerExecutorService.submit(msHandlerThread);
            }
            logger.info("Message receiver mq handle thread initialized size:{}.", handleSize);
            break;
        default:
            //归还接收器
            returnReceiver(receiver);
            logger.warn("Message receiver mq start by no model.");
            return;
        }
        
        int len = getPoolSize();
        for (int i = 0; i < len; i++) {
            //设置配置属性
            Properties properties = (Properties) props.clone();
            properties.setProperty(KafkaConstants.CLIENT_ID, getClientId() + "-" + topic + "-" + i);
            
            MsReceiverThread<K, V> msReceiverThread = new MsReceiverThread<K, V>(receiver, 
                    messageAdapter, blockingQueue, getModel(), getBatch(), getCommit(),
                    getMsReceiverThreadSleepTime(), getMsPollTimeout(), new KafkaTopic(topic));
            msReceiverThreads.add(msReceiverThread);
            receiverExecutorService.submit(msReceiverThread);
        }

        logger.info("Message receiver mq reveicer thread initialized size:{}.", len);
        
        running.set(true);
    }

    @Override
    public void destroy() {
        //关闭所有的接收线程
        for(MsReceiverThread<K, V> msReceiverThread : msReceiverThreads){
            msReceiverThread.shutdown();
        }
        
        //关闭消息接收线程池
        if(receiverExecutorService != null){
            receiverExecutorService.shutdown();
            logger.info("Message receiver thread pool closed.");
        }
        
        //阻塞等待队列释放
        if (blockingQueue != null){
            while (!blockingQueue.isEmpty()){
                logger.info("Waitting local queue empty.Current size : " + blockingQueue.size());
            }
        }
        
        //关闭所有的处理线程
        for(MsHandlerThread<K, V> msHandlerThread : msHandlerThreads){
            msHandlerThread.shutdown();
        }
        
        //关闭消息处理线程池
        if(handlerExecutorService != null){
            handlerExecutorService.shutdown();
            logger.info("Message handler thread pool closed.");
        }
        
        //标记不在执行
        running.set(false);
    }
}
