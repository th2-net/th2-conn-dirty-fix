package com.exactpro.th2;

import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.google.auto.service.AutoService;

@AutoService(IProtocolHandlerSettings.class)
public class FixHandlerSettings implements IProtocolHandlerSettings {

    private String beginString = "FIXT.1.1";
    private long heartBtInt = 30;
    private String senderCompID = "client";
    private String targetCompID = "server";
    private String encryptMethod = "0";
    private String username = "username";
    private String password = "pass";
    private Boolean resetSeqNumFlag = false;
    private Boolean resetOnLogon = false;

    private long defaultApplVerID = 9;

    private int testRequestDelay = 60;
    private int reconnectDelay = 5;
    private int disconnectRequestDelay = 5;


    public String getBeginString() {
        return beginString;
    }

    public void setBeginString(String beginString) {
        this.beginString = beginString;
    }

    public long getHeartBtInt() {
        return heartBtInt;
    }

    public void setHeartBtInt(long heartBtInt) {
        this.heartBtInt = heartBtInt;
    }

    public String getSenderCompID() {
        return senderCompID;
    }

    public void setSenderCompID(String senderCompID) {
        this.senderCompID = senderCompID;
    }

    public String getTargetCompID() {
        return targetCompID;
    }

    public void setTargetCompID(String targetCompID) {
        this.targetCompID = targetCompID;
    }

    public String getEncryptMethod() {
        return encryptMethod;
    }

    public void setEncryptMethod(String encryptMethod) {
        this.encryptMethod = encryptMethod;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Boolean getResetSeqNumFlag() { return resetSeqNumFlag; }

    public Boolean getResetOnLogon() { return resetOnLogon; }

    public int getTestRequestDelay() {
        return testRequestDelay;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setResetSeqNumFlag(Boolean resetSeqNumFlag) { this.resetSeqNumFlag = resetSeqNumFlag; }

    public void setResetOnLogon(Boolean resetOnLogon) { this.resetOnLogon = resetOnLogon; }

    public void setTestRequestDelay(int testRequestDelay) {
        this.testRequestDelay = testRequestDelay;
    }

    public void setReconnectDelay(int reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public int getDisconnectRequestDelay() {
        return disconnectRequestDelay;
    }

    public void setDisconnectRequestDelay(int disconnectRequestDelay) {
        this.disconnectRequestDelay = disconnectRequestDelay;
    }

    public long getDefaultApplVerID() {
        return defaultApplVerID;
    }

    public void setDefaultApplVerID(long defaultApplVerID) {
        this.defaultApplVerID = defaultApplVerID;
    }
}
