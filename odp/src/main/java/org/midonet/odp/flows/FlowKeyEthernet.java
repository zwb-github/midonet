/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;

import org.midonet.packets.MAC;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowKeyEthernet implements FlowKey {

    /*__u8*/ private byte[] eth_src = new byte[6]; // always 6 bytes long
    /*__u8*/ private byte[] eth_dst = new byte[6]; // always 6 bytes long

    // This is used for deserialization purposes only.
    FlowKeyEthernet() { }

    FlowKeyEthernet(byte[] src, byte[] dst) {
        eth_src = src;
        eth_dst = dst;
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(eth_src);
        builder.addValue(eth_dst);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            message.getBytes(eth_src);
            message.getBytes(eth_dst);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyEthernet> getKey() {
        return FlowKeyAttr.ETHERNET;
    }

    @Override
    public FlowKeyEthernet getValue() {
        return this;
    }

    public byte[] getSrc() {
        return eth_src;
    }

    public byte[] getDst() {
        return eth_dst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEthernet that = (FlowKeyEthernet) o;

        if (!Arrays.equals(eth_dst, that.eth_dst)) return false;
        if (!Arrays.equals(eth_src, that.eth_src)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = eth_src != null ? Arrays.hashCode(eth_src) : 0;
        result = 31 * result + (eth_dst != null ? Arrays.hashCode(eth_dst) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyEthernet{" +
            "eth_src=" +
                (eth_src == null ? "null" : MAC.bytesToString(eth_src)) +
            ", eth_dst=" +
                (eth_dst == null ? "null" : MAC.bytesToString(eth_dst)) +
            '}';
    }
}

