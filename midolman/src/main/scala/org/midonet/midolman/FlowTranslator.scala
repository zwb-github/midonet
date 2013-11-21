// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import scala.collection.{Set => ROSet, mutable, breakOut}
import scala.collection.mutable.ListBuffer
import java.{util => ju}
import java.util.UUID

import akka.actor.{ActorContext, ActorSystem}
import akka.dispatch.{ExecutionContext, Promise, Future}
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout

import org.midonet.cluster.client.Port
import org.midonet.midolman.rules.{ChainPacketContext, RuleResult}
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActionUserspace
import org.midonet.odp.flows.FlowActions
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0

object FlowTranslator {

    type TaggedActions = (Seq[FlowAction[_]], Seq[Any])

    /**
     * Dummy ChainPacketContext used in egress port set chains.
     * All that is available is the Output Port ID (there's no information
     * on the ingress port or connection tracking at the egress controller).
     * @param outportID UUID for the output port
     */
    class EgressPortSetChainPacketContext(override val outPortId: UUID,
            val tags: Option[mutable.Set[Any]]) extends ChainPacketContext {

        override val inPortId = null
        override val portGroups = new ju.HashSet[UUID]()
        override val isConnTracked = false
        override val isForwardFlow = true
        override val flowCookie = None
        override val parentCookie = None

        override def addTraversedElementID(id: UUID) { }
        override def addFlowRemovedCallback(cb: Callback0) {}

        override def addFlowTag(tag: Any) {
            tags match {
                case None =>
                case Some(tset) => tset += tag
            }
        }
    }
}

trait FlowTranslator {
    import FlowTranslator._

    implicit val executor: ExecutionContext
    implicit val system: ActorSystem

    protected val datapathConnection: OvsDatapathConnection
    protected val dpState: DatapathState

    implicit protected val requestReplyTimeout: Timeout
    implicit protected val context: ActorContext
    val log: LoggingAdapter

    protected def translateVirtualWildcardFlow(
            flow: WildcardFlow, tags: ROSet[Any] = Set.empty):
                Future[(WildcardFlow, ROSet[Any])] = {

        val flowMatch = flow.getMatch
        val inPortId = flowMatch.getInputPortUUID

        // tags can be null
        val dpTags = new mutable.HashSet[Any]
        if (tags != null)
            dpTags ++= tags

        dpState.getDpPortNumberForVport(inPortId) match {
            case Some(portNo) =>
                flowMatch.setInputPortNumber(portNo.shortValue())
                         .unsetInputPortUUID()
                dpTags += FlowTagger.invalidateDPPort(portNo.shortValue())
            case None =>
        }

        val actions = Option(flow.getActions) getOrElse Seq.empty

        val translatedFuture = translateActions(
                actions, Option(inPortId), Option(dpTags), flow.getMatch)

        translatedFuture map { translated =>
            WildcardFlow(wcmatch = flow.wcmatch,
                actions = translated.toList,
                priority = flow.priority,
                hardExpirationMillis = flow.hardExpirationMillis,
                idleExpirationMillis = flow.idleExpirationMillis)
        } recover {
            case ex =>
                log.error(
                    "Translation of {} failed because {}-> DROP", actions, ex)
                WildcardFlow(
                    wcmatch = flow.wcmatch,
                    priority = flow.priority,
                    hardExpirationMillis = 5000)
        } map { (_, dpTags) }

    }

    protected def portsForLocalPorts(localVrnPorts: Seq[UUID]): Seq[Short] = {
        localVrnPorts flatMap { portNo =>
            dpState.getDpPortNumberForVport(portNo) match {
                case Some(value) => Some(value.shortValue())
                case None =>
                    log.warning("Port number {} not found", portNo)
                    None
            }
        }
    }

    protected def applyOutboundFilters[A](localPorts: Seq[Port[_]],
                                          portSetID: UUID,
                                          pktMatch: WildcardMatch,
                                          tags: Option[mutable.Set[Any]])
    : Future[Seq[UUID]] = {
        def chainMatch(port: Port[_], chain: Chain): Boolean = {
            val fwdInfo = new EgressPortSetChainPacketContext(port.id, tags)
            Chain.apply(chain, fwdInfo, pktMatch, port.id, true).action match {
                case RuleResult.Action.ACCEPT =>
                    true
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    false
                case other =>
                    log.error("Applying chain {} produced {} which was not " +
                        "ACCEPT, DROP, or REJECT", chain.id, other)
                    false
            }
        }

        def chainsToPortsId(chains: Seq[Chain]): Seq[UUID] =
            for { (p, c) <- localPorts zip chains if chainMatch(p, c) }
                yield p.id

        def portToChain(port: Port[_]): Future[Chain] =
            if (port.outFilterID == null)
                Promise.successful(null)
            else
                expiringAsk(ChainRequest(port.outFilterID))

        // Apply the chains.
        Future.sequence(localPorts map { portToChain })
            .map{ chainsToPortsId }
            .onFailure {
                case ex =>
                    log.error("Error getting chains for PortSet {}", portSetID)
            }
    }

    /**
     * Translates a list of FlowActions into a format that Open vSwitch can
     * understand, and updates DP tags. Handles two types of FlowActions:
     * FlowActions to local ports and FlowActions to peer hosts. For local port
     * flows, generates a FlowActionOutput per each local host. For flows to
     * peer hosts, generates a FlowActionSetKey per peer host to set up
     * flow-based tunneling, with each FlowActionSetKey followed by a
     * FlowActionOutput as required by Open vSwitch.
     *
     * If a non-null set of data path tags are given, this updates it with
     * corresponding flow invalidation tags.
     */
    private def translateFlowActions(acts: Seq[FlowAction[_]], port: UUID,
            localPorts: Seq[Short], tunnelKey: Option[Long],
            peerHostIds: Set[UUID], dpTags: mutable.Set[Any]): Seq[FlowAction[_]] = {

        log.debug("Translating output actions for vport (or set) {}," +
            " having tunnel key {}, and corresponding to local dp " +
            "ports {}, and peer host IDs {}",
            port, tunnelKey, localPorts, peerHostIds)

        // TODO(pino): when we detect the flow won't have output actions,
        // set the flow to expire soon so that we can retry.
        if (localPorts.isEmpty && peerHostIds.isEmpty)
            log.warning("No local datapath ports or tunnels found. Expected " +
                        "only for forked actions, otherwise this flow will " +
                        "be dropped because we cannot make Output actions.")

        // Translate a flow to local ports.
        def translateFlowToLocalPorts: TaggedActions =
            localPorts.map { id =>
                (FlowActions.output(id), FlowTagger.invalidateDPPort(id))
            }.unzip

        // Helper functions for remote port translation
        def tunnelOutput(): Option[FlowAction[_]] =
            dpState.tunnelGre.map{ p => FlowActions.output(p.getPortNo.shortValue) }

        // Prepare an ODP object which can set the flow tunnel info
        def flowTunnelKey(key: Long, route: (Int, Int)) =
            FlowActions.setKey(FlowKeys.tunnel(key, route._1, route._2))

        // Generate a list of tunneling actions and tags for given tunnel key
        def tunnelSettings(key: Long, output: FlowAction[_]): TaggedActions = {
            val actions = ListBuffer[FlowAction[_]]()
            val tags = ListBuffer[Any]()
            for (peer <- peerHostIds; route <- dpState.peerTunnelInfo(peer)) {
                tags += FlowTagger.invalidateTunnelPort(route)
                // Each FlowActionSetKey must be followed by a corresponding
                // FlowActionOutput.
                actions += flowTunnelKey(key, route)
                actions += output
            }
            (actions.toList, tags.toList)
        }

        // Calls the tunneling actions generation if gre tunnel port exists
        def tunnelActions(key: Long): Option[TaggedActions] =
            tunnelOutput map { tunnelSettings(key, _) }

        // Translate a flow to peer hosts via tunnel key.
        def translateFlowToPeers: TaggedActions =
            tunnelKey flatMap { tunnelActions } getOrElse { (Nil,Nil) }

        val newActs = ListBuffer[FlowAction[_]]()
        var translatablePort = port

        // side-effect function which updates the buffers for actions and tags
        def updateResults(toAdd: TaggedActions): Unit = {
            val (flowActs, flowTags) = toAdd
            newActs ++= flowActs
            if (dpTags != null)
                dpTags ++= flowTags
        }

        // generate output actions if first time see translatablePort
        def handleVrnPort(portId: UUID): Unit =
            if (portId == translatablePort) {
                updateResults(translateFlowToLocalPorts)
                updateResults(translateFlowToPeers)
                translatablePort = null // we only translate the first ones.
            }

        acts.foreach {
            case FlowActionOutputToVrnPort(id) => handleVrnPort(id)
            case FlowActionOutputToVrnPortSet(id) => handleVrnPort(id)
            case a => newActs += a
        }

        newActs.toList
    }

    /** translates a Seq of FlowActions expressed in virtual references into a
     *  Seq of FlowActions expressed in physical references. Returns the
     *  results as an akka Future. */
    protected def translateActions(actions: Seq[FlowAction[_]],
                                   inPortUUID: Option[UUID],
                                   dpTags: Option[mutable.Set[Any]],
                                   wMatch: WildcardMatch): Future[Seq[FlowAction[_]]] = {

        val actionsFutures: Seq[Future[Seq[FlowAction[_]]]] =
            actions map {
                case s: FlowActionOutputToVrnPortSet =>
                    // expandPortSetAction
                    epsa(Seq(s), s.portSetId, inPortUUID, dpTags, wMatch)
                case p: FlowActionOutputToVrnPort =>
                    expandPortAction(Seq(p), p.portId, inPortUUID, dpTags,
                        wMatch)
                case u: FlowActionUserspace =>
                    u.setUplinkPid(datapathConnection.getChannel.getLocalAddress.getPid)
                    Promise.successful(Seq(u))
                case a =>
                    Promise.successful(Seq[FlowAction[_]](a))
            }

        Future.sequence(actionsFutures) recoverWith {
            case e =>
                log.error(e, "Error with action futures {}", e)
                Promise.failed(e)
        } map { seq =>
            val filteredResults = seq.flatMap{x => x}.toList
            log.debug("Results of translated actions {}", filteredResults)
            filteredResults
        }

    }

    // expandPortSetAction, name shortened to avoid an ENAMETOOLONG on ecryptfs
    // from a triply nested anonfun.
    private def epsa(actions: Seq[FlowAction[_]], portSet: UUID,
                     inPortUUID: Option[UUID], dpTags: Option[mutable.Set[Any]],
                     wMatch: WildcardMatch): Future[Seq[FlowAction[_]]] = {

        val portSetFuture = ask(
            VirtualToPhysicalMapper.getRef(),
            PortSetRequest(portSet, update = false))
            .mapTo[PortSet] andThen { case Left(e) =>
                log.error(e, "VTPM didn't provide portSet {} {}", portSet, e)
        }

        val deviceFuture = expiringAsk(BridgeRequest(portSet), log)

        portSetFuture flatMap { set => deviceFuture flatMap { br =>
            val deviceId = br.id
            val tunnelKey = br.tunnelKey
            // Don't include the input port in the expanded port set.
            var outPorts = set.localPorts
            log.debug("hosts {}", set.hosts)
            inPortUUID match {
                case Some(p) => outPorts -= p
                case None =>
            }
            log.debug("Flooding on bridge {}. inPort: {},  local ports: {}," +
                      "remote hosts having ports on it: {}",
                      deviceId, inPortUUID, set.localPorts, set.hosts)
            // add tag for flow invalidation because if the packet comes
            // from a tunnel it won't be tagged
            dpTags match {
                case None =>
                case Some(tags) =>
                    tags += FlowTagger.invalidateBroadcastFlows(deviceId,
                                                                deviceId)
            }

            activePorts(outPorts) flatMap {
                localPorts =>
                    applyOutboundFilters(localPorts, portSet, wMatch, dpTags)
            } map {
                portIDs =>
                    toPortSet(actions, portSet, portsForLocalPorts(portIDs),
                        Some(tunnelKey), set.hosts, dpTags.orNull)
            }

        }} // closing the 2 flatmaps
    }

    protected def activePorts(portIds: Set[UUID]): Future[Seq[Port[_]]] = {
        val fs = portIds.map { portID =>
            expiringAsk(PortRequest(portID), log)
        }(breakOut(Seq.canBuildFrom))

        Future.sequence(fs) map { ps => ps filter { p => p.adminStateUp } }
    }

    private def expandPortAction(actions: Seq[FlowAction[_]],
                                 port: UUID,
                                 inPortUUID: Option[UUID],
                                 dpTags: Option[mutable.Set[Any]],
                                 wMatch: WildcardMatch): Future[Seq[FlowAction[_]]] = {

        val tags = dpTags.orNull

        /* does the DPC has a local Dp Port for this UUID ? */
        dpState.getDpPortNumberForVport(port) map { portNum =>
            /* then we translate to that local Dp Port*/
            val localPort = List(portNum.shortValue())
            Promise.successful(
                towardsLocalDpPorts(actions, port, localPort, tags))
        } getOrElse {
            /* otherwise we translate to a remote port */
            expiringAsk(PortRequest(port), log) map {
                case p: Port[_] if p.isExterior =>
                    towardsRemoteHosts(
                        actions, port, p.tunnelKey, p.hostID, tags)
                case _ =>
                    log.warning("Port should be exterior.")
                    Nil
            }
        }
    }

    /** forwards to translateToDpPorts for a set of local ports. */
    def towardsLocalDpPorts(acts: Seq[FlowAction[_]], port: UUID,
        localPorts: Seq[Short], dpTags: mutable.Set[Any]) =
        translateFlowActions(acts, port, localPorts, None, Set.empty, dpTags)

    /** forwards to translateToDpPorts for a set of remote ports. */
    def towardsRemoteHosts(acts: Seq[FlowAction[_]], port: UUID,
        key: Long, peerHostId: UUID, dpTags: mutable.Set[Any]) =
        translateFlowActions(acts, port, Nil, Some(key), Set(peerHostId), dpTags)

    /** forwards to translateToDpPorts for a port set. */
    def toPortSet(acts: Seq[FlowAction[_]], port: UUID, localPorts: Seq[Short],
        key: Option[Long], peerHostIds: Set[UUID], dpTags: mutable.Set[Any]) =
        translateFlowActions(acts, port, localPorts, key, peerHostIds, dpTags)

}
