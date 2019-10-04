// Copyright (C) 2018 Ultimate Software

package com.ultimatesoftware.kafka.streams.scaladsl

import com.ultimatesoftware.scala.core.domain._
import com.ultimatesoftware.scala.core.kafka.KafkaTopic
import com.ultimatesoftware.scala.core.monitoring.metrics.{ MetricsProvider, MetricsPublisher, NoOpsMetricsPublisher }

import scala.concurrent.duration._

trait KafkaStreamsCommandBusinessLogic[Agg, AggIdType, Command, Event, CmdMeta] {
  def stateTopic: KafkaTopic
  def eventsTopic: KafkaTopic

  def internalMetadataTopic: KafkaTopic

  def domainBusinessLogicAdapter: DomainBusinessLogicAdapter[Agg, AggIdType, Command, Event, _, CmdMeta]

  // Defaults to noops publishing (for now) and 30 second interval on metrics snapshots
  // These can be overridden in the derived applications
  def metricsPublisher: MetricsPublisher = NoOpsMetricsPublisher
  def metricsInterval: FiniteDuration = 30.seconds

  def metricsProvider: MetricsProvider

  private[scaladsl] def toCore: com.ultimatesoftware.kafka.streams.core.KafkaStreamsCommandBusinessLogic[Agg, AggIdType, Command, Event, CmdMeta] = {
    new com.ultimatesoftware.kafka.streams.core.KafkaStreamsCommandBusinessLogic[Agg, AggIdType, Command, Event, CmdMeta](
      stateTopic = stateTopic, eventsTopic = eventsTopic, internalMetadataTopic = internalMetadataTopic,
      businessLogicAdapter = domainBusinessLogicAdapter,
      metricsPublisher = metricsPublisher, metricsInterval = metricsInterval)
  }
}
