package com.exactpro.th2.constants;

public class Constants {

    //Tags
    public static final String BEGIN_STRING_TAG = "8";
    public static final String BODY_LENGTH_TAG = "9";
    public static final String MSG_TYPE_TAG = "35";
    public static final String SENDER_COMP_ID_TAG = "49";
    public static final String TARGET_COMP_ID_TAG = "56";
    public static final String MSG_SEQ_NUM_TAG = "34";
    public static final String SENDING_TIME_TAG = "52";
    public static final String CHECKSUM_TAG = "10";
    public static final String ENCRYPT_METHOD_TAG = "98";
    public static final String HEART_BT_INT_TAG = "108";
    public static final String USERNAME_TAG = "553";
    public static final String PASSWORD_TAG = "554";
    public static final String SESSION_STATUS_TAG = "1409";
    public static final String TEST_REQ_ID_TAG = "112";
    public static final String BEGIN_SEQ_NO_TAG = "7";
    public static final String END_SEQ_NO_TAG = "16";
    public static final String NEW_SEQ_NO_TAG = "36";
    public static final String GAP_FILL_FLAG_TAG = "123";


    //Fields
    public static final String BEGIN_STRING = BEGIN_STRING_TAG + "=";
    public static final String BODY_LENGTH = BODY_LENGTH_TAG + "=";
    public static final String MSG_TYPE = MSG_TYPE_TAG + "=";
    public static final String SENDER_COMP_ID = SENDER_COMP_ID_TAG + "=";
    public static final String TARGET_COMP_ID = TARGET_COMP_ID_TAG + "=";
    public static final String MSG_SEQ_NUM = MSG_SEQ_NUM_TAG + "=";
    public static final String SENDING_TIME = SENDING_TIME_TAG + "=";
    public static final String CHECKSUM = CHECKSUM_TAG + "=";
    public static final String ENCRYPT_METHOD = ENCRYPT_METHOD_TAG + "=";
    public static final String HEART_BT_INT = HEART_BT_INT_TAG + "=";
    public static final String USERNAME = USERNAME_TAG + "=";
    public static final String PASSWORD = PASSWORD_TAG + "=";
    public static final String TEST_REQ_ID = TEST_REQ_ID_TAG + "=";
    public static final String BEGIN_SEQ_NO = BEGIN_SEQ_NO_TAG + "=";
    public static final String END_SEQ_NO = END_SEQ_NO_TAG + "=";
    public static final String NEW_SEQ_NO = NEW_SEQ_NO_TAG + "=";
    public static final String GAP_FILL_FLAG = GAP_FILL_FLAG_TAG + "=";


    //message types
    public static final String MSG_TYPE_LOGON = "A";
    public static final String MSG_TYPE_LOGOUT = "5";
    public static final String MSG_TYPE_HEARTBEAT = "0";
    public static final String MSG_TYPE_TEST_REQUEST = "1";
    public static final String MSG_TYPE_RESEND_REQUEST = "2";
    public static final String MSG_TYPE_SEQUENCE_RESET = "4";

}
