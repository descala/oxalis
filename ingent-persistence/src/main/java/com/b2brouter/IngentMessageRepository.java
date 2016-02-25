package com.b2brouter;

import static com.b2brouter.Util.compress_b64;
import eu.peppol.PeppolMessageMetaData;
import eu.peppol.identifier.ParticipantId;
import eu.peppol.identifier.SchemeId;
import eu.peppol.identifier.TransmissionId;
import eu.peppol.persistence.MessageRepository;
import eu.peppol.persistence.OxalisMessagePersistenceException;
import eu.peppol.util.GlobalConfiguration;
import eu.peppol.util.OxalisVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.json.simple.JSONObject;
import eu.peppol.util.Util;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Ingent implementation of MessageRepository. Received messages are stored in
 * the file system using JSON and XML. Configure directory to store messages in
 * oxalis-global.properties as property "oxalis.inbound.message.store".
 *
 * Additions: - Backup messages before storing them. Configure directory in
 * oxalis-global.properties as property "ingent.inbound.message.backup" - Store
 * log lines into a PostgreSQL database.
 *
 * @author Ingent Grup Systems
 */
public class IngentMessageRepository implements MessageRepository {

    private static final Logger LOG = LoggerFactory.getLogger(IngentMessageRepository.class);
    private final GlobalConfiguration globalConfiguration;
    private static final String BACKUPS_PATH = "ingent.inbound.message.backup.store";
    private static final String API_URL = "ingent.api.url";
    private static final String API_KEY = "ingent.api.key";
    private static final String HALTR_URL = "ingent.haltr_url";
    private static final String HALTR_API_KEY = "ingent.haltr_api_key";

    public IngentMessageRepository() {
        globalConfiguration = GlobalConfiguration.getInstance();
    }

    @Override
    // Used in START
    public void saveInboundMessage(PeppolMessageMetaData peppolMessageMetaData, Document document) throws OxalisMessagePersistenceException {

        LOG.info("Saving inbound message document using " + IngentMessageRepository.class.getSimpleName());
        LOG.debug("Default inbound message headers " + peppolMessageMetaData);

        // save a backup
        File backupDirectory = prepareBackupDirectory(globalConfiguration.getProperty(BACKUPS_PATH), peppolMessageMetaData.getRecipientId(), peppolMessageMetaData.getSenderId());
        File backupFullPath = new File("");
        try {
            backupFullPath = computeMessageFileName(peppolMessageMetaData.getTransmissionId(), backupDirectory);
            saveDocument(document, backupFullPath);
            File messageHeaderFilePath = computeHeaderFileName(peppolMessageMetaData.getTransmissionId(), backupDirectory);
            saveHeader(peppolMessageMetaData, messageHeaderFilePath);
        } catch (Exception e) {
            LOG.error("Can't save backup for " + backupFullPath);
            LOG.error(e.getMessage());
        }

        try {
            DOMSource domSource = new DOMSource(document);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.transform(domSource, result);

            String b64_document = compress_b64(writer.toString());
            //System.out.println("BASE64: " + b64_document);
            //LOG.info(b64_document);
            createTransactionToWs(b64_document, peppolMessageMetaData);

        } catch (TransformerConfigurationException ex) {
            throw new OxalisMessagePersistenceException(peppolMessageMetaData, ex);
        } catch (TransformerException ex) {
            throw new OxalisMessagePersistenceException(peppolMessageMetaData, ex);
        } catch (IOException ex) {
            throw new OxalisMessagePersistenceException(peppolMessageMetaData, ex);
        }

    }

    @Override
    // Used in AS2
    public void saveInboundMessage(PeppolMessageMetaData peppolMessageMetaData, InputStream payloadInputStream) throws OxalisMessagePersistenceException {

        LOG.info("Saving inbound message stream using " + IngentMessageRepository.class.getSimpleName());
        LOG.debug("Default inbound message headers " + peppolMessageMetaData);

        String payload;
        // convert payloadInputStream to String
        try {
	    payloadInputStream.mark(Integer.MAX_VALUE);
            payload = new String(Util.intoBuffer(payloadInputStream, Long.MAX_VALUE));
	    payloadInputStream.reset();
        } catch (IOException ex) {
            throw new OxalisMessagePersistenceException(peppolMessageMetaData, ex);
        }

        // save a backup
        File backupDirectory = prepareBackupDirectory(globalConfiguration.getProperty(BACKUPS_PATH), peppolMessageMetaData.getRecipientId(), peppolMessageMetaData.getSenderId());
        File backupFullPath = new File("");
        try {
            backupFullPath = computeMessageFileName(peppolMessageMetaData.getTransmissionId(), backupDirectory);
            saveDocument(payloadInputStream, backupFullPath);
            File messageHeaderFilePath = computeHeaderFileName(peppolMessageMetaData.getTransmissionId(), backupDirectory);
            saveHeader(peppolMessageMetaData, messageHeaderFilePath);
        } catch (Exception e) {
            LOG.error("Can't save backup for " + backupFullPath);
            LOG.error(e.getMessage());
        }

        try {
            String b64_document;
            b64_document = compress_b64(payload);
            System.out.println("BASE64: " + b64_document);
            LOG.info(b64_document);
            createTransactionToWs(b64_document, peppolMessageMetaData);
        } catch (IOException ex) {
            throw new OxalisMessagePersistenceException(peppolMessageMetaData, ex);
        }

    }

    private File computeHeaderFileName(TransmissionId messageId, File messageDirectory) {
        String headerFileName = normalize(messageId.toString()) + ".txt";
        return new File(messageDirectory, headerFileName);
    }

    private File computeMessageFileName(TransmissionId messageId, File messageDirectory) {
        String messageFileName = normalize(messageId.toString()) + ".xml";
        return new File(messageDirectory, messageFileName);
    }

    File prepareBackupDirectory(String inboundMessageBackupStore, ParticipantId recipient, ParticipantId sender) {
        String path = String.format("%s/%s",
            normalize(recipient.stringValue()),
            normalize(sender.stringValue())
        );
        File backupDirectory = new File(inboundMessageBackupStore, path);
        if (!backupDirectory.exists()) {
            if (!backupDirectory.mkdirs()) {
                LOG.error("Unable to create backup directory " + backupDirectory.toString());
            }
        }
        if (!backupDirectory.isDirectory() || !backupDirectory.canWrite()) {
            LOG.error("Backup directory " + backupDirectory + " does not exist, or there is no access");
        }
        return backupDirectory;
    }

    /**
     * Transforms and saves the headers as JSON
     */
    void saveHeader(PeppolMessageMetaData peppolMessageMetaData, File messageHeaderFilePath) {
        try {
            FileOutputStream fos = new FileOutputStream(messageHeaderFilePath);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(fos, "UTF-8"));
            pw.write(getHeadersAsJSON(peppolMessageMetaData));
            pw.close();
            LOG.debug("File " + messageHeaderFilePath + " written");
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Unable to create file " + messageHeaderFilePath + "; " + e, e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Unable to create writer for " + messageHeaderFilePath + "; " + e, e);
        }
    }

    String getHeadersAsJSON(PeppolMessageMetaData headers) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{ \"PeppolMessageMetaData\" :\n  {\n");
            sb.append(createJsonPair("messageId", headers.getMessageId()));
            sb.append(createJsonPair("recipientId", headers.getRecipientId()));
            sb.append(createJsonPair("recipientSchemeId", getSchemeId(headers.getRecipientId())));
            sb.append(createJsonPair("senderId", headers.getSenderId()));
            sb.append(createJsonPair("senderSchemeId", getSchemeId(headers.getSenderId())));
            sb.append(createJsonPair("documentTypeIdentifier", headers.getDocumentTypeIdentifier()));
            sb.append(createJsonPair("profileTypeIdentifier", headers.getProfileTypeIdentifier()));
            sb.append(createJsonPair("sendingAccessPoint", headers.getSendingAccessPoint()));
            sb.append(createJsonPair("receivingAccessPoint", headers.getReceivingAccessPoint()));
            sb.append(createJsonPair("protocol", headers.getProtocol()));
            sb.append(createJsonPair("userAgent", headers.getUserAgent()));
            sb.append(createJsonPair("userAgentVersion", headers.getUserAgentVersion()));
            sb.append(createJsonPair("sendersTimeStamp", headers.getSendersTimeStamp()));
            sb.append(createJsonPair("receivedTimeStamp", headers.getReceivedTimeStamp()));
            sb.append(createJsonPair("sendingAccessPointPrincipal", (headers.getSendingAccessPointPrincipal() == null) ? null : headers.getSendingAccessPointPrincipal().getName()));
            sb.append(createJsonPair("transmissionId", headers.getTransmissionId()));
            sb.append(createJsonPair("buildUser", OxalisVersion.getUser()));
            sb.append(createJsonPair("buildDescription", OxalisVersion.getBuildDescription()));
            sb.append(createJsonPair("buildTimeStamp", OxalisVersion.getBuildTimeStamp()));
            sb.append("    \"oxalis\" : \"").append(OxalisVersion.getVersion()).append("\"\n");
            sb.append("  }\n}\n");
            return sb.toString();
        } catch (Exception ex) {
            /* default to debug string if JSON marshalling fails */
            return headers.toString();
        }
    }

    private String getSchemeId(ParticipantId participant) {
        String id = "UNKNOWN:SCHEME";
        if (participant != null) {
            String prefix = participant.stringValue().split(":")[0]; // prefix is the first part (before colon)
            SchemeId scheme = SchemeId.fromISO6523(prefix);
            if (scheme != null) {
                id = scheme.getSchemeId();
            } else {
                id = "UNKNOWN:" + prefix;
            }
        }
        return id;
    }

    private String createJsonPair(String key, Object value) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"").append(key).append("\" : ");
        if (value == null) {
            sb.append("null,\n");
        } else {
            sb.append("\"").append(value.toString()).append("\",\n");
        }
        return sb.toString();
    }

    /**
     * Transforms and saves the document as XML
     * Used in START
     *
     * @param document the XML document to be transformed
     */
    void saveDocument(Document document, File outputFile) {
        saveDocument(new DOMSource(document), outputFile);
    }

    /**
     * Transforms and saves the string as XML
     * Used in AS2
     *
     * @param payload the string to be transformed
     */
    void saveDocument(InputStream inputStream, File destination) {
        //saveDocument(new StreamSource(inputStream), outputFile);
	try {
	    Files.copy(inputStream, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
	    LOG.debug("File " + destination + " written");
	} catch (IOException e) {
            throw new IngentRepositoryException(destination, e);
        }
    }

    private void saveDocument(Source source, File destination) {
        try {
            FileOutputStream fos = new FileOutputStream(destination);
            Writer writer = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer;
            transformer = tf.newTransformer();
            transformer.transform(source, result);
            fos.close();
            LOG.debug("File " + destination + " written");
        } catch (TransformerException e) {
            throw new IngentRepositoryException(destination, e);
        } catch (IOException e) {
            throw new IngentRepositoryException(destination, e);
        }
    }

    @Override
    public String toString() {
        return IngentMessageRepository.class.getSimpleName();
    }

    String normalize(String s) {
        return s.replaceAll("[:\\/]", "_").toLowerCase();
    }

    private void createTransactionToWs(String b64_document, PeppolMessageMetaData peppolMessageMetaData) throws OxalisMessagePersistenceException {
        try {
            String apiUrl = globalConfiguration.getProperty(API_URL);
            String apiKey = globalConfiguration.getProperty(API_KEY);

            HttpClient httpclient = HttpClients.createDefault();
            HttpPost httppost = new HttpPost(apiUrl);

            // Request parameters and other properties.
            JSONObject transaction = new JSONObject();
            transaction.put("id", "peppol_"+peppolMessageMetaData.getTransmissionId());
            transaction.put("process", "Peppol::Receive");
            transaction.put("payload", b64_document);
            transaction.put("haltr_url", globalConfiguration.getProperty(HALTR_URL));
            transaction.put("haltr_api_key", globalConfiguration.getProperty(HALTR_API_KEY));
            // Metadata
            transaction.put("meta_message_id", String.valueOf(peppolMessageMetaData.getMessageId()));
            transaction.put("meta_recipient_id", String.valueOf(peppolMessageMetaData.getRecipientId()));
            transaction.put("meta_recipient_scheme_id", getSchemeId(peppolMessageMetaData.getRecipientId()));
            transaction.put("meta_sender_id", String.valueOf(peppolMessageMetaData.getSenderId()));
            transaction.put("meta_sender_scheme_id", getSchemeId(peppolMessageMetaData.getSenderId()));
            transaction.put("meta_document_type_identifier", String.valueOf(peppolMessageMetaData.getDocumentTypeIdentifier()));
            transaction.put("meta_profile_type_identifier", String.valueOf(peppolMessageMetaData.getProfileTypeIdentifier()));
            transaction.put("meta_sending_access_point", String.valueOf(peppolMessageMetaData.getSendingAccessPoint()));
            transaction.put("meta_receiving_access_point", String.valueOf(peppolMessageMetaData.getReceivingAccessPoint()));
            transaction.put("meta_protocol", String.valueOf(peppolMessageMetaData.getProtocol()));
            transaction.put("meta_user_agent", peppolMessageMetaData.getUserAgent());
            transaction.put("meta_user_agent_version", peppolMessageMetaData.getUserAgentVersion());
            transaction.put("meta_senders_time_stamp", String.valueOf(peppolMessageMetaData.getSendersTimeStamp()));
            transaction.put("meta_received_time_stamp", String.valueOf(peppolMessageMetaData.getReceivedTimeStamp()));
            transaction.put("meta_sending_access_point_principal", (peppolMessageMetaData.getSendingAccessPointPrincipal() == null) ? "" : peppolMessageMetaData.getSendingAccessPointPrincipal().getName());
            transaction.put("meta_transmission_id", String.valueOf(peppolMessageMetaData.getTransmissionId()));
            transaction.put("meta_build_user", OxalisVersion.getUser());
            transaction.put("meta_build_description", OxalisVersion.getBuildDescription());
            transaction.put("meta_build_time_stamp", OxalisVersion.getBuildTimeStamp());
            transaction.put("meta_oxalis", OxalisVersion.getVersion());

            JSONObject params = new JSONObject();
            params.put("transaction", transaction);
            params.put("token", apiKey);

            httppost.setEntity(new StringEntity(params.toString()));
            httppost.setHeader("Content-type", "application/json");

            //Execute and get the response.
            HttpResponse response = httpclient.execute(httppost);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                InputStream instream = entity.getContent();
                try {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        throw new OxalisMessagePersistenceException(peppolMessageMetaData);
                    }
                } finally {
                    instream.close();
                }
            }

            //print result
            System.out.println(response.toString());
        } catch (MalformedURLException e) {
            System.out.println(e.getMessage());
            throw new OxalisMessagePersistenceException(peppolMessageMetaData);
        } catch (ProtocolException e) {
            System.out.println(e.getMessage());
            throw new OxalisMessagePersistenceException(peppolMessageMetaData);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            throw new OxalisMessagePersistenceException(peppolMessageMetaData);
        }
    }

}
