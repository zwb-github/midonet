/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowKeyInPort implements FlowKey<FlowKeyInPort> {

    /*__be32*/ int portNo;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(portNo);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            portNo = message.getInt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyInPort> getKey() {
        return FlowKeyAttr.IN_PORT;
    }

    @Override
    public FlowKeyInPort getValue() {
        return this;
    }

    public FlowKeyInPort setInPort(int portNumber) {
        this.portNo = portNumber;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyInPort that = (FlowKeyInPort) o;

        if (portNo != that.portNo) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return portNo;
    }

    @Override
    public String toString() {
        return "FlowKeyInPort{" +
            "portNo=" + portNo +
            '}';
    }
}
