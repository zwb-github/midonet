/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.topology

import scala.collection.mutable
import java.util.UUID
import akka.actor.ActorRef

import org.midonet.cluster.Client
import org.midonet.cluster.client.TunnelZones
import org.midonet.cluster.data.TunnelZone
import org.midonet.midolman.topology.VirtualToPhysicalMapper.ZoneChanged
import org.midonet.packets.IPv4
import org.midonet.util.collection.MapperToFirstCall

class TunnelZoneManager(clusterClient: Client,
                        actor: ActorRef) extends DeviceHandler {

    def handle(deviceId: UUID) {
        clusterClient.getTunnelZones(deviceId,
            new ZoneBuildersProvider(actor, deviceId))
    }

    class ZoneBuildersProvider(val actor: ActorRef, val zoneId:UUID)
            extends TunnelZones.BuildersProvider with MapperToFirstCall {

        def getZoneBuilder: TunnelZones.Builder = mapOnce(classOf[TunnelZones.Builder]) {
            new LocalZoneBuilder(actor, zoneId)
        }
    }

    class LocalZoneBuilder(actor: ActorRef, host: UUID) extends TunnelZones.Builder {

        var zone: TunnelZone = null
        val hosts = mutable.Map[UUID, IPv4]()

        override def setConfiguration(configuration: TunnelZone): LocalZoneBuilder = {
            zone = configuration
            this
        }

        override def addHost(hostId: UUID, hostConfig: TunnelZone.HostConfig): LocalZoneBuilder = {
            actor ! ZoneChanged(zone.getId, zone.getType, hostConfig, HostConfigOperation.Added)
            this
        }

        override def removeHost(hostId: UUID, hostConfig: TunnelZone.HostConfig): LocalZoneBuilder = {
            actor ! ZoneChanged(zone.getId, zone.getType, hostConfig, HostConfigOperation.Deleted)
            this
        }

        override def build(): Unit = { }

        override def deleted(): Unit = { }

    }
}