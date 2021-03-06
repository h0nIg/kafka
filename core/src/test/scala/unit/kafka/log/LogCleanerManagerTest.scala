/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.log

import java.io.File
import java.util.Properties

import kafka.server.{BrokerTopicStats, LogDirFailureChannel}
import kafka.utils._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record._
import org.apache.kafka.common.utils.Utils
import org.junit.Assert._
import org.junit.{After, Test}
import org.scalatest.junit.JUnitSuite

/**
  * Unit tests for the log cleaning logic
  */
class LogCleanerManagerTest extends JUnitSuite with Logging {

  val tmpDir = TestUtils.tempDir()
  val logDir = TestUtils.randomPartitionLogDir(tmpDir)
  val logProps = new Properties()
  logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)
  logProps.put(LogConfig.SegmentIndexBytesProp, 1024: java.lang.Integer)
  logProps.put(LogConfig.CleanupPolicyProp, LogConfig.Compact)
  val logConfig = LogConfig(logProps)
  val time = new MockTime(1400000000000L, 1000L)  // Tue May 13 16:53:20 UTC 2014 for `currentTimeMs`

  @After
  def tearDown(): Unit = {
    Utils.delete(tmpDir)
  }

  /**
    * When checking for logs with segments ready for deletion
    * we shouldn't consider logs where cleanup.policy=delete
    * as they are handled by the LogManager
    */
  @Test
  def testLogsWithSegmentsToDeleteShouldNotConsiderCleanupPolicyDeleteLogs(): Unit = {
    val records = TestUtils.singletonRecords("test".getBytes)
    val log: Log = createLog(records.sizeInBytes * 5, LogConfig.Delete)
    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    val readyToDelete = cleanerManager.deletableLogs().size
    assertEquals("should have 0 logs ready to be deleted", 0, readyToDelete)
  }

  /**
    * We should find logs with segments ready to be deleted when cleanup.policy=compact,delete
    */
  @Test
  def testLogsWithSegmentsToDeleteShouldConsiderCleanupPolicyCompactDeleteLogs(): Unit = {
    val records = TestUtils.singletonRecords("test".getBytes, key="test".getBytes)
    val log: Log = createLog(records.sizeInBytes * 5, LogConfig.Compact + "," + LogConfig.Delete)
    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    val readyToDelete = cleanerManager.deletableLogs().size
    assertEquals("should have 1 logs ready to be deleted", 1, readyToDelete)
  }

  /**
    * When looking for logs with segments ready to be deleted we should consider
    * logs with cleanup.policy=compact because they may have segments from before the log start offset
    */
  @Test
  def testLogsWithSegmentsToDeleteShouldConsiderCleanupPolicyCompactLogs(): Unit = {
    val records = TestUtils.singletonRecords("test".getBytes, key="test".getBytes)
    val log: Log = createLog(records.sizeInBytes * 5, LogConfig.Compact)
    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    val readyToDelete = cleanerManager.deletableLogs().size
    assertEquals("should have 1 logs ready to be deleted", 1, readyToDelete)
  }

  /**
    * log with retention in progress should not be picked up for compaction and vice versa when log cleanup policy
    * is changed between "compact" and "delete"
    */
  @Test
  def testLogsWithRetentionInprogressShouldNotPickedUpForCompactionAndViceVersa(): Unit = {
    val records = TestUtils.singletonRecords("test".getBytes, key="test".getBytes)
    val log: Log = createLog(records.sizeInBytes * 5, LogConfig.Delete)
    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    log.appendAsLeader(records, leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(records, leaderEpoch = 0)
    log.onHighWatermarkIncremented(2L)

    // simulate retention thread working on the log partition
    val deletableLog = cleanerManager.pauseCleaningForNonCompactedPartitions()
    assertEquals("should have 1 logs ready to be deleted", 1, deletableLog.size)

    // change cleanup policy from delete to compact
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, log.config.segmentSize)
    logProps.put(LogConfig.RetentionMsProp, log.config.retentionMs)
    logProps.put(LogConfig.CleanupPolicyProp, LogConfig.Compact)
    logProps.put(LogConfig.MinCleanableDirtyRatioProp, 0: Integer)
    val config = LogConfig(logProps)
    log.config = config

    // log retention inprogress, the log is not available for compaction
    val cleanable = cleanerManager.grabFilthiestCompactedLog(time)
    assertEquals("should have 0 logs ready to be compacted", 0, cleanable.size)

    // log retention finished, and log can be picked up for compaction
    cleanerManager.resumeCleaning(deletableLog.map(_._1))
    val cleanable2 = cleanerManager.grabFilthiestCompactedLog(time)
    assertEquals("should have 1 logs ready to be compacted", 1, cleanable2.size)

    // update cleanup policy to delete
    logProps.put(LogConfig.CleanupPolicyProp, LogConfig.Delete)
    val config2 = LogConfig(logProps)
    log.config = config2

    // compaction in progress, should have 0 log eligible for log retention
    val deletableLog2 = cleanerManager.pauseCleaningForNonCompactedPartitions()
    assertEquals("should have 0 logs ready to be deleted", 0, deletableLog2.size)

    // compaction done, should have 1 log eligible for log retention
    cleanerManager.doneDeleting(Seq(cleanable2.get.topicPartition))
    val deletableLog3 = cleanerManager.pauseCleaningForNonCompactedPartitions()
    assertEquals("should have 1 logs ready to be deleted", 1, deletableLog3.size)
  }

  /**
    * Test computation of cleanable range with no minimum compaction lag settings active
    */
  @Test
  def testCleanableOffsetsForNone(): Unit = {
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)

    val log = makeLog(config = LogConfig.fromProps(logConfig.originals, logProps))

    while(log.numberOfSegments < 8)
      log.appendAsLeader(records(log.logEndOffset.toInt, log.logEndOffset.toInt, time.milliseconds()), leaderEpoch = 0)

    val topicPartition = new TopicPartition("log", 0)
    val lastClean = Map(topicPartition -> 0L)
    val cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition, lastClean, time.milliseconds)
    assertEquals("The first cleanable offset starts at the beginning of the log.", 0L, cleanableOffsets._1)
    assertEquals("The first uncleanable offset begins with the active segment.", log.activeSegment.baseOffset, cleanableOffsets._2)
  }

  /**
    * Test computation of cleanable range with a minimum compaction lag time
    */
  @Test
  def testCleanableOffsetsForTime(): Unit = {
    val compactionLag = 60 * 60 * 1000
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)
    logProps.put(LogConfig.MinCompactionLagMsProp, compactionLag: java.lang.Integer)

    val log = makeLog(config = LogConfig.fromProps(logConfig.originals, logProps))

    val t0 = time.milliseconds
    while(log.numberOfSegments < 4)
      log.appendAsLeader(records(log.logEndOffset.toInt, log.logEndOffset.toInt, t0), leaderEpoch = 0)

    val activeSegAtT0 = log.activeSegment

    time.sleep(compactionLag + 1)
    val t1 = time.milliseconds

    while (log.numberOfSegments < 8)
      log.appendAsLeader(records(log.logEndOffset.toInt, log.logEndOffset.toInt, t1), leaderEpoch = 0)

    val topicPartition = new TopicPartition("log", 0)
    val lastClean = Map(topicPartition -> 0L)
    val cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition, lastClean, time.milliseconds)
    assertEquals("The first cleanable offset starts at the beginning of the log.", 0L, cleanableOffsets._1)
    assertEquals("The first uncleanable offset begins with the second block of log entries.", activeSegAtT0.baseOffset, cleanableOffsets._2)
  }

  /**
    * Test computation of cleanable range with a minimum compaction lag time that is small enough that
    * the active segment contains it.
    */
  @Test
  def testCleanableOffsetsForShortTime(): Unit = {
    val compactionLag = 60 * 60 * 1000
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)
    logProps.put(LogConfig.MinCompactionLagMsProp, compactionLag: java.lang.Integer)

    val log = makeLog(config = LogConfig.fromProps(logConfig.originals, logProps))

    val t0 = time.milliseconds
    while (log.numberOfSegments < 8)
      log.appendAsLeader(records(log.logEndOffset.toInt, log.logEndOffset.toInt, t0), leaderEpoch = 0)

    time.sleep(compactionLag + 1)

    val topicPartition = new TopicPartition("log", 0)
    val lastClean = Map(topicPartition -> 0L)
    val cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition, lastClean, time.milliseconds)
    assertEquals("The first cleanable offset starts at the beginning of the log.", 0L, cleanableOffsets._1)
    assertEquals("The first uncleanable offset begins with active segment.", log.activeSegment.baseOffset, cleanableOffsets._2)
  }

  @Test
  def testUndecidedTransactionalDataNotCleanable(): Unit = {
    val topicPartition = new TopicPartition("log", 0)
    val compactionLag = 60 * 60 * 1000
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)
    logProps.put(LogConfig.MinCompactionLagMsProp, compactionLag: java.lang.Integer)

    val log = makeLog(config = LogConfig.fromProps(logConfig.originals, logProps))

    val producerId = 15L
    val producerEpoch = 0.toShort
    val sequence = 0
    log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, producerId, producerEpoch, sequence,
      new SimpleRecord(time.milliseconds(), "1".getBytes, "a".getBytes),
      new SimpleRecord(time.milliseconds(), "2".getBytes, "b".getBytes)), leaderEpoch = 0)
    log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, producerId, producerEpoch, sequence + 2,
      new SimpleRecord(time.milliseconds(), "3".getBytes, "c".getBytes)), leaderEpoch = 0)
    log.roll()
    log.onHighWatermarkIncremented(3L)

    time.sleep(compactionLag + 1)
    // although the compaction lag has been exceeded, the undecided data should not be cleaned
    var cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition,
      Map(topicPartition -> 0L), time.milliseconds())
    assertEquals(0L, cleanableOffsets._1)
    assertEquals(0L, cleanableOffsets._2)

    log.appendAsLeader(MemoryRecords.withEndTransactionMarker(time.milliseconds(), producerId, producerEpoch,
      new EndTransactionMarker(ControlRecordType.ABORT, 15)), leaderEpoch = 0, isFromClient = false)
    log.roll()
    log.onHighWatermarkIncremented(4L)

    // the first segment should now become cleanable immediately
    cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition,
      Map(topicPartition -> 0L), time.milliseconds())
    assertEquals(0L, cleanableOffsets._1)
    assertEquals(3L, cleanableOffsets._2)

    time.sleep(compactionLag + 1)

    // the second segment becomes cleanable after the compaction lag
    cleanableOffsets = LogCleanerManager.cleanableOffsets(log, topicPartition,
      Map(topicPartition -> 0L), time.milliseconds())
    assertEquals(0L, cleanableOffsets._1)
    assertEquals(4L, cleanableOffsets._2)
  }

  @Test
  def testDoneCleaning(): Unit = {
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 1024: java.lang.Integer)
    val log = makeLog(config = LogConfig.fromProps(logConfig.originals, logProps))
    while(log.numberOfSegments < 8)
      log.appendAsLeader(records(log.logEndOffset.toInt, log.logEndOffset.toInt, time.milliseconds()), leaderEpoch = 0)

    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    val tp = new TopicPartition("log", 0)
    intercept[IllegalStateException](cleanerManager.doneCleaning(tp, log.dir, 1))

    cleanerManager.setCleaningState(tp, LogCleaningPaused)
    intercept[IllegalStateException](cleanerManager.doneCleaning(tp, log.dir, 1))

    cleanerManager.setCleaningState(tp, LogCleaningInProgress)
    cleanerManager.doneCleaning(tp, log.dir, 1)
    assertTrue(cleanerManager.cleaningState(tp).isEmpty)
    assertTrue(cleanerManager.allCleanerCheckpoints.get(tp).nonEmpty)

    cleanerManager.setCleaningState(tp, LogCleaningAborted)
    cleanerManager.doneCleaning(tp, log.dir, 1)
    assertEquals(LogCleaningPaused, cleanerManager.cleaningState(tp).get)
    assertTrue(cleanerManager.allCleanerCheckpoints.get(tp).nonEmpty)
  }

  @Test
  def testDoneDeleting(): Unit = {
    val records = TestUtils.singletonRecords("test".getBytes, key="test".getBytes)
    val log: Log = createLog(records.sizeInBytes * 5, LogConfig.Compact + "," + LogConfig.Delete)
    val cleanerManager: LogCleanerManager = createCleanerManager(log)

    val tp = new TopicPartition("log", 0)

    intercept[IllegalStateException](cleanerManager.doneDeleting(Seq(tp)))

    cleanerManager.setCleaningState(tp, LogCleaningPaused)
    intercept[IllegalStateException](cleanerManager.doneDeleting(Seq(tp)))

    cleanerManager.setCleaningState(tp, LogCleaningInProgress)
    cleanerManager.doneDeleting(Seq(tp))
    assertTrue(cleanerManager.cleaningState(tp).isEmpty)

    cleanerManager.setCleaningState(tp, LogCleaningAborted)
    cleanerManager.doneDeleting(Seq(tp))
    assertEquals(LogCleaningPaused, cleanerManager.cleaningState(tp).get)

  }

  private def createCleanerManager(log: Log): LogCleanerManager = {
    val logs = new Pool[TopicPartition, Log]()
    logs.put(new TopicPartition("log", 0), log)
    val cleanerManager = new LogCleanerManager(Array(logDir), logs, null)
    cleanerManager
  }

  private def createLog(segmentSize: Int, cleanupPolicy: String): Log = {
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, segmentSize: Integer)
    logProps.put(LogConfig.RetentionMsProp, 1: Integer)
    logProps.put(LogConfig.CleanupPolicyProp, cleanupPolicy)

    val config = LogConfig(logProps)
    val partitionDir = new File(logDir, "log-0")
    val log = Log(partitionDir,
      config,
      logStartOffset = 0L,
      recoveryPoint = 0L,
      scheduler = time.scheduler,
      time = time,
      brokerTopicStats = new BrokerTopicStats,
      maxProducerIdExpirationMs = 60 * 60 * 1000,
      producerIdExpirationCheckIntervalMs = LogManager.ProducerIdExpirationCheckIntervalMs,
      logDirFailureChannel = new LogDirFailureChannel(10))
    log
  }

  private def makeLog(dir: File = logDir, config: LogConfig) =
    Log(dir = dir, config = config, logStartOffset = 0L, recoveryPoint = 0L, scheduler = time.scheduler,
      time = time, brokerTopicStats = new BrokerTopicStats, maxProducerIdExpirationMs = 60 * 60 * 1000,
      producerIdExpirationCheckIntervalMs = LogManager.ProducerIdExpirationCheckIntervalMs,
      logDirFailureChannel = new LogDirFailureChannel(10))

  private def records(key: Int, value: Int, timestamp: Long) =
    MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord(timestamp, key.toString.getBytes, value.toString.getBytes))

}
