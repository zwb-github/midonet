/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import java.util.List;

/**
 * Builder class to allow easier building of FlowAction instances.
 */
public class FlowActions {
    public static FlowActionOutput output(int portNumber) {
        return
            new FlowActionOutput()
                .setPortNumber(portNumber);
    }

    public static FlowActionUserspace userspace() {
        return new FlowActionUserspace();
    }

    public static FlowActionSetKey setKey(FlowKey flowKey) {
        return new FlowActionSetKey().setFlowKey(flowKey);
    }

    public static FlowActionPushVLAN pushVLAN(short tagProtocolIdentifier, short tagControlIdentifier) {
        return
            new FlowActionPushVLAN()
                .setTagProtocolIdentifier(tagProtocolIdentifier)
                .setTagControlIdentifier(tagControlIdentifier);
    }

    public static FlowActionPopVLAN popVLAN() {
        return new FlowActionPopVLAN();
    }

    public static FlowActionSample sample(int probability, List<FlowAction> actions) {
        return new FlowActionSample().setProbability(probability).setActions(actions);
    }
}
