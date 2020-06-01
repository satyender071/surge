// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams.core

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ Actor, ActorRef, ActorSystem, Props, Stash }
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.ultimatesoftware.config.TimeoutConfig
import com.ultimatesoftware.kafka.streams.HealthyActor.GetHealth
import com.ultimatesoftware.kafka.streams._
import com.ultimatesoftware.scala.core.kafka._
import com.ultimatesoftware.scala.core.monitoring.metrics.{ MetricsProvider, Rate, Timer }
import org.apache.kafka.clients.producer.{ ProducerConfig, ProducerRecord }
import org.apache.kafka.common.errors.{ AuthorizationException, ProducerFencedException, UnsupportedVersionException }
import org.apache.kafka.common.{ KafkaException, TopicPartition }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

/**
 * A stateful producer actor responsible for publishing all states + events for
 * aggregates that belong to a particular state topic partition.  The state maintained
 * by this producer actor is a list of aggregate ids which are considered "in flight".
 * "In flight" is determined by keeping track of the offset this actor publishes to for
 * each aggregate id as messages are published to Kafka and listening to updates of the downstream
 * Kafka Streams consumer as it makes progress through the topic.  As a state is published,
 * this actor remembers the aggregate id and offset the state for that aggregate is.  When the
 * Kafka Streams consumer processes the state (saving it to a KTable) it saves the offset of the most
 * recently processed message for a partition to a global KTable.  This producer actor polls that global
 * table to get the last processed offset and marks any aggregates in the "in flight" state as up to date
 * if their offset is less than or equal to the last processed offset.
 *
 * When an aggregate actor wants to initialize, it must first ask this stateful producer if
 * the state for that aggregate is up to date in the Kafka Streams state store KTable.  The
 * stateful producer is able to determine this by looking at the aggregates with states that
 * are in flight - if any are in flight for an aggregate, the state in the KTable is not up to
 * date and initialization of that actor should be delayed.
 *
 * On initialization of the stateful producer, it emits an empty "flush" record to the Kafka
 * state topic.  The flush record is for an empty aggregate id, but is used to ensure on initial startup
 * that there were no remaining aggregate states that were in flight, since the newly created
 * producer cannot initialize with the knowledge of everything that was published previously.
 *
 * @param actorSystem The actor system to create the underlying stateful producer actor in
 * @param assignedPartition The state topic/partition assigned to this instance of the stateful producer.
 *                          The producer will use Kafka transactions to ensure that it is the only instance
 *                          of a stateful producer for a particular partition.  Any older producers for
 *                          that partition will be fenced out by Kafka.
 * @param metricsProvider Metrics provider interface to use for recording internal metrics to
 * @param stateMetaHandler An instance of the Kafka Streams partition metadata global KTable processor that
 *                         is responsible for tracking what offsets in Kafka have been processed by the aggregate
 *                         state indexer process.  The producer will query the underlying global KTable to determine
 *                         which aggregates are in flight.
 * @param aggregateCommandKafkaStreams Command service business logic wrapper used for determining state and event topics
 * @tparam AggId Generic aggregate id type for aggregates publishing states/events through this stateful producer
 * @tparam Agg Generic aggregate type of aggregates publishing states/events through this stateful producer
 * @tparam Event Generic base type for events that aggregate instances publish through this stateful producer
 */
class KafkaProducerActor[AggId, Agg, Event, EvtMeta](
    actorSystem: ActorSystem,
    assignedPartition: TopicPartition,
    metricsProvider: MetricsProvider,
    stateMetaHandler: GlobalKTableMetadataHandler,
    aggregateCommandKafkaStreams: KafkaStreamsCommandBusinessLogic[AggId, Agg, _, Event, _, EvtMeta]) extends HealthyComponent {

  private val log = LoggerFactory.getLogger(getClass)
  private val aggregateName: String = aggregateCommandKafkaStreams.aggregateName

  private val publisherActor = actorSystem.actorOf(
    Props(new KafkaProducerActorImpl(
      assignedPartition, metricsProvider, stateMetaHandler, aggregateCommandKafkaStreams)).withDispatcher("kafka-publisher-actor-dispatcher"))

  private val publishEventsTimer: Timer = metricsProvider.createTimer(s"${aggregateName}PublishEventsTimer")
  def publish(aggregateId: AggId, state: (String, Option[Agg]), events: Seq[(String, EvtMeta, Event)]): Future[Done] = {

    val eventKeyValuePairs = events.map {
      case (eventKey, eventMeta, event) ⇒
        log.trace(s"Publishing event for {} {}", Seq(aggregateName, eventKey): _*)
        eventKey -> aggregateCommandKafkaStreams.writeFormatting.writeEvent(event, eventMeta)
    }

    val (stateKey, stateValueOpt) = state
    val stateValue = stateValueOpt.map(aggregateCommandKafkaStreams.writeFormatting.writeState).orNull
    val stateKeyValuePair = stateKey -> stateValue
    log.trace(s"Publishing state for {} {}", Seq(aggregateName, stateKey): _*)

    publishEventsTimer.time {
      implicit val askTimeout: Timeout = Timeout(TimeoutConfig.PublisherActor.publishTimeout)
      (publisherActor ? KafkaProducerActorImpl.Publish(eventKeyValuePairs = eventKeyValuePairs, stateKeyValuePair = stateKeyValuePair))
        .map(_ ⇒ Done)(ExecutionContext.global)
    }
  }

  private val isAggregateStateCurrentTimer: Timer = metricsProvider.createTimer(s"${aggregateName}IsAggregateCurrentTimer")
  def isAggregateStateCurrent(aggregateId: String): Future[Boolean] = {
    implicit val askTimeout: Timeout = Timeout(TimeoutConfig.PublisherActor.aggregateStateCurrentTimeout)
    val expirationTime = Instant.now.plusMillis(askTimeout.duration.toMillis)
    isAggregateStateCurrentTimer.time {
      (publisherActor ? KafkaProducerActorImpl.IsAggregateStateCurrent(aggregateId, expirationTime)).mapTo[Boolean]
    }
  }

  def healthCheck(): Future[HealthCheck] = {
    publisherActor.ask(HealthyActor.GetHealth)(TimeoutConfig.HealthCheck.actorAskTimeout)
      .mapTo[HealthCheck]
      .recoverWith {
        case err: Throwable ⇒
          log.error(s"Failed to get publisher-actor health check", err)
          Future.successful(HealthCheck(
            name = "publisher-actor",
            id = aggregateName,
            status = HealthCheckStatus.DOWN))
      }(ExecutionContext.global)
  }
}

// TODO: evaluate access modifier here, it was private
private object KafkaProducerActorImpl {
  sealed trait KafkaProducerActorMessage
  case class Publish(stateKeyValuePair: (String, Array[Byte]), eventKeyValuePairs: Seq[(String, Array[Byte])]) extends KafkaProducerActorMessage
  case class StateProcessed(stateMeta: KafkaPartitionMetadata) extends KafkaProducerActorMessage
  case class IsAggregateStateCurrent(aggregateId: String, expirationTime: Instant) extends KafkaProducerActorMessage
  case class AggregateStateRates(current: Rate, notCurrent: Rate) extends KafkaProducerActorMessage

  sealed trait InternalMessage
  case class EventsPublished(originalSenders: Seq[ActorRef], recordMetadata: Seq[KafkaRecordMetadata[String]]) extends InternalMessage
  case object EventsFailedToPublish extends InternalMessage
  case object InitTransactions extends InternalMessage
  case object FailedToInitTransactions extends InternalMessage
  case class Initialize(endOffset: Long) extends InternalMessage
  case object FailedToInitialize extends InternalMessage
  case object FlushMessages extends InternalMessage
  case class PublishWithSender(sender: ActorRef, publish: Publish) extends InternalMessage
  case class PendingInitialization(actor: ActorRef, key: String, expiration: Instant) extends InternalMessage
}
private class KafkaProducerActorImpl[Agg, Event, EvtMeta](
    assignedPartition: TopicPartition, metrics: MetricsProvider,
    stateMetaHandler: GlobalKTableMetadataHandler,
    aggregateCommandKafkaStreams: KafkaStreamsCommandBusinessLogic[_, Agg, _, Event, _, _],
    kafkaProducerOverride: Option[KafkaBytesProducer] = None) extends Actor with Stash {

  import KafkaProducerActorImpl._
  import aggregateCommandKafkaStreams._
  import context.dispatcher
  import kafka._

  private val log: Logger = LoggerFactory.getLogger(getClass)

  private val config = ConfigFactory.load()
  private val assignedTopicPartitionKey = s"${assignedPartition.topic}:${assignedPartition.partition}"
  private val flushInterval = config.getDuration("kafka.publisher.flush-interval", TimeUnit.MILLISECONDS).milliseconds
  private val brokers = config.getString("kafka.brokers").split(",")

  private val transactionalIdPrefix = aggregateCommandKafkaStreams.transactionalIdPrefix
  // TODO revisit this - seems like a long transactional id, but may be close to what we want
  private val transactionalId = s"$transactionalIdPrefix-${assignedPartition.topic()}-${assignedPartition.partition()}"

  private var kafkaPublisher = getPublisher()

  private val nonTransactionalStatePublisher = kafkaProducerOverride.getOrElse(KafkaBytesProducer(brokers, stateTopic, partitioner = partitioner))

  private val kafkaPublisherTimer: Timer = metrics.createTimer(s"${aggregateName}AggregateStateKafkaPublisherTimer")
  private implicit val rates: AggregateStateRates = AggregateStateRates(
    current = metrics.createRate(s"${aggregateName}AggregateStateCurrentRate"),
    notCurrent = metrics.createRate(s"${aggregateName}AggregateStateNotCurrentRate"))

  context.system.scheduler.scheduleOnce(10.milliseconds, self, InitTransactions)
  context.system.scheduler.scheduleWithFixedDelay(200.milliseconds, 200.milliseconds)(() ⇒ refreshStateMeta())
  context.system.scheduler.scheduleWithFixedDelay(flushInterval, flushInterval, self, FlushMessages)

  private def getPublisher(): KafkaBytesProducer = {
    kafkaProducerOverride.getOrElse(newPublisher())
  }

  private def newPublisher() = {
    val kafkaConfig = Map[String, String](
      // ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG -> true, // For exactly once writes. How does this impact performance?
      ProducerConfig.TRANSACTIONAL_ID_CONFIG -> transactionalId)

    KafkaBytesProducer(brokers, stateTopic, partitioner, kafkaConfig)
  }

  override def receive: Receive = uninitialized

  private def uninitialized: Receive = {
    case InitTransactions           ⇒ initializeTransactions()
    case msg: Initialize            ⇒ handle(msg)
    case FailedToInitialize         ⇒ context.system.scheduler.scheduleOnce(3.seconds)(initializeState())
    case FlushMessages              ⇒ // Ignore from this state
    case GetHealth                  ⇒ getHealthCheck()
    case _: Publish                 ⇒ stash()
    case _: StateProcessed          ⇒ stash()
    case _: IsAggregateStateCurrent ⇒ stash()
    case unknown                    ⇒ log.warn("Receiving unhandled message {} on uninitialized state", unknown.getClass.getName)
  }

  private def recoveringBacklog(endOffset: Long): Receive = {
    case msg: StateProcessed        ⇒ handleFromRecoveringState(endOffset, msg)
    case FlushMessages              ⇒ // Ignore from this state
    case GetHealth                  ⇒ getHealthCheck()
    case _: Publish                 ⇒ stash()
    case _: IsAggregateStateCurrent ⇒ stash()
    case unknown                    ⇒ log.warn("Receiving unhandled message {} on recoveringBacklog state", unknown.getClass.getName)
  }

  private def processing(state: KafkaProducerActorState): Receive = {
    case msg: Publish                 ⇒ handle(state, msg)
    case msg: EventsPublished         ⇒ handle(state, msg)
    case EventsFailedToPublish        ⇒ handleFailedToPublish(state)
    case msg: StateProcessed          ⇒ handle(state, msg)
    case msg: IsAggregateStateCurrent ⇒ handle(state, msg)
    case GetHealth                    ⇒ getHealthCheck(state)
    case FlushMessages                ⇒ handleFlushMessages(state)
    case Done                         ⇒ // Ignore to prevent these messages to become dead letters
  }

  private def sendFlushRecord: Future[InternalMessage] = {
    val flushRecord = new ProducerRecord[String, Array[Byte]](assignedPartition.topic(), assignedPartition.partition(), "", "".getBytes)

    nonTransactionalStatePublisher.putRecord(flushRecord).map { flushRecordMeta ⇒
      val flushRecordOffset = flushRecordMeta.wrapped.offset()
      log.debug("Flush Record for partition {} is at offset {}", assignedPartition, flushRecordOffset)

      Initialize(flushRecordOffset)
    } recover {
      case e ⇒
        log.error("Failed to initialize kafka producer actor state", e)
        FailedToInitialize
    }
  }

  private def initializeTransactions(): Unit = {
    kafkaPublisher.initTransactions().map { _ ⇒
      log.debug(s"KafkaPublisherActor transactions successfully initialized: $assignedPartition")
      initializeState()
    }.recover {
      case err @ (_: UnsupportedVersionException | _: AuthorizationException | _: KafkaException) ⇒
        log.error(s"KafkaPublisherActor failed to initialize transactions with a FATAL error", err)
        log.debug("Restarting publisher and retrying in 3 seconds")
        kafkaPublisher = getPublisher()
        context.system.scheduler.scheduleOnce(3.seconds, self, InitTransactions)
      case err: Throwable ⇒
        log.error(s"KafkaPublisherActor failed to initialize kafka transactions, retrying in 3 seconds: $assignedPartition", err)
        context.system.scheduler.scheduleOnce(3.seconds, self, InitTransactions)
    }
  }

  private def initializeState(): Unit = {
    sendFlushRecord pipeTo self
  }

  private def refreshStateMeta(): Unit = {
    stateMetaHandler.isOpen().map { isOpen ⇒
      if (isOpen) {
        stateMetaHandler.getMeta(assignedTopicPartitionKey).map {
          case Some(meta) ⇒
            self ! StateProcessed(meta)
          case _ ⇒
            val meta = KafkaPartitionMetadata(assignedPartition.topic, assignedPartition.partition, offset = -1L, key = "")
            self ! StateProcessed(meta)
        } recover {
          case e ⇒
            log.error(s"Failed to fetch state metadata for $assignedPartition", e)
        }
      } else {
        log.trace(s"Skipping State Meta Refresh, stream is not open for ${assignedPartition}")
      }
    }
  }

  private def handle(initialize: Initialize): Unit = {
    log.info(s"Publisher actor initializing for topic-partition $assignedPartition with end offset ${initialize.endOffset}")
    context.become(recoveringBacklog(initialize.endOffset))
  }

  private def handle(state: KafkaProducerActorState, publish: Publish): Unit = {
    context.become(processing(state.addPendingWrites(sender(), publish)))
  }

  private def handle(state: KafkaProducerActorState, stateProcessed: StateProcessed): Unit = {
    context.become(processing(state.processedUpTo(stateProcessed.stateMeta)))
    sender() ! Done
  }

  private def handleFromRecoveringState(endOffset: Long, stateProcessed: StateProcessed): Unit = {
    val stateMeta = stateProcessed.stateMeta

    log.trace("KafkaPublisherActor partition {} received StateMeta {}", Seq(assignedPartition, stateMeta): _*)
    val partitionIsCurrent = assignedPartition.topic() == stateMeta.topic &&
      assignedPartition.partition() == stateMeta.partition &&
      endOffset <= stateMeta.offset

    if (partitionIsCurrent) {
      log.info(s"KafkaPublisherActor partition {} is fully up to date on processing", assignedPartition)
      unstashAll()
      context.become(processing(KafkaProducerActorState.empty))
    }
  }

  private def handle(state: KafkaProducerActorState, eventsPublished: EventsPublished): Unit = {
    val newState = state.addInFlight(eventsPublished.recordMetadata).completeTransaction()
    context.become(processing(newState))
    eventsPublished.originalSenders.foreach(_ ! Done)
  }

  private def handleFailedToPublish(state: KafkaProducerActorState): Unit = {
    val newState = state.completeTransaction()
    context.become(processing(newState))
  }

  private var lastTransactionInProgressWarningTime: Instant = Instant.ofEpochMilli(0L)
  private val eventsPublishedRate: Rate = metrics.createRate(s"${aggregateName}EventPublishRate")
  private def handleFlushMessages(state: KafkaProducerActorState): Unit = {
    if (state.transactionInProgress) {
      if (lastTransactionInProgressWarningTime.plusSeconds(1L).isBefore(Instant.now())) {
        lastTransactionInProgressWarningTime = Instant.now
        log.warn(s"KafkaPublisherActor partition {} tried to flush, but another transaction is already in progress. " +
          s"The previous transaction has been in progress for {} milliseconds. If the time to complete the previous transaction continues to grow " +
          s"that typically indicates slowness in the Kafka brokers.", assignedPartition, state.currentTransactionTimeMillis)
      }
    } else if (state.pendingWrites.nonEmpty) {
      val eventMessages = state.pendingWrites.flatMap(_.publish.eventKeyValuePairs)
      val stateMessages = state.pendingWrites.map(_.publish.stateKeyValuePair)

      val eventRecords = eventMessages.map {
        case (eventKey, eventValue) ⇒
          new ProducerRecord(eventsTopic.name, eventKey, eventValue)
      }
      val stateRecords = stateMessages.map {
        case (aggregateKey, aggregateValue) ⇒
          new ProducerRecord(stateTopic.name, assignedPartition.partition(), aggregateKey, aggregateValue)
      }
      val records = eventRecords ++ stateRecords

      log.info(s"KafkaPublisherActor partition {} writing {} events to Kafka", assignedPartition, eventRecords.length)
      log.info(s"KafkaPublisherActor partition {} writing {} states to Kafka", assignedPartition, stateRecords.length)
      eventsPublishedRate.mark(eventMessages.length)
      doFlushRecords(state, records)
    }
  }

  private def doFlushRecords(state: KafkaProducerActorState, records: Seq[ProducerRecord[String, Array[Byte]]]): Unit = {
    val senders = state.pendingWrites.map(_.sender)
    val futureMsg = kafkaPublisherTimer.time {
      Try(kafkaPublisher.beginTransaction()) match {
        case Failure(_: ProducerFencedException) ⇒
          producerFenced()
          Future.successful(EventsFailedToPublish) // Only used for the return type, the actor is stopped in the producerFenced() method
        case Failure(err) ⇒
          log.error(s"KafkaPublisherActor partition $assignedPartition there was an error beginning transaction", err)
          Future.successful(EventsFailedToPublish)
        case _ ⇒
          Future.sequence(kafkaPublisher.putRecords(records)).map { recordMeta ⇒
            log.info(s"KafkaPublisherActor partition {} committing transaction", assignedPartition)
            kafkaPublisher.commitTransaction()
            EventsPublished(senders, recordMeta.filter(_.wrapped.topic() == stateTopic.name))
          } recover {
            case _: ProducerFencedException ⇒
              producerFenced()
            case e ⇒
              log.error(s"KafkaPublisherActor partition $assignedPartition got error while trying to publish to Kafka", e)
              kafkaPublisher.abortTransaction()
              EventsFailedToPublish
          }
      }
    }
    context.become(processing(state.flushWrites().startTransaction()))
    futureMsg.pipeTo(self)(sender())
  }

  private def producerFenced(): Unit = {
    val producerFencedErrorLog = s"KafkaPublisherActor partition $assignedPartition tried to commit a transaction, but was " +
      s"fenced out by another producer instance. This instance of the producer for the assigned partition will shut down in favor of the " +
      s"newer producer for this partition.  If this message persists, check that two independent application clusters are not using the same " +
      s"transactional id prefix of [$transactionalId] for the same Kafka cluster."
    log.error(producerFencedErrorLog)
    context.stop(self)
  }

  private def handle(state: KafkaProducerActorState, isAggregateStateCurrent: IsAggregateStateCurrent): Unit = {
    val aggregateId = isAggregateStateCurrent.aggregateId
    val noRecordsInFlight = state.inFlightForAggregate(aggregateId).isEmpty

    if (noRecordsInFlight) {
      rates.current.mark()
      sender() ! noRecordsInFlight
    } else {
      context.become(processing(state.addPendingInitialization(sender(), isAggregateStateCurrent)))
    }
  }

  private def getHealthCheck(): Unit = {
    val healthCheck = HealthCheck(
      name = "producer-actor",
      id = assignedTopicPartitionKey,
      status = HealthCheckStatus.UP)
    sender() ! healthCheck
  }

  private def getHealthCheck(state: KafkaProducerActorState): Unit = {
    val transactionsAppearStuck = state.currentTransactionTimeMillis > 2.minutes.toMillis

    val healthStatus = if (transactionsAppearStuck) {
      HealthCheckStatus.DOWN
    } else {
      HealthCheckStatus.UP
    }

    val healthCheck = HealthCheck(
      name = "producer-actor",
      id = assignedTopicPartitionKey,
      status = healthStatus,
      details = Some(Map(
        "inFlight" -> state.inFlight.size.toString,
        "pendingInitializations" -> state.pendingInitializations.size.toString,
        "pendingWrites" -> state.pendingWrites.size.toString,
        "currentTransactionTimeMillis" -> state.currentTransactionTimeMillis.toString)))
    sender() ! healthCheck
  }
}

private[core] object KafkaProducerActorState {
  def empty(implicit sender: ActorRef, rates: KafkaProducerActorImpl.AggregateStateRates): KafkaProducerActorState = {
    KafkaProducerActorState(Seq.empty, Seq.empty, Seq.empty, transactionInProgressSince = None, sender = sender, rates = rates)
  }
}
// TODO optimize:
//  Add in a warning if state gets too large
private[core] case class KafkaProducerActorState(
    inFlight: Seq[KafkaRecordMetadata[String]],
    pendingWrites: Seq[KafkaProducerActorImpl.PublishWithSender],
    pendingInitializations: Seq[KafkaProducerActorImpl.PendingInitialization],
    transactionInProgressSince: Option[Instant],
    sender: ActorRef, rates: KafkaProducerActorImpl.AggregateStateRates) {

  import KafkaProducerActorImpl._

  private implicit val senderActor: ActorRef = sender
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def transactionInProgress: Boolean = transactionInProgressSince.nonEmpty
  def currentTransactionTimeMillis: Long = {
    Instant.now.minusMillis(transactionInProgressSince.getOrElse(Instant.now).toEpochMilli).toEpochMilli
  }

  def inFlightByKey: Map[String, Seq[KafkaRecordMetadata[String]]] = {
    inFlight.groupBy(_.key.getOrElse(""))
  }

  def inFlightForAggregate(aggregateId: String): Seq[KafkaRecordMetadata[String]] = {
    inFlightByKey.getOrElse(aggregateId, Seq.empty)
  }

  def addPendingInitialization(sender: ActorRef, isAggregateStateCurrent: IsAggregateStateCurrent): KafkaProducerActorState = {
    val pendingInitialization = PendingInitialization(
      sender,
      isAggregateStateCurrent.aggregateId, isAggregateStateCurrent.expirationTime)

    this.copy(pendingInitializations = pendingInitializations :+ pendingInitialization)
  }

  def addPendingWrites(sender: ActorRef, publish: Publish): KafkaProducerActorState = {
    val newWriteRequest = PublishWithSender(sender, publish)
    this.copy(pendingWrites = pendingWrites :+ newWriteRequest)
  }

  def flushWrites(): KafkaProducerActorState = {
    this.copy(pendingWrites = Seq.empty)
  }

  def startTransaction(): KafkaProducerActorState = {
    this.copy(transactionInProgressSince = Some(Instant.now))
  }

  def completeTransaction(): KafkaProducerActorState = {
    this.copy(transactionInProgressSince = None)
  }

  def addInFlight(recordMetadata: Seq[KafkaRecordMetadata[String]]): KafkaProducerActorState = {
    val newTotalInFlight = inFlight ++ recordMetadata
    val newInFlight = newTotalInFlight.groupBy(_.key).mapValues(_.maxBy(_.wrapped.offset())).values

    this.copy(inFlight = newInFlight.toSeq)
  }

  def processedUpTo(stateMeta: KafkaPartitionMetadata): KafkaProducerActorState = {
    val processedRecordsFromPartition = inFlight.filter(record ⇒ record.wrapped.offset() <= stateMeta.offset)

    if (processedRecordsFromPartition.nonEmpty) {
      val processedOffsets = processedRecordsFromPartition.map(_.wrapped.offset())
      log.debug(s"${stateMeta.topic}:${stateMeta.partition} processed up to offset ${stateMeta.offset}. " +
        s"Outstanding offsets that were processed are [${processedOffsets.min} -> ${processedOffsets.max}]")
    }
    val newInFlight = inFlight.filterNot(processedRecordsFromPartition.contains)

    val newPendingAggregates = {
      val processedAggregates = pendingInitializations.filter { pending ⇒
        !newInFlight.exists(_.key.contains(pending.key))
      }
      if (processedAggregates.nonEmpty) {
        processedAggregates.foreach { pending ⇒
          pending.actor ! true
        }
        rates.current.mark(processedAggregates.length)
      }

      val expiredAggregates = pendingInitializations
        .filter(pending ⇒ Instant.now().isAfter(pending.expiration))
        .filterNot(processedAggregates.contains)

      if (expiredAggregates.nonEmpty) {
        expiredAggregates.foreach { pending ⇒
          log.debug(s"Aggregate ${pending.key} expiring since it is past ${pending.expiration}")
          pending.actor ! false
        }
        rates.notCurrent.mark(expiredAggregates.length)
      }
      pendingInitializations.filterNot(agg ⇒ processedAggregates.contains(agg) || expiredAggregates.contains(agg))
    }

    copy(inFlight = newInFlight, pendingInitializations = newPendingAggregates)
  }
}
