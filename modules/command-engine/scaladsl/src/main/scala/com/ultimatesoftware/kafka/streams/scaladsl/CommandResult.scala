// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams.scaladsl

sealed trait CommandResult[Agg]
case class CommandSuccess[Agg](aggregateState: Option[Agg]) extends CommandResult[Agg]
case class CommandFailure[Agg](reason: Throwable) extends CommandResult[Agg]
