/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.Inject;
import com.google.inject.Provider;

import com.midokura.midolman.config.MidolmanConfig;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class ScheduledExecutorServiceProvider implements
                                              Provider<ScheduledExecutorService> {
    @Inject
    MidolmanConfig config;

    @Override
    public ScheduledExecutorService get() {
        return Executors.newScheduledThreadPool(1);
    }
}
