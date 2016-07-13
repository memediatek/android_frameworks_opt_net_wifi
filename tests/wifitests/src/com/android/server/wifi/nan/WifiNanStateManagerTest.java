/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.os.Message;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * Unit test harness for WifiNanStateManager.
 */
@SmallTest
public class WifiNanStateManagerTest {
    private TestLooper mMockLooper;
    private Random mRandomNg = new Random(15687);
    private WifiNanStateManager mDut;
    @Mock private WifiNanNative mMockNative;
    @Mock private Context mMockContext;
    @Mock private WifiNanRttStateManager mMockNanRttStateManager;
    TestAlarmManager mAlarmManager;
    @Mock private WifiNanDataPathStateManager mMockNanDataPathStatemanager;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    /**
     * Pre-test configuration. Initialize and install mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mAlarmManager = new TestAlarmManager();
        when(mMockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());

        when(mMockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mock(ConnectivityManager.class));

        mMockLooper = new TestLooper();

        mDut = installNewNanStateManager();
        mDut.start(mMockContext, mMockLooper.getLooper());
        installMocksInStateManager(mDut, mMockNanRttStateManager, mMockNanDataPathStatemanager);

        when(mMockNative.enableAndConfigure(anyShort(), any(ConfigRequest.class), anyBoolean()))
                .thenReturn(true);
        when(mMockNative.disable(anyShort())).thenReturn(true);
        when(mMockNative.publish(anyShort(), anyInt(), any(PublishConfig.class))).thenReturn(true);
        when(mMockNative.subscribe(anyShort(), anyInt(), any(SubscribeConfig.class)))
                .thenReturn(true);
        when(mMockNative.sendMessage(anyShort(), anyInt(), anyInt(), any(byte[].class),
                any(byte[].class), anyInt(), anyInt())).thenReturn(true);
        when(mMockNative.stopPublish(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.stopSubscribe(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.getCapabilities(anyShort())).thenReturn(true);

        installMockWifiNanNative(mMockNative);
    }

    /**
     * Validate that NAN data-path interfaces are brought up and down correctly.
     */
    @Test
    public void testNanDataPathInterfaceUpDown() throws Exception {
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mMockNanDataPathStatemanager);

        // (1) enable usage
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        validateCorrectNanStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNanDataPathStatemanager).createAllInterfaces();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) disable usage
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitNan();
        inOrder.verify(mMockNanDataPathStatemanager).onNanDownCleanupDataPaths();
        validateCorrectNanStatusChangeBroadcast(inOrder, false);
        inOrder.verify(mMockNanDataPathStatemanager).deleteAllInterfaces();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));

        verifyNoMoreInteractions(mMockNative, mMockNanDataPathStatemanager);
    }

    /**
     * Validate that APIs aren't functional when usage is disabled.
     */
    @Test
    public void testDisableUsageDisablesApis() throws Exception {
        final int clientId = 12314;
        final int uid = 1000;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mockCallback);

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);

        // (1) check initial state
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        validateCorrectNanStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) disable usage and validate state
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitNan();
        validateCorrectNanStatusChangeBroadcast(inOrder, false);

        // (3) try connecting and validate that get nothing (app should be aware of non-availability
        // through state change broadcast and/or query API)
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validate that when API usage is disabled while in the middle of a connection that internal
     * state is cleaned-up, and that all subsequent operations are NOP. Then enable usage again and
     * validate that operates correctly.
     */
    @Test
    public void testDisableUsageFlow() throws Exception {
        final int clientId = 12341;
        final int uid = 1000;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mockCallback);

        // (1) check initial state
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        validateCorrectNanStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) connect (successfully)
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (3) disable usage & verify callbacks
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitNan();
        validateCorrectNanStatusChangeBroadcast(inOrder, false);
        validateInternalClientInfoCleanedUp(clientId);

        // (4) try connecting again and validate that just get an onNanDown
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();

        // (5) disable usage again and validate that not much happens
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));

        // (6) enable usage
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));
        validateCorrectNanStatusChangeBroadcast(inOrder, true);

        // (7) connect (should be successful)
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validates that all events are delivered with correct arguments. Validates
     * that IdentityChanged not delivered if configuration disables delivery.
     */
    @Test
    public void testNanEventsDelivery() throws Exception {
        final int clientId1 = 1005;
        final int clientId2 = 1007;
        final int clusterLow = 5;
        final int clusterHigh = 100;
        final int masterPref = 111;
        final int uid = 1000;
        final int reason = WifiNanEventCallback.REASON_OTHER;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref)
                .setEnableIdentityChangeCallback(false).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref)
                .setEnableIdentityChangeCallback(true).build();

        IWifiNanEventCallback mockCallback1 = mock(IWifiNanEventCallback.class);
        IWifiNanEventCallback mockCallback2 = mock(IWifiNanEventCallback.class);
        ArgumentCaptor<Short> transactionIdCapture = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback1, mockCallback2, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionIdCapture.capture());
        mDut.onCapabilitiesUpdateResponse(transactionIdCapture.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect 1st and 2nd clients
        mDut.connect(clientId1, uid, mockCallback1, configRequest1);
        mDut.connect(clientId2, uid, mockCallback2, configRequest2);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionIdCapture.capture(),
                eq(configRequest1), eq(true));
        short transactionId = transactionIdCapture.getValue();
        mDut.onConfigSuccessResponse(transactionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback1).onConnectSuccess();

        // (2) finish connection of 2nd client
        inOrder.verify(mMockNative).enableAndConfigure(transactionIdCapture.capture(),
                eq(configRequest2), eq(false));
        transactionId = transactionIdCapture.getValue();
        mDut.onConfigSuccessResponse(transactionId);

        // (3) deliver NAN events
        mDut.onClusterChangeNotification(WifiNanClientState.CLUSTER_CHANGE_EVENT_STARTED, someMac);
        mDut.onInterfaceAddressChangeNotification(someMac);
        mDut.onNanDownNotification(reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback2).onConnectSuccess();
        inOrder.verify(mockCallback2, times(2)).onIdentityChanged();

        validateInternalClientInfoCleanedUp(clientId1);
        validateInternalClientInfoCleanedUp(clientId2);

        verifyNoMoreInteractions(mockCallback1, mockCallback2, mMockNative);
    }

    /**
     * Validate that when the HAL doesn't respond we get a TIMEOUT (which
     * results in a failure response) at which point we can process additional
     * commands. Steps: (1) connect, (2) publish - timeout, (3) publish +
     * success.
     */
    @Test
    public void testHalNoResponseTimeout() throws Exception {
        final int clientId = 12341;
        final int uid = 1000;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect (successfully)
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish + timeout
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(anyShort(), eq(0), eq(publishConfig));
        assertTrue(mAlarmManager.dispatch(WifiNanStateManager.HAL_COMMAND_TIMEOUT_TAG));
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiNanSessionCallback.REASON_OTHER);
        validateInternalNoSessions(clientId);

        // (3) publish + success
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, 9999);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validates publish flow: (1) initial publish (2) fail. Expected: get a
     * failure callback.
     */
    @Test
    public void testPublishFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int reasonFail = WifiNanSessionCallback.REASON_NO_RESOURCES;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish failure
        mDut.onSessionConfigFailResponse(transactionId.getValue(), true, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);
        validateInternalNoSessions(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates the publish flow: (1) initial publish (2) success (3)
     * termination (e.g. DONE) (4) update session attempt (5) terminateSession
     * (6) update session attempt. Expected: session ID callback + session
     * cleaned-up.
     */
    @Test
    public void testPublishSuccessTerminated() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int reasonTerminate = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) publish termination (from firmware - not app!)
        mDut.onSessionTerminatedNotification(publishId, reasonTerminate, true);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionTerminated(reasonTerminate);

        // (4) app update session (race condition: app didn't get termination
        // yet)
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();

        // (5) app terminates session
        mDut.terminateSession(clientId, sessionId.getValue());
        mMockLooper.dispatchAll();

        // (6) app updates session (app already knows that terminated - will get
        // a local FAIL).
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();

        validateInternalSessionInfoCleanedUp(clientId, sessionId.getValue());

        verifyNoMoreInteractions(mockSessionCallback, mMockNative);
    }

    /**
     * Validate the publish flow: (1) initial publish + (2) success + (3) update
     * + (4) update fails + (5) update + (6). Expected: session is still alive
     * after update failure so second update succeeds (no callbacks).
     */
    @Test
    public void testPublishUpdateFail() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int publishId = 15;
        final int reasonFail = WifiNanSessionCallback.REASON_INVALID_ARGS;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update publish
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId),
                eq(publishConfig));

        // (4) update fails
        mDut.onSessionConfigFailResponse(transactionId.getValue(), true, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);

        // (5) another update publish
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId),
                eq(publishConfig));

        // (6) update succeeds
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigSuccess();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate race condition: publish pending but session terminated (due to
     * disconnect - can't terminate such a session directly from app). Need to
     * make sure that once publish succeeds (failure isn't a problem) the
     * session is immediately terminated since no-one is listening for it.
     */
    @Test
    public void testDisconnectWhilePublishPending() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int publishId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) disconnect (but doesn't get executed until get response for
        // publish command)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (3) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopPublish(transactionId.capture(), eq(publishId));
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates subscribe flow: (1) initial subscribe (2) fail. Expected: get a
     * failure callback.
     */
    @Test
    public void testSubscribeFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int reasonFail = WifiNanSessionCallback.REASON_NO_RESOURCES;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe failure
        mDut.onSessionConfigFailResponse(transactionId.getValue(), false, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);
        validateInternalNoSessions(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates the subscribe flow: (1) initial subscribe (2) success (3)
     * termination (e.g. DONE) (4) update session attempt (5) terminateSession
     * (6) update session attempt. Expected: session ID callback + session
     * cleaned-up
     */
    @Test
    public void testSubscribeSuccessTerminated() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int reasonTerminate = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int subscribeId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) subscribe termination (from firmware - not app!)
        mDut.onSessionTerminatedNotification(subscribeId, reasonTerminate, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionTerminated(reasonTerminate);

        // (4) app update session (race condition: app didn't get termination
        // yet)
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();

        // (5) app terminates session
        mDut.terminateSession(clientId, sessionId.getValue());
        mMockLooper.dispatchAll();

        // (6) app updates session
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();

        validateInternalSessionInfoCleanedUp(clientId, sessionId.getValue());

        verifyNoMoreInteractions(mockSessionCallback, mMockNative);
    }

    /**
     * Validate the subscribe flow: (1) initial subscribe + (2) success + (3)
     * update + (4) update fails + (5) update + (6). Expected: session is still
     * alive after update failure so second update succeeds (no callbacks).
     */
    @Test
    public void testSubscribeUpdateFail() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int subscribeId = 15;
        final int reasonFail = WifiNanSessionCallback.REASON_INVALID_ARGS;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update subscribe
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId),
                eq(subscribeConfig));

        // (4) update fails
        mDut.onSessionConfigFailResponse(transactionId.getValue(), false, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);

        // (5) another update subscribe
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId),
                eq(subscribeConfig));

        // (6) update succeeds
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigSuccess();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate race condition: subscribe pending but session terminated (due to
     * disconnect - can't terminate such a session directly from app). Need to
     * make sure that once subscribe succeeds (failure isn't a problem) the
     * session is immediately terminated since no-one is listening for it.
     */
    @Test
    public void testDisconnectWhileSubscribePending() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int subscribeId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) disconnect (but doesn't get executed until get response for
        // subscribe command)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (3) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopSubscribe((short) 0, subscribeId);
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate (1) subscribe (success), (2) match (i.e. discovery), (3) message reception,
     * (4) message transmission failed (after ok queuing), (5) message transmission success.
     */
    @Test
    public void testMatchAndMessages() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionCallback.REASON_TX_FAIL;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final String peerMsg = "some message from peer";
        final int messageId = 6948;
        final int messageId2 = 6949;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (2) match
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) message Rx
        mDut.onMessageReceivedNotification(subscribeId, requestorId, peerMac, peerMsg.getBytes(),
                peerMsg.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(requestorId, peerMsg.getBytes(),
                peerMsg.length());

        // (4) message Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(), ssi.length(),
                messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), eq(messageId));
        short tid1 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid1);
        mMockLooper.dispatchAll();

        // (5) message Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(), ssi.length(),
                messageId2, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), eq(messageId2));
        short tid2 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid2);
        mMockLooper.dispatchAll();

        // (4) and (5) final Tx results (on-air results)
        mDut.onMessageSendFailNotification(tid1, reasonFail);
        mDut.onMessageSendSuccessNotification(tid2);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId, reasonFail);
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId2);
        validateInternalSendMessageQueuesCleanedUp(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId2);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Summary: in a single publish session interact with multiple peers
     * (different MAC addresses).
     */
    @Test
    public void testMultipleMessageSources() throws Exception {
        final int clientId = 300;
        final int uid = 1000;
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId1 = 568;
        final int peerId2 = 873;
        final byte[] peerMac1 = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMac2 = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        final int reason = WifiNanSessionCallback.REASON_OTHER;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) message received from peers 1 & 2
        mDut.onMessageReceivedNotification(publishId, peerId1, peerMac1, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        mDut.onMessageReceivedNotification(publishId, peerId2, peerMac2, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId1, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId2, msgFromPeer2.getBytes(),
                msgFromPeer2.length());

        // (4) sending messages back to same peers: one Tx fails, other succeeds
        mDut.sendMessage(clientId, sessionId.getValue(), peerId2, msgToPeer2.getBytes(),
                msgToPeer2.length(), msgToPeerId2, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId2),
                eq(peerMac2), eq(msgToPeer2.getBytes()), eq(msgToPeer2.length()), eq(msgToPeerId2));
        short transactionIdVal = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionIdVal);
        mDut.onMessageSendSuccessNotification(transactionIdVal);

        mDut.sendMessage(clientId, sessionId.getValue(), peerId1, msgToPeer1.getBytes(),
                msgToPeer1.length(), msgToPeerId1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId1),
                eq(peerMac1), eq(msgToPeer1.getBytes()), eq(msgToPeer1.length()), eq(msgToPeerId1));
        transactionIdVal = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionIdVal);
        mDut.onMessageSendFailNotification(transactionIdVal, reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(msgToPeerId1, reason);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId1);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId2);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Summary: interact with a peer which changed its identity (MAC address)
     * but which keeps its requestor instance ID. Should be transparent.
     */
    @Test
    public void testMessageWhilePeerChangesIdentity() throws Exception {
        final int clientId = 300;
        final int uid = 1000;
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId = 568;
        final byte[] peerMacOrig = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMacLater = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) message received & responded to
        mDut.onMessageReceivedNotification(publishId, peerId, peerMacOrig, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        mDut.sendMessage(clientId, sessionId.getValue(), peerId, msgToPeer1.getBytes(),
                msgToPeer1.length(), msgToPeerId1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacOrig), eq(msgToPeer1.getBytes()), eq(msgToPeer1.length()),
                eq(msgToPeerId1));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId1);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId1);

        // (4) message received with same peer ID but different MAC
        mDut.onMessageReceivedNotification(publishId, peerId, peerMacLater, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        mDut.sendMessage(clientId, sessionId.getValue(), peerId, msgToPeer2.getBytes(),
                msgToPeer2.length(), msgToPeerId2, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacLater), eq(msgToPeer2.getBytes()), eq(msgToPeer2.length()),
                eq(msgToPeerId2));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId2);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that get failure (with correct code) when trying to send a
     * message to an invalid peer ID.
     */
    @Test
    public void testSendMessageToInvalidPeerId() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) send message to invalid peer ID
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId + 5, ssi.getBytes(),
                ssi.length(), messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiNanSessionCallback.REASON_NO_MATCH_SESSION);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that on send message timeout correct callback is dispatched and that a later
     * firmware notification is ignored.
     */
    @Test
    public void testSendMessageTimeout() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) send 2 messages and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                ssi.length(), messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), eq(messageId));
        short transactionId1 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionId1);
        mMockLooper.dispatchAll();

        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                ssi.length(), messageId + 1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()),
                eq(messageId + 1));
        short transactionId2 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionId2);
        mMockLooper.dispatchAll();

        // (4) message send timeout
        assertTrue(mAlarmManager.dispatch(WifiNanStateManager.HAL_SEND_MESSAGE_TIMEOUT_TAG));
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiNanSessionCallback.REASON_TX_FAIL);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        // (5) firmware response (unlikely - but good to check)
        mDut.onMessageSendSuccessNotification(transactionId1);
        mDut.onMessageSendSuccessNotification(transactionId2);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId + 1);

        validateInternalSendMessageQueuesCleanedUp(messageId + 1);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that when sending a message with a retry count the message is retried the specified
     * number of times. Scenario ending with success.
     */
    @Test
    public void testSendMessageRetransmitSuccess() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) send message and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                ssi.length(), messageId, retryCount);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), eq(messageId));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();

        // (4) loop and fail until reach retryCount
        for (int i = 0; i < retryCount; ++i) {
            mDut.onMessageSendFailNotification(transactionId.getValue(),
                    WifiNanSessionCallback.REASON_TX_FAIL);
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                    eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()),
                    eq(messageId));
            mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
            mMockLooper.dispatchAll();
        }

        // (5) succeed on last retry
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that when sending a message with a retry count the message is retried the specified
     * number of times. Scenario ending with failure.
     */
    @Test
    public void testSendMessageRetransmitFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) send message and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                ssi.length(), messageId, retryCount);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), eq(messageId));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();

        // (4) loop and fail until reach retryCount+1
        for (int i = 0; i < retryCount + 1; ++i) {
            mDut.onMessageSendFailNotification(transactionId.getValue(),
                    WifiNanSessionCallback.REASON_TX_FAIL);
            mMockLooper.dispatchAll();

            if (i != retryCount) {
                inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                        eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()),
                        eq(messageId));
                mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
                mMockLooper.dispatchAll();
            }
        }

        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiNanSessionCallback.REASON_TX_FAIL);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    @Test
    public void testSendMessageQueueAllQueueFail() throws Exception {
        WifiNanNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_FAIL, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxSuccess() throws Exception {
        WifiNanNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_OK, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxFailRetxOk() throws Exception {
        WifiNanNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxFail() throws Exception {
        WifiNanNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueRandomize() throws Exception {
        WifiNanNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_RANDOMIZE, cap,
                cap.maxQueuedTransmitMessages * 10);
    }

    /**
     * Validate that when sending more messages than can be queued by the firmware (based on
     * capability information) they are queued. Support all possible test success/failure codes.
     * @param behavior: SendMessageAnswer.OP_*.
     */
    private void testSendMessageQueue(int behavior, WifiNanNative.Capabilities cap, int numMessages)
            throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;
        final int reason = WifiNanSessionCallback.REASON_OTHER;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> msgId = ArgumentCaptor.forClass(Integer.class);

        // (0) initial conditions
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), cap);
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) send large number of messages
        SendMessageAnswer answerObj = new SendMessageAnswer(behavior);
        when(mMockNative.sendMessage(anyShort(), anyInt(), anyInt(), any(byte[].class),
                any(byte[].class), anyInt(), anyInt())).thenAnswer(answerObj);
        for (int i = 0; i < numMessages; ++i) {
            mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                    ssi.length(), messageId + i, retryCount);
        }
        mMockLooper.dispatchAll();

        int numSends = answerObj.ops[SendMessageAnswer.OP_QUEUE_FAIL]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_OK]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK] * 2
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL] * (retryCount + 1);
        int numOnSendSuccess = answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_OK]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK];
        int numOnSendFail = answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL];

        Log.v("WifiNanStateManagerTest",
                "testSendMessageQueue: ops=" + Arrays.toString(answerObj.ops) + ", numSends="
                        + numSends + ", numOnSendSuccess=" + numOnSendSuccess + ", numOnSendFail="
                        + numOnSendFail);

        verify(mMockNative, times(numSends)).sendMessage(anyShort(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()), anyInt());
        verify(mockSessionCallback, times(numOnSendSuccess)).onMessageSendSuccess(anyInt());
        verify(mockSessionCallback, times(numOnSendFail)).onMessageSendFail(anyInt(), anyInt());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    private class SendMessageAnswer extends MockAnswerUtil.AnswerWithArguments {
        public static final int OP_QUEUE_FAIL = 0;
        public static final int OP_QUEUE_OK_SEND_OK = 1;
        public static final int OP_QUEUE_OK_SEND_RETX_OK = 2;
        public static final int OP_QUEUE_OK_SEND_RETX_FAIL = 3;

        /* psuedo operation: randomly pick from the above 4 operations */
        public static final int OP_QUEUE_RANDOMIZE = -1;

        /* the number of operations which can be executed. Doesn't cound RANDOMIZE since it is
         * resolved to one of the 4 types */
        private static final int NUM_OPS = 4;

        public int[] ops = new int[NUM_OPS];

        private int mBehavior = 0;
        private SparseIntArray mPacketBehavior = new SparseIntArray();

        SendMessageAnswer(int behavior) {
            mBehavior = behavior;
        }

        public boolean answer(short transactionId, int pubSubId, int requestorInstanceId,
                byte[] dest, byte[] message, int messageLength, int messageId) throws Exception {
            Log.v("WifiNanStateManagerTest",
                    "SendMessageAnswer.answer: mBehavior=" + mBehavior + ", transactionId="
                            + transactionId + ", messageId=" + messageId
                            + ", mPacketBehavior[messageId]" + mPacketBehavior.get(messageId, -1));

            int behavior = mBehavior;
            if (behavior == OP_QUEUE_RANDOMIZE) {
                behavior = mRandomNg.nextInt(NUM_OPS);
            }

            boolean packetRetx = mPacketBehavior.get(messageId, -1) != -1;
            if (packetRetx) {
                behavior = mPacketBehavior.get(messageId);
            } else {
                mPacketBehavior.put(messageId, behavior);
            }

            if (behavior == OP_QUEUE_FAIL) {
                ops[OP_QUEUE_FAIL]++;
                mDut.onMessageSendQueuedFailResponse(transactionId,
                        WifiNanSessionCallback.REASON_OTHER);
            } else if (behavior == OP_QUEUE_OK_SEND_OK) {
                ops[OP_QUEUE_OK_SEND_OK]++;
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                mDut.onMessageSendSuccessNotification(transactionId);
            } else if (behavior == OP_QUEUE_OK_SEND_RETX_OK) {
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                if (!packetRetx) {
                    mDut.onMessageSendFailNotification(transactionId,
                            WifiNanSessionCallback.REASON_TX_FAIL);
                } else {
                    ops[OP_QUEUE_OK_SEND_RETX_OK]++;
                    mDut.onMessageSendSuccessNotification(transactionId);
                }
            } else if (behavior == OP_QUEUE_OK_SEND_RETX_FAIL) {
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                if (!packetRetx) {
                    ops[OP_QUEUE_OK_SEND_RETX_FAIL]++;
                }
                mDut.onMessageSendFailNotification(transactionId,
                        WifiNanSessionCallback.REASON_TX_FAIL);
            }
            return true;
        }
    }

    /**
     * Validate that start ranging function fills-in correct MAC addresses for peer IDs and
     * passed along to RTT module.
     */
    @Test
    public void testStartRanging() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int rangingId = 18423;
        final RttManager.RttParams[] params = new RttManager.RttParams[2];
        params[0] = new RttManager.RttParams();
        params[0].bssid = Integer.toString(requestorId);
        params[1] = new RttManager.RttParams();
        params[1].bssid = Integer.toString(requestorId + 5);

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<WifiNanClientState> clientCaptor =
                ArgumentCaptor.forClass(WifiNanClientState.class);
        ArgumentCaptor<RttManager.RttParams[]> rttParamsCaptor =
                ArgumentCaptor.forClass(RttManager.RttParams[].class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative,
                mMockNanRttStateManager);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerSsi.length(), peerMatchFilter.getBytes(), peerMatchFilter.length());

        // (3) start ranging: pass along a valid peer ID and an invalid one
        mDut.startRanging(clientId, sessionId.getValue(), params, rangingId);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNanRttStateManager).startRanging(eq(rangingId), clientCaptor.capture(),
                rttParamsCaptor.capture());
        collector.checkThat("RttParams[0].bssid", "06:07:08:09:0A:0B",
                equalTo(rttParamsCaptor.getValue()[0].bssid));
        collector.checkThat("RttParams[1].bssid", "", equalTo(rttParamsCaptor.getValue()[1].bssid));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative,
                mMockNanRttStateManager);
    }

    /**
     * Test sequence of configuration: (1) config1, (2) config2 - incompatible,
     * (3) config3 - compatible with config1 (requiring upgrade), (4) disconnect
     * config3 (should get a downgrade), (5) disconnect config1 (should get a
     * disable).
     */
    @Test
    public void testConfigs() throws Exception {
        final int clientId1 = 9999;
        final int uid = 1000;
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int clientId2 = 1001;
        final boolean support5g2 = true;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;
        final int clientId3 = 55;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<ConfigRequest> crCapture = ArgumentCaptor.forClass(ConfigRequest.class);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1)
                .setEnableIdentityChangeCallback(false).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setSupport5gBand(support5g2)
                .setClusterLow(clusterLow2).setClusterHigh(clusterHigh2)
                .setMasterPreference(masterPref2).build();

        ConfigRequest configRequest3 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1)
                .setEnableIdentityChangeCallback(true).build();

        IWifiNanEventCallback mockCallback1 = mock(IWifiNanEventCallback.class);
        IWifiNanEventCallback mockCallback2 = mock(IWifiNanEventCallback.class);
        IWifiNanEventCallback mockCallback3 = mock(IWifiNanEventCallback.class);

        InOrder inOrder = inOrder(mMockNative, mockCallback1, mockCallback2, mockCallback3);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) config1 (valid)
        mDut.connect(clientId1, uid, mockCallback1, configRequest1);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture(), eq(true));
        collector.checkThat("merge: stage 1", crCapture.getValue(), equalTo(configRequest1));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback1).onConnectSuccess();

        // (2) config2 (incompatible with config1)
        mDut.connect(clientId2, uid, mockCallback2, configRequest2);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback2)
                .onConnectFail(WifiNanEventCallback.REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG);
        validateInternalClientInfoCleanedUp(clientId2);

        // (3) config3 (compatible with config1 but requires upgrade - i.e. no
        // OTA changes)
        mDut.connect(clientId3, uid, mockCallback3, configRequest3);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture(), eq(false));
        collector.checkThat("merge: stage 3: support 5g", crCapture.getValue().mSupport5gBand,
                equalTo(false));
        collector.checkThat("merge: stage 3: master pref", crCapture.getValue().mMasterPreference,
                equalTo(masterPref1));
        collector.checkThat("merge: stage 3: cluster low", crCapture.getValue().mClusterLow,
                equalTo(clusterLow1));
        collector.checkThat("merge: stage 3: cluster high", crCapture.getValue().mClusterHigh,
                equalTo(clusterHigh1));
        collector.checkThat("merge: stage 3: enable identity change callback",
                crCapture.getValue().mEnableIdentityChangeCallback, equalTo(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback3).onConnectSuccess();

        // (4) disconnect config3: want a downgrade
        mDut.disconnect(clientId3);
        mMockLooper.dispatchAll();
        validateInternalClientInfoCleanedUp(clientId3);
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture(), eq(false));
        collector.checkThat("merge: stage 4", crCapture.getValue(), equalTo(configRequest1));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();

        // (5) disconnect config1: disable
        mDut.disconnect(clientId1);
        mMockLooper.dispatchAll();
        validateInternalClientInfoCleanedUp(clientId1);
        inOrder.verify(mMockNative).disable((short) 0);

        verifyNoMoreInteractions(mMockNative, mockCallback1, mockCallback2, mockCallback3);
    }

    /**
     * Summary: disconnect a client while there are pending transactions.
     */
    @Test
    public void testDisconnectWithPendingTransactions() throws Exception {
        final int clientId = 125;
        final int uid = 1000;
        final int clusterLow = 5;
        final int clusterHigh = 100;
        final int masterPref = 111;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 22;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish (no response yet)
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (3) disconnect (but doesn't get executed until get a RESPONSE to the
        // previous publish)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (4) get successful response to the publish
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopPublish((short) 0, publishId);
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        // (5) trying to publish on the same client: NOP
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();

        // (6) got some callback on original publishId - should be ignored
        mDut.onSessionTerminatedNotification(publishId, reason, true);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that an unknown transaction (i.e. a callback from HAL with an
     * unknown type) is simply ignored - but also cleans up its state.
     */
    @Test
    public void testUnknownTransactionType() throws Exception {
        final int clientId = 129;
        final int uid = 1000;
        final int clusterLow = 15;
        final int clusterHigh = 192;
        final int masterPref = 234;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockPublishSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockPublishSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish - no response
        mDut.publish(clientId, publishConfig, mockPublishSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        verifyNoMoreInteractions(mMockNative, mockCallback, mockPublishSessionCallback);
    }

    /**
     * Validate that a NoOp transaction (i.e. a callback from HAL which doesn't
     * require any action except clearing up state) actually cleans up its state
     * (and does nothing else).
     */
    @Test
    public void testNoOpTransaction() throws Exception {
        final int clientId = 1294;
        final int uid = 1000;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect (no response)
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that getting callbacks from HAL with unknown (expired)
     * transaction ID or invalid publish/subscribe ID session doesn't have any
     * impact.
     */
    @Test
    public void testInvalidCallbackIdParameters() throws Exception {
        final int pubSubId = 1235;
        final int clientId = 132;
        final int uid = 1000;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect and succeed
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        short transactionIdConfig = transactionId.getValue();
        mDut.onConfigSuccessResponse(transactionIdConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) use the same transaction ID to send a bunch of other responses
        mDut.onConfigSuccessResponse(transactionIdConfig);
        mDut.onConfigFailedResponse(transactionIdConfig, -1);
        mDut.onSessionConfigFailResponse(transactionIdConfig, true, -1);
        mDut.onMessageSendQueuedSuccessResponse(transactionIdConfig);
        mDut.onMessageSendQueuedFailResponse(transactionIdConfig, -1);
        mDut.onSessionConfigFailResponse(transactionIdConfig, false, -1);
        mDut.onMatchNotification(-1, -1, new byte[0], new byte[0], 0, new byte[0], 0);
        mDut.onSessionTerminatedNotification(-1, -1, true);
        mDut.onSessionTerminatedNotification(-1, -1, false);
        mDut.onMessageReceivedNotification(-1, -1, new byte[0], new byte[0], 0);
        mDut.onSessionConfigSuccessResponse(transactionIdConfig, true, pubSubId);
        mDut.onSessionConfigSuccessResponse(transactionIdConfig, false, pubSubId);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validate that trying to update-subscribe on a publish session fails.
     */
    @Test
    public void testSubscribeOnPublishSessionType() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        final int publishId = 25;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update-subscribe -> failure
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiNanSessionCallback.REASON_OTHER);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that trying to (re)subscribe on a publish session or (re)publish
     * on a subscribe session fails.
     */
    @Test
    public void testPublishOnSubscribeSessionType() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        final int subscribeId = 25;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update-publish -> error
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiNanSessionCallback.REASON_OTHER);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that the session ID increments monotonically
     */
    @Test
    public void testSessionIdIncrement() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        int loopCount = 100;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, mockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        int prevId = 0;
        for (int i = 0; i < loopCount; ++i) {
            // (2) publish
            mDut.publish(clientId, publishConfig, mockSessionCallback);
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

            // (3) publish-success
            mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, i + 1);
            mMockLooper.dispatchAll();
            inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

            if (i != 0) {
                assertTrue("Session ID incrementing", sessionId.getValue() > prevId);
            }
            prevId = sessionId.getValue();
        }
    }

    /*
     * Tests of internal state of WifiNanStateManager: very limited (not usually
     * a good idea). However, these test that the internal state is cleaned-up
     * appropriately. Alternatively would cause issues with memory leaks or
     * information leak between sessions.
     */

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * after a client is disconnected. To be used in every test which terminates
     * a client.
     *
     * @param clientId The ID of the client which should be deleted.
     */
    private void validateInternalClientInfoCleanedUp(int clientId) throws Exception {
        WifiNanClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record not cleared up for clientId=" + clientId, client,
                nullValue());
    }

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * (deleted) after a session is terminated through API (not callback!). To
     * be used in every test which terminates a session.
     *
     * @param clientId The ID of the client containing the session.
     * @param sessionId The ID of the terminated session.
     */
    private void validateInternalSessionInfoCleanedUp(int clientId, int sessionId)
            throws Exception {
        WifiNanClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record exists clientId=" + clientId, client, notNullValue());
        WifiNanSessionState session = getInternalSessionState(client, sessionId);
        collector.checkThat("Client record not cleaned-up for sessionId=" + sessionId, session,
                nullValue());
    }

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * (deleted) correctly. Checks that a specific client has no sessions
     * attached to it.
     *
     * @param clientId The ID of the client which we want to check.
     */
    private void validateInternalNoSessions(int clientId) throws Exception {
        WifiNanClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record exists clientId=" + clientId, client, notNullValue());

        Field field = WifiNanClientState.class.getDeclaredField("mSessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiNanSessionState> sessions = (SparseArray<WifiNanSessionState>) field
                .get(client);

        collector.checkThat("No sessions exist for clientId=" + clientId, sessions.size(),
                equalTo(0));
    }

    /**
     * Validates that the broadcast sent on NAN status change is correct.
     *
     * @param expectedEnabled The expected change status - i.e. are we expected
     *            to announce that NAN is enabled (true) or disabled (false).
     */
    private void validateCorrectNanStatusChangeBroadcast(InOrder inOrder, boolean expectedEnabled) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        inOrder.verify(mMockContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));

        collector.checkThat("intent action", intent.getValue().getAction(),
                equalTo(WifiNanManager.WIFI_NAN_STATE_CHANGED_ACTION));
        collector.checkThat("intent contains wifi status key",
                intent.getValue().getExtras().containsKey(WifiNanManager.EXTRA_WIFI_STATE),
                equalTo(true));
        collector.checkThat("intnent wifi status key value",
                intent.getValue().getExtras().getInt(WifiNanManager.EXTRA_WIFI_STATE),
                equalTo(expectedEnabled ? WifiNanManager.WIFI_NAN_STATE_ENABLED
                        : WifiNanManager.WIFI_NAN_STATE_DISABLED));
    }

    /*
     * Utilities
     */

    private static WifiNanStateManager installNewNanStateManager()
            throws Exception {
        Constructor<WifiNanStateManager> ctr = WifiNanStateManager.class.getDeclaredConstructor();
        ctr.setAccessible(true);
        WifiNanStateManager nanStateManager = ctr.newInstance();

        Field field = WifiNanStateManager.class.getDeclaredField("sNanStateManagerSingleton");
        field.setAccessible(true);
        field.set(null, nanStateManager);

        return WifiNanStateManager.getInstance();
    }

    private static void installMocksInStateManager(WifiNanStateManager nanStateManager,
            WifiNanRttStateManager mockRtt, WifiNanDataPathStateManager mockDpMgr)
            throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mRtt");
        field.setAccessible(true);
        field.set(nanStateManager, mockRtt);

        field = WifiNanStateManager.class.getDeclaredField("mDataPathMgr");
        field.setAccessible(true);
        field.set(nanStateManager, mockDpMgr);
    }

    private static void installMockWifiNanNative(WifiNanNative obj) throws Exception {
        Field field = WifiNanNative.class.getDeclaredField("sWifiNanNativeSingleton");
        field.setAccessible(true);
        field.set(null, obj);
    }

    private static WifiNanClientState getInternalClientState(WifiNanStateManager dut, int clientId)
            throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mClients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiNanClientState> clients = (SparseArray<WifiNanClientState>) field.get(dut);

        return clients.get(clientId);
    }

    private static WifiNanSessionState getInternalSessionState(WifiNanClientState client,
            int sessionId) throws Exception {
        Field field = WifiNanClientState.class.getDeclaredField("mSessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiNanSessionState> sessions = (SparseArray<WifiNanSessionState>) field
                .get(client);

        return sessions.get(sessionId);
    }

    private void validateInternalSendMessageQueuesCleanedUp(int messageId) throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mSm");
        field.setAccessible(true);
        WifiNanStateManager.WifiNanStateMachine sm =
                (WifiNanStateManager.WifiNanStateMachine) field.get(mDut);

        field = WifiNanStateManager.WifiNanStateMachine.class.getDeclaredField(
                "mHostQueuedSendMessages");
        field.setAccessible(true);
        SparseArray<Message> hostQueuedSendMessages = (SparseArray<Message>) field.get(sm);

        field = WifiNanStateManager.WifiNanStateMachine.class.getDeclaredField(
                "mFwQueuedSendMessages");
        field.setAccessible(true);
        Map<Short, Message> fwQueuedSendMessages = (Map<Short, Message>) field.get(sm);

        for (int i = 0; i < hostQueuedSendMessages.size(); ++i) {
            Message msg = hostQueuedSendMessages.valueAt(i);
            if (msg.getData().getInt("message_id") == messageId) {
                collector.checkThat(
                        "Message not cleared-up from host queue. Message ID=" + messageId, msg,
                        nullValue());
            }
        }

        for (Message msg: fwQueuedSendMessages.values()) {
            if (msg.getData().getInt("message_id") == messageId) {
                collector.checkThat(
                        "Message not cleared-up from firmware queue. Message ID=" + messageId, msg,
                        nullValue());
            }
        }
    }

    private static WifiNanNative.Capabilities getCapabilities() {
        WifiNanNative.Capabilities cap = new WifiNanNative.Capabilities();
        cap.maxConcurrentNanClusters = 1;
        cap.maxPublishes = 2;
        cap.maxSubscribes = 2;
        cap.maxServiceNameLen = 255;
        cap.maxMatchFilterLen = 255;
        cap.maxTotalMatchFilterLen = 255;
        cap.maxServiceSpecificInfoLen = 255;
        cap.maxVsaDataLen = 255;
        cap.maxMeshDataLen = 255;
        cap.maxNdiInterfaces = 1;
        cap.maxNdpSessions = 1;
        cap.maxAppInfoLen = 255;
        cap.maxQueuedTransmitMessages = 6;
        return cap;
    }
}

