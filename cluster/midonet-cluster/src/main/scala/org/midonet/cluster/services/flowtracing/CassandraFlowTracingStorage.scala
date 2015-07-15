/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.services.flowtracing

import java.io.IOException
import java.util.{ArrayList, Date, List, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import com.datastax.driver.core.{PreparedStatement, Row, Session}
import com.datastax.driver.core.utils.UUIDs

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.flowtracing.FlowTracing.{FlowTrace => FlowTraceProto}
import org.midonet.midolman.logging.FlowTracingSchema
import org.midonet.packets.{IPAddr, MAC}

class CassandraFlowTracingStorage(cass: CassandraClient)
        extends FlowTracingStorageBackend {

    lazy val schema: FlowTracingSchema = new FlowTracingSchema(getSession())

    override def getFlowCount(traceRequestId: UUID,
                              minTime: Option[Date] = None,
                              maxTime: Option[Date] = None): Long = {
        val res = getSession().execute(schema.bindFlowCountStatement(
                                           traceRequestId, minTime, maxTime))
        res.one() match {
            case r: Row => { r.getLong("count") }
            case _ => 0
        }
    }

    override def getFlowTraces(traceRequestId: UUID,
                               minTime: Option[Date] = None,
                               maxTime: Option[Date] = None,
                               ascending: Boolean = false,
                               limit: Option[Int] = None): List[FlowTrace] = {
        val res = getSession().execute(schema.bindGetFlowsStatement(
                                           traceRequestId,
                                           minTime, maxTime, ascending, limit))
        val traces = new ArrayList[FlowTrace]
        val iter = res.iterator
        while (iter.hasNext) {
            traces.add(row2trace(iter.next))
        }
        traces
    }

    override def getFlowTraceData(traceRequestId: UUID,
                                  flowTraceId: UUID,
                                  minTime: Option[Date] = None,
                                  maxTime: Option[Date] = None,
                                  ascending: Boolean = false,
                                  limit: Option[Int] = None): (FlowTrace, List[FlowTraceData]) = {
        val res = getSession().execute(schema.bindGetFlowStatement(
                                           traceRequestId, flowTraceId))
        val trace = res.one() match {
            case r: Row => row2trace(r)
            case _ => throw new TraceNotFoundException
        }

        val traceData = getSession().execute(
            schema.bindGetDataStatement(
                traceRequestId, flowTraceId, minTime,
                maxTime, ascending, limit))
        val iter = traceData.iterator
        val data = new ArrayList[FlowTraceData]
        while (iter.hasNext) {
            val row = iter.next
            data.add(FlowTraceData(
                         row.getUUID("host"),
                         UUIDs.unixTimestamp(row.getUUID("time")),
                         row.getString("data")))
        }
        (trace, data)
    }

    private def row2trace(row: Row): FlowTrace = {
        val ethSrc = row.getString("ethSrc")
        val ethDst = row.getString("ethDst")
        val networkSrc = row.getString("networkSrc")
        val networkDst = row.getString("networkDst")
        FlowTrace(row.getUUID("flowTraceId"),
                  if (ethSrc != null) MAC.fromString(ethSrc) else null,
                  if (ethDst != null) MAC.fromString(ethDst) else null,
                  row.getInt("etherType").toShort,
                  if (networkSrc != null) IPAddr.fromString(networkSrc)
                  else null,
                  if (networkDst != null) IPAddr.fromString(networkDst)
                  else null,
                  row.getInt("networkProto").toByte,
                  row.getInt("srcPort"), row.getInt("dstPort"))
    }

    def getSession(): Session = {
        if (cass.session == null) {
            cass.connect()
            var i = 10
            while (cass.session == null && i > 0) {
                Thread.sleep(100)
                i -= 1
            }
            if (cass.session == null) {
                throw new IOException("Couldn't connect to cassandra")
            }
        }
        cass.session
    }

}
