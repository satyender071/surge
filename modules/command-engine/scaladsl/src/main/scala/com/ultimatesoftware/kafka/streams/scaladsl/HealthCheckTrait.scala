// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams.scaladsl

import scala.concurrent.Future
import com.ultimatesoftware.kafka.streams.HealthCheck

trait HealthCheckTrait {
  def healthCheck(): Future[HealthCheck]
}