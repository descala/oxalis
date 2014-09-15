package eu.peppol.outbound.transmission;

import com.google.inject.Inject;
import eu.peppol.BusDoxProtocol;
import eu.peppol.PeppolStandardBusinessHeader;
import eu.peppol.document.*;
import eu.peppol.identifier.MessageId;
import eu.peppol.identifier.ParticipantId;
import eu.peppol.identifier.PeppolDocumentTypeId;
import eu.peppol.identifier.PeppolProcessTypeId;
import eu.peppol.security.CommonName;
import eu.peppol.smp.SmpLookupManager;
import eu.peppol.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * @author steinar
 * @author thore
 *         Date: 04.11.13
 *         Time: 10:04
 */
public class TransmissionRequestBuilder {

    public static final Logger log = LoggerFactory.getLogger(TransmissionRequestBuilder.class);

    final SbdhParser sbdhParser;
    final NoSbdhParser noSbdhParser;
    final SmpLookupManager smpLookupManager;

    private boolean traceEnabled;

    /**
     * Will contain the payload PEPPOL document
     */
    private byte[] payload;

    /**
     * The address of the endpoint either supplied by the caller or looked up in the SMP
     */
    private SmpLookupManager.PeppolEndpointData endpointAddress;

    /**
     * The header fields supplied by the caller as opposed to the header fields parsed from the payload
     * */
    private PeppolStandardBusinessHeader suppliedHeaderFields = new PeppolStandardBusinessHeader();

    /**
     * The header fields in effect, i.e. merge the parsed header fields with the supplied ones, giving precedence to the supplied ones.
     */
    private PeppolStandardBusinessHeader effectiveStandardBusinessHeader;

    /**
     * Indicates whether the payload contains an SBDH or not, which is determined by sniffing at the document before parsing it
     */
    private boolean sbdhDetected;

    @Inject
    public TransmissionRequestBuilder(SbdhParser sbdhParser, NoSbdhParser noSbdhParser, SmpLookupManager smpLookupManager) {
        this.sbdhParser = sbdhParser;
        this.noSbdhParser = noSbdhParser;
        this.smpLookupManager = smpLookupManager;
    }

    /**
     * Supplies the  builder with the contents of the message to be sent.
     */
    public TransmissionRequestBuilder payLoad(InputStream inputStream) {
        savePayLoad(inputStream);
        return this;
    }

    /**
     * Overrides the endpoint URL for the START transmission protocol.
     */
    public TransmissionRequestBuilder overrideEndpointForStartProtocol(URL url) {
        endpointAddress = new SmpLookupManager.PeppolEndpointData(url, BusDoxProtocol.START);
        return this;
    }

    /**
     * Overrides the endpoint URL and the AS2 System identifier for the AS2 protocol.
     * You had better know what you are doing :-)
     */
    public TransmissionRequestBuilder overrideAs2Endpoint(URL url, String accessPointSystemIdentifier) {
        endpointAddress = new SmpLookupManager.PeppolEndpointData(url, BusDoxProtocol.AS2, new CommonName(accessPointSystemIdentifier));
        return this;
    }

    public TransmissionRequestBuilder receiver(ParticipantId receiverId) {
        suppliedHeaderFields.setRecipientId(receiverId);
        return this;
    }

    public TransmissionRequestBuilder sender(ParticipantId senderId) {
        suppliedHeaderFields.setSenderId(senderId);
        return this;
    }

    public TransmissionRequestBuilder documentType(PeppolDocumentTypeId documentTypeId) {
        suppliedHeaderFields.setDocumentTypeIdentifier(documentTypeId);
        return this;
    }

    public TransmissionRequestBuilder processType(PeppolProcessTypeId processTypeId) {
        suppliedHeaderFields.setProfileTypeIdentifier(processTypeId);
        return this;
    }

    public TransmissionRequestBuilder messageId(MessageId messageId) {
        suppliedHeaderFields.setMessageId(messageId);
        return this;
    }

    public TransmissionRequestBuilder trace(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
        return this;
    }

    public TransmissionRequest build() {

        // insect payload and check if it contains SBDH
        sbdhDetected = checkForSbdh();

        // inspect supplied meta data before sending
        if (suppliedHeaderFields.isComplete()) {
            // we have sufficient meta data (set explicitly by the caller using API functions)
            effectiveStandardBusinessHeader = suppliedHeaderFields;
        } else {
            // missing meta data, parse payload to deduce missing fields
            PeppolStandardBusinessHeader parsedPeppolStandardBusinessHeader = parsePayLoadAndDeduceSbdh();
            effectiveStandardBusinessHeader = createEffectiveHeader(parsedPeppolStandardBusinessHeader, suppliedHeaderFields);
            // ensure the effective meta data is complete
            if (!effectiveStandardBusinessHeader.isComplete()) {
                StringBuilder sb = new StringBuilder("TransmissionRequest can not be built");
                for (String missing : effectiveStandardBusinessHeader.listMissingProperties()) {
                    sb.append(", ");
                    sb.append(missing);
                }
                sb.append(" metadata was missing");
                throw new IllegalStateException(sb.toString());
            }
        }

        // If the endpoint has not been overridden by the caller, look up the endpoint address in the SMP using the data supplied in the payload
        if (!isEndpointOverridden()) {
            endpointAddress = smpLookupManager.getEndpointTransmissionData(effectiveStandardBusinessHeader.getRecipientId(), effectiveStandardBusinessHeader.getDocumentTypeIdentifier());
        }

        // make sure payload is encapsulated in SBDH for AS2 protocol
        if (BusDoxProtocol.AS2.equals(endpointAddress.getBusDoxProtocol())  && !sbdhDetected) {
            // Wraps the payload with an SBDH, as this is required for AS2
            payload = wrapPayLoadWithSBDH(new ByteArrayInputStream(payload), effectiveStandardBusinessHeader);
        }
        // TODO enable this check to not allow SBDG in START
        // else if (endpointAddress.getBusDoxProtocol() == BusDoxProtocol.START && sbdhDetected) {
        //     throw new IllegalStateException("Payload may not contain SBDH when using protocol " + endpointAddress.getBusDoxProtocol().toString());
        // }

        if (traceEnabled) {
            log.debug("This payload was built\n" + new String(payload));
        }

        // Transfers all the properties of this object into the newly created TransmissionRequest
        return new TransmissionRequest(this);

    }

    /**
     * Merges the supplied header fields with the SBDH parsed from the payload thus allowing the caller
     * to explicitly override whatever has been supplied in the payload.
     *
     * @param parsed the PeppolStandardBusinessHeader parsed from the payload
     * @param supplied the header fields supplied by the caller
     * @return the merged and effective headers
     */
    protected PeppolStandardBusinessHeader createEffectiveHeader(final PeppolStandardBusinessHeader parsed, PeppolStandardBusinessHeader supplied) {

        // Creates a copy of the original business headers
        PeppolStandardBusinessHeader mergedHeaders = new PeppolStandardBusinessHeader(parsed);

        if (supplied.getSenderId() != null) {
            mergedHeaders.setSenderId(supplied.getSenderId());
        }
        if (supplied.getRecipientId() != null) {
            mergedHeaders.setRecipientId(supplied.getRecipientId());
        }
        if (supplied.getDocumentTypeIdentifier() != null) {
            mergedHeaders.setDocumentTypeIdentifier(supplied.getDocumentTypeIdentifier());
        }
        if (supplied.getProfileTypeIdentifier() != null) {
            mergedHeaders.setProfileTypeIdentifier(supplied.getProfileTypeIdentifier());
        }
        if (supplied.getMessageId() != null) {
            mergedHeaders.setMessageId(supplied.getMessageId());
        }

        return mergedHeaders;

    }

    protected boolean isEndpointOverridden() {
        return endpointAddress != null;
    }

    boolean checkForSbdh() {
        // Sniff, sniff; does it contain a SBDH?
        DocumentSniffer documentSniffer = new DocumentSniffer(new ByteArrayInputStream(payload));
        return documentSniffer.isSbdhDetected();
    }

    void savePayLoad(InputStream inputStream) {
        try {
            payload = Util.intoBuffer(inputStream, 101L * 1024 * 1024);     // Copies the contents into a buffer
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save the payload: " + e.getMessage(), e);
        }
    }

    PeppolStandardBusinessHeader getEffectiveStandardBusinessHeader() {
        return effectiveStandardBusinessHeader;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    byte[] getPayload() {
        return payload;
    }

    SmpLookupManager.PeppolEndpointData getEndpointAddress() {
        return endpointAddress;
    }

    private byte[] wrapPayLoadWithSBDH(ByteArrayInputStream byteArrayInputStream, PeppolStandardBusinessHeader effectiveStandardBusinessHeader) {
        SbdhWrapper sbdhWrapper = new SbdhWrapper();
        return sbdhWrapper.wrap(byteArrayInputStream, effectiveStandardBusinessHeader);
    }

    private PeppolStandardBusinessHeader parsePayLoadAndDeduceSbdh() {
        PeppolStandardBusinessHeader peppolSbdh;
        if (sbdhDetected) {
            // Parses the SBDH to determine the receivers endpoint URL etc.
            peppolSbdh = sbdhParser.parse(new ByteArrayInputStream(payload));
        } else {
            // Parses the PEPPOL document in order to determine the header fields
            peppolSbdh = noSbdhParser.parse(new ByteArrayInputStream(payload));
        }
        return peppolSbdh;
    }

}
