/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.services.telephony;

import com.android.internal.telephony.PhoneConstants;
import com.android.phone.R;
import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConferenceParticipant;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.text.TextUtils;

/**
 * Represents a participant in a conference call.
 */
public class ConferenceParticipantConnection extends Connection {
    /**
     * RFC5767 states that a SIP URI with an unknown number should use an address of
     * {@code anonymous@anonymous.invalid}.  E.g. the host name is anonymous.invalid.
     */
    private static final String ANONYMOUS_INVALID_HOST = "anonymous.invalid";

    /**
     * The user entity URI For the conference participant.
     */
    private final Uri mUserEntity;

    /**
     * The endpoint URI For the conference participant.
     */
    private final Uri mEndpoint;

    /**
     * The connection which owns this participant.
     */
    private final com.android.internal.telephony.Connection mParentConnection;

    /**
     * Creates a new instance.
     *
     * @param participant The conference participant to create the instance for.
     */
    public ConferenceParticipantConnection(
            com.android.internal.telephony.Connection parentConnection,
            ConferenceParticipant participant) {

        mParentConnection = parentConnection;
        int presentation = getParticipantPresentation(participant);
        Uri address;
        if (presentation != PhoneConstants.PRESENTATION_ALLOWED) {
            address = null;
        } else {
            address = getParticipantAddress(participant);
        }
        setAddress(address, presentation);
        setCallerDisplayName(participant.getDisplayName(), presentation);

        mUserEntity = participant.getHandle();
        mEndpoint = participant.getEndpoint();

        setCapabilities();
    }

    /**
     * Changes the state of the conference participant.
     *
     * @param newState The new state.
     */
    public void updateState(int newState) {
        Log.v(this, "updateState endPoint: %s state: %s", Log.pii(mEndpoint),
                Connection.stateToString(newState));
        if (newState == getState()) {
            return;
        }

        switch (newState) {
            case STATE_INITIALIZING:
                setInitializing();
                break;
            case STATE_RINGING:
                setRinging();
                break;
            case STATE_DIALING:
                setDialing();
                break;
            case STATE_HOLDING:
                setOnHold();
                break;
            case STATE_ACTIVE:
                setActive();
                break;
            case STATE_DISCONNECTED:
                setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
                destroy();
                break;
            default:
                setActive();
        }
    }

    /**
     * Disconnects the current {@code ConferenceParticipantConnection} from the conference.
     * <p>
     * Sends a participant disconnect signal to the associated parent connection.  The participant
     * connection is not disconnected and cleaned up here.  On successful disconnection of the
     * participant, the conference server will send an update to the conference controller
     * indicating the disconnection was successful.
     */
    @Override
    public void onDisconnect() {
        mParentConnection.onDisconnectConferenceParticipant(mUserEntity);
    }

    /**
     * Retrieves the user handle for this connection.
     *
     * @return The userEntity.
     */
    public Uri getUserEntity() {
        return mUserEntity;
    }

    /**
     * Retrieves the endpoint for this connection.
     *
     * @return The endpoint.
     */
    public Uri getEndpoint() {
        return mEndpoint;
    }

    /**
     * Configures the capabilities applicable to this connection.  A
     * conference participant can only be disconnected from a conference since there is not
     * actual connection to the participant which could be split from the conference.
     */
    private void setCapabilities() {
        int capabilities = CAPABILITY_DISCONNECT_FROM_CONFERENCE;
        setConnectionCapabilities(capabilities);
    }

    /**
     * Determines the number presentation for a conference participant.  Per RFC5767, if the host
     * name contains {@code anonymous.invalid} we can assume that there is no valid caller ID
     * information for the caller, otherwise we'll assume that the URI can be shown.
     *
     * @param participant The conference participant.
     * @return The number presentation.
     */
    private int getParticipantPresentation(ConferenceParticipant participant) {
        Uri address = participant.getHandle();
        if (address == null) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }

        String number = address.getSchemeSpecificPart();
        // If no number, bail early and set restricted presentation.
        if (TextUtils.isEmpty(number)) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }

        String numberParts[] = number.split("[@]");
        // If we can't parse the host name out of the URI, then there is probably other data
        // present, and is likely a valid SIP URI.
        if (numberParts.length != 2) {
            return PhoneConstants.PRESENTATION_ALLOWED;
        }
        String hostName = numberParts[1];

        // If the hostname portion of the SIP URI is the invalid host string, presentation is
        // restricted.
        if (hostName.equals(ANONYMOUS_INVALID_HOST) && !showAnonymousParticipant()) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }

        return PhoneConstants.PRESENTATION_ALLOWED;
    }

    /**
     * Configuration to determine if anonymous participants names are to be shown
     *
     * @return true/false.
     */
    private boolean showAnonymousParticipant() {
        return TelephonyGlobals.getApplicationContext().getResources().getBoolean(
                R.bool.config_enable_show_anonymous_participant);
    }

    /**
     * Attempts to build a tel: style URI from a conference participant.
     * Conference event package data contains SIP URIs, so we try to extract the phone number and
     * format into a typical tel: style URI.
     *
     * @param participant The conference participant.
     * @return The participant's address URI.
     */
    private Uri getParticipantAddress(ConferenceParticipant participant) {
        Uri address = participant.getHandle();
        if (address == null) {
            return address;
        }

        // If the participant's address is already a TEL scheme, just return it as is.
        if (PhoneAccount.SCHEME_TEL.equals(address.getScheme())) {
            return address;
        }

        // Conference event package participants are identified using SIP URIs (see RFC3261).
        // A valid SIP uri has the format: sip:user:password@host:port;uri-parameters?headers
        // Per RFC3261, the "user" can be a telephone number.
        // For example: sip:1650555121;phone-context=blah.com@host.com
        // In this case, the phone number is in the user field of the URI, and the parameters can be
        // ignored.
        //
        // A SIP URI can also specify a phone number in a format similar to:
        // sip:+1-212-555-1212@something.com;user=phone
        // In this case, the phone number is again in user field and the parameters can be ignored.
        // We can get the user field in these instances by splitting the string on the @, ;, or :
        // and looking at the first found item.
        String number = address.getSchemeSpecificPart();
        if (TextUtils.isEmpty(number)) {
            return address;
        }

        String numberParts[] = number.split("[@;:]");
        if (numberParts.length == 0) {
            return address;
        }
        number = numberParts[0];

        return Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    }

    /**
     * Builds a string representation of this conference participant connection.
     *
     * @return String representation of connection.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ConferenceParticipantConnection objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" endPoint:");
        sb.append(Log.pii(mEndpoint));
        sb.append(" parentConnection:");
        sb.append(Log.pii(mParentConnection.getAddress()));
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append("]");

        return sb.toString();
    }
}
