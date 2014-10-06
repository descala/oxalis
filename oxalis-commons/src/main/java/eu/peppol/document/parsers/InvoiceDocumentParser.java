package eu.peppol.document.parsers;

import eu.peppol.document.PlainUBLParser;
import eu.peppol.identifier.ParticipantId;
import eu.peppol.start.identifier.Log;

/**
 * Parser to retrieves information from PEPPOL Invoice scenarios.
 * Should be able to decode Invoices in plain UBL and Norwegian EHF variants.
 *
 * @author thore
 */
public class InvoiceDocumentParser extends AbstractDocumentParser {

    public InvoiceDocumentParser(PlainUBLParser parser) {
        super(parser);
    }

    @Override
    public ParticipantId getSender() {
        String endpoint = "//cac:AccountingSupplierParty/cac:Party/cbc:EndpointID";
        String company  = "//cac:AccountingSupplierParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID";
        ParticipantId s;
        try {
            s = participantId(company);
        } catch (IllegalStateException e) {
            s = participantId(endpoint);
        }
        Log.debug("Sender from xml: " + s);
        return s;
    }

    @Override
    public ParticipantId getReceiver() {
        String endpoint = "//cac:AccountingCustomerParty/cac:Party/cbc:EndpointID";
        String company  = "//cac:AccountingCustomerParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID";
        ParticipantId s;
        try {
            s = participantId(company);
        } catch (IllegalStateException e) {
            s = participantId(endpoint);
        }
        Log.debug("Receiver from xml: " + s);
        return s;
    }

}
