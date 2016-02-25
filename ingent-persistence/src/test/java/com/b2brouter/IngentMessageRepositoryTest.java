package com.b2brouter;

import eu.peppol.PeppolMessageMetaData;
import eu.peppol.identifier.AccessPointIdentifier;
import eu.peppol.identifier.ParticipantId;
import eu.peppol.identifier.PeppolDocumentTypeId;
import eu.peppol.identifier.PeppolProcessTypeId;
import eu.peppol.identifier.TransmissionId;
import eu.peppol.util.GlobalConfiguration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;



public class IngentMessageRepositoryTest {

  @Test
  public void saveBomPayload() throws Exception{
    GlobalConfiguration conf = GlobalConfiguration.getInstance();
    IngentMessageRepository repo = new IngentMessageRepository();
    PeppolMessageMetaData meta = new PeppolMessageMetaData();
    meta.setTransmissionId(new TransmissionId("asdf-asdf"));
    meta.setMessageId("asdf-asdf");
    meta.setSenderId(new ParticipantId("sender"));
    meta.setRecipientId(new ParticipantId("recipient"));
    meta.setDocumentTypeIdentifier(new PeppolDocumentTypeId("asdf","asfd",null,"asf"));
    meta.setProfileTypeIdentifier(new PeppolProcessTypeId("urn:asdf"));
    meta.setSendingAccessPointId(new AccessPointIdentifier("AP1"));
    meta.setReceivingAccessPoint(new AccessPointIdentifier("AP2"));
    Path in_path = FileSystems.getDefault().getPath("test_bom_payload.xml");
    byte[] payload = Files.readAllBytes(in_path);
    InputStream stream = new ByteArrayInputStream(payload);
    repo.saveInboundMessage(meta, stream);
    Path out_path = FileSystems.getDefault().getPath(conf.getProperty("ingent.inbound.message.backup.store"),"recipient","sender","asdf-asdf.xml");
    assertEquals(out_path.toString(), "/var/spool/b2brouter/backup/recipient/sender/asdf-asdf.xml", "Configure in ~/.oxalis/oxalis-global.properties");
    assertEquals(Files.readAllBytes(out_path),payload);
  }

}
