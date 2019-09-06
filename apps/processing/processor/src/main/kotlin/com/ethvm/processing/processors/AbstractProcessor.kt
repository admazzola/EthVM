package com.ethvm.processing.processors

import com.ethvm.avro.capture.CanonicalKeyRecord
import com.ethvm.common.config.NetConfig
import com.ethvm.common.extensions.bigInteger
import com.ethvm.db.Tables.SYNC_STATUS
import com.ethvm.db.Tables.SYNC_STATUS_HISTORY
import com.ethvm.db.tables.records.SyncStatusHistoryRecord
import com.ethvm.db.tables.records.SyncStatusRecord
import com.ethvm.processing.cache.BlockHashCache
import mu.KLogger
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.joda.time.DateTime
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.koin.core.qualifier.named
import org.mapdb.DB
import org.mapdb.DBMaker
import java.math.BigInteger
import java.sql.Timestamp
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService

interface Processor : Runnable {

  fun initialise()

  fun rewindUntil(rewindBlockNumber: BigInteger)

  fun reset()

  fun stop()
}

enum class BlockType {
  NEW, DUPLICATE, FORK
}

abstract class AbstractProcessor<V>(protected val processorId: String) : KoinComponent, Processor {

  protected abstract val logger: KLogger

  // the base directory in which all persistent processing state will be stored
  private val storageDir: String by inject(named("storageDir"))

  // system wide base kafka properties
  private val baseKafkaProps: Properties by inject(named("baseKafkaProps"))

  // optional kafka consumer property overrides
  protected open val kafkaProps: Properties = Properties()

  // list of kafka topics to consume
  protected abstract val topics: List<String>

  // max amount of time to block when polling the kafka consumer for new records
  protected val pollTimeout = Duration.ofSeconds(10)

  // the network config e.g. mainnet, ropsten etc.
  protected val netConfig: NetConfig by inject()

  // base database context
  private val dbContext: DSLContext by inject()

  // used for evicting keys from the in memory caches
  protected val scheduledExecutor: ScheduledExecutorService by inject()

  // flatten all the various kafka properties into the realised properties for this processor
  private val mergedKafkaProps by lazy {
    val merged = Properties()

    // base settings
    merged.putAll(baseKafkaProps)

    // convenience setting of group id for the consumer
    merged.put(ConsumerConfig.GROUP_ID_CONFIG, "${netConfig.chainId.name.toLowerCase()}-$processorId")

    // disable auto commit, we will explicitly tell kafka when we have finished processing a given set of records
    merged.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)

    // processor specific settings and overrides
    merged.putAll(kafkaProps)

    merged
  }

  // map db instance for persistent storage
  protected val diskDb: DB

  // in memory map db instance for caching
  protected val memoryDb: DB

  // a cache of [blockNumber] => blockhash used for detecting new, duplicate or fork blocks
  private val hashCache: BlockHashCache

  // kafka consumer for ingesting data
  private lateinit var consumer: KafkaConsumer<CanonicalKeyRecord, V>

  @Volatile
  private var stop = false

  private val stopLatch = CountDownLatch(1)

  protected open val maxTransactionTime: Duration = Duration.ofMillis(100)

  init {

    // setup local storage db

    diskDb = DBMaker
      .fileDB("$storageDir/$processorId")
      .fileMmapEnable()
      .fileMmapEnableIfSupported()
      .fileMmapPreclearDisable()
      .cleanerHackEnable()
      .transactionEnable()
      .make()

    memoryDb = DBMaker
      .memoryDirectDB()
      .make()

    // create hash cache
    hashCache = BlockHashCache(memoryDb, scheduledExecutor, processorId)
  }

  protected abstract fun initialise(txCtx: DSLContext, latestBlockNumber: BigInteger)

  protected abstract fun reset(txCtx: DSLContext)

  protected abstract fun rewindUntil(txCtx: DSLContext, blockNumber: BigInteger)

  protected abstract fun blockHashFor(value: V): String

  protected abstract fun process(txCtx: DSLContext, record: ConsumerRecord<CanonicalKeyRecord, V>)

  override fun initialise() {

    dbContext.transaction { txConfig ->

      val txCtx = DSL.using(txConfig)

      logger.info { "Initialising..." }

      // initialise our hash cache

      hashCache.initialise(txCtx)

      // determine the last progress we made

      val latestSyncStatus = getLatestSyncRecord(dbContext)
      val latestBlockNumber = latestSyncStatus?.blockNumber?.toBigInteger() ?: BigInteger.ONE.negate()

      logger.info { "Latest sync block number = $latestBlockNumber" }

      // call the implementation initialise method

      initialise(txCtx, latestBlockNumber)

      logger.info { "initialised" }
    }
  }

  override fun reset() {

    dbContext.transaction { txConfig ->

      val txCtx = DSL.using(txConfig)

      // reset the hash cache

      hashCache.reset(txCtx)

      // allow the implementation to reset

      reset(txCtx)

      // reset the sync status history for this processor

      txCtx
        .deleteFrom(SYNC_STATUS)
        .where(SYNC_STATUS.COMPONENT.eq(processorId))
        .execute()

      txCtx
        .deleteFrom(SYNC_STATUS_HISTORY)
        .where(SYNC_STATUS_HISTORY.COMPONENT.eq(processorId))
        .execute()

    }
  }

  override fun rewindUntil(rewindBlockNumber: BigInteger) {

    logger.info { "Rewind requested for $processorId to block number $rewindBlockNumber (inclusive)" }

    try {

      dbContext.transaction { txConfig ->

        val txCtx = DSL.using(txConfig)

        // Find closest batch end in sync_status_history before the rewind block number as it is inclusive

        val lastBatchSyncStatus = txCtx
          .selectFrom(SYNC_STATUS_HISTORY)
          .where(SYNC_STATUS_HISTORY.COMPONENT.eq(processorId))
          .and(SYNC_STATUS_HISTORY.BLOCK_NUMBER.lessThan(rewindBlockNumber.toBigDecimal()))
          .orderBy(SYNC_STATUS_HISTORY.BLOCK_NUMBER.desc())
          .limit(1)
          .fetchOne()

        // determine the rewind until block number based on the last batch
        // e.g. we want to rewind until block 500 which is in the middle of a batch. The previous batch
        // ended with block 450. So we rewind all state until and including 451

        val closestBatchBlockNumber = lastBatchSyncStatus.blockNumber?.toBigInteger() ?: BigInteger.ONE.negate()
        val rewindUntilBlockNumber = closestBatchBlockNumber.plus(BigInteger.ONE)

        logger.info { "Closest batch block number = $closestBatchBlockNumber for $processorId" }
        logger.info { "Rewinding until and including $rewindUntilBlockNumber" }

        rewindUntil(txCtx, rewindUntilBlockNumber)

        // remove all knowledge of any blocks from the rewind block number forward so we will re-process them
        hashCache.removeKeysFrom(txCtx, rewindUntilBlockNumber)

        logger.info { "HashCache updated, $processorId latest block number = $closestBatchBlockNumber" }

        // delete sync status history

        txCtx
          .deleteFrom(SYNC_STATUS_HISTORY)
          .where(SYNC_STATUS_HISTORY.COMPONENT.eq(processorId))
          .and(SYNC_STATUS_HISTORY.BLOCK_NUMBER.ge(rewindUntilBlockNumber.toBigDecimal()))
          .execute()

        // update latest sync status

        val syncStatusRecord = SyncStatusRecord()
          .apply {
            this.component = lastBatchSyncStatus.component
            this.blockNumber = lastBatchSyncStatus.blockNumber
            this.blockTimestamp = lastBatchSyncStatus.blockTimestamp
            this.timestamp = lastBatchSyncStatus.timestamp
          }

        txCtx
          .insertInto(SYNC_STATUS)
          .set(syncStatusRecord)
          .onDuplicateKeyUpdate()
          .set(SYNC_STATUS.BLOCK_NUMBER, syncStatusRecord.blockNumber)
          .set(SYNC_STATUS.BLOCK_TIMESTAMP, syncStatusRecord.blockTimestamp)
          .set(SYNC_STATUS.TIMESTAMP, syncStatusRecord.timestamp)
          .execute()

        logger.info { "Sync status history updated, $processorId latest block number = $closestBatchBlockNumber" }

        diskDb.commit()
      }

    } catch (e: Exception) {

      logger.error(e) { "Fatal exception. Rolling back local changes" }
      diskDb.rollback()

    }

  }

  override fun run() {

    try {

      // determine the chain time to reset the kafka consumer to

      val latestSyncRecord = getLatestSyncRecord(dbContext)
      val latestSyncTimeMs = latestSyncRecord?.blockTimestamp?.time ?: 0L

      var restartTimeMs = latestSyncTimeMs - Duration.ofHours(3).toMillis()
      if (restartTimeMs < 0L) restartTimeMs = 0L

      // initialise the kafka consumer and subscribe to topics

      consumer = KafkaConsumer(mergedKafkaProps)
      consumer.subscribe(this.topics)

      logger.info { "Last sync time = ${DateTime(latestSyncTimeMs)}. Re-setting consumer to time = ${DateTime(restartTimeMs)}" }

      // we poll so we are assigned topics then we will re-seek before consuming again

      consumer.poll(pollTimeout)

      val offsetsQuery = consumer
        .assignment()
        .map { topicPartition -> topicPartition to restartTimeMs }
        .toMap()

      consumer
        .offsetsForTimes(offsetsQuery)
        .forEach { (topicPartition, offsetAndTimestamp) ->
          consumer.seek(topicPartition, offsetAndTimestamp?.offset() ?: 0L)
        }

      // main processing loop

      while (!stop) {

        // fetch the next batch of records from kafka. This will block for up to pollTimeout seconds
        val records = consumer.poll(pollTimeout)

        // if empty return
        if (records.isEmpty) continue

        // using the hash cache we classify each of the records we have received

        val classifiedRecords =
          records
            .map { record ->

              val blockNumber = record.key().number.bigInteger()
              val blockHash = blockHashFor(record.value())

              // lookup to see if we have encountered this block before

              when (hashCache[blockNumber]) {

                // never seen this before, it's new
                null -> Pair(BlockType.NEW, record)

                // hash cache entry matches the current block hash, so this is a duplicate
                blockHash -> {
                  logger.warn { "Ignoring duplicate block. Number = $blockNumber, hash = $blockHash" }
                  Pair(BlockType.DUPLICATE, record)
                }

                // otherwise we have an entry in the hash cache but it does not match the current block hash
                // due to the design of the parity block source we know this to be the beginning of a fork
                else -> Pair(BlockType.FORK, record)

              }
            }

        // now that we have classified the records we start to process them

        val classifiedRecordIterator = classifiedRecords
          // filter out the duplicates
          .filterNot { it.first == BlockType.DUPLICATE }
          .iterator()

        while (classifiedRecordIterator.hasNext()) {

          // reset

          var recordCount = 0
          var lastRecord: ConsumerRecord<CanonicalKeyRecord, V>? = null

          //

          val maxTxTimeMs = maxTransactionTime.toMillis()
          val txStartTimeMs = System.currentTimeMillis()
          var txElapsedTimeMs = 0L

          // we write as much as we can into a transaction until we reach the max time

          try {

            dbContext
              .transaction { txConfig ->

                val txCtx = DSL.using(txConfig)

                // we keep taking records until we breach the max tx time or the iterator has no more records

                while (txElapsedTimeMs < maxTxTimeMs && classifiedRecordIterator.hasNext()) {

                  // retrieve the next block with it's classification
                  val (blockType, record) = classifiedRecordIterator.next()

                  // used for updating sync status at the end
                  lastRecord = record

                  val blockNumber = record.key().number.bigInteger()

                  when (blockType) {

                    // if it's a new block we allow the implementation to process it and then we update the hash cache

                    BlockType.NEW -> {

                      process(txCtx, record)

                      // update hash entry
                      hashCache[blockNumber] = blockHashFor(record.value())
                    }

                    // if it's a fork block we first allow the implementation to rewind and then process the new blcok

                    BlockType.FORK -> {

                      rewindUntil(txCtx, blockNumber)
                      hashCache.removeKeysFrom(txCtx, blockNumber)

                      process(txCtx, record)
                      hashCache[blockNumber] = blockHashFor(record.value())

                    }

                    else -> {} // do nothing
                  }

                  // record the record count and update the tx elapsed time

                  recordCount += 1
                  txElapsedTimeMs = System.currentTimeMillis() - txStartTimeMs

                }

                // flush the hash cache state to the db
                hashCache.writeToDb(txCtx)

                // update latest block number
                val lastBlockNumber = lastRecord!!.key().number.bigInteger()
                setLatestSyncBlock(txCtx, lastBlockNumber, lastRecord!!.timestamp())

                // flush to disk
                diskDb.commit()

                logger.info { "Tx elapsed time = $txElapsedTimeMs ms. Record count = $recordCount. Latest block number = $lastBlockNumber, block time = ${Date(lastRecord!!.timestamp())}" }
              }

          } catch (e: Exception) {

            // rollback any persistent changes to local state
            diskDb.rollback()

            // re throw to stop processing
            throw e
          }

        }

        // commit to kafka
        consumer.commitSync()

        val last = records.last()
        logger.info { "Kafka batch complete. Count = ${records.count()}, head = ${last.key().number.bigInteger()}, block timestamp = ${last.timestamp()}" }
      }
    } catch (e: Exception) {

      logger.error(e) { "Fatal exception, stopping" }

    } finally {

      close()
      // wake up the caller of the stop method
      stopLatch.countDown()
    }
  }

  protected fun getLatestSyncRecord(txCtx: DSLContext): SyncStatusRecord? =
    txCtx
      .selectFrom(SYNC_STATUS)
      .where(SYNC_STATUS.COMPONENT.eq(processorId))
      .fetchOne()

  protected fun setLatestSyncBlock(txCtx: DSLContext, blockNumber: BigInteger, blockTimestamp: Long) {

    val blockNumberDecimal = blockNumber.toBigDecimal()
    val timestampNowMs = Timestamp(System.currentTimeMillis())
    val blockTimestampMs = Timestamp(blockTimestamp)

    val record = SyncStatusHistoryRecord()
      .apply {
        this.component = processorId
        this.blockNumber = blockNumberDecimal
        this.blockTimestamp = blockTimestampMs
        this.timestamp = timestampNowMs
      }

    // history

    txCtx
      .insertInto(SYNC_STATUS_HISTORY)
      .set(record)
      .execute()

    // update latest

    val latestRecord = SyncStatusRecord()
      .apply {
        this.component = record.component
        this.blockNumber = record.blockNumber
        this.blockTimestamp = record.blockTimestamp
        this.timestamp = record.timestamp
      }

    txCtx
      .insertInto(SYNC_STATUS)
      .set(latestRecord)
      .onDuplicateKeyUpdate()
      .set(SYNC_STATUS.BLOCK_NUMBER, latestRecord.blockNumber)
      .set(SYNC_STATUS.BLOCK_TIMESTAMP, latestRecord.blockTimestamp)
      .set(SYNC_STATUS.TIMESTAMP, latestRecord.timestamp)
      .execute()
  }

  override fun stop() {

    logger.info { "stop requested" }
    this.stop = true

    stopLatch.await()
  }

  private fun close() {

    diskDb.close()
    memoryDb.close()

    consumer.close(Duration.ofSeconds(30))
    logger.info { "clean up complete" }
  }
}
