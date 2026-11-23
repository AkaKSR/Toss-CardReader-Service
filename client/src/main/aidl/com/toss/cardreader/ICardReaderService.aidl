package com.toss.cardreader;

import com.toss.cardreader.ICardReaderCallback;
import com.toss.cardreader.IcCardData;
import com.toss.cardreader.MsrCardData;
import com.toss.cardreader.ServiceStatus;

/**
 * Toss Card Reader Service, interface version 1. Bind with action com.toss.cardreader.action.BIND and package
 * com.toss.cardreader.service. Only apps signed with an allowed certificate may call; others get SecurityException.
 * Errors are CardReaderError codes: waitForCard returns one (0 = started), readIc/readMsr carry one in [error].
 * Transaction ids are fixed.
 */
interface ICardReaderService {
    /** Interface version of the service (1). */
    int getVersion() = 1;

    ServiceStatus getStatus() = 2;

    /**
     * Starts the one card wait the reader allows. types: CardTypes IC and/or MSR bits; timeoutSec 1..120.
     * Returns 0, or BUSY while another app's session is active. A new call from the session's own process replaces it.
     */
    int waitForCard(int types, int timeoutSec, ICardReaderCallback callback) = 3;

    /** After onCardDetected(IC): reads ATR and public EMV records, powers the card off and ends the session. error != 0 on failure. */
    IcCardData readIc() = 4;

    /** After onCardDetected(MSR): returns tracks 1-3 of that swipe once and ends the session. error != 0 on failure. */
    MsrCardData readMsr() = 5;

    /** Ends the caller's session (no-op without one). */
    void cancel() = 6;
}
