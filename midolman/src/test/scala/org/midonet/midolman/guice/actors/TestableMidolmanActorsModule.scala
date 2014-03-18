/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.actors

import scala.collection.mutable
import java.util.concurrent.{TimeUnit, LinkedBlockingDeque}

import akka.actor._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.TestActor
import akka.testkit.TestActor.{AutoPilot, Message}
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.util.Timeout

import org.midonet.midolman.guice.MidolmanActorsModule
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.midolman._
import scala.reflect.ClassTag
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.DeduplicationActor.HandlePackets

/**
 * A [[org.midonet.midolman.guice.MidolmanActorsModule]] that can will override
 * the top level actors with probes and also provide an easy way to access the
 * actual actors internal state.
 *
 * @see [[org.midonet.midolman.MidolmanTestCase]] for an usage example.
 */
class TestableMidolmanActorsModule(probes: mutable.Map[String, TestKit],
                                   actors: mutable.Map[String, TestActorRef[Actor]])
    extends MidolmanActorsModule {

    protected override def bindMidolmanActorsService() {
        bind(classOf[TestablePacketsEntryPoint])
        expose(classOf[TestablePacketsEntryPoint])
        bind(classOf[MidolmanActorsService])
            .toInstance(new TestableMidolmanActorsService())
    }

    class TestableMidolmanActorsService extends MidolmanActorsService {
        protected override def startActor(actorProps: Props, actorName: String): ActorRef = {
            val testKit = new ProbingTestKit(system, actorName)

            val targetActor = TestActorRef[Actor](actorProps, testKit.testActor, "real")

            testKit.setAutoPilot(new AutoPilot {
                val replyHandlers = mutable.Map[ActorRef, ActorRef]()
                def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {

                    msg match {
                        case "stop" => null

                        case OutgoingMessage(m, originalSender) =>
                            originalSender.tell(m, testKit.testActor)
                            this

                        case m if sender != targetActor =>
                            val handler =
                                replyHandlers.get(sender) match {
                                    case None =>
                                        val proxy = testKit.instance.actorOf(
                                            Props(new ProxyActor(
                                                sender,testKit.testActor)))
                                        replyHandlers.put(sender, proxy)
                                        proxy
                                    case Some(proxy) =>
                                        proxy
                                }

                            targetActor.tell(m, handler)
                            this
                    }
                }
            })

            probes.put(actorName, testKit)
            actors.put(actorName, targetActor)

            testKit.testActor
        }

        protected override def actorSpecs = List(
            (propsFor(classOf[VirtualTopologyActor]),      VirtualTopologyActor.Name),
            (propsFor(classOf[VirtualToPhysicalMapper]),   VirtualToPhysicalMapper.Name),
            (propsFor(classOf[DatapathController]),        DatapathController.Name),
            (propsFor(classOf[FlowController]),            FlowController.Name),
            (propsFor(classOf[RoutingManagerActor]),       RoutingManagerActor.Name),
            (propsFor(classOf[TestablePacketsEntryPoint]), PacketsEntryPoint.Name),
            (propsFor(classOf[NetlinkCallbackDispatcher]), NetlinkCallbackDispatcher.Name))
    }


    class ProxyActor(originalSender: ActorRef, probeActor: ActorRef) extends Actor {
        val log = Logging(context.system, self)

        def receive = {
            case m =>
                probeActor ! OutgoingMessage(m, originalSender)
        }
    }

    class ProbingTestKit(_system: ActorSystem, actorName: String) extends TestKit(_system) {
        var instance: ProbingTestActor = null

        override val testActor: ActorRef = {
            implicit val tout = new Timeout(3 seconds)
            val actorFuture = SupervisorActor ?
                SupervisorActor.StartChild(Props[ProbingTestActor](makeInstance()), actorName)
            Await.result(actorFuture.mapTo[ActorRef], tout.duration)
        }

        private def makeInstance(): ProbingTestActor = {
            val field = this.getClass.getSuperclass.getDeclaredField("akka$testkit$TestKitBase$$queue")
            field.setAccessible(true)
            val queue = field.get(this).asInstanceOf[LinkedBlockingDeque[Message]]
            instance = new ProbingTestActor(queue)
            instance
        }

        override def expectMsgType[T](implicit m: ClassTag[T]) =
            super.expectMsgType(m)
    }
}

case class OutgoingMessage(m: Any, target: ActorRef)

class ProbingTestActor(queue: LinkedBlockingDeque[Message]) extends TestActor(queue) {
    var value = 0

    def actorOf(props: Props): ActorRef = {
        value += 1
        context.actorOf(props, "proxy-" + value)
    }
}

class TestablePacketsEntryPoint extends PacketsEntryPoint {
    override def NUM_WORKERS = 1

    def dda: ActorRef = if (workers.length > 0) workers(0) else null

    override def receive = super.receive orElse {
        case m: HandlePackets => dda ! m
    }
}
