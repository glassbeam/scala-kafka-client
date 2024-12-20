package cakesolutions.kafka.testkit

import java.io.File
import java.net.ServerSocket

import kafka.server._
import kafka.server.KafkaServer
import org.apache.curator.test.TestingServer
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetResetStrategy}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
//import scala.jdk.CollectionConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Random, Try}

object KafkaServer {

  private val log = LoggerFactory.getLogger(getClass)

  private def randomAvailablePort(): Int = {
    val socket = new ServerSocket(0)
    val port = socket.getLocalPort
    socket.close()
    port
  }

  /**
    * Default configurations used by the Kafka test server
    */
  val defaultConfig: Map[String, String] = Map.empty[String, String]

    /** Map(
    KafkaConfig.BrokerIdProp  -> "1",
    KafkaConfig.ReplicaSocketTimeoutMsProp -> "1500",
    KafkaConfig.ControlledShutdownEnableProp -> "true",
   // KafkaConfig.AdvertisedHostNameProp -> "localhost",
    KafkaConfig.AdvertisedListenersProp -> "localhost"
  ) **/


  /**
    * Default configurations used for consuming records using [[KafkaServer]].
    */
  val defaultConsumerConfig: Map[String, String] = Map(
    ConsumerConfig.GROUP_ID_CONFIG -> "test",
    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> OffsetResetStrategy.EARLIEST.toString.toLowerCase
  )

  /**
    * Default configurations used for producing records using [[KafkaServer]].
    */
  val defaultProducerConfig: Map[String, String] = Map(
    ProducerConfig.ACKS_CONFIG -> "all"
  )

  private def createConfig(
    port: Int,
    zookeeperPort: Int,
    logDir: File,
    otherOptions: Map[String, String]
  ): KafkaConfig = {

    val baseConfig = Map(
      "port" -> port.toString,
      "zookeeper.connect" -> ("localhost:" + zookeeperPort.toString),
      "log.dir" -> logDir.getAbsolutePath,
      "offsets.topic.replication.factor" -> "1",
      "transaction.state.log.replication.factor" -> "1",
      "transaction.state.log.min.isr" -> "1",
      "min.insync.replicas" -> "1"
    )

    val extendedConfig = otherOptions ++ baseConfig

    new KafkaConfig(extendedConfig.asJava)
  }

  private def createTempDir(dirPrefix: String): File = {
    val randomString = Random.alphanumeric.take(10).mkString("")
    val f = new File(System.getProperty("java.io.tmpdir"), dirPrefix + randomString)
    f.deleteOnExit()
    f
  }

  private def deleteFile(path: File): Unit = {
    if (path.isDirectory)
      path.listFiles().foreach(deleteFile)

    path.delete()
  }
}

/**
  * A startable Kafka and Zookeeper server. Kafka and ZookeeperPort is generated by default.
  */
final class KafkaServer(
  val kafkaPort: Int = KafkaServer.randomAvailablePort(),
  val zookeeperPort: Int = KafkaServer.randomAvailablePort(),
  kafkaConfig: Map[String, String] = KafkaServer.defaultConfig
) {

  import KafkaServer._

  private val logDir = createTempDir("kafka-local")

  private val bootstrapServerAddress = "localhost:" + kafkaPort.toString

  // Zookeeper server
  private val zkServer = new TestingServer(zookeeperPort, false)

  // Build Kafka config with zookeeper connection
  private val config = createConfig(kafkaPort, zookeeperPort, logDir = logDir, kafkaConfig)

  // Kafka Test Server
  private val kafkaServer = new KafkaRaftServer(config, time = ???)
  //Server(config)

  def startup(): Unit = {
    log.info("ZK Connect String: {}", zkServer.getConnectString)
    zkServer.start()
    kafkaServer.startup()
    log.info(s"Started kafka on port [$kafkaPort]")
  }

  def close(): Unit = {
    log.info(s"Stopping kafka on port [$kafkaPort]")
    kafkaServer.shutdown()
    zkServer.stop()
    Try(deleteFile(logDir)).failed.foreach(_.printStackTrace)
  }

  /**
    * Consume records from Kafka synchronously.
    *
    * Calling this function block the function call for a given amount of time
    * or until expected number of records have been received from Kafka.
    * Runtime exception is thrown if the consumer didn't receive expected amount of records.
    *
    * @param topic the topic to subscribe to
    * @param expectedNumOfRecords maximum number of messages to read from Kafka
    * @param timeout the duration in milliseconds for consuming messages
    * @param keyDeserializer the deserializer for the record key
    * @param valueDeserializer the deserializer for the record value
    * @param consumerConfig custom configurations for Kafka consumer
    * @return a sequence of key-value pairs received from Kafka
    */
  def consume[Key, Value](
    topic: String,
    expectedNumOfRecords: Int,
    timeout: Long,
    keyDeserializer: Deserializer[Key],
    valueDeserializer: Deserializer[Value],
    consumerConfig: Map[String, String] = defaultConsumerConfig
  ): Seq[(Option[Key], Value)] = {
    val extendedConfig: Map[String, Object] = consumerConfig + (ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServerAddress)
    val consumer = new KafkaConsumer(extendedConfig.asJava, keyDeserializer, valueDeserializer)

    try {
      consumer.subscribe(List(topic).asJava)

      var total = 0
      val collected = ArrayBuffer.empty[(Option[Key], Value)]
      val start = System.currentTimeMillis()

      while (total < expectedNumOfRecords && System.currentTimeMillis() < start + timeout) {
        val records = consumer.poll(100)
        val kvs = records.asScala.map(r => (Option(r.key()), r.value()))
        collected ++= kvs
        total += records.count()
      }

      if (collected.size < expectedNumOfRecords) {
        sys.error(s"Did not receive expected amount records. Expected $expectedNumOfRecords but got ${collected.size}.")
      }

      collected.toVector
    } finally {
      consumer.close()
    }
  }

  /**
    * Send records to Kafka synchronously.
    *
    * Calling this function will block until all records have been successfully written to Kafka.
    *
    * @param records the records to write to Kafka
    * @param keySerializer the serializer for the record key
    * @param valueSerializer the serializer for the record value
    * @param producerConfig custom configurations for Kafka producer
    */
  def produce[Key, Value](
    records: Iterable[ProducerRecord[Key, Value]],
    keySerializer: Serializer[Key],
    valueSerializer: Serializer[Value],
    producerConfig: Map[String, String] = defaultProducerConfig
  ): Unit = {
    val extendedConfig: Map[String, Object] = producerConfig + (ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServerAddress)
    val producer = new KafkaProducer(extendedConfig.asJava, keySerializer, valueSerializer)

    try {
      records.foreach { r => producer.send(r) }
    } finally {
      producer.flush()
      producer.close()
    }
  }
}
