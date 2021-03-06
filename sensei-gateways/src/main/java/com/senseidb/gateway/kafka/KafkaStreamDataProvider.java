/**
 * This software is licensed to you under the Apache License, Version 2.0 (the
 * "Apache License").
 *
 * LinkedIn's contributions are made under the Apache License. If you contribute
 * to the Software, the contributions will be deemed to have been made under the
 * Apache License, unless you expressly indicate otherwise. Please do not make any
 * contributions that would be inconsistent with the Apache License.
 *
 * You may obtain a copy of the Apache License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, this software
 * distributed under the Apache License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Apache
 * License for the specific language governing permissions and limitations for the
 * software governed under the Apache License.
 *
 * © 2012 LinkedIn Corp. All Rights Reserved.  
 */

package com.senseidb.gateway.kafka;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import kafka.message.MessageAndMetadata;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import proj.zoie.api.DataConsumer.DataEvent;
import proj.zoie.impl.indexing.StreamDataProvider;

import com.senseidb.indexing.DataSourceFilter;
import com.senseidb.metrics.MetricFactory;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;

public class KafkaStreamDataProvider extends StreamDataProvider<JSONObject>{

  private final Set<String> _topics;
  private final String _consumerGroupId;
  private Properties _kafkaConfig;
  private ConsumerConnector _consumerConnector;
  private Iterator<byte[]> _consumerIterator;
  private ExecutorService _executorService;
  private Meter _indexingErrorMeter;
  private Meter _indexingTimeoutMeter;
  
  private static Logger logger = Logger.getLogger(KafkaStreamDataProvider.class);
    private final String _zookeeperUrl;
    private final int _kafkaSoTimeout;
    private volatile boolean _started = false;
    private final DataSourceFilter<DataPacket> _dataConverter;
  
  public KafkaStreamDataProvider(Comparator<String> versionComparator,String zookeeperUrl,int soTimeout,int batchSize,
                                 String consumerGroupId,String topic,long startingOffset,DataSourceFilter<DataPacket> dataConverter){
    this(versionComparator, zookeeperUrl, soTimeout, batchSize, consumerGroupId, topic, startingOffset, dataConverter, new Properties());
  }

  public KafkaStreamDataProvider(Comparator<String> versionComparator,String zookeeperUrl,int soTimeout,int batchSize,
                                 String consumerGroupId,String topic,long startingOffset,DataSourceFilter<DataPacket> dataConverter,Properties kafkaConfig){
    super(versionComparator);
    _consumerGroupId = consumerGroupId;
    _topics = new HashSet<String>();
    for (String raw : topic.split("[, ;]+"))
    {
      String t = raw.trim();
      if (t.length() != 0)
      {
        _topics.add(t);
      }
    }
    super.setBatchSize(batchSize);
    _zookeeperUrl = zookeeperUrl;
    _kafkaSoTimeout = soTimeout;
    _consumerConnector = null;
    _consumerIterator = null;

    _kafkaConfig = kafkaConfig;
    if (kafkaConfig == null) {
      kafkaConfig = new Properties();
    }

    _dataConverter = dataConverter;
    if (_dataConverter == null){
      throw new IllegalArgumentException("kafka data converter is null");
    }
    _indexingErrorMeter = MetricFactory.newMeter(new MetricName(KafkaStreamDataProvider.class, "indexing-error"),
				"error-rate",
				TimeUnit.SECONDS);
    _indexingTimeoutMeter = MetricFactory.newMeter(new MetricName(KafkaStreamDataProvider.class, "indexing-timeout"),
				"timeout-rate",
				TimeUnit.SECONDS);
  }
  
  @Override
  public void setStartingOffset(String version){
  }
  
  @Override
  public DataEvent<JSONObject> next() {
    if (!_started) return null;

    try
    {
      if (!_consumerIterator.hasNext())
        return null;
    }
    catch (Exception e)
    {
      // Most likely timeout exception - ok to ignore
      _indexingTimeoutMeter.mark();
      return null;
    }

    byte[] msg = _consumerIterator.next();
    if (logger.isDebugEnabled()){
      logger.debug("got new message: "+msg);
    }
    long version = System.currentTimeMillis();
    
    JSONObject data;
    try {
      data = _dataConverter.filter(new DataPacket(msg,0,msg.length));
      
      if (logger.isDebugEnabled()){
        logger.debug("message converted: "+data);
      }
      return new DataEvent<JSONObject>(data, String.valueOf(version));
    } catch (Exception e) {
      logger.error(e.getMessage(),e);
      _indexingErrorMeter.mark();
      return null;
    }
  }

  @Override
  public void reset() {
  }

  @Override
  public void start() {
    Properties props = new Properties();

    // remove after kafka 0.8 migration is finished
    props.put("zk.connect", _zookeeperUrl);
    //props.put("consumer.timeout.ms", _kafkaSoTimeout);
    props.put("groupid", _consumerGroupId);

    // kafka 0.8 configs
    props.put("zookeeper.connect", _zookeeperUrl);
    props.put("group.id", _consumerGroupId);

    for (String key : _kafkaConfig.stringPropertyNames()) {
      props.put(key, _kafkaConfig.getProperty(key));
    }

    logger.info("Kafka properties: " + props);

    ConsumerConfig consumerConfig = new ConsumerConfig(props);
    _consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);

    Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
    for (String topic : _topics)
    {
      topicCountMap.put(topic, 1);
    }

    Map<String, List<KafkaStream<byte[],byte[]>>> topicMessageStreams =
        _consumerConnector.createMessageStreams(topicCountMap);

    final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(8, true);

    _executorService = Executors.newFixedThreadPool(topicMessageStreams.size());


    for (List<KafkaStream<byte[], byte[]>> streams : topicMessageStreams.values())
    {
      for (KafkaStream<byte[], byte[]> stream : streams)
      {
        final KafkaStream<byte[], byte[]> messageStream = stream;
        _executorService.execute(new Runnable()
          {
            @Override
            public void run()
            {
            logger.info("Kafka consumer thread started: " + Thread.currentThread().getId());
            try
            {
              for (MessageAndMetadata<byte[], byte[]> message : messageStream)
              {
                queue.put(message.message());
              }
            }
            catch(Exception e)
            {
              // normally it should the stop interupt exception.
              logger.error(e.getMessage(), e);
            }
            logger.info("Kafka consumer thread ended: " + Thread.currentThread().getId());
            }
          });
      }
    }

    _consumerIterator = new Iterator<byte[]>()
    {
      private byte[] message = null;

      @Override
      public boolean hasNext()
      {
        if (message != null)  return true;

        try
        {
          message = queue.poll(1, TimeUnit.SECONDS);
        }
        catch(InterruptedException ie)
        {
          return false;
        }

        if (message != null)
        {
          return true;
        }
        else
        {
          return false;
        }
      }

      @Override
      public byte[] next()
      {
        if (hasNext())
        {
          byte[] res = message;
          message = null;
          return res;
        }
        else
        {
          throw new NoSuchElementException();
        }
      }

      @Override
      public void remove()
      {
        throw new UnsupportedOperationException("not supported");
      }
    };

    super.start();
    _started = true;
  }

  @Override
  public void stop() {
    _started = false;

    try
    {
      if (_executorService != null)
      {
        _executorService.shutdown();
      }
    }
    finally
    {
      try
      {
        if (_consumerConnector != null)
        {
          _consumerConnector.shutdown();
        }
      }
      finally
      {
        if (_indexingErrorMeter != null)
        {
          _indexingErrorMeter.stop();
        }
        if (_indexingTimeoutMeter != null)
        {
          _indexingTimeoutMeter.stop();
        }
        super.stop();
      }
    }
  }  
}
