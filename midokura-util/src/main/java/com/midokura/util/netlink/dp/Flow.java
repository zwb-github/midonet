/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

import java.util.ArrayList;
import java.util.List;

import com.midokura.util.netlink.dp.flows.FlowAction;
import com.midokura.util.netlink.dp.flows.FlowKey;
import com.midokura.util.netlink.dp.flows.FlowStats;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class Flow {

    List<FlowKey> keys = new ArrayList<FlowKey>();
    List<FlowAction> actions = new ArrayList<FlowAction>();
    FlowStats stats;
    Byte tcpFlags;
    Long lastUsedTime;

    public List<FlowKey> getKeys() {
        return keys;
    }

    public void setKeys(List<FlowKey> keys) {
        this.keys = keys;
    }

    public List<FlowAction> getActions() {
        return actions;
    }

    public void setActions(List<FlowAction> actions) {
        this.actions = actions;
    }

    public FlowStats getStats() {
        return stats;
    }

    public Flow setStats(FlowStats stats) {
        this.stats = stats;
        return this;
    }

    public Byte getTcpFlags() {
        return tcpFlags;
    }

    public void setTcpFlags(Byte tcpFlags) {
        this.tcpFlags = tcpFlags;
    }

    public Long getLastUsedTime() {
        return lastUsedTime;
    }

    public Flow setLastUsedTime(Long lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
        return this;
    }

    public Flow addAction(FlowAction action) {
        actions.add(action);
        return this;
    }

    public Flow addKey(FlowKey key) {
        keys.add(key);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Flow flow = (Flow) o;

        if (actions != null ? !actions.equals(
            flow.actions) : flow.actions != null)
            return false;
        if (keys != null ? !keys.equals(flow.keys) : flow.keys != null)
            return false;
        if (lastUsedTime != null ? !lastUsedTime.equals(
            flow.lastUsedTime) : flow.lastUsedTime != null) return false;
        if (stats != null ? !stats.equals(flow.stats) : flow.stats != null)
            return false;
        if (tcpFlags != null ? !tcpFlags.equals(
            flow.tcpFlags) : flow.tcpFlags != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = keys != null ? keys.hashCode() : 0;
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        result = 31 * result + (stats != null ? stats.hashCode() : 0);
        result = 31 * result + (tcpFlags != null ? tcpFlags.hashCode() : 0);
        result = 31 * result + (lastUsedTime != null ? lastUsedTime.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Flow{" +
            "keys=" + keys +
            ", actions=" + actions +
            ", stats=" + stats +
            ", tcpFlags=" + tcpFlags +
            ", lastUsedTime=" + lastUsedTime +
            '}';
    }
}

