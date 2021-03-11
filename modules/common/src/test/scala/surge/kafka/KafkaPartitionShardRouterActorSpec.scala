// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.kafka

import akka.actor.{ Actor, ActorSystem, DeadLetter, Props }
import akka.testkit.{ TestKit, TestProbe }
import org.apache.kafka.common.TopicPartition
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import surge.akka.cluster.ActorSystemHostAwareness
import surge.scala.core.kafka.{ HostPort, KafkaProducerTrait, KafkaTopic, PartitionAssignments }

object KafkaPartitionShardRouterActorSpecModels {
  case class Command(id: String)
  case class WrappedCmd(topicPartition: TopicPartition, cmd: Command)

  class ProbeInterceptorActor(topicPartition: TopicPartition, probe: TestProbe) extends Actor {
    override def receive: Receive = {
      case cmd: Command => probe.ref.forward(WrappedCmd(topicPartition, cmd))
    }
  }

  class ProbeInterceptorRegionCreator(probe: TestProbe) extends TopicPartitionRegionCreator {
    override def propsFromTopicPartition(topicPartition: TopicPartition): Props = Props(new ProbeInterceptorActor(topicPartition, probe))
  }
}

trait KafkaPartitionShardRouterActorSpecLike extends MockitoSugar {
  import KafkaPartitionShardRouterActorSpecModels._

  implicit val system: ActorSystem

  val partitionAssignments: Map[HostPort, List[TopicPartition]]

  private val trackedTopic = KafkaTopic("test")

  private val partitionMappings = Map(
    "partition0" -> 0,
    "partition1" -> 1,
    "partition2" -> 2,
    "partition1Again" -> 1)
  val partition0 = new TopicPartition(trackedTopic.name, 0)
  val partition1 = new TopicPartition(trackedTopic.name, 1)
  val partition2 = new TopicPartition(trackedTopic.name, 2)

  case class TestContext(partitionProbe: TestProbe, regionProbe: TestProbe, shardRouterProps: Props)

  case object ThrowExceptionInExtractEntityId
  def setupTestContext(): TestContext = {
    val partitionProbe = TestProbe()
    val regionProbe = TestProbe()

    val producer = mock[KafkaProducerTrait[String, Array[Byte]]]
    when(producer.topic).thenReturn(trackedTopic)
    when(producer.partitionFor(anyString)).thenAnswer((invocation: InvocationOnMock) => {
      val key = invocation.getArgument[String](0)
      partitionMappings.get(key)
    })

    val extractEntityId: PartialFunction[Any, String] = {
      case cmd: Command                    => cmd.id
      case ThrowExceptionInExtractEntityId => throw new RuntimeException("Received ThrowExceptionInExtractEntityId in extractEntityId function")
    }
    val shardRouterProps = Props(new KafkaPartitionShardRouterActor(
      partitionTracker = partitionProbe.ref,
      kafkaStateProducer = producer,
      regionCreator = new ProbeInterceptorRegionCreator(regionProbe),
      extractEntityId = extractEntityId))

    TestContext(partitionProbe = partitionProbe, regionProbe = regionProbe, shardRouterProps = shardRouterProps)
  }

  def initializePartitionAssignments(partitionProbe: TestProbe, assignments: Map[HostPort, List[TopicPartition]] = partitionAssignments): Unit = {
    partitionProbe.expectMsgType[KafkaConsumerStateTrackingActor.Register]
    partitionProbe.reply(PartitionAssignments(assignments))
  }
}

class KafkaPartitionShardRouterActorSpec extends TestKit(ActorSystem("KafkaPartitionShardRouterActorSpec")) with AnyWordSpecLike with Matchers
  with KafkaPartitionShardRouterActorSpecLike with ActorSystemHostAwareness {
  import KafkaPartitionShardRouterActorSpecModels._

  override val actorSystem: ActorSystem = system

  private val hostPort1 = HostPort(localHostname, localPort)
  private val hostPort2 = HostPort("not-localhost", 1234)

  val partitionAssignments: Map[HostPort, List[TopicPartition]] = Map[HostPort, List[TopicPartition]](
    hostPort1 -> List(partition0, partition1),
    hostPort2 -> List(partition2))

  "KafkaPartitionShardRouterActor" should {
    "Handle updates to partition assignments" in {
      val testContext = setupTestContext()
      val probe = TestProbe()
      import testContext._

      val routerActor = system.actorOf(shardRouterProps, "RouterActorUpdatedPartitionsTest")

      initializePartitionAssignments(partitionProbe)

      val newPartitionAssignments = Map[HostPort, List[TopicPartition]](
        hostPort1 -> List(partition0, partition1, partition2),
        hostPort2 -> List())

      partitionProbe.send(routerActor, PartitionAssignments(newPartitionAssignments))

      val command = Command("partition2")
      probe.send(routerActor, command)
      regionProbe.expectMsg(WrappedCmd(partition2, command))
      regionProbe.reply(command)
      probe.expectMsg(command)
    }

    "Stash messages before initialized" in {
      val testContext = setupTestContext()
      val probe = TestProbe()
      import testContext._
      val routerActor = system.actorOf(shardRouterProps)

      initializePartitionAssignments(partitionProbe, Map.empty)

      val command0 = Command("partition0")
      probe.send(routerActor, command0)

      partitionProbe.send(routerActor, PartitionAssignments(partitionAssignments))

      regionProbe.expectMsg(WrappedCmd(partition0, command0))
      regionProbe.reply(command0)
      probe.expectMsg(command0)
    }

    "Send messages that can't be routed to dead letters" in {
      val testContext = setupTestContext()
      import testContext._

      val deadLetterProbe = TestProbe()
      system.eventStream.subscribe(deadLetterProbe.ref, classOf[DeadLetter])
      val routerActor = system.actorOf(shardRouterProps)

      initializePartitionAssignments(partitionProbe)

      routerActor ! ThrowExceptionInExtractEntityId

      val dead = deadLetterProbe.expectMsgType[DeadLetter]
      dead.message shouldEqual ThrowExceptionInExtractEntityId
      dead.sender shouldEqual routerActor
      dead.recipient shouldEqual system.deadLetters
    }
  }
}