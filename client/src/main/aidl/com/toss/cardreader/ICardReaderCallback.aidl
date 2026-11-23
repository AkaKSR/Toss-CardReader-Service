package com.toss.cardreader;

oneway interface ICardReaderCallback {
    /** type: CardTypes.IC or CardTypes.MSR. Read the card with readIc() or readMsr(). */
    void onCardDetected(int type) = 1;

    /** The wait ended without a card: CardReaderError code (TIMEOUT, CANCELLED, READER_ERROR, ...). */
    void onError(int code, String message) = 2;
}
